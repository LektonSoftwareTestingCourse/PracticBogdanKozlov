package com.processing.config;

import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class RetryFactoryTest {

    @ParameterizedTest(name = "backoff {0}, failure number {1} -> {2} ms")
    @MethodSource("backoffCases")
    void shouldApplyConfiguredBackoff(List<Long> backoffMs, int failedAttempts, long expectedDelay) {
        Retry retry = RetryFactory.authorizationRetry(properties(backoffMs));

        long actualDelay = retry.getRetryConfig().getIntervalFunction().apply(failedAttempts);

        assertThat(actualDelay).isEqualTo(expectedDelay);
        assertThat(retry.getName()).isEqualTo("authorization");
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    void shouldCreateSeparateLoggerRetry() {
        Retry retry = RetryFactory.loggerRetry(properties(List.of(10L)));

        assertThat(retry.getName()).isEqualTo("logger");
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> backoffCases() {
        return Stream.of(
                arguments(null, 1, 1L),
                arguments(List.of(), 1, 1L),
                arguments(List.of(0L, 100L), 1, 1L),
                arguments(List.of(10L, 100L), 2, 100L),
                arguments(List.of(10L, 100L), 9, 100L),
                arguments(List.of(25L), 0, 25L)
        );
    }

    private SwitchProperties properties(List<Long> backoffMs) {
        return new SwitchProperties(
                "test", Map.of(), "http://auth", "http://logger", "http://merchant", null,
                new SwitchProperties.RetryProperties(3, backoffMs), null);
    }
}
