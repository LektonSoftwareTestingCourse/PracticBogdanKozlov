package com.processing.api;

import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static com.processing.api.support.Api.CARDS;
import static com.processing.api.support.Api.GATEWAY;
import static com.processing.api.support.Fixtures.*;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

@Tag("contract")
class GatewayContractTest {
    @Test
    void reportsHealthOfTheDownstreamServices() {
        GATEWAY.request()
                .get("/health").then()
                .statusCode(200)
                .body("status", equalTo("ok"))
                .body("service", equalTo("gateway"))
                .body("services", allOf(hasKey("switch"), hasKey("logger"), hasKey("cardManagement"),
                        hasKey("authorization")));
    }

    @Test
    void exposesOperationalHttpEndpoints() {
        GATEWAY.request().get("/actuator/health").then()
                .statusCode(200).body("status", equalTo("UP"));
        GATEWAY.request().get("/actuator/info").then()
                .statusCode(200).body("$", instanceOf(Map.class));
        GATEWAY.request().get("/actuator/metrics").then()
                .statusCode(200).body("names", hasItem("jvm.memory.used"));
        GATEWAY.request().get("/actuator/prometheus").then()
                .statusCode(200).body(containsString("jvm_memory_used_bytes"));
        GATEWAY.request().get("/v3/api-docs").then()
                .statusCode(200).body("paths", hasKey("/health"));
        GATEWAY.request().get("/docs").then()
                .statusCode(200).body(containsString("Swagger UI"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"switch", "logger", "terminal", "merchant", "cards"})
    void exposesOpenApiForEachConfiguredProxy(String service) {
        GATEWAY.request()
                .get("/{service}-docs", service).then()
                .statusCode(200)
                .body("openapi", startsWith("3."))
                .body("paths", not(anEmptyMap()));
    }

    @Test
    void approvesReservesAndEventuallyFindsTheSameTransaction() {
        String pan = createCard();
        Map<String, Object> payload = transaction(pan, AMOUNT);

        String rrn = GATEWAY.request().body(payload)
                .post("/api/transactions").then()
                .statusCode(200)
                .contentType("application/json")
                .body(matchesJsonSchemaInClasspath("schemas/approved-transaction.json"))
                .body("stan", equalTo(payload.get("stan")))
                .body("rrn", matchesPattern("\\d{12}"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("processingTimeMs", greaterThanOrEqualTo(0))
                .body("declineReason", nullValue())
                .extract().path("rrn");

        // Read directly from Card Management so Gateway's GET cache cannot mask the side effect.
        CARDS.request().get("/api/cards/{pan}", pan).then()
                .statusCode(200)
                .body("availableBalance", equalTo(BALANCE - AMOUNT));

        String match = matchingRows("stan", payload.get("stan"));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                GATEWAY.request().queryParam("pan", pan)
                        .get("/api/transactions/search").then()
                        .statusCode(200)
                        .body(match, hasSize(1))
                        .body(match + "[0].rrn", equalTo(rrn))
                        .body(match + "[0].status", equalTo("APPROVED"))
                        .body(match + "[0].issuerId", equalTo("ISS001"))
                        .body(match + "[0].amount", equalTo(AMOUNT)));
    }

    @Test
    void acceptsMinimumPositiveMinorUnitAndOptionalTerminalType() {
        Map<String, Object> payload = transaction(createCard(), 1);
        payload.remove("terminalType");

        GATEWAY.request().body(payload)
                .post("/api/transactions").then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"));
    }

    @Test
    void returnsBusinessDeclineAtGatewayAndKeepsFunds() {
        String pan = createCard(AMOUNT - 1, DAILY_LIMIT, MONTHLY_LIMIT);
        Map<String, Object> payload = transaction(pan, AMOUNT);

        GATEWAY.request().body(payload)
                .post("/api/transactions").then()
                .statusCode(200)
                .body("stan", equalTo(payload.get("stan")))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51"))
                .body("declineReason", equalTo("INSUFFICIENT_FUNDS"))
                .body("authCode", nullValue());

        CARDS.request().get("/api/cards/{pan}", pan).then()
                .statusCode(200)
                .body("availableBalance", equalTo(AMOUNT - 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "amount", "currencyCode",
            "transmissionDateTime", "terminalId", "merchantId", "mcc", "acquirerId"})
    void rejectsOmissionOfEachRequiredGatewayField(String field) {
        Map<String, Object> payload = transaction(unusedPan(KNOWN_BIN), AMOUNT);
        payload.remove(field);

        validationError(payload).body("message", equalTo("Field '" + field + "' is required"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidValues")
    void rejectsEachGatewayValidationRule(String field, Object value, String rule) {
        Map<String, Object> payload = transaction(unusedPan(KNOWN_BIN), AMOUNT);
        payload.put(field, value);

        validationError(payload).body("message", equalTo("Field '" + field + "' " + rule));
    }

    static Stream<Arguments> invalidValues() {
        // TransactionRequestValidator: include both sides of exact-length constraints.
        return Stream.of(
                Arguments.of("mti", "0400", "must be '0100'"),
                Arguments.of("pan", "400000123456789", "must be exactly 16 digits"),
                Arguments.of("pan", "40000012345678901", "must be exactly 16 digits"),
                Arguments.of("pan", "400000123456789X", "must be exactly 16 digits"),
                Arguments.of("pan", " ", "is required"),
                Arguments.of("amount", 0, "must be > 0"),
                Arguments.of("amount", -1, "must be > 0"),
                Arguments.of("currencyCode", "64", "must be exactly 3 characters"),
                Arguments.of("currencyCode", "6430", "must be exactly 3 characters"),
                Arguments.of("mcc", "541", "must be exactly 4 digits"),
                Arguments.of("mcc", "54110", "must be exactly 4 digits"),
                Arguments.of("mcc", "541A", "must be exactly 4 digits"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"transmissionDateTime\":\"invalid-date\"}", ""})
    void rejectsUnreadableRequestBody(String payload) {
        validationError(payload).body("message", equalTo("Request body must be valid JSON"));
    }

    private ValidatableResponse validationError(Object payload) {
        return GATEWAY.request().body(payload)
                .post("/api/transactions").then()
                .statusCode(400)
                .body("error", equalTo("VALIDATION_ERROR"))
                .body("serviceName", equalTo("gateway"))
                .body("timestamp", notNullValue())
                .body("$", hasKey("retryAfterMs"))
                .body("retryAfterMs", nullValue());
    }
}
