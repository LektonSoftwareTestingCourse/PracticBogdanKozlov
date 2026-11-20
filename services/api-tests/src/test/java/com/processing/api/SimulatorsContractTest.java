package com.processing.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.processing.api.support.Api.*;
import static com.processing.api.support.Fixtures.*;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@Tag("contract")
class SimulatorsContractTest {
    private static final String TERMINAL_START = "/api/simulator/terminal/start-continuous";
    private static final String TERMINAL_STOP = "/api/simulator/terminal/stop";
    private static final String MERCHANT_RUN = "/api/simulator/merchant/run";
    private static final String MERCHANT_FEE = "/api/simulator/merchant/fee";

    @ParameterizedTest
    @CsvSource({"/api/simulator/terminal/run,normal", "/api/simulator/merchant/run,grocery"})
    void runsOneTransactionAndReturnsConsistentCounters(String path, String scenario) {
        createCard();

        var response = GATEWAY.request().body(Map.of("count", 1, "scenario", scenario, "tps", 5))
                .post(path).then().statusCode(200)
                .body("totalSubmitted", equalTo(1)).body("transactions", hasSize(1))
                .body("transactions[0].status", oneOf("APPROVED", "DECLINED"))
                .extract().jsonPath();

        assertThat(response.getInt("approved") + response.getInt("declined"), equalTo(1));
    }

    @ParameterizedTest
    @CsvSource({"/api/simulator/terminal/run,normal,0,1,count",
            "/api/simulator/terminal/run,normal,1,0,tps",
            "/api/simulator/terminal/run,invalid,1,1,TerminalScenario",
            "/api/simulator/merchant/run,grocery,0,1,count",
            "/api/simulator/merchant/run,grocery,1,0,tps",
            "/api/simulator/merchant/run,invalid,1,1,ScenarioType"})
    void rejectsInvalidSimulationInput(String path, String scenario, int count, int tps, String field) {
        GATEWAY.request().body(Map.of("count", count, "scenario", scenario, "tps", tps))
                .post(path).then().statusCode(400)
                .body("error", not(isEmptyOrNullString())).body("message", containsString(field));
    }

    @Test
    void startsAndStopsContinuousSimulationAndRejectsSecondStart() {
        createCard();
        Map<String, Object> setup = Map.of("tps", 1, "transactionType", "NORMAL");

        GATEWAY.request().body(setup).post(TERMINAL_START).then()
                .statusCode(200).body(emptyOrNullString());
        try {
            GATEWAY.request().body(setup).post(TERMINAL_START).then().statusCode(422)
                    .body("serviceName", equalTo("terminal-simulator"))
                    .body("message", containsString("already running"));
        } finally {
            GATEWAY.request().post(TERMINAL_STOP).then().statusCode(200).body(emptyOrNullString());
        }
        GATEWAY.request().post(TERMINAL_STOP).then().statusCode(200).body(emptyOrNullString());
    }

    @Test
    void rejectsZeroTpsForContinuousSimulation() {
        GATEWAY.request().body(Map.of("tps", 0, "transactionType", "NORMAL"))
                .post(TERMINAL_START).then().statusCode(400)
                .body("message", containsString("tps"));
    }

    @Test
    void rejectsUnknownMerchantCategory() {
        createCard();

        GATEWAY.request().body(Map.of("count", 1, "scenario", "grocery", "mccCodes", List.of("0000")))
                .post(MERCHANT_RUN).then().statusCode(404)
                .body("error", equalTo("Resource not found")).body("message", containsString("0000"));
    }

    @Test
    void returnsMerchantDirectoryDirectlyAndIdentifiesMissingGatewayRoute() {
        MERCHANT.request().get("/api/simulator/merchants").then()
                .statusCode(200).body("$", not(empty()));

        GATEWAY.request().get("/api/simulator/merchants").then()
                .statusCode(404).body("path", equalTo("/api/simulator/merchants"));
    }

    @Test
    void looksUpFeeForTransactionGeneratedByMerchant() {
        createCard();
        String stan = GATEWAY.request().body(Map.of("count", 1, "scenario", "grocery", "tps", 5))
                .post(MERCHANT_RUN).then().statusCode(200)
                .body("transactions", hasSize(1)).extract().path("transactions[0].stan");

        String match = matchingRows("stan", stan) + "[0]";
        var transaction = await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250))
                .until(() -> GATEWAY.request().queryParam("limit", 30)
                        .get("/api/transactions/search").then().statusCode(200)
                        .extract().jsonPath().getMap(match), notNullValue());
        Map<String, Object> key = Map.of(
                "pan", transaction.get("pan"), "stan", stan, "terminalId", transaction.get("terminalId"),
                "amount", transaction.get("amount"), "transmissionDateTime", transaction.get("transmissionDateTime"));

        GATEWAY.request().body(key).post(MERCHANT_FEE).then().statusCode(200)
                .body("acquirerFee", allOf(notNullValue(), equalTo(transaction.get("acquiringFee"))));
    }

    @Test
    void returns404ForFeeOfUnknownTransaction() {
        Map<String, Object> payload = transaction(unusedPan(KNOWN_BIN), AMOUNT);

        GATEWAY.request().body(payload).post(MERCHANT_FEE).then().statusCode(404)
                .body("error", equalTo("Resource not found")).body("message", containsString(payload.get("stan").toString()));
    }

    @Test
    void rejectsFeeRequestWithoutRequiredTimestamp() {
        Map<String, Object> payload = transaction(unusedPan(KNOWN_BIN), AMOUNT);
        payload.remove("transmissionDateTime");

        GATEWAY.request().body(payload).post(MERCHANT_FEE).then().statusCode(400)
                .body("error", equalTo("Invalid request")).body("message", containsString("transmissionDateTime"));
    }
}
