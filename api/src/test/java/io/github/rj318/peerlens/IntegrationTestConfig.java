package io.github.rj318.peerlens;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.rj318.peerlens.config.PeerLensProperties;
import io.github.rj318.peerlens.sec.SecClient;
import io.github.rj318.peerlens.sec.SecClientConfig;

/**
 * Runs the whole service against a fake SEC: a real SecClient whose HTTP layer is a MockRestServiceServer,
 * and a small two-sector watchlist with invented companies.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestConfig {

    public static final String USER_AGENT = "Peer Lens Test test@example.com";
    private static final RestClient.Builder BUILDER = RestClient.builder();
    public static final MockRestServiceServer SEC = MockRestServiceServer.bindTo(BUILDER).ignoreExpectOrder(true).build();

    @Bean
    @Primary
    PeerLensProperties testProperties() {
        return properties(USER_AGENT, "test-admin-token");
    }

    @Bean
    @Primary
    SecClient testSecClient(PeerLensProperties testProperties) {
        return new SecClient(SecClientConfig.withSecHeaders(BUILDER, testProperties.sec()).build(), testProperties);
    }

    public static PeerLensProperties properties(String userAgent, String adminToken) {
        PeerLensProperties.Sec sec = new PeerLensProperties.Sec(URI.create("https://data.sec.gov"),
                URI.create("https://www.sec.gov"), userAgent, Duration.ZERO, 2, Duration.ofMillis(1),
                Duration.ofSeconds(5));
        PeerLensProperties.Ingest ingest = new PeerLensProperties.Ingest(false, "0 0 0 1 1 *", "UTC", 6);
        PeerLensProperties.Concepts concepts = new PeerLensProperties.Concepts(
                List.of("RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet"),
                List.of("NetIncomeLoss"),
                List.of("NetCashProvidedByUsedInOperatingActivities"));
        Map<String, PeerLensProperties.Sector> sectors = new LinkedHashMap<>();
        sectors.put("alpha", new PeerLensProperties.Sector("Alpha", List.of("EXMP", "SMPL", "GONE"), null));
        sectors.put("beta", new PeerLensProperties.Sector("Beta", List.of("BETA", "PART"), List.of("Revenues")));
        return new PeerLensProperties(sec, ingest, new PeerLensProperties.Admin(adminToken), concepts, sectors);
    }
}
