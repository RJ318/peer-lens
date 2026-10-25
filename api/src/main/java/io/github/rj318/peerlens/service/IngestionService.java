package io.github.rj318.peerlens.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

import io.github.rj318.peerlens.config.PeerLensProperties;
import io.github.rj318.peerlens.domain.AnnualValue;
import io.github.rj318.peerlens.domain.Metric;
import io.github.rj318.peerlens.repository.CompanyRepository;
import io.github.rj318.peerlens.repository.FactRepository;
import io.github.rj318.peerlens.repository.IngestionRunRepository;
import io.github.rj318.peerlens.sec.AnnualFactExtractor;
import io.github.rj318.peerlens.sec.CompanyFacts;
import io.github.rj318.peerlens.sec.SecClient;
import io.github.rj318.peerlens.sec.TickerEntry;

/**
 * Loads the watchlist from SEC EDGAR. One company failing never stops the others, each company is
 * replaced inside its own transaction, and every run is recorded with a per-company result.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final SecClient secClient;
    private final SectorCatalog catalog;
    private final CompanyRepository companies;
    private final FactRepository facts;
    private final IngestionRunRepository runs;
    private final TransactionTemplate transactions;
    private final PeerLensProperties properties;
    private final Clock clock;
    private final AnnualFactExtractor extractor = new AnnualFactExtractor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sec-ingestion");
        thread.setDaemon(true);
        return thread;
    });

    public IngestionService(SecClient secClient, SectorCatalog catalog, CompanyRepository companies,
                            FactRepository facts, IngestionRunRepository runs, TransactionTemplate transactions,
                            PeerLensProperties properties, Clock clock) {
        this.secClient = secClient;
        this.catalog = catalog;
        this.companies = companies;
        this.facts = facts;
        this.runs = runs;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /** First start with an empty database: close out stale runs and load data so the dashboard is not blank. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int abandoned = runs.abandonUnfinished(clock.instant());
        if (abandoned > 0) {
            log.warn("Marked {} unfinished ingestion run(s) as abandoned", abandoned);
        }
        if (properties.ingest().runOnStartupIfEmpty() && facts.count() == 0) {
            startInBackground("STARTUP");
        }
    }

    @Scheduled(cron = "${peerlens.ingest.cron}", zone = "${peerlens.ingest.zone}")
    public void onSchedule() {
        if (startInBackground("SCHEDULE").isEmpty()) {
            log.info("Scheduled ingestion skipped because a run is already in progress");
        }
    }

    /**
     * Records the run before returning, so a caller that immediately asks for the latest run sees this one.
     *
     * @return the new run's id, or empty if a run is already in progress
     */
    public OptionalLong startInBackground(String trigger) {
        if (!running.compareAndSet(false, true)) {
            return OptionalLong.empty();
        }
        long runId;
        try {
            runId = runs.create(trigger, clock.instant());
            executor.submit(() -> {
                try {
                    execute(runId, trigger);
                } catch (RuntimeException e) {
                    log.error("Ingestion run {} failed unexpectedly", runId, e);
                    finishQuietly(runId, "Unexpected error: " + e.getMessage());
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return OptionalLong.of(runId);
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Runs a full load on the calling thread and returns the final status. */
    public String runNow(String trigger) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("An ingestion run is already in progress");
        }
        try {
            return execute(runs.create(trigger, clock.instant()), trigger);
        } finally {
            running.set(false);
        }
    }

    private void finishQuietly(long runId, String message) {
        try {
            runs.finish(runId, "FAILED", clock.instant(), message);
        } catch (RuntimeException e) {
            log.error("Could not record the failure of ingestion run {}", runId, e);
        }
    }

    private String execute(long runId, String trigger) {
        log.info("Ingestion run {} started ({})", runId, trigger);

        if (!secClient.isConfigured()) {
            return finish(runId, "FAILED", "SEC_USER_AGENT is not set. The SEC requires a name and contact email, "
                    + "for example: SEC_USER_AGENT=\"Jane Doe jane@example.com\".");
        }

        Map<String, TickerEntry> directory;
        try {
            directory = secClient.tickerDirectory();
        } catch (RuntimeException e) {
            return finish(runId, "FAILED", "Could not load the SEC ticker list: " + e.getMessage());
        }

        int succeeded = 0;
        int incomplete = 0;
        int failed = 0;
        for (String sectorKey : catalog.keys()) {
            PeerLensProperties.Sector sector = catalog.get(sectorKey);
            for (String ticker : catalog.tickers(sectorKey)) {
                try {
                    LoadResult result = loadCompany(ticker, sectorKey, sector, directory);
                    if (result.missingMetrics().isEmpty()) {
                        runs.addResult(runId, ticker, "SUCCEEDED", result.factsLoaded(), null);
                        succeeded++;
                    } else {
                        String warning = "No annual values found for " + String.join(", ", result.missingMetrics())
                                + ". Check which XBRL concepts this company uses.";
                        log.warn("Ingestion of {} is incomplete: {}", ticker, warning);
                        runs.addResult(runId, ticker, "INCOMPLETE", result.factsLoaded(), warning);
                        incomplete++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Ingestion of {} failed: {}", ticker, e.getMessage());
                    runs.addResult(runId, ticker, "FAILED", 0, e.getMessage());
                    failed++;
                }
            }
        }

        String status = failed == 0 && incomplete == 0 ? "SUCCEEDED"
                : succeeded == 0 && incomplete == 0 ? "FAILED"
                : "PARTIAL";
        return finish(runId, status,
                succeeded + " companies loaded, " + incomplete + " incomplete, " + failed + " failed.");
    }

    /** @param missingMetrics metrics for which no annual value was found */
    private record LoadResult(int factsLoaded, List<String> missingMetrics) {
    }

    private LoadResult loadCompany(String ticker, String sectorKey, PeerLensProperties.Sector sector,
                                   Map<String, TickerEntry> directory) {
        TickerEntry entry = directory.get(ticker);
        if (entry == null) {
            throw new IllegalStateException(ticker + " is not in the SEC ticker list");
        }
        CompanyFacts companyFacts = secClient.companyFacts(entry.paddedCik());

        Map<Metric, List<AnnualValue>> allYears = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) {
            allYears.put(metric, extractor.extract(companyFacts, metric.concepts(properties.concepts(), sector)));
        }
        Map<Metric, List<AnnualValue>> series =
                AnnualFactExtractor.keepRecentYears(allYears, properties.ingest().yearsToKeep());
        if (series.values().stream().allMatch(List::isEmpty)) {
            throw new IllegalStateException("No annual figures found for " + ticker + " under the configured concepts");
        }

        Instant now = clock.instant();
        Integer written = transactions.execute(status -> {
            companies.upsert(ticker, entry.paddedCik(), entry.displayName(), sectorKey);
            int count = facts.replaceFacts(ticker, series);
            companies.markIngested(ticker, now);
            return count;
        });
        List<String> missing = series.entrySet().stream()
                .filter(metricValues -> metricValues.getValue().isEmpty())
                .map(metricValues -> metricValues.getKey().key())
                .toList();
        return new LoadResult(written == null ? 0 : written, missing);
    }

    private String finish(long runId, String status, String message) {
        runs.finish(runId, status, clock.instant(), message);
        log.info("Ingestion run {} finished: {} ({})", runId, status, message);
        return status;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
