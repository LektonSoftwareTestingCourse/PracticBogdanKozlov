package com.processing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.processing.config.RabbitMQConfig.DLQ_QUEUE;
import static com.processing.config.RabbitMQConfig.QUEUE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
// The JUnit extension closes the containers, including the temporary database.
@SuppressWarnings({"resource", "SqlNoDataSourceInspection"})
class TransactionChainIT {

    private static final int HTTP_PORT = 8080;
    private static final int AMOUNT = 1500;
    private static final int BALANCE = 100_000;
    private static final String BIN = "400000";
    private static final String USER = "smp";
    private static final String PASSWORD = "smp-integration";
    private static final Network NETWORK = Network.newNetwork();
    private static final Map<String, String> DATABASE_ENV = Map.of(
            "POSTGRES_HOST", "postgres", "POSTGRES_DB", "smp", "POSTGRES_USER", USER, "POSTGRES_PASSWORD", PASSWORD);
    private static final Map<String, String> BROKER_ENV = Map.of(
            "SPRING_RABBITMQ_HOST", "rabbit", "SPRING_RABBITMQ_USERNAME", USER, "SPRING_RABBITMQ_PASSWORD", PASSWORD);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withNetwork(NETWORK).withNetworkAliases("postgres")
            .withDatabaseName("smp").withUsername(USER).withPassword(PASSWORD);

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management")
            .withNetwork(NETWORK).withNetworkAliases("rabbit")
            .withAdminUser(USER).withAdminPassword(PASSWORD);

    @Container
    private static final GenericContainer<?> WIREMOCK = new GenericContainer<>("wiremock/wiremock:3.13.1")
            .withNetwork(NETWORK).withNetworkAliases("wiremock")
            .withExposedPorts(HTTP_PORT)
            .withCopyFileToContainer(MountableFile.forClasspathResource("wiremock/bin-lookup.json"),
                    "/home/wiremock/mappings/bin-lookup.json")
            .withCopyFileToContainer(MountableFile.forClasspathResource("wiremock/merchant-fee.json"),
                    "/home/wiremock/mappings/merchant-fee.json")
            .waitingFor(Wait.forHttp("/__admin/mappings"));

    @Container
    private static final GenericContainer<?> CARDS = service("card-management")
            .withEnv(DATABASE_ENV).withEnv(BROKER_ENV)
            .withEnv("METRICS_SCHEDULER_ENABLED", "false")
            .dependsOn(POSTGRES, RABBIT);

    @Container
    private static final GenericContainer<?> AUTH = service("authorization")
            .withEnv(DATABASE_ENV)
            .withEnv("CARD_MGMT_URL", "http://card-management:8080")
            .withEnv("BIN_LOOKUP_URL", "http://wiremock:8080")
            .dependsOn(CARDS, WIREMOCK);

    @Container
    private static final GenericContainer<?> LOGGER = service("transaction-logger")
            .withEnv(DATABASE_ENV).withEnv(BROKER_ENV)
            .withEnv("SPRING_AMQP_DESERIALIZATION_TRUST_ALL", "true")
            .dependsOn(CARDS, RABBIT);

    @Container
    private static final GenericContainer<?> SWITCH = service("switch")
            .withEnv(BROKER_ENV)
            .withEnv("AUTH_URL", "http://authorization:8080")
            .withEnv("LOGGER_URL", "http://transaction-logger:8080")
            .withEnv("MERCHANT_URL", "http://wiremock:8080")
            .dependsOn(AUTH, LOGGER, WIREMOCK);

