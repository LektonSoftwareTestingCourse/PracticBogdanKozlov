package com.processing.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

import static com.processing.api.support.Api.*;
import static com.processing.api.support.Fixtures.*;
import static org.hamcrest.Matchers.*;

@Tag("contract")
class AuthorizationContractTest {
    @Test
    void approvesAtExactBalanceAndLimits() {
        String pan = createCard(AMOUNT, AMOUNT, AMOUNT);

        AUTH.request().body(authorization(pan, AMOUNT))
                .post("/api/internal/authorize").then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("\\d{12}"));

        CARDS.request().get("/api/cards/{pan}", pan).then()
                .statusCode(200)
                .body("availableBalance", equalTo(0));
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,05,CARD_BLOCKED", "INACTIVE,05,CARD_INACTIVE", "EXPIRED,54,CARD_EXPIRED",
            "DELETED,14,CARD_NOT_FOUND"})
    void distinguishesCardStatusesEvenWhenTheyShareAnIsoCode(String status, String code, String reason) {
        String pan = createCard();
        if (status.equals("DELETED")) {
            GATEWAY.request().delete("/api/cards/{pan}", pan).then().statusCode(204);
        } else {
            GATEWAY.request().body(Map.of("status", status))
                    .patch("/api/cards/{pan}", pan).then().statusCode(200).body("status", equalTo(status));
        }

        assertDecline(authorization(pan, AMOUNT), status.equals("DELETED") ? 404 : 403, code, reason);
    }

    @Test
    void approvesOnLastDayOfExpiryAndDeclinesTheNextDay() {
        String pan = createCard();
        String expiry = CARDS.request().get("/api/cards/{pan}", pan).then().statusCode(200).extract().path("expiryDate");
        LocalDate lastValidDay = YearMonth.parse(expiry).atEndOfMonth();
        Map<String, Object> valid = authorization(pan, AMOUNT);
        valid.put("transmissionDateTime", lastValidDay.atStartOfDay(ZoneOffset.UTC).toInstant().toString());

        AUTH.request().body(valid).post("/api/internal/authorize").then()
                .statusCode(200).body("status", equalTo("APPROVED")).body("responseCode", equalTo("00"));

        Map<String, Object> expired = authorization(pan, AMOUNT);
        expired.put("transmissionDateTime", lastValidDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        assertDecline(expired, 403, "54", "CARD_EXPIRED");
    }

    @Test
    void distinguishesMissingCardAtKnownBinFromUnknownBinAtSwitch() {
        String pan = unusedPan(KNOWN_BIN);
        assertDecline(authorization(pan, AMOUNT), 404, "14", "CARD_NOT_FOUND");

        SWITCH.request().body(transaction(unusedPan(UNKNOWN_BIN), AMOUNT))
                .post("/api/internal/route").then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    void routesValidPurchaseDirectlyThroughSwitch() {
        Map<String, Object> request = transaction(createCard(), AMOUNT);

        SWITCH.request().body(request).post("/api/internal/route").then().statusCode(200)
                .body("stan", equalTo(request.get("stan"))).body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00")).body("rrn", matchesPattern("\\d{12}"));
    }

    @Test
    void rejectsUnreadableSwitchRequest() {
        SWITCH.request().body("{").post("/api/internal/route").then().statusCode(400)
                .body("status", equalTo(400)).body("path", equalTo("/api/internal/route"));
    }

    @Test
    void returns422WhenAmountExceedsBalanceByOne() {
        String pan = createCard(AMOUNT - 1, DAILY_LIMIT, MONTHLY_LIMIT);

        assertDecline(authorization(pan, AMOUNT), 422, "51", "INSUFFICIENT_FUNDS");
    }

    @ParameterizedTest
    @CsvSource({"1500,70000,0", "20000,1500,1"})
    void declinesWhenAccumulatedDailyOrMonthlyLimitWouldBeExceeded(int dailyLimit, int monthlyLimit, int dayShift) {
        String pan = createCard(BALANCE, dailyLimit, monthlyLimit);
        Instant day = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, Object> first = authorization(pan, AMOUNT);
        first.put("transmissionDateTime", day.toString());
        AUTH.request().body(first).post("/api/internal/authorize").then()
                .statusCode(200).body("responseCode", equalTo("00"));

        Map<String, Object> next = authorization(pan, 1);
        next.put("transmissionDateTime", day.plusSeconds(dayShift * 86_400L).toString());
        assertDecline(next, 400, "61", "EXCEEDS_AMOUNT_LIMIT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "amount", "currencyCode",
            "transmissionDateTime", "terminalId", "merchantId", "mcc", "acquirerId", "issuerId"})
    void rejectsMissingRequiredAuthorizationField(String field) {
        Map<String, Object> request = authorization(unusedPan(KNOWN_BIN), AMOUNT);
        request.remove(field);

        AUTH.request().body(request)
                .post("/api/internal/authorize").then()
                .statusCode(400)
                .body("status", equalTo(400))
                .body("path", equalTo("/api/internal/authorize"));
    }

    @ParameterizedTest
    @CsvSource({"mti,100", "stan,12345", "pan,400000123456789", "processingCode,00000",
            "currencyCode,64", "terminalId,TERM001", "merchantId,MERCH1", "mcc,541"})
    void rejectsIncorrectLengthAtAuthorization(String field, String value) {
        Map<String, Object> request = authorization(unusedPan(KNOWN_BIN), AMOUNT);
        request.put(field, value);

        AUTH.request().body(request)
                .post("/api/internal/authorize").then()
                .statusCode(400)
                .body("status", equalTo(400))
                .body("path", equalTo("/api/internal/authorize"));
    }

    @Test
    void rollsBackExistingReservationThenRejectsTheRepeat() {
        String pan = createCard();
        String rrn = AUTH.request().body(authorization(pan, AMOUNT))
                .post("/api/internal/authorize").then().statusCode(200).extract().path("rrn");
        Map<String, Object> rollback = Map.of("pan", pan, "rrn", rrn, "amount", AMOUNT);

        AUTH.request().body(rollback).post("/api/internal/rollback").then()
                .statusCode(200).body("status", equalTo("APPROVED")).body("responseCode", equalTo("00"));
        CARDS.request().get("/api/cards/{pan}", pan).then()
                .statusCode(200).body("availableBalance", equalTo(BALANCE));
        AUTH.request().body(rollback).post("/api/internal/rollback").then()
                .statusCode(409).body("responseCode", equalTo("05")).body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
    }

    @Test
    void reportsUnknownReservationOnRollback() {
        Map<String, Object> request = Map.of("pan", unusedPan(KNOWN_BIN), "rrn", rrn(), "amount", AMOUNT);

        AUTH.request().body(request).post("/api/internal/rollback").then()
                .statusCode(404).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14")).body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void rejectsMalformedRollbackRrn() {
        Map<String, Object> request = Map.of("pan", unusedPan(KNOWN_BIN), "rrn", "123", "amount", AMOUNT);

        AUTH.request().body(request).post("/api/internal/rollback").then()
                .statusCode(400).body("path", equalTo("/api/internal/rollback"));
    }

    @ParameterizedTest
    @CsvSource({"400000,200", "999999,404"})
    void checksBinLookupUsedByAuthorization(String bin, int status) {
        var response = BIN_LOOKUP.request().get("/api/bin/{bin}", bin).then().statusCode(status);
        if (status == 200) {
            response.body("issuerId", equalTo("ISS001"));
        } else {
            response.body(emptyOrNullString());
        }
    }

    @Test
    void binLookupExposesDocumentedFailureMode() {
        BIN_LOOKUP.request().queryParam("fail", true).get("/api/bin/{bin}", KNOWN_BIN).then()
                .statusCode(500).body(emptyOrNullString());
    }

    private void assertDecline(Map<String, Object> request, int http, String code, String reason) {
        AUTH.request().body(request)
                .post("/api/internal/authorize").then()
                .statusCode(http)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason))
                .body("stan", equalTo(request.get("stan")))
                .body("authCode", nullValue());
    }
}
