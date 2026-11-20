package com.processing.api.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.LogConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

public enum Api {
    GATEWAY("gateway", 8080),
    CARDS("cards", 8081),
    SWITCH("switch", 8082),
    AUTH("authorization", 8083),
    MERCHANT("merchant", 8084),
    TERMINAL("terminal", 8085),
    LOGGER("logger", 8088),
    BIN_LOOKUP("bin", 8096);

    private final RequestSpecification specification;

    Api(String service, int port) {
        String address = System.getProperty("api." + service + ".url", "http://127.0.0.1:" + port);
        specification = new RequestSpecBuilder()
                .setBaseUri(address)
                .setContentType(ContentType.JSON)
                .setAccept("*/*")
                .setConfig(RestAssuredConfig.config()
                        .logConfig(LogConfig.logConfig().enableLoggingOfRequestAndResponseIfValidationFails())
                        .httpClient(HttpClientConfig.httpClientConfig()
                                .setParam("http.connection.timeout", 5000)
                                .setParam("http.socket.timeout", 45000)))
                .build();
    }

    public RequestSpecification request() {
        return given().spec(specification);
    }
}
