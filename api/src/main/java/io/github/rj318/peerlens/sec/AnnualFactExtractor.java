package io.github.rj318.peerlens.sec;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import io.github.rj318.peerlens.domain.AnnualValue;
import io.github.rj318.peerlens.domain.AnnualValue.YearOverYear;

/**
 * Turns the raw, repetitive company facts into one clean value per fiscal year.
 *
 * <p>Four problems in the raw data drive the rules here:
 * <ul>
 *   <li>Every 10-K repeats the prior two years as comparatives, and amendments and restatements
 *       re-report periods. For each period the most recently filed value wins, whichever concept it
 *       was tagged with.</li>
 *   <li>Annual filings also contain quarterly values. Only periods of roughly one year count
 *       (350 to 380 days covers 52- and 53-week fiscal years).</li>
 *   <li>Companies tag line items with different concepts, change concepts over time, and sometimes tag a
 *       component (product revenue) and the total (all revenue) in the same filing. Concept priority
 *       decides only between values reported in the same filing.</li>
 *   <li>A restatement recasts only the years in the restating filing. Year-over-year change is therefore
 *       measured within one filing that reports both years, never across filings.</li>
 * </ul>
 */
public final class AnnualFactExtractor {

    static final int MIN_ANNUAL_DAYS = 350;
    static final int MAX_ANNUAL_DAYS = 380;
    /** A prior fiscal year ends roughly twelve months before the current one. */
    static final int MIN_DAYS_BETWEEN_YEARS = 330;
    static final int MAX_DAYS_BETWEEN_YEARS = 400;
    private static final String TAXONOMY = "us-gaap";
    private static final String UNIT = "USD";
    private static final int CHANGE_SCALE = 6;

    /** One annual fact and the priority of the concept it was tagged with (0 is highest). */
    private record Candidate(CompanyFacts.Fact fact, String concept, int priority) {
    }

    /** Greater means preferred: filed later, then an amendment, then a higher-priority concept. */
    private static final Comparator<Candidate> PREFERRED = Comparator
            .comparing((Candidate c) -> c.fact().filed())
            .thenComparing(c -> c.fact().form().endsWith("/A"))
            .thenComparing(Candidate::priority, Comparator.reverseOrder())
            .thenComparing(c -> c.fact().accn());

    /**
     * @param facts    the company facts response
     * @param concepts concepts to try, highest priority first
     * @return every annual value found, oldest first
     */
    public List<AnnualValue> extract(CompanyFacts facts, List<String> concepts) {
        List<Candidate> candidates = annualCandidates(facts, concepts);

        Map<LocalDate, Candidate> best = candidates.stream().collect(
                Collectors.toMap(c -> c.fact().end(), c -> c, BinaryOperator.maxBy(PREFERRED), TreeMap::new));

        // Group values by filing and concept, so year-over-year change can be measured within one filing.
        Map<String, Map<LocalDate, Candidate>> byFilingAndConcept = new HashMap<>();
        for (Candidate candidate : candidates) {
            byFilingAndConcept
                    .computeIfAbsent(candidate.fact().accn() + "|" + candidate.concept(), key -> new HashMap<>())
                    .merge(candidate.fact().end(), candidate, BinaryOperator.maxBy(PREFERRED));
        }

        List<AnnualValue> values = new ArrayList<>();
        best.forEach((periodEnd, candidate) ->
                values.add(toAnnualValue(candidate, yearOverYear(periodEnd, byFilingAndConcept))));
        return values;
    }

    /**
     * Keeps the same fiscal years for every metric: the {@code yearsToKeep} most recent period ends seen in
     * any metric. Trimming each metric on its own would misalign the years whenever one metric has a gap.
     *
     * @return a new map with each metric's values limited to the shared window, oldest first
     */
    public static <K> Map<K, List<AnnualValue>> keepRecentYears(Map<K, List<AnnualValue>> series, int yearsToKeep) {
        TreeSet<LocalDate> periodEnds = new TreeSet<>();
        series.values().forEach(values -> values.forEach(value -> periodEnds.add(value.periodEnd())));
        Set<LocalDate> window = periodEnds.descendingSet().stream().limit(yearsToKeep).collect(Collectors.toSet());

        Map<K, List<AnnualValue>> trimmed = new LinkedHashMap<>();
        series.forEach((key, values) -> trimmed.put(key,
                values.stream().filter(value -> window.contains(value.periodEnd())).toList()));
        return trimmed;
    }

    /** Change versus the prior year from the most authoritative filing that reports both years. */
    private static YearOverYear yearOverYear(LocalDate periodEnd,
                                             Map<String, Map<LocalDate, Candidate>> byFilingAndConcept) {
        Candidate chosen = null;
        Candidate chosenPrior = null;
        for (Map<LocalDate, Candidate> filing : byFilingAndConcept.values()) {
            Candidate current = filing.get(periodEnd);
            if (current == null) {
                continue;
            }
            Candidate prior = filing.values().stream()
                    .filter(c -> isPriorYear(c.fact().end(), periodEnd))
                    .findFirst()
                    .orElse(null);
            if (prior == null || prior.fact().val().signum() <= 0) {
                continue;
            }
            if (chosen == null || PREFERRED.compare(current, chosen) > 0) {
                chosen = current;
                chosenPrior = prior;
            }
        }
        if (chosen == null) {
            return null;
        }
        BigDecimal priorAmount = chosenPrior.fact().val();
        BigDecimal change = chosen.fact().val().subtract(priorAmount)
                .divide(priorAmount, MathContext.DECIMAL64)
                .setScale(CHANGE_SCALE, RoundingMode.HALF_UP);
        CompanyFacts.Fact fact = chosen.fact();
        return new YearOverYear(change, chosen.concept(), fact.form(), fact.filed(), fact.accn());
    }

    static boolean isPriorYear(LocalDate candidate, LocalDate periodEnd) {
        long days = ChronoUnit.DAYS.between(candidate, periodEnd);
        return days >= MIN_DAYS_BETWEEN_YEARS && days <= MAX_DAYS_BETWEEN_YEARS;
    }

    private static List<Candidate> annualCandidates(CompanyFacts facts, List<String> concepts) {
        Map<String, CompanyFacts.Concept> taxonomy = facts.facts() == null ? Map.of()
                : facts.facts().getOrDefault(TAXONOMY, Map.of());
        List<Candidate> candidates = new ArrayList<>();
        for (int priority = 0; priority < concepts.size(); priority++) {
            String conceptName = concepts.get(priority);
            CompanyFacts.Concept concept = taxonomy.get(conceptName);
            if (concept == null || concept.units() == null || !concept.units().containsKey(UNIT)) {
                continue;
            }
            for (CompanyFacts.Fact fact : concept.units().get(UNIT)) {
                if (isAnnualFromAnnualReport(fact)) {
                    candidates.add(new Candidate(fact, conceptName, priority));
                }
            }
        }
        return candidates;
    }

    static boolean isAnnualFromAnnualReport(CompanyFacts.Fact fact) {
        if (fact.val() == null || fact.start() == null || fact.end() == null || fact.filed() == null
                || fact.form() == null || fact.accn() == null || !fact.form().startsWith("10-K")) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(fact.start(), fact.end());
        return days >= MIN_ANNUAL_DAYS && days <= MAX_ANNUAL_DAYS;
    }

    private static AnnualValue toAnnualValue(Candidate candidate, YearOverYear yearOverYear) {
        CompanyFacts.Fact fact = candidate.fact();
        return new AnnualValue(fact.end(), fact.val(), candidate.concept(), fact.form(), fact.filed(), fact.accn(),
                yearOverYear);
    }
}
