package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingServiceTest {

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties(
                "test", Map.of("400000", "ISSUER_A"), "http://auth", "http://logger",
                "http://merchant", null, null, null);
        routingService = new RoutingService(properties);
    }

    @Test
    void shouldReturnIssuerForConfiguredBin() {
        assertThat(routingService.getIssuerIdByPan("4000001234567893")).isEqualTo("ISSUER_A");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "12345"})
    void shouldRejectNullOrShortPan(String pan) {
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class);
    }

    @Test
    void shouldRejectUnknownBin() {
        assertThatThrownBy(() -> routingService.getIssuerIdByPan("9999991234567893"))
                .isInstanceOf(UnknownBinException.class)
                .hasMessage("Unknown BIN '999999'");
    }
}
