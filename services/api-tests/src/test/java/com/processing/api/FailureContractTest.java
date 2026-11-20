package com.processing.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static com.processing.api.support.Api.*;
import static com.processing.api.support.Fixtures.*;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

class FailureContractTest {
    @Test
    @Tag("broker-down")
    void rollsBackFundsAndDoesNotLogWhenBrokerCannotAcceptMessage() {
        String pan = createCard();
        Map<String, Object> payload = transaction(pan, AMOUNT);

        GATEWAY.request().body(payload).post("/api/transactions").then().statusCode(200)
                .body("stan", equalTo(payload.get("stan")))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("96"))
                .body("declineReason", equalTo("System error"));

        CARDS.request().get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("availableBalance", equalTo(BALANCE));

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6))
                .pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                        GATEWAY.request().queryParam("pan", pan).get("/api/transactions/search").then()
                                .statusCode(200).body(matchingRows("stan", payload.get("stan")), empty()));
    }

    @Test
    @Tag("cards-down")
    void authorizationReturnsServiceUnavailableWhenCardManagementConnectionIsRefused() {
        String pan = unusedPan(KNOWN_BIN);

        AUTH.request().body(authorization(pan, AMOUNT)).post("/api/internal/authorize").then().statusCode(503)
                .body("responseCode", equalTo("96")).body("declineReason", equalTo("SERVICE_UNAVAILABLE"));
        AUTH.request().body(Map.of("pan", pan, "rrn", rrn(), "amount", AMOUNT))
                .post("/api/internal/rollback").then().statusCode(503)
                .body("responseCode", equalTo("96")).body("declineReason", equalTo("SERVICE_UNAVAILABLE"));
    }

    @Test
    @Tag("switch-down")
    void gatewayReportsUnavailableSwitchAndDegradedHealth() {
        Map<String, Object> payload = transaction(unusedPan(KNOWN_BIN), AMOUNT);

        GATEWAY.request().body(payload).post("/api/transactions").then().statusCode(503)
                .body("error", equalTo("SERVICE_UNAVAILABLE")).body("serviceName", equalTo("switch"));
        GATEWAY.request().get("/health").then().statusCode(503)
                .body("status", equalTo("degraded")).body("services.switch", equalTo("unavailable"));
    }

    @Test
    @Tag("rate-limit")
    void returns429WithRetryAfterOnSmallIsolatedTokenBucket() {
        // The runner configures one token in the isolated Gateway. An empty payload avoids side effects.
        GATEWAY.request().body("{}").post("/api/transactions").then().statusCode(400);

        await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                        GATEWAY.request().body("{}").post("/api/transactions").then().statusCode(429)
                                .header("Retry-After", "1")
                                .body("error", equalTo("RATE_LIMIT_EXCEEDED"))
                                .body("retryAfterMs", equalTo(1000)));
    }
}
