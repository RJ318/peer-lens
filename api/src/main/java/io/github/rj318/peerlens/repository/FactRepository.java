package io.github.rj318.peerlens.repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.rj318.peerlens.domain.AnnualValue;
import io.github.rj318.peerlens.domain.AnnualValue.YearOverYear;
import io.github.rj318.peerlens.domain.Metric;

@Repository
public class FactRepository {

    private final JdbcClient jdbc;

    public FactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replace everything stored for one company. Callers run this inside a transaction, so a load that
     * fails halfway leaves the previous data in place rather than a partial set.
     *
     * @return number of facts written
     */
    public int replaceFacts(String ticker, Map<Metric, List<AnnualValue>> facts) {
        jdbc.sql("DELETE FROM annual_fact WHERE ticker = :ticker").param("ticker", ticker).update();
        int written = 0;
        for (Map.Entry<Metric, List<AnnualValue>> entry : facts.entrySet()) {
            for (AnnualValue value : entry.getValue()) {
                YearOverYear yoy = value.yearOverYear();
                jdbc.sql("""
                        INSERT INTO annual_fact (ticker, metric, period_end, amount, concept, form, filed, accession,
                                                 yoy_change, yoy_concept, yoy_form, yoy_filed, yoy_accession)
                        VALUES (:ticker, :metric, :periodEnd, :amount, :concept, :form, :filed, :accession,
                                :yoyChange, :yoyConcept, :yoyForm, :yoyFiled, :yoyAccession)
                        """)
                        .param("ticker", ticker)
                        .param("metric", entry.getKey().key())
                        .param("periodEnd", value.periodEnd())
                        .param("amount", value.amount())
                        .param("concept", value.concept())
                        .param("form", value.form())
                        .param("filed", value.filed())
                        .param("accession", value.accession())
                        .param("yoyChange", yoy == null ? null : yoy.change(), Types.NUMERIC)
                        .param("yoyConcept", yoy == null ? null : yoy.concept(), Types.VARCHAR)
                        .param("yoyForm", yoy == null ? null : yoy.form(), Types.VARCHAR)
                        .param("yoyFiled", yoy == null ? null : yoy.filed(), Types.DATE)
                        .param("yoyAccession", yoy == null ? null : yoy.accession(), Types.VARCHAR)
                        .update();
                written++;
            }
        }
        return written;
    }

    /** Stored facts for the given tickers, grouped by ticker and metric, oldest period first. */
    public Map<String, Map<Metric, List<AnnualValue>>> findByTickers(Collection<String> tickers) {
        Map<String, Map<Metric, List<AnnualValue>>> result = new HashMap<>();
        if (tickers.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                SELECT ticker, metric, period_end, amount, concept, form, filed, accession,
                       yoy_change, yoy_concept, yoy_form, yoy_filed, yoy_accession
                FROM annual_fact
                WHERE ticker IN (:tickers)
                ORDER BY ticker, metric, period_end
                """)
                .param("tickers", tickers)
                .query(rs -> {
                    BigDecimal yoyChange = rs.getBigDecimal("yoy_change");
                    YearOverYear yoy = yoyChange == null ? null : new YearOverYear(yoyChange,
                            rs.getString("yoy_concept"), rs.getString("yoy_form"),
                            rs.getObject("yoy_filed", LocalDate.class), rs.getString("yoy_accession"));
                    AnnualValue value = new AnnualValue(
                            rs.getObject("period_end", LocalDate.class),
                            rs.getBigDecimal("amount"),
                            rs.getString("concept"),
                            rs.getString("form"),
                            rs.getObject("filed", LocalDate.class),
                            rs.getString("accession"),
                            yoy);
                    result.computeIfAbsent(rs.getString("ticker"), t -> new EnumMap<>(Metric.class))
                            .computeIfAbsent(Metric.fromKey(rs.getString("metric")), m -> new ArrayList<>())
                            .add(value);
                });
        return result;
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM annual_fact").query(Long.class).single();
    }
}
