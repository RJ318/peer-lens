package io.github.rj318.peerlens.sec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One row of www.sec.gov/files/company_tickers.json, which maps tickers to CIK numbers. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickerEntry(@JsonProperty("cik_str") long cik, String ticker, String title) {

    /** The 10-digit, zero-padded form the data.sec.gov URLs expect. */
    public String paddedCik() {
        return String.format("%010d", cik);
    }

    /**
     * The registrant name without the state-of-incorporation suffix EDGAR appends to some names,
     * for example "BANK OF AMERICA CORP /DE/" becomes "BANK OF AMERICA CORP".
     */
    public String displayName() {
        return title == null ? null : title.replaceFirst("\\s*/[A-Z]{2}/?\\s*$", "").strip();
    }
}
