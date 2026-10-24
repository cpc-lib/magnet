package com.magnetplatform.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsEndpointIntegrationTest {

    @TempDir
    static Path tempDir;

    private static final String TEST_PASSWORD = "test-secret-password-123";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws IOException {
        Path passwordFile = tempDir.resolve("prometheus-scrape-password");
        Files.writeString(passwordFile, TEST_PASSWORD);
        registry.add("magnet.metrics.scrape-password-file", () -> passwordFile.toAbsolutePath().toString());
        registry.add("magnet.metrics.scrape-user", () -> "prometheus");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void unauthenticatedRequestToMetricsEndpointReturns401() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bearerTokenRequestToMetricsEndpointReturns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("dummy-jwt-token");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/prometheus", HttpMethod.GET, entity, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void basicAuthWithCorrectCredentialsReturns200AndMetrics() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("prometheus", TEST_PASSWORD)
                .getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("magnet_");
    }
}
