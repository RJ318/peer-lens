package io.github.rj318.peerlens.sec;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.rj318.peerlens.domain.AnnualValue;

class AnnualFactExtractorTest {

    private static final List<String> REVENUE = List.of(
            "RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet");

    private final AnnualFactExtractor extractor = new AnnualFactExtractor();

    @Test
    void keepsOneValuePerFiscalYearUsingTheMostRecentFiling() {
        List<AnnualValue> values = extractor.extract(company(
                fact("2022-01-01", "2022-12-31", 900, "A-23", "10-K", "2023-02-20"),
                fact("2022-01-01", "2022-12-31", 905, "A-24", "10-K", "2024-02-20")), REVENUE);

        assertThat(values).singleElement().satisfies(value -> {
            assertThat(value.amount()).isEqualByComparingTo("905");
            assertThat(value.accession()).isEqualTo("A-24");
        });
    }

    @Test
    void amendmentWinsOverOriginalFiledSameDay() {
        List<AnnualValue> values = extractor.extract(company(
                fact("2023-01-01", "2023-12-31", 1000, "A-1", "10-K", "2024-02-20"),
                fact("2023-01-01", "2023-12-31", 990, "A-2", "10-K/A", "2024-02-20")), REVENUE);

        assertThat(values).singleElement().extracting(AnnualValue::form).isEqualTo("10-K/A");
    }

    @Test
    void ignoresQuarterlyPeriodsAndQuarterlyReports() {
        List<AnnualValue> values = extractor.extract(company(
                fact("2024-01-01", "2024-03-31", 260, "Q-1", "10-Q", "2024-05-01"),
                fact("2024-10-01", "2024-12-31", 300, "K-1", "10-K", "2025-02-20"),
                fact("2024-01-01", "2024-12-31", 1100, "Q-9", "10-Q", "2025-05-01"),
                fact("2024-01-01", "2024-12-31", 1100, "K-1", "10-K", "2025-02-20")), REVENUE);

        assertThat(values).singleElement().extracting(AnnualValue::accession).isEqualTo("K-1");
    }

    @Test
    void laterFilingWinsEvenWhenTaggedWithALowerPriorityConcept() {
        CompanyFacts facts = new CompanyFacts(1, "Example", Map.of("us-gaap", Map.of(
                "RevenueFromContractWithCustomerExcludingAssessedTax", concept(
                        fact("2022-01-01", "2022-12-31", 917, "OLD", "10-K", "2023-02-20")),
                "Revenues", concept(
                        fact("2022-01-01", "2022-12-31", 1003, "NEW", "10-K", "2025-02-20")))));

        assertThat(extractor.extract(facts, REVENUE)).singleElement()
                .satisfies(value -> assertThat(value.amount()).isEqualByComparingTo("1003"));
    }

    @Test
    void conceptPriorityDecidesBetweenValuesInTheSameFiling() {
        // Pfizer's pattern: one filing tags product revenue with the contract concept and total revenue with
        // Revenues. With Revenues listed first, the total wins.
        CompanyFacts facts = new CompanyFacts(1, "Example", Map.of("us-gaap", Map.of(
                "RevenueFromContractWithCustomerExcludingAssessedTax", concept(
                        fact("2021-01-01", "2021-12-31", 736, "SAME", "10-K", "2024-02-22")),
                "Revenues", concept(
                        fact("2021-01-01", "2021-12-31", 813, "SAME", "10-K", "2024-02-22")))));

        List<String> revenuesFirst = List.of("Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax");
        assertThat(extractor.extract(facts, revenuesFirst)).singleElement()
                .satisfies(value -> {
                    assertThat(value.amount()).isEqualByComparingTo("813");
                    assertThat(value.concept()).isEqualTo("Revenues");
                });
    }

