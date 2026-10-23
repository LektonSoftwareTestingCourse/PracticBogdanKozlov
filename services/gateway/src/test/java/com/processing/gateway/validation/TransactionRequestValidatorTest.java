package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestValidatorTest {

    private TransactionRequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TransactionRequestValidator();
    }

    @Test
    void shouldAcceptValidAuthorizationRequest() {
        assertThatCode(() -> validator.validate(validRequest()))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNullRequest() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    @Test
    void shouldRejectUnsupportedMessageType() {
        assertThatThrownBy(() -> validator.validate(request("0200", "4000001234560001", "1.00", "643", "5045")))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mti' must be '0100'");
    }

    @ParameterizedTest(name = "rejects PAN {0}")
    @CsvSource({
            "400000123456001",
            "40000012345600012",
            "40000012345600AB"
    })
    void shouldRejectPanThatIsNotExactlySixteenDigits(String pan) {
        assertThatThrownBy(() -> validator.validate(request("0100", pan, "1.00", "643", "5045")))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'pan' must be exactly 16 digits");
    }

    @ParameterizedTest(name = "rejects non-positive amount {0}")
    @CsvSource({"0.00", "-0.01"})
    void shouldRejectNonPositiveAmount(String amount) {
        assertThatThrownBy(() -> validator.validate(request("0100", "4000001234560001", amount, "643", "5045")))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' must be > 0");
    }

    @ParameterizedTest(name = "rejects currency code {0}")
    @CsvSource({"64", "6430"})
    void shouldRejectCurrencyCodeWithInvalidLength(String currencyCode) {
        assertThatThrownBy(() -> validator.validate(request("0100", "4000001234560001", "1.00", currencyCode, "5045")))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'currencyCode' must be exactly 3 characters");
    }

    @ParameterizedTest(name = "rejects merchant category code {0}")
    @CsvSource({"504", "50450", "50A5"})
    void shouldRejectMerchantCategoryCodeThatIsNotFourDigits(String mcc) {
        assertThatThrownBy(() -> validator.validate(request("0100", "4000001234560001", "1.00", "643", mcc)))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mcc' must be exactly 4 digits");
    }

    private AuthorizationRequest validRequest() {
        return request("0100", "4000001234560001", "1.00", "643", "5045");
    }

    private AuthorizationRequest request(String mti, String pan, String amount, String currencyCode, String mcc) {
        return AuthorizationRequest.builder()
                .mti(mti)
                .stan("000001")
                .pan(pan)
                .processingCode("000000")
                .amount(new BigDecimal(amount))
                .currencyCode(currencyCode)
                .transmissionDateTime(Instant.parse("2026-10-22T10:00:00Z"))
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId("MERCH0000000001")
                .mcc(mcc)
                .acquirerId("ACQ001")
                .issuerId("ISSUER_A")
                .build();
    }
}
