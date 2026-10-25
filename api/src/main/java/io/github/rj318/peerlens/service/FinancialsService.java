package io.github.rj318.peerlens.service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.rj318.peerlens.config.PeerLensProperties;
import io.github.rj318.peerlens.domain.AnnualValue;
import io.github.rj318.peerlens.domain.FiscalYear;
import io.github.rj318.peerlens.domain.Metric;
import io.github.rj318.peerlens.domain.MetricsCalculator;
import io.github.rj318.peerlens.repository.CompanyRepository;
import io.github.rj318.peerlens.repository.CompanyRepository.CompanyRecord;
import io.github.rj318.peerlens.repository.FactRepository;
import io.github.rj318.peerlens.web.ApiException;
import io.github.rj318.peerlens.web.ApiViews.CompanyDetailView;
import io.github.rj318.peerlens.web.ApiViews.CompanyHistoryView;
import io.github.rj318.peerlens.web.ApiViews.CompanySummaryView;
import io.github.rj318.peerlens.web.ApiViews.ComparisonView;
import io.github.rj318.peerlens.web.ApiViews.HistoryView;
import io.github.rj318.peerlens.web.ApiViews.SectorView;
import io.github.rj318.peerlens.web.ApiViews.SourceView;
import io.github.rj318.peerlens.web.ApiViews.YearView;

/** Read side of the API: assembles stored facts into the views the dashboard needs. */
@Service
public class FinancialsService {

    private static final String FILING_INDEX = "https://www.sec.gov/Archives/edgar/data/%d/%s/%s-index.htm";

    private final SectorCatalog catalog;
    private final CompanyRepository companies;
    private final FactRepository facts;
    private final MetricsCalculator calculator;

    public FinancialsService(SectorCatalog catalog, CompanyRepository companies, FactRepository facts,
                             MetricsCalculator calculator) {
        this.catalog = catalog;
        this.companies = companies;
        this.facts = facts;
        this.calculator = calculator;
    }

    public List<SectorView> sectors() {
        return catalog.keys().stream().map(this::sectorView).toList();
    }

    /** Each company's most recent fiscal year. Companies with no data yet still appear, with nulls. */
    public ComparisonView comparison(String sectorKey) {
        Map<String, List<YearView>> history = yearsByTicker(requireSector(sectorKey));
        List<YearView> latest = history.entrySet().stream()
                .map(entry -> entry.getValue().isEmpty()
                        ? emptyYear(entry.getKey())
                        : entry.getValue().get(entry.getValue().size() - 1))
                .toList();
        return new ComparisonView(sectorView(sectorKey), latest);
    }

    public HistoryView history(String sectorKey) {
        Map<String, List<YearView>> history = yearsByTicker(requireSector(sectorKey));
        Map<String, CompanyRecord> stored = companies.findAll();
        List<CompanyHistoryView> rows = history.entrySet().stream()
                .map(entry -> new CompanyHistoryView(entry.getKey(), displayName(entry.getKey(), stored),
                        entry.getValue()))
                .toList();
        return new HistoryView(sectorView(sectorKey), rows);
    }

    public List<CompanySummaryView> companies(String sectorKey) {
        List<String> tickers = sectorKey == null ? catalog.allTickers() : requireSector(sectorKey);
        Map<String, CompanyRecord> stored = companies.findAll();
        return tickers.stream().map(ticker -> {
            CompanyRecord record = stored.get(ticker);
            return new CompanySummaryView(ticker, displayName(ticker, stored), record == null ? null : record.cik(),
                    catalog.sectorOf(ticker).orElseThrow(), record == null ? null : record.lastIngestedAt());
        }).toList();
    }

    public CompanyDetailView company(String rawTicker) {
        String ticker = SectorCatalog.normalize(rawTicker);
        String sector = catalog.sectorOf(ticker)
                .orElseThrow(() -> ApiException.notFound("Ticker " + ticker + " is not on the watchlist."));
        Map<String, CompanyRecord> stored = companies.findAll();
        CompanyRecord record = stored.get(ticker);
        List<YearView> years = yearsByTicker(List.of(ticker), stored).get(ticker);
        return new CompanyDetailView(ticker, displayName(ticker, stored), record == null ? null : record.cik(),
                sector, record == null ? null : record.lastIngestedAt(), years);
    }

    private List<String> requireSector(String sectorKey) {
        if (catalog.find(sectorKey).isEmpty()) {
            throw ApiException.notFound("Unknown sector: " + sectorKey + ". Valid sectors: " + catalog.keys());
        }
        return catalog.tickers(sectorKey);
    }

    private Map<String, List<YearView>> yearsByTicker(List<String> tickers) {
        return yearsByTicker(tickers, companies.findAll());
    }

    private Map<String, List<YearView>> yearsByTicker(List<String> tickers, Map<String, CompanyRecord> stored) {
        Map<String, Map<Metric, List<AnnualValue>>> storedFacts = facts.findByTickers(tickers);
        Map<String, List<YearView>> result = new LinkedHashMap<>();
        for (String ticker : tickers) {
            Map<Metric, List<AnnualValue>> series = storedFacts.getOrDefault(ticker, new EnumMap<>(Metric.class));
            CompanyRecord record = stored.get(ticker);
            String name = displayName(ticker, stored);
            result.put(ticker, calculator.fiscalYears(series).stream()
                    .map(year -> toView(ticker, name, record, year))
                    .toList());
        }
        return result;
    }

    private YearView toView(String ticker, String name, CompanyRecord record, FiscalYear year) {
        Map<String, SourceView> sources = new LinkedHashMap<>();
        for (Metric metric : Metric.values()) {
            AnnualValue value = year.value(metric);
            if (value != null) {
                sources.put(metric.key(), new SourceView(value.concept(), value.form(), value.filed(),
                        value.accession(), filingUrl(record, value.accession())));
            }
        }
        AnnualValue revenue = year.value(Metric.REVENUE);
        if (revenue != null && revenue.yearOverYear() != null) {
            // Growth may come from a different filing than the displayed revenue (the latest one that
            // reports both years), so it gets its own source.
            var yoy = revenue.yearOverYear();
            sources.put("revenueGrowth", new SourceView(yoy.concept(), yoy.form(), yoy.filed(), yoy.accession(),
                    filingUrl(record, yoy.accession())));
        }
        return new YearView(ticker, name, year.fiscalYear(), year.periodEnd(),
                dollars(year, Metric.REVENUE), dollars(year, Metric.NET_INCOME),
                dollars(year, Metric.OPERATING_CASH_FLOW),
                year.netMargin(), year.revenueGrowth(), year.cashConversion(), sources);
    }

    private YearView emptyYear(String ticker) {
        String name = displayName(ticker, companies.findAll());
        return new YearView(ticker, name, null, null, null, null, null, null, null, null, Map.of());
    }

    private SectorView sectorView(String key) {
        PeerLensProperties.Sector sector = catalog.get(key);
        return new SectorView(key, sector.label(), sector.tickers().size());
    }

    private static java.math.BigDecimal dollars(FiscalYear year, Metric metric) {
        AnnualValue value = year.value(metric);
        return value == null ? null : value.amount().setScale(0, java.math.RoundingMode.HALF_UP);
    }

    private static String filingUrl(CompanyRecord record, String accession) {
        if (record == null || accession == null) {
            return null;
        }
        return FILING_INDEX.formatted(Long.parseLong(record.cik()), accession.replace("-", ""), accession);
    }

    private static String displayName(String ticker, Map<String, CompanyRecord> stored) {
        CompanyRecord record = stored.get(ticker);
        return record == null ? ticker : record.name();
    }
}
