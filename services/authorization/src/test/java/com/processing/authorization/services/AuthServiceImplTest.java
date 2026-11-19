package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedCardStatusEvent;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceImplTest {

    private static final String PAN = "4000001234567893";
    private static final Instant INPUT_TIME = Instant.now().minusSeconds(30);

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    @InjectMocks
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        lenient().when(cardManagementClient.getCard(PAN)).thenReturn(card(
                CardModelStatus.ACTIVE, YearMonth.of(2027, 12), new BigDecimal("1000.00")));
        lenient().when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        lenient().when(limitUsageRepository.upsertLimitUsage(
                anyString(), any(LocalDate.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(1);
    }

    @Test
    void shouldApproveValidTransactionAndReserveFunds() {
        AuthorizationResponse response = authService.authorize(request(BigDecimal.TEN), INPUT_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_APPROVED);
        assertThat(response.rrn()).isNotBlank();
        assertThat(response.authCode()).hasSize(6);
        verify(cardManagementClient).reserve(BigDecimal.TEN, response.rrn(), PAN);

        ArgumentCaptor<AuthorizationEvent> event = ArgumentCaptor.forClass(AuthorizationEvent.class);
        verify(eventNotifier).notify(event.capture());
        assertThat(event.getValue()).isInstanceOf(AuthServiceAuthorizeEvent.class);
    }

    @ParameterizedTest(name = "card status {0} is declined")
    @EnumSource(value = CardModelStatus.class, names = {"EXPIRED", "BLOCKED", "INACTIVE"})
    void shouldDeclineNonActiveCard(CardModelStatus status) {
        when(cardManagementClient.getCard(PAN)).thenReturn(card(status, YearMonth.of(2027, 12), BigDecimal.TEN));

        AuthorizationResponse response = authService.authorize(request(BigDecimal.ONE), INPUT_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("CARD_" + status.name());
        verify(eventNotifier).notify(any(AuthServiceAuthDeclinedCardStatusEvent.class));
    }

    @Test
    void shouldDeclineWhenCardExpiryMonthHasPassed() {
        when(cardManagementClient.getCard(PAN)).thenReturn(card(
                CardModelStatus.ACTIVE, YearMonth.of(2026, 9), BigDecimal.TEN));

        AuthorizationResponse response = authService.authorize(request(BigDecimal.ONE), INPUT_TIME);

        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
    }

    @Test
    void shouldDeclineWhenAmountExceedsAvailableBalance() {
        when(cardManagementClient.getCard(PAN)).thenReturn(card(
                CardModelStatus.ACTIVE, YearMonth.of(2027, 12), BigDecimal.ONE));

        AuthorizationResponse response = authService.authorize(request(BigDecimal.TEN), INPUT_TIME);

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_INSUFFICIENT_FUNDS);
    }

    @Test
    void shouldDeclineWhenLimitRepositoryRejectsTransaction() {
        when(limitUsageRepository.upsertLimitUsage(
                anyString(), any(LocalDate.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(0);

        AuthorizationResponse response = authService.authorize(request(BigDecimal.ONE), INPUT_TIME);

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_EXCEEDS_LIMIT);
        verify(cardManagementClient).getCard(PAN);
    }

    @Test
    void shouldDeclineWhenCardManagementCannotFindCard() {
        when(cardManagementClient.getCard(PAN)).thenThrow(new CardNotFoundException("missing"));

        AuthorizationResponse response = authService.authorize(request(BigDecimal.ONE), INPUT_TIME);

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        verify(limitUsageRepository, org.mockito.Mockito.never()).upsertLimitUsage(
                anyString(), any(LocalDate.class), any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void shouldReturnWhetherLimitUsageWasUpdated(int updatedCount) {
        CardModel card = card(CardModelStatus.ACTIVE, YearMonth.of(2027, 12), BigDecimal.TEN);
        when(limitUsageRepository.upsertLimitUsage(
                PAN, LocalDate.of(2026, 10, 1), BigDecimal.ONE, card.dailyLimit(), card.monthlyLimit()))
                .thenReturn(updatedCount);

        boolean updated = authService.checkAndUpdateLimits(
                card, BigDecimal.ONE, LocalDate.of(2026, 10, 1));

        assertThat(updated).isEqualTo(updatedCount == 1);
    }

    @Test
    void shouldApproveRollbackAfterCardManagementCompletesIt() {
        RollbackRequest request = new RollbackRequest("RRN-1", PAN, BigDecimal.ONE);

        RollbackResponse response = authService.rollback(request, INPUT_TIME);

        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
        assertThat(response.rrn()).isEqualTo("RRN-1");
    }

    private AuthorizationRequest request(BigDecimal amount) {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan("000001")
                .pan(PAN)
                .processingCode("000000")
                .amount(amount)
                .currencyCode("643")
                .transmissionDateTime(INPUT_TIME)
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId("MERCH1234567890")
                .mcc("5045")
                .acquirerId("ACQ001")
                .issuerId("ISSUER_A")
                .build();
    }

    private CardModel card(CardModelStatus status, YearMonth expiryDate, BigDecimal balance) {
        return new CardModel(
                UUID.randomUUID(), PAN, "400000", "TEST HOLDER", expiryDate, status, "643",
                new BigDecimal("500.00"), new BigDecimal("5000.00"), balance, "ISSUER_A", INPUT_TIME);
    }
}
