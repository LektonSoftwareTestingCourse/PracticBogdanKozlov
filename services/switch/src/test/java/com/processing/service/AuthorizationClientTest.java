package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.CircuitBreakerFactory;
import com.processing.config.RetryFactory;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthorizationClientTest {

    private SwitchProperties properties;
    private MockRestServiceServer server;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = properties();
        client = client(builder.build(), CircuitBreakerFactory.authorizationCircuitBreaker(properties));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldAcceptValidAuthorizationStatuses(String status) {
        server.expect(requestTo("http://auth/api/internal/authorize"))
                .andRespond(withSuccess(authorizationJson(status), MediaType.APPLICATION_JSON));

        AuthorizationResponse response = client.authorize(request());

        assertThat(response.status()).isEqualTo(status);
        server.verify();
    }

    @Test
    void shouldRejectAuthorizationResponseWithUnknownStatus() {
        server.expect(requestTo("http://auth/api/internal/authorize"))
                .andRespond(withSuccess(authorizationJson("PENDING"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response from Authorization");
        server.verify();
    }

    @Test
    void shouldRejectEmptyAuthorizationResponse() {
        server.expect(requestTo("http://auth/api/internal/authorize")).andRespond(withNoContent());

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response from Authorization");
        server.verify();
    }

    @Test
    void shouldTranslateAuthorizationHttpErrorIntoUnavailableException() {
        server.expect(requestTo("http://auth/api/internal/authorize")).andRespond(withServerError());

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class);
        server.verify();
    }

    @Test
    void shouldTranslateOpenCircuitIntoAuthorizationException() {
        CircuitBreaker openCircuit = CircuitBreakerFactory.authorizationCircuitBreaker(properties);
        openCircuit.transitionToOpenState();
        AuthorizationClient clientWithOpenCircuit = client(RestClient.builder().build(), openCircuit);

        assertThatThrownBy(() -> clientWithOpenCircuit.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturnValidRollbackResponse(String status) {
        server.expect(requestTo("http://auth/api/internal/rollback"))
                .andRespond(withSuccess(rollbackJson(status), MediaType.APPLICATION_JSON));

        RollbackResponse response = client.rollback(
                request(), "RRN-1");

        assertThat(response.status()).isEqualTo(status);
        server.verify();
    }

    @Test
    void shouldReturnNullWhenRollbackResponseIsEmpty() {
        server.expect(requestTo("http://auth/api/internal/rollback")).andRespond(withNoContent());

        RollbackResponse response = client.rollback(request(), "RRN-1");

        assertThat(response).isNull();
        server.verify();
    }

    @Test
    void shouldReturnNullWhenRollbackRequestFails() {
        server.expect(requestTo("http://auth/api/internal/rollback")).andRespond(withServerError());

        RollbackResponse response = client.rollback(request(), "RRN-1");

        assertThat(response).isNull();
        server.verify();
    }

    @Test
    void shouldReportAuthorizationHealth() {
        server.expect(requestTo("http://auth/health")).andRespond(withSuccess());

        assertThat(client.checkHealth()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void shouldReportAuthorizationAsDownWhenHealthRequestFails() {
        server.expect(requestTo("http://auth/health")).andRespond(withServerError());

        assertThat(client.checkHealth()).isEqualTo("down");
        server.verify();
    }

    private AuthorizationClient client(RestClient restClient, CircuitBreaker circuitBreaker) {
        return new AuthorizationClient(
                properties, restClient, RetryFactory.authorizationRetry(properties), circuitBreaker);
    }

    private SwitchProperties properties() {
        return new SwitchProperties(
                "test",
                Map.of(),
                "http://auth",
                "http://logger",
                "http://merchant",
                null,
                new SwitchProperties.RetryProperties(1, List.of(1L)),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 1000)));
    }

    private AuthorizationRequest request() {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan("000001")
                .pan("4000001234567893")
                .processingCode("000000")
                .amount(BigDecimal.ONE)
                .currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-10-01T10:00:00Z"))
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId("MERCH1234567890")
                .mcc("5045")
                .acquirerId("ACQ001")
                .issuerId("ISSUER_A")
                .build();
    }

    private String authorizationJson(String status) {
        return """
                {
                  "mti": "0110",
                  "stan": "000001",
                  "rrn": "RRN-1",
                  "authCode": "AUTH01",
                  "responseCode": "00",
                  "status": "%s",
                  "declineReason": null,
                  "processingTimeMs": 5
                }
                """.formatted(status);
    }

    private String rollbackJson(String status) {
        return """
                {
                  "rrn": "RRN-1",
                  "responseCode": "00",
                  "status": "%s",
                  "declineReason": null,
                  "processingTimeMs": 5
                }
                """.formatted(status);
    }
}
