package com.processing.cardmanagement.repositories;

import com.processing.cardmanagement.exceptions.PanCollisionException;
import com.processing.cardmanagement.mappers.CardExpiryDateMapper;
import com.processing.cardmanagement.mappers.CardPersistenceMapperImpl;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CardRepositoryPersistenceAdapter.class, CardPersistenceMapperImpl.class, CardExpiryDateMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@SuppressWarnings("SqlNoDataSourceInspection") // The database exists only for the test run.
class CardRepositoryIT {

    private static final AtomicInteger PAN_SEQUENCE = new AtomicInteger();

    @Container
    @ServiceConnection
    @SuppressWarnings("unused") // Started by the Testcontainers JUnit extension.
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CardRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private String pan;

    @BeforeEach
    void clearCards() {
        jdbc.execute("TRUNCATE cards RESTART IDENTITY CASCADE");
        pan = String.format(Locale.ROOT, "400000%010d", PAN_SEQUENCE.incrementAndGet());
    }

    @Test
    void roundTripsCardThroughPostgres() {
        Card card = newCard(CardStatus.ACTIVE);

        transactions.executeWithoutResult(transaction -> repository.create(card));

        Card reloaded = transactions.execute(transaction -> repository.findByPan(pan).orElseThrow());
        assertThat(reloaded).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(card);
    }

    @Test
    void rejectsSecondActiveCardWithSamePan() {
        Card first = newCard(CardStatus.ACTIVE);
        transactions.executeWithoutResult(transaction -> repository.create(first));
        Card duplicate = newCard(CardStatus.ACTIVE);

        assertThatThrownBy(() -> transactions.executeWithoutResult(transaction -> repository.create(duplicate)))
                .isInstanceOf(PanCollisionException.class);

        transactions.executeWithoutResult(transaction -> {
            assertThat(repository.findByPan(pan)).get().extracting(Card::id).isEqualTo(first.id());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cards WHERE pan = ?", Integer.class, pan))
                    .isEqualTo(1);
        });
    }

    @Test
    void reusesPanOfDeletedCard() {
        Card deleted = newCard(CardStatus.DELETED);
        transactions.executeWithoutResult(transaction -> repository.create(deleted));
        Card replacement = newCard(CardStatus.ACTIVE);

        transactions.executeWithoutResult(transaction -> repository.create(replacement));

        transactions.executeWithoutResult(transaction -> {
            assertThat(repository.findByPan(pan)).get().extracting(Card::id).isEqualTo(replacement.id());
            assertThat(jdbc.queryForList("SELECT status FROM cards WHERE pan = ?", String.class, pan))
                    .containsExactlyInAnyOrder("DELETED", "ACTIVE");
        });
    }

    private Card newCard(CardStatus status) {
        return new Card(UUID.randomUUID(), pan, "400000", "INTEGRATION TEST", YearMonth.of(2035, 2),
                status, "643", new BigDecimal("9999"), new BigDecimal("29999"), new BigDecimal("12345"),
                "ISS001", Instant.parse("2026-01-15T12:00:00.123Z"));
    }
}
