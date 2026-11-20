package com.processing.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.processing.api.support.Api.*;
import static com.processing.api.support.Fixtures.*;
import static org.hamcrest.Matchers.*;

@Tag("contract")
class CardsContractTest {
    @Test
    void createsReadsCachesAndPatchesCard() {
        String pan = createCard();

        GATEWAY.request().get("/api/cards/{pan}", pan).then()
                .statusCode(200).header("X-Cache", "MISS")
                .body("pan", equalTo(pan)).body("bin", equalTo(KNOWN_BIN))
                .body("status", equalTo("ACTIVE")).body("availableBalance", equalTo(BALANCE))
                .body("dailyLimit", equalTo(DAILY_LIMIT)).body("monthlyLimit", equalTo(MONTHLY_LIMIT));
        GATEWAY.request().get("/api/cards/{pan}", pan).then()
                .statusCode(200).header("X-Cache", "HIT").body("pan", equalTo(pan));

        GATEWAY.request().body(Map.of("status", "BLOCKED", "availableBalance", AMOUNT))
                .patch("/api/cards/{pan}", pan).then().statusCode(200)
                .body("status", equalTo("BLOCKED")).body("availableBalance", equalTo(AMOUNT));
        GATEWAY.request().get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("status", equalTo("BLOCKED")).body("availableBalance", equalTo(AMOUNT));
    }

