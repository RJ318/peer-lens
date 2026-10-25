package io.github.rj318.peerlens.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class IngestionRunRepository {

    private static final int MAX_TEXT = 1000;

    private final JdbcClient jdbc;

    public IngestionRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String trigger, Instant startedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO ingestion_run (triggered_by, status, started_at) VALUES (:trigger, 'RUNNING', :startedAt)")
                .param("trigger", trigger)
                .param("startedAt", utc(startedAt))
                .update(keys, "id");
        return keys.getKeyAs(Long.class);
    }

    public void addResult(long runId, String ticker, String status, int factsLoaded, String error) {
        jdbc.sql("""
                INSERT INTO ingestion_result (run_id, ticker, status, facts_loaded, error)
                VALUES (:runId, :ticker, :status, :factsLoaded, :error)
                """)
                .param("runId", runId).param("ticker", ticker).param("status", status)
                .param("factsLoaded", factsLoaded).param("error", truncate(error))
                .update();
    }

    public void finish(long runId, String status, Instant finishedAt, String message) {
        jdbc.sql("UPDATE ingestion_run SET status = :status, finished_at = :finishedAt, message = :message WHERE id = :id")
                .param("status", status).param("finishedAt", utc(finishedAt)).param("message", truncate(message))
                .param("id", runId)
                .update();
    }

    /** Runs left in RUNNING by a crash or restart are closed out so they never look active forever. */
    public int abandonUnfinished(Instant at) {
        return jdbc.sql("""
                UPDATE ingestion_run SET status = 'ABANDONED', finished_at = :at,
                       message = 'The service stopped before this run finished.'
                WHERE status = 'RUNNING'
                """)
                .param("at", utc(at))
                .update();
    }

    public Optional<RunRecord> findLatest() {
        return jdbc.sql("""
                SELECT id, triggered_by, status, started_at, finished_at, message
                FROM ingestion_run ORDER BY id DESC FETCH FIRST 1 ROWS ONLY
                """)
                .query((rs, row) -> {
                    long id = rs.getLong("id");
                    OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
                    return new RunRecord(id, rs.getString("triggered_by"), rs.getString("status"),
                            rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                            finished == null ? null : finished.toInstant(),
                            rs.getString("message"), results(id));
                })
                .optional();
    }

    private List<ResultRecord> results(long runId) {
        return jdbc.sql("""
                SELECT ticker, status, facts_loaded, error FROM ingestion_result
                WHERE run_id = :runId ORDER BY ticker
                """)
                .param("runId", runId)
                .query((rs, row) -> new ResultRecord(rs.getString("ticker"), rs.getString("status"),
                        rs.getInt("facts_loaded"), rs.getString("error")))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 3) + "...";
    }

    public record RunRecord(long id, String trigger, String status, Instant startedAt, Instant finishedAt,
                            String message, List<ResultRecord> results) {
    }

    public record ResultRecord(String ticker, String status, int factsLoaded, String error) {
    }
}
