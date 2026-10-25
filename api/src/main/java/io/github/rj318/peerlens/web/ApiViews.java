package io.github.rj318.peerlens.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** JSON shapes returned by the API. Documented in docs/API.md. */
public final class ApiViews {

    private ApiViews() {
    }

    public record SectorView(String key, String label, int companyCount) {
    }

    /** Where a number came from, with a link to the filing index on sec.gov. */
    public record SourceView(String concept, String form, LocalDate filed, String accession, String url) {
    }

    public record YearView(String ticker, String name, Integer fiscalYear, LocalDate periodEnd, BigDecimal revenue,
                           BigDecimal netIncome, BigDecimal operatingCashFlow, Double netMargin,
                           Double revenueGrowth, Double cashConversion, Map<String, SourceView> sources) {
    }

    public record ComparisonView(SectorView sector, List<YearView> companies) {
    }

    public record CompanyHistoryView(String ticker, String name, List<YearView> years) {
    }

    public record HistoryView(SectorView sector, List<CompanyHistoryView> companies) {
    }

    public record CompanySummaryView(String ticker, String name, String cik, String sector, Instant lastIngestedAt) {
    }

    public record CompanyDetailView(String ticker, String name, String cik, String sector, Instant lastIngestedAt,
                                    List<YearView> years) {
    }

    public record IngestionStartedView(long runId, String status, String latestRunUrl) {
    }

    public record IngestionResultView(String ticker, String status, int factsLoaded, String error) {
    }

    public record IngestionRunView(long id, String trigger, String status, Instant startedAt, Instant finishedAt,
                                   String message, List<IngestionResultView> results) {
    }
}
