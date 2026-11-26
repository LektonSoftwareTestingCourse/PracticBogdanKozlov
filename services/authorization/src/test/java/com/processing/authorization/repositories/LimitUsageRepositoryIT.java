package com.processing.authorization.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@SuppressWarnings("SqlNoDataSourceInspection") // The database exists only for the test run.
class LimitUsageRepositoryIT {

    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 1, 15);
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("100.00");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("300.00");
    private static final AtomicInteger PAN_SEQUENCE = new AtomicInteger();

    @Container
    @ServiceConnection
    @SuppressWarnings("unused") // Started by the Testcontainers JUnit extension.
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private LimitUsageRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private String pan;

    @BeforeEach
    void clearLimitUsage() {
        jdbc.execute("TRUNCATE limit_usage RESTART IDENTITY CASCADE");
        pan = String.format(Locale.ROOT, "400000%010d", PAN_SEQUENCE.incrementAndGet());
    }

    @Test
    void firstUpsertInsertsUsage() {
        assertThat(upsert("25.50")).isEqualTo(1);

        assertUsage("25.50");
    }

    @Test
    void secondUpsertAccumulatesOnTheSameRowUpToDailyLimit() {
        assertThat(upsert("40.25")).isEqualTo(1);

        assertThat(upsert("59.75")).isEqualTo(1);

        assertUsage("100.00");
    }

    @Test
    void exceedingDailyLimitLeavesPreviouslyCommittedUsageIntact() {
        assertThat(upsert("99.00")).isEqualTo(1);

        assertThat(upsert("1.01")).isZero();

        assertUsage("99.00");
    }

    private Integer upsert(String amount) {
        return transactions.execute(transaction -> repository.upsertLimitUsage(
                pan, USAGE_DATE, new BigDecimal(amount), DAILY_LIMIT, MONTHLY_LIMIT));
    }

    private void assertUsage(String amount) {
        transactions.executeWithoutResult(transaction -> assertThat(repository.findAll()).singleElement()
                .satisfies(usage -> {
                    assertThat(usage.getPan()).isEqualTo(pan);
                    assertThat(usage.getUsageDate()).isEqualTo(USAGE_DATE);
                    assertThat(usage.getDailyAmount()).isEqualByComparingTo(amount);
                    assertThat(usage.getMonthlyAmount()).isEqualByComparingTo(amount);
                }));
    }
}
