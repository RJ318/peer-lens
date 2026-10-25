package io.github.rj318.peerlens.domain;

import java.time.LocalDate;
import java.util.Map;

/**
 * One company's fiscal year: the reported values plus ratios derived from them. Any field can be
 * null when the filings do not support it; a missing number is never silently treated as zero.
 *
 * @param fiscalYear      calendar year in which the fiscal period ends
 * @param periodEnd       last day of the fiscal period
 * @param reported        reported values by metric, each with its source filing
 * @param netMargin       net income divided by revenue
 * @param revenueGrowth   change in revenue versus the prior fiscal year
 * @param cashConversion  operating cash flow divided by net income
 */
public record FiscalYear(int fiscalYear, LocalDate periodEnd, Map<Metric, AnnualValue> reported, Double netMargin,
                         Double revenueGrowth, Double cashConversion) {

    public FiscalYear {
        reported = Map.copyOf(reported);
    }

    public AnnualValue value(Metric metric) {
        return reported.get(metric);
    }
}
