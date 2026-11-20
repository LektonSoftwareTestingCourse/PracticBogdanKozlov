package com.processing.exception;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationExceptionTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "connection refused")
    void shouldIncludeLastErrorOrUnknownFallback(String lastError) {
        AuthorizationException exception = new AuthorizationException("000001", 3, lastError);

        assertThat(exception.getStan()).isEqualTo("000001");
        assertThat(exception.getMessage()).endsWith(lastError == null ? "unknown" : lastError);
    }
}
