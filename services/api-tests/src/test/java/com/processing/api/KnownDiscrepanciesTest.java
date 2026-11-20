package com.processing.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.processing.api.support.Api.GATEWAY;
import static com.processing.api.support.Fixtures.AMOUNT;
import static com.processing.api.support.Fixtures.createCard;
import static com.processing.api.support.Fixtures.transaction;
import static org.hamcrest.Matchers.*;

@Tag("known-discrepancy")
class KnownDiscrepanciesTest {
    @Test
    void shouldReturnAuthorizationResponseMtiDocumentedInApiSpec() {
        GATEWAY.request().body(transaction(createCard(), AMOUNT))
                .post("/api/transactions").then().statusCode(200)
                .body("status", equalTo("APPROVED")).body("mti", equalTo("0110"));
    }

    @Test
    void shouldExplainReversedDateRangeInLoggerError() {
        GATEWAY.request().queryParam("dateFrom", "2026-04-02").queryParam("dateTo", "2026-04-01")
                .get("/api/transactions/search").then().statusCode(400)
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", not(isEmptyOrNullString()));
    }

    @Test
    void shouldExposeDocumentedMerchantDirectoryThroughGateway() {
        GATEWAY.request().get("/api/simulator/merchants").then()
                .statusCode(200).body("$", not(empty()));
    }
}
