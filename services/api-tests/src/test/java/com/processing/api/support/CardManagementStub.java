package com.processing.api.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;

/**
 * Serves only the Card Management responses needed to exercise rare HTTP outcomes in Authorization.
 * Started on the isolated Compose network after the tests against real Card Management.
 */
public final class CardManagementStub {
    private CardManagementStub() {
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/health", exchange -> reply(exchange, 200, """
                {"status":"ok","service":"card-management-stub"}
                """));
        server.createContext("/api/cards/", CardManagementStub::handleCardRequest);
        server.start();
    }

    private static void handleCardRequest(HttpExchange exchange) throws IOException {
        String[] path = exchange.getRequestURI().getPath().split("/");
        if (path.length < 4 || path[3].length() != 16) {
            reply(exchange, 404, "{}");
            return;
        }
        String pan = path[3];
        String scenario = pan.substring(0, 6);
        String operation = path.length == 4 ? "get" : path[4];
        switch (operation) {
            case "get" -> {
                if (scenario.equals("830101")) {
                    reply(exchange, 402, "{}");
                } else {
                    String status = scenario.equals("830102") ? "null" : "\"ACTIVE\"";
                    reply(exchange, 200, """
                            {"pan":"%s","status":%s,"expiryDate":"%s",
                             "availableBalance":100000,"dailyLimit":20000,"monthlyLimit":70000}
                            """.formatted(pan, status, YearMonth.now().plusYears(3)));
                }
            }
            case "reserve" -> {
                int status = switch (scenario) {
                    case "830103" -> 409;
                    case "830104" -> 402;
                    case "830105" -> 400;
                    case "830106" -> 404;
                    case "830107" -> 503;
                    default -> 200;
                };
                reply(exchange, status, "{}");
            }
            case "rollback" -> {
                int status = switch (scenario) {
                    case "830108" -> 422;
                    case "830109" -> 400;
                    default -> 404;
                };
                reply(exchange, status, "{}");
            }
            default -> reply(exchange, 404, "{}");
        }
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        try (exchange) {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
