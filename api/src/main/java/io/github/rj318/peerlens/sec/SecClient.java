package io.github.rj318.peerlens.sec;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import io.github.rj318.peerlens.config.PeerLensProperties;

/**
 * Client for SEC EDGAR that follows the SEC fair-access policy: a declared User-Agent on every request
 * (set on the underlying RestClient), requests spaced well under the 10-per-second limit, and polite
 * retries with exponential backoff on 429, 5xx, and network errors.
 */
@Component
public class SecClient {

    private static final Logger log = LoggerFactory.getLogger(SecClient.class);
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final PeerLensProperties.Sec settings;
    private final Object throttleLock = new Object();
    private long nextRequestAllowedAt;

    public SecClient(@Qualifier("secRestClient") RestClient restClient, PeerLensProperties properties) {
        this.restClient = restClient;
        this.settings = properties.sec();
        this.nextRequestAllowedAt = System.nanoTime();
    }

    /** False until SEC_USER_AGENT is set to a name and contact email, as the SEC requires. */
    public boolean isConfigured() {
        return settings.hasValidUserAgent();
    }

    /** Every ticker the SEC knows, keyed by upper-case ticker. */
    public Map<String, TickerEntry> tickerDirectory() {
        URI uri = settings.filesBaseUrl().resolve("/files/company_tickers.json");
        Map<String, TickerEntry> rows = get(uri, new ParameterizedTypeReference<Map<String, TickerEntry>>() { });
        return rows.values().stream().collect(Collectors.toMap(
                entry -> entry.ticker().toUpperCase(Locale.ROOT), Function.identity(), (first, second) -> first));
    }

    /** All XBRL facts a company has reported. The response for a large filer is several megabytes. */
    public CompanyFacts companyFacts(String paddedCik) {
        URI uri = settings.dataBaseUrl().resolve("/api/xbrl/companyfacts/CIK" + paddedCik + ".json");
        return get(uri, new ParameterizedTypeReference<CompanyFacts>() { });
    }

    private <T> T get(URI uri, ParameterizedTypeReference<T> type) {
        Duration backoff = settings.initialBackoff();
        for (int attempt = 1; ; attempt++) {
            throttle();
            try {
                T body = restClient.get().uri(uri).retrieve().body(type);
                if (body == null) {
                    throw new SecException("SEC returned an empty response for " + uri);
                }
                return body;
            } catch (HttpClientErrorException.Forbidden e) {
                throw new SecException("SEC returned 403 Forbidden for " + uri
                        + ". Check that SEC_USER_AGENT names you and includes a contact email.", e);
            } catch (HttpClientErrorException.NotFound e) {
                throw new SecException("SEC has no data at " + uri, e);
            } catch (HttpClientErrorException.TooManyRequests | HttpServerErrorException | ResourceAccessException e) {
                if (attempt >= settings.maxAttempts()) {
                    throw new SecException("SEC request failed after " + attempt + " attempts: " + uri
                            + " (" + e.getMessage() + ")", e);
                }
                Duration wait = retryAfter(e).orElse(backoff);
                log.warn("SEC request to {} failed on attempt {} ({}); retrying in {} ms",
                        uri, attempt, e.getMessage(), wait.toMillis());
                sleep(wait);
                backoff = backoff.multipliedBy(2);
            } catch (RestClientResponseException e) {
                throw new SecException("SEC returned " + e.getStatusCode().value() + " for " + uri, e);
            }
        }
    }

    /** Honors a Retry-After header in seconds, capped so a bad header cannot stall a run. */
    private static Optional<Duration> retryAfter(Exception e) {
        if (!(e instanceof HttpStatusCodeException statusException) || statusException.getResponseHeaders() == null) {
            return Optional.empty();
        }
        String header = statusException.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (header == null) {
            return Optional.empty();
        }
        try {
            Duration requested = Duration.ofSeconds(Long.parseLong(header.strip()));
            return Optional.of(requested.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : requested);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private void throttle() {
        synchronized (throttleLock) {
            long waitNanos = nextRequestAllowedAt - System.nanoTime();
            if (waitNanos > 0) {
                sleep(Duration.ofNanos(waitNanos));
            }
            nextRequestAllowedAt = System.nanoTime() + settings.minRequestInterval().toNanos();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecException("Interrupted while waiting to call SEC EDGAR", e);
        }
    }
}
