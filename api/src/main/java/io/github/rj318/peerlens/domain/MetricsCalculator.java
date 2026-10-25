package io.github.rj318.peerlens.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/** Lines up the reported metrics by fiscal year and derives the ratios. */
@Component
public class MetricsCalculator {

    private static final int RATIO_SCALE = 4;
    /**
     * Companies on a 52- or 53-week calendar sometimes end a fiscal year in the first days of January,
     * and label it by the year before (a year ending 2023-01-01 is fiscal 2022). Stepping back a week
     * before taking the year handles that without mislabeling years that genuinely end in January.
     */
    static final int FISCAL_YEAR_LABEL_LOOKBACK_DAYS = 7;

    /**
     * @param series reported values by metric, in any order
     * @return fiscal years, oldest first
     */
    public List<FiscalYear> fiscalYears(Map<Metric, List<AnnualValue>> series) {
        TreeMap<LocalDate, Map<Metric, AnnualValue>> byPeriodEnd = new TreeMap<>();
        series.forEach((metric, values) -> values.forEach(value -> byPeriodEnd
                .computeIfAbsent(value.periodEnd(), end -> new EnumMap<>(Metric.class))
                .put(metric, value)));

        List<FiscalYear> years = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<Metric, AnnualValue>> entry : byPeriodEnd.entrySet()) {
            Map<Metric, AnnualValue> reported = entry.getValue();
            BigDecimal revenue = amount(reported, Metric.REVENUE);
            BigDecimal netIncome = amount(reported, Metric.NET_INCOME);
            BigDecimal cashFlow = amount(reported, Metric.OPERATING_CASH_FLOW);

            years.add(new FiscalYear(
                    fiscalYearLabel(entry.getKey()),
                    entry.getKey(),
                    reported,
                    ratio(netIncome, revenue),
                    revenueGrowth(reported.get(Metric.REVENUE)),
                    netIncome != null && netIncome.signum() > 0 ? ratio(cashFlow, netIncome) : null));
        }
        return years;
    }

    static int fiscalYearLabel(LocalDate periodEnd) {
        return periodEnd.minusDays(FISCAL_YEAR_LABEL_LOOKBACK_DAYS).getYear();
    }

    /** Growth comes from a single filing that reports both years, so restatements never distort it. */
    private static Double revenueGrowth(AnnualValue revenue) {
        if (revenue == null || revenue.yearOverYear() == null) {
            return null;
        }
        return revenue.yearOverYear().change().setScale(RATIO_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    /** numerator / denominator to four decimal places, or null when either side is missing or the base is not positive. */
    static Double ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, MathContext.DECIMAL64)
                .setScale(RATIO_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static BigDecimal amount(Map<Metric, AnnualValue> reported, Metric metric) {
        AnnualValue value = reported.get(metric);
        return value == null ? null : value.amount();
    }
}
