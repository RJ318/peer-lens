package io.github.rj318.peerlens.domain;

import java.util.List;

import io.github.rj318.peerlens.config.PeerLensProperties;

/** The three values loaded from SEC filings. Everything else is derived from these. */
public enum Metric {

    REVENUE("revenue"),
    NET_INCOME("netIncome"),
    OPERATING_CASH_FLOW("operatingCashFlow");

    private final String key;

    Metric(String key) {
        this.key = key;
    }

    /** Name used in the database and the JSON API. */
    public String key() {
        return key;
    }

    public static Metric fromKey(String key) {
        for (Metric metric : values()) {
            if (metric.key.equals(key)) {
                return metric;
            }
        }
        throw new IllegalArgumentException("Unknown metric: " + key);
    }

    /** Concepts to try for this metric, honoring a sector's own revenue list when it has one. */
    public List<String> concepts(PeerLensProperties.Concepts concepts, PeerLensProperties.Sector sector) {
        return switch (this) {
            case REVENUE -> sector.revenueConcepts() != null && !sector.revenueConcepts().isEmpty()
                    ? sector.revenueConcepts()
                    : concepts.revenue();
            case NET_INCOME -> concepts.netIncome();
            case OPERATING_CASH_FLOW -> concepts.operatingCashFlow();
        };
    }
}
