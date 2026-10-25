package io.github.rj318.peerlens.sec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.github.rj318.peerlens.TestData;
import io.github.rj318.peerlens.config.PeerLensProperties;
import io.github.rj318.peerlens.domain.AnnualValue;

class SecClientTest {

    private static final String USER_AGENT = "Peer Lens Test test@example.com";
    private static final String FACTS_URL = "https://data.sec.gov/api/xbrl/companyfacts/CIK0001234567.json";

    private MockRestServiceServer server;
    private SecClient client;

    @BeforeEach
    void setUp() {
        PeerLensProperties properties = properties(USER_AGENT);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = SecClientConfig.withSecHeaders(builder, properties.sec()).build();
        client = new SecClient(restClient, properties);
    }

    @Test
    void sendsDeclaredUserAgentAndParsesCompanyFacts() {
        server.expect(requestTo(FACTS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.USER_AGENT, USER_AGENT))
                .andRespond(withSuccess(TestData.read(TestData.COMPANY_FACTS), MediaType.APPLICATION_JSON));

        CompanyFacts facts = client.companyFacts("0001234567");

        assertThat(facts.entityName()).isEqualTo("Example Corp");
        List<AnnualValue> revenue = new AnnualFactExtractor().extract(facts,
                List.of("RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet"));
        // 2020 comes from the older concept; 2023 is the restated value from the most recent filing.
        assertThat(revenue).extracting(v -> v.periodEnd().getYear(), v -> v.amount().intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2020, 700),
                        org.assertj.core.groups.Tuple.tuple(2021, 810),
                        org.assertj.core.groups.Tuple.tuple(2022, 905),
                        org.assertj.core.groups.Tuple.tuple(2023, 990),
                        org.assertj.core.groups.Tuple.tuple(2024, 1100));
        server.verify();
    }

    @Test
    void parsesTickerDirectoryKeyedByTicker() {
        server.expect(requestTo("https://www.sec.gov/files/company_tickers.json"))
                .andRespond(withSuccess(TestData.read(TestData.TICKERS), MediaType.APPLICATION_JSON));

        Map<String, TickerEntry> directory = client.tickerDirectory();

        assertThat(directory).containsOnlyKeys("EXMP", "SMPL", "PART");
        assertThat(directory.get("EXMP").paddedCik()).isEqualTo("0001234567");
    }

    @Test
    void retriesServerErrorsThenSucceeds() {
        server.expect(ExpectedCount.twice(), requestTo(FACTS_URL)).andRespond(withServiceUnavailable());
        server.expect(requestTo(FACTS_URL))
                .andRespond(withSuccess(TestData.read(TestData.COMPANY_FACTS), MediaType.APPLICATION_JSON));

        assertThat(client.companyFacts("0001234567").cik()).isEqualTo(1234567);
        server.verify();
    }

    @Test
    void honorsRetryAfterOnTooManyRequests() {
        server.expect(requestTo(FACTS_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "0"));
        server.expect(requestTo(FACTS_URL))
                .andRespond(withSuccess(TestData.read(TestData.COMPANY_FACTS), MediaType.APPLICATION_JSON));

        assertThat(client.companyFacts("0001234567").entityName()).isEqualTo("Example Corp");
    }

    @Test
    void givesUpAfterMaxAttempts() {
        server.expect(ExpectedCount.times(3), requestTo(FACTS_URL)).andRespond(withServiceUnavailable());

        assertThatThrownBy(() -> client.companyFacts("0001234567"))
                .isInstanceOf(SecException.class)
                .hasMessageContaining("after 3 attempts");
    }

    @Test
    void forbiddenPointsToTheUserAgentSetting() {
        server.expect(requestTo(FACTS_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.companyFacts("0001234567"))
                .isInstanceOf(SecException.class)
                .hasMessageContaining("SEC_USER_AGENT");
    }

    @Test
    void notFoundIsNotRetried() {
        server.expect(ExpectedCount.once(), requestTo(FACTS_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.companyFacts("0001234567")).hasMessageContaining("no data");
        server.verify();
    }

    @Test
    void userAgentWithoutContactEmailIsNotConfigured() {
        assertThat(new SecClient(RestClient.create(), properties("Peer Lens")).isConfigured()).isFalse();
        assertThat(new SecClient(RestClient.create(), properties("")).isConfigured()).isFalse();
        assertThat(client.isConfigured()).isTrue();
    }

    static PeerLensProperties properties(String userAgent) {
        PeerLensProperties.Sec sec = new PeerLensProperties.Sec(URI.create("https://data.sec.gov"),
                URI.create("https://www.sec.gov"), userAgent, Duration.ZERO, 3, Duration.ofMillis(1),
                Duration.ofSeconds(5));
        return new PeerLensProperties(sec, null, null, null, Map.of());
    }
}
