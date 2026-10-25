package io.github.rj318.peerlens.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CompanyRepository {

    private final JdbcClient jdbc;

    public CompanyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert the company, or refresh its CIK, name, and sector if it already exists. */
    public void upsert(String ticker, String cik, String name, String sector) {
        int updated = jdbc.sql("UPDATE company SET cik = :cik, name = :name, sector = :sector WHERE ticker = :ticker")
                .param("cik", cik).param("name", name).param("sector", sector).param("ticker", ticker)
                .update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO company (ticker, cik, name, sector) VALUES (:ticker, :cik, :name, :sector)")
                    .param("ticker", ticker).param("cik", cik).param("name", name).param("sector", sector)
                    .update();
        }
    }

    public void markIngested(String ticker, Instant at) {
        jdbc.sql("UPDATE company SET last_ingested_at = :at WHERE ticker = :ticker")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).param("ticker", ticker)
                .update();
    }

    /** Every stored company keyed by ticker. */
    public Map<String, CompanyRecord> findAll() {
        Map<String, CompanyRecord> companies = new LinkedHashMap<>();
        jdbc.sql("SELECT ticker, cik, name, sector, last_ingested_at FROM company ORDER BY ticker")
                .query((rs, row) -> {
                    OffsetDateTime ingested = rs.getObject("last_ingested_at", OffsetDateTime.class);
                    return new CompanyRecord(rs.getString("ticker"), rs.getString("cik"), rs.getString("name"),
                            rs.getString("sector"), ingested == null ? null : ingested.toInstant());
                })
                .list()
                .forEach(company -> companies.put(company.ticker(), company));
        return companies;
    }

    public record CompanyRecord(String ticker, String cik, String name, String sector, Instant lastIngestedAt) {
    }
}
