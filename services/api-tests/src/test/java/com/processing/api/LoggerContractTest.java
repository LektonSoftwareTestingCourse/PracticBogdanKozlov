package com.processing.api;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.processing.api.support.Api.GATEWAY;
import static com.processing.api.support.Api.LOGGER;
import static com.processing.api.support.Fixtures.*;
import static org.hamcrest.Matchers.*;

@Tag("contract")
class LoggerContractTest {
    private static final String LOG = "/api/internal/log";
    private static final String SEARCH = "/api/transactions/search";
    private static final String EXPORT = "/api/transactions/export";

    @Test
    void storesOnceOnExactReplayAndRejectsConflictingReplay() {
        String pan = unusedPan(KNOWN_BIN);
        Map<String, Object> entry = logEntry(pan);
        String id = entry.get("id").toString();

        LOGGER.request().body(entry).post(LOG).then().statusCode(201)
                .body("id", equalTo(id)).body("status", equalTo("stored"));
        LOGGER.request().body(entry).post(LOG).then().statusCode(200)
                .body("id", equalTo(id)).body("stan", equalTo(entry.get("stan")))
                .body("amount", equalTo(AMOUNT));

        Map<String, Object> conflict = new LinkedHashMap<>(entry);
        conflict.put("amount", AMOUNT + 1);
        LOGGER.request().body(conflict).post(LOG).then().statusCode(409)
                .body("error", equalTo("Transaction conflict"))
                .body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString(id));

        String match = matchingRows("id", id);
        GATEWAY.request().queryParam("pan", pan).get(SEARCH).then().statusCode(200)
                .body("total", equalTo(1)).body(match, hasSize(1))
                .body(match + "[0].amount", equalTo(AMOUNT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "mti", "stan", "pan", "processingCode", "amount", "currencyCode",
            "terminalId", "merchantId", "mcc", "acquirerId", "status", "transmissionDateTime", "createdAt"})
    void rejectsEachRequiredLogField(String field) {
        Map<String, Object> entry = logEntry(unusedPan(KNOWN_BIN));
        entry.remove(field);

        invalidLog(entry, field);
    }

    @ParameterizedTest
    @MethodSource("invalidLogFields")
    void rejectsInvalidLogFieldValue(String field, Object value) {
        Map<String, Object> entry = logEntry(unusedPan(KNOWN_BIN));
        entry.put(field, value);

        invalidLog(entry, field);
    }

    static Stream<Arguments> invalidLogFields() {
        return Stream.of(
                Arguments.of("mti", "00000"), Arguments.of("stan", "12345"), Arguments.of("rrn", "1".repeat(13)),
                Arguments.of("pan", "123"), Arguments.of("processingCode", "ABC123"), Arguments.of("amount", 0),
                Arguments.of("currencyCode", "64X"), Arguments.of("terminalId", "1".repeat(9)),
                Arguments.of("terminalType", "1".repeat(11)), Arguments.of("merchantId", "1".repeat(16)),
                Arguments.of("mcc", "541A"), Arguments.of("acquirerId", "1".repeat(11)),
                Arguments.of("issuerId", "1".repeat(11)), Arguments.of("acquiringFee", -1),
                Arguments.of("declineReason", "X".repeat(101)), Arguments.of("authCode", "X".repeat(7)),
                Arguments.of("processingTimeMs", -1));
    }

