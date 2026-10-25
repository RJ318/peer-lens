package io.github.rj318.peerlens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.github.rj318.peerlens.repository.FactRepository;
import io.github.rj318.peerlens.repository.IngestionRunRepository;
import io.github.rj318.peerlens.service.IngestionService;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:peerlens-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@Import(IntegrationTestConfig.class)
class PeerLensIntegrationTest {

    private static final String TICKERS_URL = "https://www.sec.gov/files/company_tickers.json";
    private static final String EXMP_URL = "https://data.sec.gov/api/xbrl/companyfacts/CIK0001234567.json";
    private static final String SMPL_URL = "https://data.sec.gov/api/xbrl/companyfacts/CIK0007654321.json";
    private static final String PART_URL = "https://data.sec.gov/api/xbrl/companyfacts/CIK0005555555.json";
    private static final String ADMIN_TOKEN = "test-admin-token";

    @Autowired
    private IngestionService ingestion;
    @Autowired
    private FactRepository facts;
    @Autowired
    private IngestionRunRepository runs;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.sql("DELETE FROM ingestion_result").update();
        jdbc.sql("DELETE FROM ingestion_run").update();
        jdbc.sql("DELETE FROM annual_fact").update();
        jdbc.sql("DELETE FROM company").update();
        IntegrationTestConfig.SEC.reset();
    }

    @Test
    void loadsWatchlistAndRecordsPerCompanyResults() {
        expectSec();

        String status = ingestion.runNow("TEST");

        assertThat(status).isEqualTo("PARTIAL");
        // EXMP: 5 revenue years (2020-2024) + 3 net income + 2 operating cash flow. PART: 2 revenue years.
        assertThat(facts.count()).isEqualTo(12);
        var run = runs.findLatest().orElseThrow();
        assertThat(run.results()).extracting(r -> r.ticker() + ":" + r.status()).containsExactly(
                "BETA:FAILED", "EXMP:SUCCEEDED", "GONE:FAILED", "PART:INCOMPLETE", "SMPL:FAILED");
        assertThat(run.results().get(2).error()).contains("not in the SEC ticker list");
        assertThat(run.results().get(3).error()).contains("netIncome, operatingCashFlow");
        assertThat(run.results().get(4).error()).contains("after 2 attempts");
        assertThat(run.message()).isEqualTo("1 companies loaded, 1 incomplete, 3 failed.");
        IntegrationTestConfig.SEC.verify();
    }

    @Test
    void reloadingReplacesRatherThanDuplicates() {
        expectSec();
        ingestion.runNow("TEST");
        ingestion.runNow("TEST");

        assertThat(facts.count()).isEqualTo(12);
    }

    @Test
    void comparisonReturnsLatestYearWithDerivedMetricsAndSources() throws Exception {
        expectSec();
        ingestion.runNow("TEST");

        mvc.perform(get("/api/sectors/alpha/comparison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sector.label").value("Alpha"))
                .andExpect(jsonPath("$.companies", hasSize(3)))
                .andExpect(jsonPath("$.companies[0].ticker").value("EXMP"))
                .andExpect(jsonPath("$.companies[0].name").value("Example Corp"))
                .andExpect(jsonPath("$.companies[0].fiscalYear").value(2024))
                .andExpect(jsonPath("$.companies[0].periodEnd").value("2024-12-31"))
                .andExpect(jsonPath("$.companies[0].revenue").value(1100))
                .andExpect(jsonPath("$.companies[0].netMargin").value(0.15))
                .andExpect(jsonPath("$.companies[0].revenueGrowth").value(0.1111))
                .andExpect(jsonPath("$.companies[0].cashConversion").value(1.2))
                .andExpect(jsonPath("$.companies[0].sources.revenue.url").value(
                        "https://www.sec.gov/Archives/edgar/data/1234567/000123456725000010/0001234567-25-000010-index.htm"))
                .andExpect(jsonPath("$.companies[1].ticker").value("SMPL"))
                .andExpect(jsonPath("$.companies[1].revenue").value(nullValue()));
    }

    @Test
    void historyReturnsEveryStoredYearOldestFirst() throws Exception {
        expectSec();
        ingestion.runNow("TEST");

        mvc.perform(get("/api/sectors/alpha/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].years", hasSize(5)))
                .andExpect(jsonPath("$.companies[0].years[0].fiscalYear").value(2020))
                .andExpect(jsonPath("$.companies[0].years[0].sources.revenue.concept").value("SalesRevenueNet"))
                .andExpect(jsonPath("$.companies[0].years[3].revenue").value(990));
    }

    @Test
    void listsSectorsInConfiguredOrder() throws Exception {
        mvc.perform(get("/api/sectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("alpha"))
                .andExpect(jsonPath("$[0].companyCount").value(3))
                .andExpect(jsonPath("$[1].key").value("beta"));
    }

    @Test
    void unknownSectorAndTickerAreProblemResponses() throws Exception {
        mvc.perform(get("/api/sectors/energy/comparison"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail", containsString("Unknown sector: energy")));
        mvc.perform(get("/api/companies/ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail", containsString("not on the watchlist")));
    }

    @Test
    void companyLookupIsCaseInsensitive() throws Exception {
        expectSec();
        ingestion.runNow("TEST");

        mvc.perform(get("/api/companies/exmp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("EXMP"))
                .andExpect(jsonPath("$.cik").value("0001234567"))
                .andExpect(jsonPath("$.sector").value("alpha"))
                .andExpect(jsonPath("$.years", hasSize(5)));
    }

    @Test
    void adminEndpointsRequireTheToken() throws Exception {
        mvc.perform(post("/api/admin/ingestions"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/ingestions").header("X-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanStartARunAndReadItsResult() throws Exception {
        expectSec();

        mvc.perform(post("/api/admin/ingestions").header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.runId").isNumber());
        waitForIngestionToFinish();

        mvc.perform(get("/api/admin/ingestions/latest").header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trigger").value("ADMIN"))
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.results", hasSize(5)));
    }

    @Test
    void frameworkErrorsAreProblemResponsesToo() throws Exception {
        mvc.perform(get("/api/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/api/sectors"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** EXMP loads, PART lacks two metrics, SMPL keeps failing with 503, GONE and BETA are not in the ticker list. */
    private static void expectSec() {
        IntegrationTestConfig.SEC.expect(ExpectedCount.manyTimes(), requestTo(TICKERS_URL))
                .andExpect(header(HttpHeaders.USER_AGENT, IntegrationTestConfig.USER_AGENT))
                .andRespond(withSuccess(TestData.read(TestData.TICKERS), MediaType.APPLICATION_JSON));
        IntegrationTestConfig.SEC.expect(ExpectedCount.manyTimes(), requestTo(EXMP_URL))
                .andRespond(withSuccess(TestData.read(TestData.COMPANY_FACTS), MediaType.APPLICATION_JSON));
        IntegrationTestConfig.SEC.expect(ExpectedCount.manyTimes(), requestTo(PART_URL))
                .andRespond(withSuccess(TestData.read("sec/companyfacts-revenue-only.json"), MediaType.APPLICATION_JSON));
        IntegrationTestConfig.SEC.expect(ExpectedCount.manyTimes(), requestTo(SMPL_URL))
                .andRespond(withServiceUnavailable());
    }

    private void waitForIngestionToFinish() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        Thread.sleep(50);
        while (ingestion.isRunning() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(ingestion.isRunning()).isFalse();
    }
}
