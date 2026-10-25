package io.github.rj318.peerlens.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One reported annual figure and the filing it came from, so every number can be traced back.
 *
 * @param periodEnd      last day of the fiscal year
 * @param amount         value in US dollars
 * @param concept        XBRL concept the value was tagged with
 * @param form           SEC form type, for example 10-K or 10-K/A
 * @param filed          date the filing was submitted
 * @param accession      SEC accession number of the filing
 * @param yearOverYear   change versus the prior fiscal year, measured within a single filing; null when no
 *                       filing reports both years
 */
public record AnnualValue(LocalDate periodEnd, BigDecimal amount, String concept, String form, LocalDate filed,
                          String accession, YearOverYear yearOverYear) {

    public AnnualValue(LocalDate periodEnd, BigDecimal amount, String concept, String form, LocalDate filed,
                       String accession) {
        this(periodEnd, amount, concept, form, filed, accession, null);
    }

    /**
     * Change versus the prior fiscal year with both values taken from the same filing, so a restatement in
     * one year is never compared with an unrestated figure for the other.
     *
     * @param change    (current - prior) / prior
     * @param concept   XBRL concept both values were tagged with
     * @param form      form type of the filing
     * @param filed     filing date
     * @param accession accession number of the filing
     */
    public record YearOverYear(BigDecimal change, String concept, String form, LocalDate filed, String accession) {
    }
}
