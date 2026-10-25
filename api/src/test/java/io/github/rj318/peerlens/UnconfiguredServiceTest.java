package io.github.rj318.peerlens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

import io.github.rj318.peerlens.config.PeerLensProperties;
import io.github.rj318.peerlens.repository.IngestionRunRepository;
import io.github.rj318.peerlens.sec.SecClient;
import io.github.rj318.peerlens.service.IngestionService;

/** A fresh install with no SEC_USER_AGENT and no admin token must fail safe and explain why. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:peerlens-unconfigured;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class UnconfiguredServiceTest {

    @Autowired
    private IngestionService ingestion;
    @Autowired
    private IngestionRunRepository runs;
    @Autowired
    private WebApplicationContext context;

    @Test
    void ingestionFailsWithAnActionableMessageWhenUserAgentIsMissing() {
        assertThat(ingestion.runNow("TEST")).isEqualTo("FAILED");
        assertThat(runs.findLatest().orElseThrow().message()).contains("SEC_USER_AGENT is not set");
    }

    @Test
    void adminEndpointsAreHiddenWithoutAToken() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(post("/api/admin/ingestions").header("X-Admin-Token", "anything"))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Primary
        PeerLensProperties unconfiguredProperties() {
            return IntegrationTestConfig.properties("", "");
        }

        @Bean
        @Primary
        SecClient unconfiguredSecClient(PeerLensProperties unconfiguredProperties) {
            return new SecClient(RestClient.create(), unconfiguredProperties);
        }
    }
}
