package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "ATM, ATM00000",
            "TERM0001, TERM0001",
            "TERMINAL-01, TERMINAL"
    })
    void shouldPadOrTruncateTerminalId(String terminalId, String expected) {
        assertThat(AuthorizationRequestNormalizer.normalizeTerminalId(terminalId)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = "   ")
    void shouldPreserveMissingTerminalId(String terminalId) {
        assertThat(AuthorizationRequestNormalizer.normalizeTerminalId(terminalId)).isEqualTo(terminalId);
    }

    @ParameterizedTest
    @CsvSource({
            "MERCH1234567890, MERCH1234567890",
            "MERCH01234567890, MERCH1234567890",
            "MERCH1, MERCH1000000000",
            "MERCH012345678901, MERCH0123456789",
            "MERCH11234567890, MERCH1123456789",
            "OTHER12345678901, OTHER1234567890"
    })
    void shouldNormalizeMerchantId(String merchantId, String expected) {
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId(merchantId)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = "   ")
    void shouldPreserveMissingMerchantId(String merchantId) {
        assertThat(AuthorizationRequestNormalizer.normalizeMerchantId(merchantId)).isEqualTo(merchantId);
    }

    @Test
    void shouldCopyRequestFieldsAndNormalizeIdentifiers() {
        AuthorizationRequest request = AuthorizationRequest.builder()
                .mti("0100")
                .stan("000001")
                .pan("4000001234567893")
                .processingCode("000000")
                .amount(new BigDecimal("12.50"))
                .currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-10-01T10:00:00Z"))
                .terminalId("ATM")
                .terminalType("POS")
                .merchantId("MERCH1")
                .mcc("5045")
                .acquirerId("ACQ001")
                .issuerId("ISSUER_A")
                .build();

        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(request);

        assertThat(normalized.terminalId()).isEqualTo("ATM00000");
        assertThat(normalized.merchantId()).isEqualTo("MERCH1000000000");
        assertThat(normalized.pan()).isEqualTo(request.pan());
        assertThat(normalized.amount()).isEqualByComparingTo(request.amount());
        assertThat(normalized.issuerId()).isEqualTo(request.issuerId());
    }
}
