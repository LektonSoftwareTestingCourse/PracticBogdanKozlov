package com.processing.gateway.health;

import com.processing.gateway.health.models.HealthResponse;
import com.processing.gateway.health.models.HealthStatus;
import com.processing.gateway.properties.ServiceProperties;
import com.processing.gateway.properties.SmpGatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

    @Mock
    private HealthClient healthClient;

    @Mock
    private ServiceProperties serviceProperties;

    @Mock
    private HealthProperties healthProperties;

    @Mock
    private SmpGatewayProperties smpGatewayProperties;

    @InjectMocks
    private HealthService healthService;

    @BeforeEach
    void setUp() {
        when(serviceProperties.getSwitchUrl()).thenReturn("http://switch");
        when(serviceProperties.getAuthUrl()).thenReturn("http://authorization");
        when(serviceProperties.getCardsUrl()).thenReturn("http://cards");
        when(serviceProperties.getLoggerUrl()).thenReturn("http://logger");
        when(healthProperties.getUrl()).thenReturn("/health");
        when(smpGatewayProperties.getVersion()).thenReturn("test-version");

    }

    @Test
    void shouldReportHealthyWhenAllDownstreamServicesAreHealthy() {
        // Arrange
        stubHealthChecks(null);

        // Act / Assert
        StepVerifier.create(healthService.getDownstreamServicesHealth())
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(HealthStatus.OK);
                    assertThat(response.service()).isEqualTo("gateway");
                    assertThat(response.version()).isEqualTo("test-version");
                    assertThat(response.services()).containsEntry("switch", HealthStatus.OK)
                            .containsEntry("authorization", HealthStatus.OK)
                            .containsEntry("cardManagement", HealthStatus.OK)
                            .containsEntry("logger", HealthStatus.OK);
                })
                .verifyComplete();
    }

    @ParameterizedTest(name = "unavailable {0} makes the gateway degraded")
    @CsvSource({
            "switch",
            "authorization",
            "cardManagement",
            "logger"
    })
    void shouldReportDegradedWhenAnyDownstreamServiceIsUnavailable(String serviceName) {
        // Arrange
        stubHealthChecks(serviceName);

        // Act / Assert
        StepVerifier.create(healthService.getDownstreamServicesHealth())
                .assertNext(response -> assertDegradedService(response, serviceName))
                .verifyComplete();
    }

    private void stubHealthChecks(String unavailableService) {
        when(healthClient.sendHealthCheckRequest("http://switch/health", "switch"))
                .thenReturn(healthStatusFor("switch", unavailableService));
        when(healthClient.sendHealthCheckRequest("http://authorization/health", "authorization"))
                .thenReturn(healthStatusFor("authorization", unavailableService));
        when(healthClient.sendHealthCheckRequest("http://cards/health", "cardManagement"))
                .thenReturn(healthStatusFor("cardManagement", unavailableService));
        when(healthClient.sendHealthCheckRequest("http://logger/health", "logger"))
                .thenReturn(healthStatusFor("logger", unavailableService));
    }

    private Mono<HealthStatus> healthStatusFor(String serviceName, String unavailableService) {
        HealthStatus status = serviceName.equals(unavailableService)
                ? HealthStatus.UNAVAILABLE : HealthStatus.OK;
        return Mono.just(status);
    }

    private void assertDegradedService(HealthResponse response, String unavailableService) {
        assertThat(response.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(response.services()).containsEntry(unavailableService, HealthStatus.UNAVAILABLE);
        assertThat(response.services()).hasSize(4);
    }
}
