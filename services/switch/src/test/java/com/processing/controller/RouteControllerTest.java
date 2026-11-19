package com.processing.controller;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteControllerTest {

    private RouteService routeService;
    private RouteController routeController;

    @BeforeEach
    void setUp() {
        routeService = mock(RouteService.class);
        routeController = new RouteController(routeService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1234567", "12345678"})
    void shouldReturnServiceResponseForShortOrMissingPan(String pan) {
        AuthorizationRequest request = request(pan);
        AuthorizationResponse response = AuthorizationResponse.unknownBin(request.stan());
        when(routeService.route(request)).thenReturn(response);

        ResponseEntity<AuthorizationResponse> result = routeController.routeTransaction(request);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(response);
        verify(routeService).route(request);
    }

    @Test
    void shouldReturnServiceResponseForFullPan() {
        AuthorizationRequest request = request("4000001234567893");
        AuthorizationResponse response = AuthorizationResponse.unknownBin(request.stan());
        when(routeService.route(request)).thenReturn(response);

        ResponseEntity<AuthorizationResponse> result = routeController.routeTransaction(request);

        assertThat(result.getBody()).isEqualTo(response);
        verify(routeService).route(request);
    }

    private AuthorizationRequest request(String pan) {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan("000001")
                .pan(pan)
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
}
