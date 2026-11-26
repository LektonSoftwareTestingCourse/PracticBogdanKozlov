package com.processing.transactionlogger.listener;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.transactionlogger.config.RabbitMQConfig;
import com.processing.transactionlogger.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import(TransactionLoggerDlqIT.DeadLetterTopology.class)
@Testcontainers
@SuppressWarnings("SqlNoDataSourceInspection") // The database exists only for the test run.
class TransactionLoggerDlqIT {

    @Container
    @ServiceConnection
    @SuppressWarnings("unused") // Started by the Testcontainers JUnit extension.
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    @SuppressWarnings("unused") // Started by the Testcontainers JUnit extension.
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionRepository repository;

    @BeforeEach
    void clearDatabaseAndQueues() {
        rabbitAdmin.purgeQueue(RabbitMQConfig.QUEUE);
        rabbitAdmin.purgeQueue(RabbitMQConfig.DLQ_QUEUE);
        jdbc.execute("TRUNCATE transactions RESTART IDENTITY CASCADE");
    }

    @Test
    void deadLettersMalformedJsonAndPersistsTheNextValidEvent() {
        byte[] malformedJson = ("{\"id\":\"" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        Message poison = MessageBuilder.withBody(malformedJson)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        rabbitTemplate.send(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, poison);

        AtomicReference<Message> received = new AtomicReference<>();
        await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() -> {
            received.set(rabbitTemplate.receive(RabbitMQConfig.DLQ_QUEUE));
            assertThat(received.get()).as("malformed message in the dead-letter queue").isNotNull();
        });
        assertThat(received.get().getBody()).isEqualTo(malformedJson);
        assertThat(received.get().getMessageProperties().getXDeathHeader()).singleElement()
                .satisfies(death -> {
                    assertThat(death.get("queue")).isEqualTo(RabbitMQConfig.QUEUE);
                    assertThat(death.get("reason")).isEqualTo("rejected");
                    assertThat(death.get("count")).isEqualTo(1L);
                });
        Long mainQueueCount = rabbitTemplate.execute(channel -> channel.messageCount(RabbitMQConfig.QUEUE));
        assertThat(mainQueueCount).isZero();
        assertThat(repository.count()).isZero();

        TransactionRequest valid = newEvent();
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, valid);

        await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() ->
                assertThat(repository.findById(valid.id())).get().satisfies(saved -> {
                    assertThat(saved.getRrn()).isEqualTo(valid.rrn());
                    assertThat(saved.getStan()).isEqualTo(valid.stan());
                    assertThat(saved.getAmount()).isEqualByComparingTo(valid.amount());
                }));
    }

    private static TransactionRequest newEvent() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String stan = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String rrn = String.format(Locale.ROOT, "%012d", random.nextLong(1_000_000_000_000L));
        String pan = String.format(Locale.ROOT, "400000%010d", random.nextLong(10_000_000_000L));
        Instant now = Instant.now();
        return new TransactionRequest(UUID.randomUUID(), "0100", stan, rrn, pan, "000000", new BigDecimal("1500"),
                "643", "TERM0001", "POS", "MERCHANT0001", "5411", "ACQ001", "ISS001", null,
                TransactionStatus.APPROVED, null, "TEST01", 1, now, now);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeadLetterTopology {

        @Bean
        DirectExchange smpTransactionsDlx() {
            return new DirectExchange(RabbitMQConfig.DLX_EXCHANGE, true, false);
        }

        @Bean
        Queue transactionLogDlq() {
            return QueueBuilder.durable(RabbitMQConfig.DLQ_QUEUE).ttl(60_000).build();
        }

        @Bean
        Binding dlqBinding(DirectExchange smpTransactionsDlx, Queue transactionLogDlq) {
            return BindingBuilder.bind(transactionLogDlq).to(smpTransactionsDlx)
                    .with(RabbitMQConfig.DLQ_ROUTING_KEY);
        }
    }
}