    @Test
    void returns204ForDeletionAnd404OnSubsequentLookup() {
        String pan = createCard();

        GATEWAY.request().delete("/api/cards/{pan}", pan).then().statusCode(204).body(emptyOrNullString());
        GATEWAY.request().get("/api/cards/{pan}", pan).then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
        GATEWAY.request().delete("/api/cards/{pan}", pan).then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    @ParameterizedTest
    @CsvSource({"GET,400,123", "PATCH,400,123", "DELETE,400,123",
            "GET,404,absent", "PATCH,404,absent", "DELETE,404,absent"})
    void rejectsMalformedOrUnknownPanOnCardCrud(String method, int status, String input) {
        String pan = input.equals("absent") ? unusedPan(KNOWN_BIN) : input;
        var request = GATEWAY.request();
        if (method.equals("PATCH")) {
            request.body(Map.of("status", "ACTIVE"));
        }

        request.request(method, "/api/cards/{pan}", pan).then()
                .statusCode(status)
                .body("serviceName", equalTo("card-management"))
                .body("message", not(isEmptyOrNullString()));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidCardFields")
    void rejectsInvalidCardCreationField(String field, Object value) {
        Map<String, Object> payload = cardRequest();
        payload.put(field, value);

        GATEWAY.request().body(payload).post("/api/cards").then().statusCode(400)
                .body("serviceName", equalTo("card-management"))
                .body("message", not(isEmptyOrNullString()));
    }

    static Stream<Arguments> invalidCardFields() {
        return Stream.of(
                Arguments.of("bin", null), Arguments.of("bin", "40000"), Arguments.of("bin", "40000X"),
                Arguments.of("cardholderName", null), Arguments.of("cardholderName", "lowercase"),
                Arguments.of("currencyCode", null), Arguments.of("currencyCode", "64"),
                Arguments.of("currencyCode", "64X"), Arguments.of("dailyLimit", -1),
                Arguments.of("monthlyLimit", -1));
    }

    @Test
    void refusesCreationWithUnknownBin() {
        Map<String, Object> payload = cardRequest();
        payload.put("bin", UNKNOWN_BIN);

        GATEWAY.request().body(payload).post("/api/cards").then().statusCode(404)
                .body("error", equalTo("BinNotFoundException"));
    }

    @Test
    void generatesRequestedCardsWithSelectedBin() {
        GATEWAY.request().body(Map.of("count", 2, "bins", List.of(KNOWN_BIN)))
                .post("/api/cards/generate").then()
                .statusCode(201).body("generated", equalTo(2)).body("cards", hasSize(2))
                .body("cards.bin", everyItem(equalTo(KNOWN_BIN)))
                .body("cards.pan", everyItem(matchesPattern("\\d{16}")));
    }

    @ParameterizedTest
    @MethodSource("invalidGenerationRequests")
    void rejectsInvalidGeneration(Map<String, Object> payload) {
        GATEWAY.request().body(payload).post("/api/cards/generate").then().statusCode(400)
                .body("serviceName", equalTo("card-management"))
                .body("message", not(isEmptyOrNullString()));
    }

    static Stream<Map<String, Object>> invalidGenerationRequests() {
        return Stream.of(Map.of("count", 0, "bins", List.of(KNOWN_BIN)),
                Map.of("count", 1, "bins", List.of()), Map.of("count", 1, "bins", List.of("X")),
                Map.of("bins", List.of(KNOWN_BIN)), Map.of("count", 1));
    }

    @Test
    void listsCardsWithCombinedFiltersAndPaginates() {
        String pan = createCard();
        String issuer = "ISS001";

        GATEWAY.request().queryParam("bin", KNOWN_BIN).queryParam("status", "ACTIVE")
                .queryParam("issuerId", issuer).queryParam("limit", 1).queryParam("offset", 0)
                .get("/api/cards").then().statusCode(200)
                .body("total", greaterThanOrEqualTo(1)).body("cards", hasSize(1))
                .body("cards[0].bin", equalTo(KNOWN_BIN)).body("cards[0].issuerId", equalTo(issuer))
                .body("cards[0].status", equalTo("ACTIVE"));

        String createdAt = CARDS.request().get("/api/cards/{pan}", pan).then().statusCode(200)
                .extract().path("createdAt");
        GATEWAY.request().queryParam("startDate", createdAt).queryParam("endDate", "2099-01-01T00:00:00Z")
                .queryParam("limit", 10).get("/api/cards").then().statusCode(200)
                .body("cards.pan", hasItem(pan));
        GATEWAY.request().queryParam("bin", UNKNOWN_BIN).queryParam("offset", 1)
                .get("/api/cards").then().statusCode(200)
                .body("total", equalTo(0)).body("cards", empty());
    }

    @ParameterizedTest
    @CsvSource({"limit,0,400", "limit,10001,413", "offset,-1,400", "bin,invalid,400",
            "issuerId,^,400"})
    void rejectsInvalidCardListFilter(String field, String value, int status) {
        GATEWAY.request().queryParam(field, value).get("/api/cards").then()
                .statusCode(status).body("serviceName", equalTo("card-management"))
                .body("message", not(isEmptyOrNullString()));
    }

    @ParameterizedTest
    @CsvSource({"dailyLimit", "monthlyLimit"})
    void rejectsNegativeCardPatchFields(String field) {
        GATEWAY.request().body(Map.of(field, -1)).patch("/api/cards/{pan}", createCard()).then()
                .statusCode(400).body("serviceName", equalTo("card-management"));
    }

    @Test
    void updatesBalanceToZero() {
        String pan = createCard();

        GATEWAY.request().body(Map.of("availableBalance", 0)).patch("/api/cards/{pan}", pan).then()
                .statusCode(200).body("pan", equalTo(pan)).body("availableBalance", equalTo(0));
    }

    @Test
    void updatesOnlySelectedCardsInBulk() {
        String selected = createCard();
        String untouched = createCard();

        GATEWAY.request().body(Map.of("pans", List.of(selected), "status", "INACTIVE"))
                .post("/api/cards/bulk-update").then().statusCode(200).body("updated", equalTo(1));
        GATEWAY.request().get("/api/cards/{pan}", selected).then().statusCode(200)
                .body("status", equalTo("INACTIVE"));
        GATEWAY.request().get("/api/cards/{pan}", untouched).then().statusCode(200)
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    void rejectsBulkUpdateMissingSelector() {
        GATEWAY.request().body(Map.of("status", "ACTIVE")).post("/api/cards/bulk-update").then()
                .statusCode(400).body("message", containsString("bin"));
    }

    @Test
    void reservesRollsBackAndRejectsDuplicates() {
        String pan = createCard();
        String rrn = rrn();
        Map<String, Object> reserve = Map.of("rrn", rrn, "amount", AMOUNT);
        Map<String, Object> rollback = Map.of("pan", pan, "rrn", rrn, "amount", AMOUNT);

        GATEWAY.request().body(reserve).post("/api/cards/{pan}/reserve", pan).then()
                .statusCode(200).body("availableBalance", equalTo(BALANCE - AMOUNT));
        GATEWAY.request().body(reserve).post("/api/cards/{pan}/reserve", pan).then()
                .statusCode(409).body("error", equalTo("ReservationAlreadyExistsException"));
        GATEWAY.request().body(rollback).post("/api/cards/{pan}/rollback", pan).then()
                .statusCode(200).body("availableBalance", equalTo(BALANCE));
        GATEWAY.request().body(rollback).post("/api/cards/{pan}/rollback", pan).then()
                .statusCode(409).body("error", equalTo("RollbackAlreadySatisfiedException"));
    }

    @Test
    void rejectsReserveAboveBalance() {
        String pan = createCard(BALANCE, DAILY_LIMIT, MONTHLY_LIMIT);

        GATEWAY.request().body(Map.of("rrn", rrn(), "amount", BALANCE + 1))
                .post("/api/cards/{pan}/reserve", pan).then()
                .statusCode(402).body("error", equalTo("InsufficientFundsException"));
    }

    @ParameterizedTest
    @CsvSource({"reserve,404", "rollback,404"})
    void rejectsOperationsForMissingCardOrReservation(String operation, int status) {
        String pan = unusedPan(KNOWN_BIN);

        GATEWAY.request().body(Map.of("pan", pan, "rrn", rrn(), "amount", AMOUNT))
                .post("/api/cards/{pan}/{operation}", pan, operation).then()
                .statusCode(status).body("serviceName", equalTo("card-management"));
    }

    @ParameterizedTest
    @CsvSource({"reserve", "rollback"})
    void rejectsNonPositiveAmountOnMoneyOperations(String operation) {
        String pan = unusedPan(KNOWN_BIN);

        GATEWAY.request().body(Map.of("pan", pan, "rrn", rrn(), "amount", 0))
                .post("/api/cards/{pan}/{operation}", pan, operation).then()
                .statusCode(400).body("serviceName", equalTo("card-management"));
    }
}
