package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CardServiceImplTest {

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRollbackRepository reservationRollbackRepository;

    @Mock
    private CardServiceSettings settings;

    @Mock
    private CardServiceDefaults defaults;

    @Mock
    private PanGenerator panGenerator;

    @Mock
    private CardEventNotifier eventNotifier;

    @Mock
    private BinIssuerService binIssuerService;

    @Mock
    private RetryService retryService;

    @Mock
    private TransactionRunner transactionRunner;

    @InjectMocks
    private CardServiceImpl cardService;

    @BeforeEach
    void setUp() {
        when(settings.maxPageLimit()).thenReturn(100);
        when(defaults.pageLimit()).thenReturn(25);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cardRepository.findCards(
                eq(25), eq(0L), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of());
    }

    @Test
    void shouldUseDefaultPaginationWhenNoPageIsRequested() {
        List<?> cards = cardService.getCards(null, null, null, null, null, null, null);

        assertThat(cards).isEmpty();
        verify(cardRepository).findCards(25, 0L, null, null, null, null, null);
    }

    @Test
    void shouldPassExplicitFiltersAndPaginationToRepository() {
        Instant start = Instant.parse("2026-10-01T00:00:00Z");
        Instant end = Instant.parse("2026-10-31T23:59:59Z");
        when(cardRepository.findCards(
                eq(50), eq(10L), eq(CardStatus.ACTIVE), eq("400000"), eq("ISSUER_A"), eq(start), eq(end)))
                .thenReturn(List.of());

        cardService.getCards(50, 10L, CardStatus.ACTIVE, "400000", "ISSUER_A", start, end);

        verify(cardRepository).findCards(50, 10L, CardStatus.ACTIVE, "400000", "ISSUER_A", start, end);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void shouldAcceptPaginationLimitAtConfiguredBoundaries(int limit) {
        when(cardRepository.findCards(
                eq(limit), eq(0L), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of());

        assertThat(cardService.getCards(limit, 0L, null, null, null, null, null)).isEmpty();
        verify(cardRepository).findCards(limit, 0L, null, null, null, null, null);
    }

    @Test
    void shouldRejectPageLimitAboveConfiguredMaximum() {
        assertThatThrownBy(() -> cardService.getCards(101, 0L, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class)
                .hasMessage("Page limit (101 received) can not be more than 100");
    }
}
