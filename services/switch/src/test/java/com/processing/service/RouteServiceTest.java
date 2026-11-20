package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class RouteServiceTest {

    private static final String PAN = "4000001234567893";
    private static final String STAN = "000001";

    private RoutingService routingService;
    private AuthorizationClient authorizationClient;
    private AcquiringFeeClient acquiringFeeClient;
    private LoggerClient loggerClient;
    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routingService = mock(RoutingService.class);
        authorizationClient = mock(AuthorizationClient.class);
        acquiringFeeClient = mock(AcquiringFeeClient.class);
        loggerClient = mock(LoggerClient.class);
        routeService = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);

        when(routingService.getIssuerIdByPan(PAN)).thenReturn("ISSUER_A");
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse(10));
        when(acquiringFeeClient.fetchAcquiringFee(any(), eq(STAN), eq(PAN), eq("TERM0001"), any()))
                .thenReturn(new BigDecimal("0.25"));
        when(loggerClient.log(any())).thenReturn(true);
    }

    @Test
    void shouldRouteAuthorizeAndLogTransaction() {
        AuthorizationRequest request = request(PAN);

        AuthorizationResponse response = routeService.route(request);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(transaction.capture());
        assertThat(transaction.getValue().stan()).isEqualTo(STAN);
        assertThat(transaction.getValue().issuerId()).isEqualTo("ISSUER_A");
        assertThat(transaction.getValue().acquiringFee()).isEqualByComparingTo("0.25");
        verify(authorizationClient).authorize(any(AuthorizationRequest.class));
        verify(acquiringFeeClient).fetchAcquiringFee(any(), eq(STAN), eq(PAN), eq("TERM0001"), any());
    }

    @Test
    void shouldDeclineUnknownBinAndStillLogTransaction() {
        AuthorizationRequest request = request(PAN);
        when(routingService.getIssuerIdByPan(PAN)).thenThrow(new UnknownBinException("400000"));

        AuthorizationResponse response = routeService.route(request);

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        verify(loggerClient).log(any(TransactionRequest.class));
        verify(authorizationClient, never()).authorize(any());
        verify(acquiringFeeClient, never()).fetchAcquiringFee(any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnAuthorizationUnavailableWhenAuthorizationFails() {
        AuthorizationRequest request = request(PAN);
        when(authorizationClient.authorize(any()))
                .thenThrow(new AuthorizationException(STAN, 3, "connection refused"));

        AuthorizationResponse response = routeService.route(request);

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_DECLINED_GENERAL);
        assertThat(response.declineReason()).isEqualTo("Authorization service unavailable");
        verify(loggerClient).log(any(TransactionRequest.class));
        verify(acquiringFeeClient, never()).fetchAcquiringFee(any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "logger failure triggers rollback ({0})")
    @MethodSource("rollbackResponses")
    void shouldRollbackApprovedTransactionWhenLoggingFails(String description, RollbackResponse rollback) {
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), eq("RRN-1"))).thenReturn(rollback);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponseWithRrn("RRN-1", 10));

        AuthorizationResponse response = routeService.route(request(PAN));

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        assertThat(response.declineReason()).isEqualTo("System error");
        verify(authorizationClient).rollback(any(AuthorizationRequest.class), eq("RRN-1"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"12345"})
    void shouldUseUnknownBinFallbackForMissingOrShortPan(String pan) {
        AuthorizationRequest request = request(pan);
        when(routingService.getIssuerIdByPan(pan)).thenThrow(new UnknownBinException("null-or-short"));

        AuthorizationResponse response = routeService.route(request);

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        verify(loggerClient).log(any(TransactionRequest.class));
    }

    @Test
    void shouldWriteZeroProcessingTimeWhenResponseDoesNotProvideOne() {
        AuthorizationResponse responseWithNoProcessingTime = new AuthorizationResponse(
                "0110", STAN, "RRN-1", "AUTH01", AuthorizationResponse.CODE_APPROVED,
                AuthorizationResponse.STATUS_APPROVED, null, null);
        when(authorizationClient.authorize(any())).thenReturn(responseWithNoProcessingTime);

        routeService.route(request(PAN));

        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(transaction.capture());
        assertThat(transaction.getValue().processingTimeMs()).isZero();
    }

    @Test
    void shouldNotRollbackWhenDeclinedTransactionCannotBeLogged() {
        AuthorizationResponse declined = new AuthorizationResponse(
                "0110", STAN, null, null, AuthorizationResponse.CODE_INSUFFICIENT_FUNDS,
                AuthorizationResponse.STATUS_DECLINED, "INSUFFICIENT_FUNDS", 5);
        when(authorizationClient.authorize(any())).thenReturn(declined);
        when(loggerClient.log(any())).thenReturn(false);

        AuthorizationResponse response = routeService.route(request(PAN));

        assertThat(response).isSameAs(declined);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> rollbackResponses() {
        return Stream.of(
                arguments("no response", null),
                arguments("approved", new RollbackResponse("RRN-1", "00", "APPROVED", null, 2)),
                arguments("declined", new RollbackResponse("RRN-1", "05", "DECLINED", "conflict", 2))
        );
    }

    private AuthorizationResponse approvedResponse(Integer processingTimeMs) {
        return new AuthorizationResponse("0110", STAN, "RRN-1", "AUTH01", AuthorizationResponse.CODE_APPROVED,
                AuthorizationResponse.STATUS_APPROVED, null, processingTimeMs);
    }

    private AuthorizationResponse approvedResponseWithRrn(String rrn, Integer processingTimeMs) {
        return new AuthorizationResponse("0110", STAN, rrn, "AUTH01", AuthorizationResponse.CODE_APPROVED,
                AuthorizationResponse.STATUS_APPROVED, null, processingTimeMs);
    }

    private AuthorizationRequest request(String pan) {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan(STAN)
                .pan(pan)
                .processingCode("000000")
                .amount(new BigDecimal("10.00"))
                .currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-10-01T10:00:00Z"))
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId("MERCH1234567890")
                .mcc("5045")
                .acquirerId("ACQ001")
                .issuerId(null)
                .build();
    }
}
