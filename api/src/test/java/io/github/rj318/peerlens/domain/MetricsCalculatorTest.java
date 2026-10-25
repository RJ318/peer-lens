package io.github.rj318.peerlens.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.rj318.peerlens.domain.AnnualValue.YearOverYear;

class MetricsCalculatorTest {

    private final MetricsCalculator calculator = new MetricsCalculator();

    @Test
    void derivesMarginAndCashConversionAndUsesSameFilingGrowth() {
        List<FiscalYear> years = calculator.fiscalYears(Map.of(
                Metric.REVENUE, List.of(value("2023-12-31", 1000, null), value("2024-12-31", 1100, "0.1")),
                Metric.NET_INCOME, List.of(value("2023-12-31", 120, null), value("2024-12-31", 165, null)),
                Metric.OPERATING_CASH_FLOW, List.of(value("2024-12-31", 198, null))));

        assertThat(years).hasSize(2);
        FiscalYear latest = years.get(1);
        assertThat(latest.fiscalYear()).isEqualTo(2024);
        assertThat(latest.netMargin()).isEqualTo(0.15);
        assertThat(latest.revenueGrowth()).isEqualTo(0.1);
        assertThat(latest.cashConversion()).isEqualTo(1.2);
        assertThat(years.get(0).revenueGrowth()).isNull();
        assertThat(years.get(0).cashConversion()).isNull();
    }

    @Test
    void growthIsNeverRecomputedAcrossFilings() {
        // A restated 2024 (850) next to an unrestated 2023 (1000) would read as -15%. Without a same-filing
        // comparison the calculator must leave growth empty rather than compare the two.
        List<FiscalYear> years = calculator.fiscalYears(Map.of(
                Metric.REVENUE, List.of(value("2023-12-31", 1000, null), value("2024-12-31", 850, null))));

        assertThat(years.get(1).revenueGrowth()).isNull();
    }

    @Test
    void cashConversionIsUndefinedForLosses() {
        List<FiscalYear> years = calculator.fiscalYears(Map.of(
                Metric.REVENUE, List.of(value("2024-12-31", 900, null)),
                Metric.NET_INCOME, List.of(value("2024-12-31", -50, null)),
                Metric.OPERATING_CASH_FLOW, List.of(value("2024-12-31", 30, null))));

        assertThat(years.get(0).cashConversion()).isNull();
        assertThat(years.get(0).netMargin()).isEqualTo(-0.0556);
    }

    @Test
    void labelsFiftyTwoWeekYearsEndingInEarlyJanuaryByThePriorYear() {
        // Johnson & Johnson: the years ending 2023-01-01 and 2023-12-31 are fiscal 2022 and 2023.
        assertThat(MetricsCalculator.fiscalYearLabel(LocalDate.parse("2023-01-01"))).isEqualTo(2022);
        assertThat(MetricsCalculator.fiscalYearLabel(LocalDate.parse("2021-01-03"))).isEqualTo(2020);
        assertThat(MetricsCalculator.fiscalYearLabel(LocalDate.parse("2023-12-31"))).isEqualTo(2023);
        // NVIDIA: the year ending 2026-01-25 is its fiscal 2026.
        assertThat(MetricsCalculator.fiscalYearLabel(LocalDate.parse("2026-01-25"))).isEqualTo(2026);
    }

    @Test
    void zeroRevenueNeverDividesByZero() {
        assertThat(MetricsCalculator.ratio(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
        assertThat(MetricsCalculator.ratio(null, BigDecimal.TEN)).isNull();
    }

    private static AnnualValue value(String periodEnd, long amount, String growth) {
        LocalDate end = LocalDate.parse(periodEnd);
        YearOverYear yoy = growth == null ? null
                : new YearOverYear(new BigDecimal(growth), "Concept", "10-K", end.plusMonths(2), "0000000000-00-000001");
        return new AnnualValue(end, BigDecimal.valueOf(amount), "Concept", "10-K", end.plusMonths(2),
                "0000000000-00-000001", yoy);
    }
}
