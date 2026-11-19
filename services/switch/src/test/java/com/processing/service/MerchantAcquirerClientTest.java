package com.processing.service;

import com.processing.config.SwitchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MerchantAcquirerClientTest {

    private MockRestServiceServer server;
    private MerchantAcquirerClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SwitchProperties properties = new SwitchProperties(
                "test", Map.of(), "http://auth", "http://logger", "http://merchant", null, null, null);
        client = new MerchantAcquirerClient(properties, builder.build());
    }

    @Test
    void shouldReturnFeeFromMerchantAcquirer() {
        server.expect(requestTo("http://merchant/api/simulator/merchant/fee"))
                .andRespond(withSuccess("{\"acquirerFee\":\"0.25\"}", MediaType.APPLICATION_JSON));

        BigDecimal fee = client.fetchAcquiringFee(
                Instant.parse("2026-10-01T10:00:00Z"), "000001", "4000001234567893", "TERM0001",
                new BigDecimal("10.00"));

        assertThat(fee).isEqualByComparingTo("0.25");
        server.verify();
    }

    @Test
    void shouldReturnNullForEmptyResponse() {
        server.expect(requestTo("http://merchant/api/simulator/merchant/fee"))
                .andRespond(withNoContent());

        BigDecimal fee = client.fetchAcquiringFee(
                Instant.parse("2026-10-01T10:00:00Z"), "000001", "4000001234567893", "TERM0001",
                BigDecimal.TEN);

        assertThat(fee).isNull();
        server.verify();
    }

    @Test
    void shouldReturnNullWhenMerchantAcquirerFails() {
        server.expect(requestTo("http://merchant/api/simulator/merchant/fee"))
                .andRespond(withServerError());

        BigDecimal fee = client.fetchAcquiringFee(
                Instant.parse("2026-10-01T10:00:00Z"), "000001", "4000001234567893", "TERM0001",
                BigDecimal.TEN);

        assertThat(fee).isNull();
        server.verify();
    }
}