    private final RestClient http = RestClient.create();
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearTestState() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD));
        jdbc.execute("TRUNCATE transactions, limit_usage, reservations, cards, outbox_event RESTART IDENTITY CASCADE");

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(USER);
        factory.setPassword(PASSWORD);
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            channel.queuePurge(QUEUE);
            channel.queuePurge(DLQ_QUEUE);
        }

        http.delete().uri(baseUrl(WIREMOCK) + "/__admin/requests").retrieve().toBodilessEntity();
    }

    @Test
    void approvedSwitchRequestCommitsUsageReservationAndRabbitLog() {
        JsonNode card = post(CARDS, "/api/cards", Map.of(
                "bin", BIN, "cardholderName", "INTEGRATION TEST", "currencyCode", "643",
                "dailyLimit", 20_000, "monthlyLimit", 70_000, "initialBalance", BALANCE), 201);
        assertThat(card.path("status").asText()).isEqualTo("ACTIVE");
        String pan = card.path("pan").asText();
        String stan = String.format(Locale.ROOT, "%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        Instant transmission = Instant.now();
        LocalDate usageDate = transmission.atZone(ZoneOffset.UTC).toLocalDate();

        JsonNode response = post(SWITCH, "/api/internal/route", Map.ofEntries(
                Map.entry("mti", "0100"), Map.entry("stan", stan), Map.entry("pan", pan),
                Map.entry("processingCode", "000000"), Map.entry("amount", AMOUNT),
                Map.entry("currencyCode", "643"), Map.entry("transmissionDateTime", transmission.toString()),
                Map.entry("terminalId", "TERM0001"), Map.entry("terminalType", "POS"),
                Map.entry("merchantId", "MERCHANT0001"), Map.entry("mcc", "5411"),
                Map.entry("acquirerId", "ACQ001")), 200);

        assertThat(response.path("status").asText()).isEqualTo("APPROVED");
        assertThat(response.path("responseCode").asText()).isEqualTo("00");
        assertThat(response.path("stan").asText()).isEqualTo(stan);
        String rrn = response.path("rrn").asText();
        assertThat(rrn).matches("\\d{12}");

        assertThat(jdbc.queryForList("SELECT daily_amount, monthly_amount FROM limit_usage WHERE pan = ? AND usage_date = ?",
                pan, usageDate)).singleElement().satisfies(row -> {
                    assertThat((BigDecimal) row.get("daily_amount")).isEqualByComparingTo(BigDecimal.valueOf(AMOUNT));
                    assertThat((BigDecimal) row.get("monthly_amount")).isEqualByComparingTo(BigDecimal.valueOf(AMOUNT));
                });
        assertThat(jdbc.queryForObject("SELECT reservation_amount FROM reservations WHERE rrn = ? AND pan = ?",
                BigDecimal.class, rrn, pan)).isEqualByComparingTo(BigDecimal.valueOf(AMOUNT));
        assertThat(jdbc.queryForObject("SELECT available_balance FROM cards WHERE pan = ?", BigDecimal.class, pan))
                .isEqualByComparingTo(BigDecimal.valueOf(BALANCE - AMOUNT));

        await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForList("SELECT stan, amount, status, acquiring_fee FROM transactions WHERE rrn = ?",
                        rrn)).singleElement().satisfies(row -> {
                            assertThat(row.get("stan")).isEqualTo(stan);
                            assertThat(row.get("status")).isEqualTo("APPROVED");
                            assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(BigDecimal.valueOf(AMOUNT));
                            assertThat((BigDecimal) row.get("acquiring_fee")).isEqualByComparingTo("27");
                        }));

        assertThat(post(WIREMOCK, "/__admin/requests/count", Map.of("method", "GET", "url", "/api/bin/" + BIN), 200)
                .path("count").asInt()).isEqualTo(1);
        assertThat(post(WIREMOCK, "/__admin/requests/count",
                Map.of("method", "POST", "url", "/api/simulator/merchant/fee"), 200).path("count").asInt()).isEqualTo(1);
    }

    private JsonNode post(GenericContainer<?> container, String path, Map<String, ?> body, int status) {
        ResponseEntity<JsonNode> response = http.post().uri(baseUrl(container) + path)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        return response.getBody();
    }

    private static String baseUrl(GenericContainer<?> container) {
        return "http://%s:%d".formatted(container.getHost(), container.getMappedPort(HTTP_PORT));
    }

    private static GenericContainer<?> service(String name) {
        Path jar = Path.of("..", name, "target", name + "-" + System.getProperty("serviceJarVersion") + ".jar");
        return new GenericContainer<>("eclipse-temurin:21-jre-alpine")
                .withNetwork(NETWORK).withNetworkAliases(name).withExposedPorts(HTTP_PORT)
                .withEnv("SERVER_PORT", Integer.toString(HTTP_PORT))
                .withCopyFileToContainer(MountableFile.forHostPath(jar.toAbsolutePath()), "/app.jar")
                .withCommand("java", "-jar", "/app.jar")
                .waitingFor(Wait.forHttp("/actuator/health").withStartupTimeout(Duration.ofMinutes(2)));
    }
}