    @Test
    void growthIsMeasuredWithinASingleFiling() {
        // Kimberly-Clark's pattern: a later filing restates 2023 downward. Comparing restated 2023 (850)
        // with unrestated 2022 (950) would read as -10.5%. The filing that reports both 2023 and 2022 on
        // the same basis says +5.3%.
        List<AnnualValue> values = extractor.extract(company(
                fact("2022-01-01", "2022-12-31", 950, "K23", "10-K", "2024-02-10"),
                fact("2023-01-01", "2023-12-31", 1000, "K23", "10-K", "2024-02-10"),
                fact("2023-01-01", "2023-12-31", 850, "K25", "10-K", "2026-02-10"),
                fact("2024-01-01", "2024-12-31", 867, "K25", "10-K", "2026-02-10")), REVENUE);

        assertThat(values).extracting(v -> v.periodEnd().getYear(), v -> v.amount().intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2022, 950),
                        org.assertj.core.groups.Tuple.tuple(2023, 850),
                        org.assertj.core.groups.Tuple.tuple(2024, 867));
        assertThat(values.get(0).yearOverYear()).isNull();
        assertThat(values.get(1).yearOverYear().change()).isEqualByComparingTo("0.052632");
        assertThat(values.get(1).yearOverYear().accession()).isEqualTo("K23");
        assertThat(values.get(2).yearOverYear().change()).isEqualByComparingTo("0.020000");
        assertThat(values.get(2).yearOverYear().accession()).isEqualTo("K25");
    }

    @Test
    void growthPrefersTheMostRecentFilingThatReportsBothYears() {
        List<AnnualValue> values = extractor.extract(company(
                fact("2022-01-01", "2022-12-31", 100, "K23", "10-K", "2023-02-10"),
                fact("2023-01-01", "2023-12-31", 110, "K23", "10-K", "2023-02-10"),
                fact("2022-01-01", "2022-12-31", 105, "K24", "10-K", "2024-02-10"),
                fact("2023-01-01", "2023-12-31", 126, "K24", "10-K", "2024-02-10")), REVENUE);

        assertThat(values.get(1).yearOverYear().change()).isEqualByComparingTo("0.200000");
        assertThat(values.get(1).yearOverYear().accession()).isEqualTo("K24");
    }

    @Test
    void growthHandlesFiftyThreeWeekYears() {
        List<AnnualValue> values = extractor.extract(company(
                fact("2021-09-26", "2022-09-24", 400, "K", "10-K", "2023-11-03"),
                fact("2022-09-25", "2023-09-30", 440, "K", "10-K", "2023-11-03")), REVENUE);

        assertThat(values.get(1).yearOverYear().change()).isEqualByComparingTo("0.100000");
    }

    @Test
    void acceptsFiftyThreeWeekFiscalYears() {
        assertThat(AnnualFactExtractor.isAnnualFromAnnualReport(
                fact("2022-09-25", "2023-09-30", 1, "A", "10-K", "2023-11-03"))).isTrue();
        assertThat(AnnualFactExtractor.isAnnualFromAnnualReport(
                fact("2023-01-01", "2023-06-30", 1, "A", "10-K", "2023-11-03"))).isFalse();
    }

    @Test
    void higherPriorityConceptWinsAndOlderConceptFillsEarlierYears() {
        CompanyFacts facts = new CompanyFacts(1, "Example", Map.of("us-gaap", Map.of(
                "SalesRevenueNet", concept(
                        fact("2020-01-01", "2020-12-31", 700, "S-20", "10-K", "2021-02-20"),
                        fact("2021-01-01", "2021-12-31", 800, "S-21", "10-K", "2022-02-20")),
                "RevenueFromContractWithCustomerExcludingAssessedTax", concept(
                        fact("2021-01-01", "2021-12-31", 810, "R-22", "10-K", "2023-02-20")))));

        List<AnnualValue> values = extractor.extract(facts, REVENUE);

        assertThat(values).extracting(AnnualValue::periodEnd, AnnualValue::concept).containsExactly(
                org.assertj.core.groups.Tuple.tuple(LocalDate.parse("2020-12-31"), "SalesRevenueNet"),
                org.assertj.core.groups.Tuple.tuple(LocalDate.parse("2021-12-31"),
                        "RevenueFromContractWithCustomerExcludingAssessedTax"));
    }

    @Test
    void returnsEveryYearOldestFirst() {
        List<AnnualValue> values = extractor.extract(company(
                fact("2023-01-01", "2023-12-31", 3, "C", "10-K", "2024-02-01"),
                fact("2021-01-01", "2021-12-31", 1, "A", "10-K", "2022-02-01"),
                fact("2022-01-01", "2022-12-31", 2, "B", "10-K", "2023-02-01")), REVENUE);

        assertThat(values).extracting(v -> v.periodEnd().getYear()).containsExactly(2021, 2022, 2023);
    }

    @Test
    void recentYearWindowIsSharedAcrossMetrics() {
        // Revenue is missing for 2020 and 2021 (a real tagging gap seen in one bank's filings). Trimming each
        // metric to its own last three values would pull in 2017 and 2018 revenue and misalign the years.
        Map<String, List<AnnualValue>> series = Map.of(
                "revenue", List.of(value("2017-12-31"), value("2018-12-31"), value("2022-12-31")),
                "netIncome", List.of(value("2020-12-31"), value("2021-12-31"), value("2022-12-31")));

        Map<String, List<AnnualValue>> trimmed = AnnualFactExtractor.keepRecentYears(series, 3);

        assertThat(trimmed.get("revenue")).extracting(v -> v.periodEnd().getYear()).containsExactly(2022);
        assertThat(trimmed.get("netIncome")).extracting(v -> v.periodEnd().getYear())
                .containsExactly(2020, 2021, 2022);
    }

    private static AnnualValue value(String periodEnd) {
        return new AnnualValue(LocalDate.parse(periodEnd), BigDecimal.ONE, "Concept", "10-K",
                LocalDate.parse(periodEnd).plusMonths(2), "0000000000-00-000001");
    }

    @Test
    void returnsNothingWhenNoConceptMatches() {
        CompanyFacts facts = new CompanyFacts(1, "Example", Map.of("us-gaap", Map.of(
                "Assets", concept(fact(null, "2024-12-31", 5000, "A", "10-K", "2025-02-20")))));

        assertThat(extractor.extract(facts, REVENUE)).isEmpty();
        assertThat(extractor.extract(new CompanyFacts(1, "Empty", null), REVENUE)).isEmpty();
    }

    private static CompanyFacts company(CompanyFacts.Fact... revenueFacts) {
        return new CompanyFacts(1, "Example", Map.of("us-gaap",
                Map.of("RevenueFromContractWithCustomerExcludingAssessedTax", concept(revenueFacts))));
    }

    private static CompanyFacts.Concept concept(CompanyFacts.Fact... facts) {
        return new CompanyFacts.Concept("label", Map.of("USD", List.of(facts)));
    }

    static CompanyFacts.Fact fact(String start, String end, long value, String accession, String form, String filed) {
        return new CompanyFacts.Fact(start == null ? null : LocalDate.parse(start), LocalDate.parse(end),
                BigDecimal.valueOf(value), accession, null, "FY", form, LocalDate.parse(filed), null);
    }
}
