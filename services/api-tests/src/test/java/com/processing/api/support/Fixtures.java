package com.processing.api.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static com.processing.api.support.Api.GATEWAY;
import static com.processing.api.support.Api.LOGGER;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

public final class Fixtures {
    public static final String KNOWN_BIN = "400000";
    public static final String UNKNOWN_BIN = "999999";
    public static final int BALANCE = 100_000;
    public static final int AMOUNT = 1_500;
    public static final int DAILY_LIMIT = 20_000;
    public static final int MONTHLY_LIMIT = 70_000;

    private static final AtomicInteger STAN = new AtomicInteger(ThreadLocalRandom.current().nextInt(1_000_000));

    private Fixtures() {
    }

    public static Map<String, Object> cardRequest() {
        return new LinkedHashMap<>(Map.of(
                "bin", KNOWN_BIN,
                "cardholderName", "API CONTRACT TEST",
                "currencyCode", "643",
                "dailyLimit", DAILY_LIMIT,
                "monthlyLimit", MONTHLY_LIMIT,
                "initialBalance", BALANCE));
    }

    public static String createCard() {
        return createCard(BALANCE, DAILY_LIMIT, MONTHLY_LIMIT);
    }

    public static String createCard(int balance, int dailyLimit, int monthlyLimit) {
        Map<String, Object> request = cardRequest();
        request.put("initialBalance", balance);
        request.put("dailyLimit", dailyLimit);
        request.put("monthlyLimit", monthlyLimit);
        return GATEWAY.request().body(request)
                .post("/api/cards").then()
                .statusCode(201)
                .body("status", equalTo("ACTIVE"))
                .body("pan", matchesPattern("\\d{16}"))
                .extract().path("pan");
    }

    public static String unusedPan(String bin) {
        String prefix = bin + "%09d".formatted(ThreadLocalRandom.current().nextInt(1_000_000_000));
        int sum = 0;
        for (int index = 0; index < prefix.length(); index++) {
            int digit = prefix.charAt(index) - '0';
            int weighted = index % 2 == 0 ? digit * 2 : digit;
            sum += weighted > 9 ? weighted - 9 : weighted;
        }
        return prefix + (10 - sum % 10) % 10;
    }

    public static String rrn() {
        return String.format(Locale.ROOT, "%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
    }

    public static Map<String, Object> transaction(String pan, int amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mti", "0100");
        payload.put("stan", String.format(Locale.ROOT, "%06d", Math.floorMod(STAN.getAndIncrement(), 1_000_000)));
        payload.put("pan", pan);
        payload.put("processingCode", "000000");
        payload.put("amount", amount);
        payload.put("currencyCode", "643");
        payload.put("transmissionDateTime", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        payload.put("terminalId", "TERM0001");
        payload.put("terminalType", "POS");
        payload.put("merchantId", "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
        payload.put("mcc", "5411");
        payload.put("acquirerId", "ACQ001");
        return payload;
    }

    public static Map<String, Object> authorization(String pan, int amount) {
        Map<String, Object> payload = transaction(pan, amount);
        payload.put("issuerId", "ISS001");
        return payload;
    }

    public static Map<String, Object> logEntry(String pan) {
        Map<String, Object> payload = authorization(pan, AMOUNT);
        payload.put("id", UUID.randomUUID().toString());
        payload.put("createdAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        payload.put("rrn", rrn());
        payload.put("status", "APPROVED");
        payload.put("authCode", "TEST01");
        payload.put("processingTimeMs", 0);
        return payload;
    }

    public static String store(Map<String, Object> payload) {
        LOGGER.request().body(payload).post("/api/internal/log").then().statusCode(201);
        return payload.get("id").toString();
    }

    public static String matchingRows(String field, Object value) {
        return "transactions.findAll { it." + field + " == '" + value + "' }";
    }

}
