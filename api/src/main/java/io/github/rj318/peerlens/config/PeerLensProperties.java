package io.github.rj318.peerlens.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything Peer Lens reads from application.yml or environment variables.
 *
 * @param sec      how to reach SEC EDGAR politely
 * @param ingest   when and how much data to load
 * @param admin    protection for the admin endpoints
 * @param concepts XBRL concepts to try, in priority order, for each metric
 * @param sectors  the watchlist: peer groups in display order
 */
@ConfigurationProperties("peerlens")
public record PeerLensProperties(Sec sec, Ingest ingest, Admin admin, Concepts concepts, Map<String, Sector> sectors) {

    /**
     * @param dataBaseUrl        host for the XBRL company facts API
     * @param filesBaseUrl       host for the ticker-to-CIK file
     * @param userAgent          SEC fair-access policy requires a name and contact email in every request
     * @param minRequestInterval spacing between requests; the SEC allows 10 per second, this stays well under
     * @param maxAttempts        attempts per request before giving up on 429, 5xx, or network errors
     * @param initialBackoff     first retry delay, doubled on each retry
     * @param timeout            connect and read timeout
     */
    public record Sec(URI dataBaseUrl, URI filesBaseUrl, String userAgent, Duration minRequestInterval,
                      int maxAttempts, Duration initialBackoff, Duration timeout) {

        /** A declared User-Agent must at least carry a contact email address. */
        public boolean hasValidUserAgent() {
            return userAgent != null && userAgent.contains("@") && userAgent.strip().length() > 5;
        }
    }

    /**
     * @param runOnStartupIfEmpty load data automatically the first time the service starts
     * @param cron                refresh schedule
     * @param zone                time zone for the schedule
     * @param yearsToKeep         fiscal years stored per company
     */
    public record Ingest(boolean runOnStartupIfEmpty, String cron, String zone, int yearsToKeep) {
    }

    /** @param token shared secret for the admin endpoints; blank disables them entirely */
    public record Admin(String token) {

        public boolean enabled() {
            return token != null && !token.isBlank();
        }
    }

    /**
     * @param revenue           default revenue concepts
     * @param netIncome         net income concepts
     * @param operatingCashFlow operating cash flow concepts
     */
    public record Concepts(List<String> revenue, List<String> netIncome, List<String> operatingCashFlow) {
    }

    /**
     * @param label           display name
     * @param tickers         companies in the peer group, in display order
     * @param revenueConcepts optional sector-specific revenue concepts (banks report net revenue)
     */
    public record Sector(String label, List<String> tickers, List<String> revenueConcepts) {
    }
}