    private void invalidLog(Map<String, Object> entry, String field) {
        LOGGER.request().body(entry).post(LOG).then().statusCode(400)
                .body("error", equalTo("Validation error")).body("message", containsString(field + ":"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
    void findsFixtureAcrossPairwiseCombinationsOfAllQueryParameters(int row) {
        String pan = unusedPan(KNOWN_BIN);
        Map<String, Object> entry = logEntry(pan);
        entry.put("status", "DECLINED");
        entry.put("declineReason", "insufficient funds");
        String id = store(entry);
        String date = LocalDate.ofInstant(Instant.parse(entry.get("transmissionDateTime").toString()), ZoneOffset.UTC)
                .toString();

        List<String> names = List.of("pan", "status", "dateFrom", "dateTo", "merchantId", "issuerId", "mcc",
                "declineReason", "limit", "offset");
        List<Object> values = List.of(pan, "DECLINED", date, date, entry.get("merchantId"), entry.get("issuerId"),
                entry.get("mcc"), "insufficient", 500, 0);
        Map<String, Object> query = new LinkedHashMap<>();
        // Distinct nonzero linear functions on four bits: every pair yields 00, 01, 10 and 11.
        IntStream.range(0, names.size())
                .filter(column -> Integer.bitCount(row & (column + 1)) % 2 == 1)
                .forEach(column -> query.put(names.get(column), values.get(column)));

        GATEWAY.request().queryParams(query).get(SEARCH).then().statusCode(200)
                .body(matchingRows("id", id), hasSize(1));
    }

    @ParameterizedTest
    @CsvSource({"pan,9999999999999999", "status,DECLINED", "dateFrom,2099-01-01", "dateTo,2000-01-01",
            "merchantId,OTHER-MERCHANT", "issuerId,OTHER", "mcc,0000", "declineReason,unrelated"})
    void excludesKnownEntryWhenIndividualFilterDoesNotMatch(String field, String mismatch) {
        String id = store(logEntry(unusedPan(KNOWN_BIN)));

        GATEWAY.request().queryParam(field, mismatch).queryParam("limit", 500)
                .get(SEARCH).then().statusCode(200)
                .body(matchingRows("id", id), empty());
    }

    @Test
    void sortsPaginatesAndPreservesTotal() {
        String pan = unusedPan(KNOWN_BIN);
        Map<String, Object> older = logEntry(pan);
        older.put("createdAt", "2025-01-01T00:00:00Z");
        String oldId = store(older);
        String newId = store(logEntry(pan));

        GATEWAY.request().queryParam("pan", pan).queryParam("limit", 1).queryParam("offset", 0)
                .get(SEARCH).then().statusCode(200).body("total", equalTo(2))
                .body("transactions.id", contains(newId));
        GATEWAY.request().queryParam("pan", pan).queryParam("limit", 1).queryParam("offset", 1)
                .get(SEARCH).then().statusCode(200).body("total", equalTo(2))
                .body("transactions.id", contains(oldId));
        GATEWAY.request().queryParam("pan", pan).queryParam("limit", 500).queryParam("offset", 2)
                .get(SEARCH).then().statusCode(200).body("total", equalTo(2)).body("transactions", empty());
    }

    @Test
    void returnsEmptySearchWhenNoTransactionMatches() {
        GATEWAY.request().queryParam("pan", unusedPan(UNKNOWN_BIN)).get(SEARCH).then().statusCode(200)
                .body("total", equalTo(0)).body("transactions", empty());
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "limit,501", "offset,-1", "offset,not-a-number", "status,INVALID", "dateFrom,2026-02-30"})
    void rejectsInvalidSearchFilter(String field, String value) {
        GATEWAY.request().queryParam(field, value).get(SEARCH).then().statusCode(400)
                .body("serviceName", equalTo("transaction-logger")).body("message", containsString(field));
    }

    @Test
    void rejectsReversedDateRange() {
        GATEWAY.request().queryParam("dateFrom", "2026-02-02").queryParam("dateTo", "2026-02-01")
                .get(SEARCH).then().statusCode(400).body("serviceName", equalTo("transaction-logger"))
                .body("error", equalTo("Validation error")).body("timestamp", notNullValue());
    }

    @Test
    void exportsCsvWithOnlyFilteredTransactions() {
        String pan = unusedPan(KNOWN_BIN);
        String first = store(logEntry(pan));
        String second = store(logEntry(pan));
        String unrelated = store(logEntry(unusedPan(KNOWN_BIN)));

        GATEWAY.request().queryParam("pan", pan).queryParam("limit", 1).queryParam("offset", 1)
                .get(EXPORT).then().statusCode(200).contentType("text/csv")
                .header("Content-Disposition", containsString("transactions.csv"))
                .body(allOf(containsString(first), containsString(second), not(containsString(unrelated))));
    }

    @Test
    void validatesExportFilters() {
        GATEWAY.request().queryParam("limit", 0).get(EXPORT).then().statusCode(400)
                .body("serviceName", equalTo("transaction-logger")).body("message", containsString("limit"));
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/transactions/search,405", "GET,/api/transactions/absent,404"})
    void reportsMethodAndPathErrorsFromLogger(String method, String path, int status) {
        GATEWAY.request().request(method, path).then().statusCode(status)
                .body("serviceName", equalTo("transaction-logger")).body("message", not(isEmptyOrNullString()));
    }

    @Test
    void exposesAggregatedStatsAndRecentTransaction() {
        String id = store(logEntry(unusedPan(KNOWN_BIN)));

        GATEWAY.request().get("/api/dashboard/stats").then().statusCode(200)
                .body("totalTransactions", greaterThanOrEqualTo(1)).body("approvedCount", greaterThanOrEqualTo(1))
                .body("approvalRate", both(greaterThanOrEqualTo(0f)).and(lessThanOrEqualTo(1f)));
        GATEWAY.request().queryParam("limit", 1).get("/api/dashboard/recent").then().statusCode(200)
                .body("id", contains(id));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 501})
    void validatesDashboardRecentLimit(int limit) {
        GATEWAY.request().queryParam("limit", limit).get("/api/dashboard/recent").then()
                .statusCode(400).body("message", containsString("limit"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hour", "day"})
    void groupsChartResultsWithinRequestedTimeWindow(String granularity) {
        Map<String, Object> entry = logEntry(unusedPan(KNOWN_BIN));
        entry.put("createdAt", "2024-12-11T13:45:00Z");
        store(entry);
        String expected = granularity.equals("hour") ? "2024-12-11T13:00:00Z" : "2024-12-11T00:00:00Z";

        GATEWAY.request().queryParam("granularity", granularity).queryParam("from", "2024-12-11T13:00:00Z")
                .queryParam("to", "2024-12-11T14:00:00Z").get("/api/dashboard/charts").then().statusCode(200)
                .body("timestamp", contains(expected)).body("approved[0]", greaterThanOrEqualTo(1));
    }

    @Test
    void rejectsInvalidChartGranularityAndReversedRange() {
        GATEWAY.request().queryParam("granularity", "minute").get("/api/dashboard/charts").then().statusCode(400)
                .body("message", containsString("granularity"));
        GATEWAY.request().queryParam("from", "2026-04-02T00:00:00Z").queryParam("to", "2026-04-01T00:00:00Z")
                .get("/api/dashboard/charts").then().statusCode(400)
                .body("error", equalTo("Validation error")).body("serviceName", equalTo("transaction-logger"));
    }
}
