package io.github.rj318.peerlens.sec;

import java.time.Duration;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.rj318.peerlens.config.PeerLensProperties;

@Configuration(proxyBeanMethods = false)
public class SecClientConfig {

    @Bean
    RestClient secRestClient(RestClient.Builder builder, PeerLensProperties properties) {
        PeerLensProperties.Sec sec = properties.sec();
        return withSecHeaders(builder.requestFactory(requestFactory(sec.timeout())), sec).build();
    }

    /** Headers the SEC expects on every request. Shared with the tests so they check the real setup. */
    public static RestClient.Builder withSecHeaders(RestClient.Builder builder, PeerLensProperties.Sec sec) {
        return builder
                .defaultHeader(HttpHeaders.USER_AGENT, sec.userAgent() == null ? "" : sec.userAgent().strip())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * Apache HttpClient sends Accept-Encoding: gzip and decompresses responses transparently, which matters
     * because a large filer's company facts file is several megabytes. It also honors the standard
     * https.proxyHost settings, so the service works behind a corporate proxy.
     */
    private static HttpComponentsClientHttpRequestFactory requestFactory(Duration timeout) {
        Timeout limit = Timeout.of(timeout);
        CloseableHttpClient httpClient = HttpClients.custom()
                .useSystemProperties()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .useSystemProperties()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(limit)
                                .setSocketTimeout(limit)
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(limit).build())
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
