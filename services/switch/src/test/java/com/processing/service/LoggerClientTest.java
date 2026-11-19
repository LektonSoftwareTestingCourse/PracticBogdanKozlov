package com.processing.service;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class LoggerClientTest {

    private RabbitTemplate rabbitTemplate;
    private LoggerClient loggerClient;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        loggerClient = new LoggerClient(rabbitTemplate);
    }

    @Test
    void shouldReturnTrueWhenMessageIsPublished() {
        assertThat(loggerClient.log(transaction())).isTrue();
    }

    @Test
    void shouldReturnFalseWhenRabbitMqRejectsMessage() {
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate).convertAndSend(
                        eq(RabbitMQConfig.EXCHANGE),
                        eq(RabbitMQConfig.ROUTING_KEY),
                        any(TransactionRequest.class));

        assertThat(loggerClient.log(transaction())).isFalse();
    }

    private TransactionRequest transaction() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        return new TransactionRequest(
                UUID.randomUUID(), "0100", "000001", "RRN-1", "4000001234567893", "000000",
                BigDecimal.ONE, "643", "TERM0001", "POS", "MERCH1234567890", "5045", "ACQ001", "ISSUER_A",
                BigDecimal.ZERO, TransactionStatus.APPROVED, null, "AUTH01", 1, now, now);
    }
}
