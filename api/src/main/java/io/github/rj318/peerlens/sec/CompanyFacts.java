package io.github.rj318.peerlens.sec;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The subset of the SEC company facts response (data.sec.gov/api/xbrl/companyfacts) that Peer Lens reads.
 *
 * @param cik        Central Index Key
 * @param entityName registrant name as filed
 * @param facts      taxonomy (for example us-gaap) to concept name to concept
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompanyFacts(long cik, String entityName, Map<String, Map<String, Concept>> facts) {

    /** @param units unit of measure (for example USD) to every reported value */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Concept(String label, Map<String, List<Fact>> units) {
    }

    /**
     * One reported value. A single 10-K reports the current year and prior-year comparatives,
     * so the same period appears in several filings.
     *
     * @param start start of the period (absent for point-in-time values such as total assets)
     * @param end   end of the period
     * @param val   reported value
     * @param accn  accession number of the filing that reported it
     * @param fy    fiscal year of the filing, not of the period
     * @param fp    fiscal period of the filing (FY, Q1, ...)
     * @param form  form type of the filing
     * @param filed filing date
     * @param frame calendar frame the SEC assigned, if any
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fact(LocalDate start, LocalDate end, BigDecimal val, String accn, Integer fy, String fp,
                       String form, LocalDate filed, String frame) {
    }
}
