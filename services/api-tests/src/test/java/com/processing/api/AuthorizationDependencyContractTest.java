package com.processing.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static com.processing.api.support.Api.AUTH;
import static com.processing.api.support.Fixtures.*;
import static org.hamcrest.Matchers.equalTo;

@Tag("card-stub")
class AuthorizationDependencyContractTest {
    @ParameterizedTest
    @CsvSource({"830101,403,05,CARD_BLOCKED", "830102,400,05,UNKNOWN_REASON",
            "830103,503,96,RESERVATION_FAILED", "830104,422,51,INSUFFICIENT_FUNDS",
            "830105,404,14,CARD_NOT_FOUND", "830106,404,14,CARD_NOT_FOUND",
            "830107,503,96,SERVICE_UNAVAILABLE"})
    void mapsDependencyOutcomesToExpectedAuthorizationResponse(String bin, int http, String code, String reason) {
        String pan = unusedPan(bin);
        Map<String, Object> request = authorization(pan, AMOUNT);

        AUTH.request().body(request).post("/api/internal/authorize").then().statusCode(http)
                .body("stan", equalTo(request.get("stan"))).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code)).body("declineReason", equalTo(reason));
    }

    @ParameterizedTest
    @CsvSource({"830108,503,96,ROLLBACK_FAILED", "830109,404,14,TRANSACTION_NOT_FOUND"})
    void mapsDependencyFailuresDuringRollback(String bin, int http, String code, String reason) {
        Map<String, Object> request = Map.of("pan", unusedPan(bin), "rrn", rrn(), "amount", AMOUNT);

        AUTH.request().body(request).post("/api/internal/rollback").then().statusCode(http)
                .body("status", equalTo("DECLINED")).body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason));
    }
}
