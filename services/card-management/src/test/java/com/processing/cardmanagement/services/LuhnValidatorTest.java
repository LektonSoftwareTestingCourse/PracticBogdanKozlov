package com.processing.cardmanagement.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {

    private final LuhnValidator validator = new LuhnValidator();

    @ParameterizedTest(name = "PAN {0} validity is {1}")
    @CsvSource({
            "4111111111111111, true",
            "4000000000000002, true",
            "4111111111111112, false",
            "1234567890123456, false"
    })
    void shouldValidatePanUsingLuhnChecksum(String pan, boolean expected) {
        assertThat(validator.isValid(pan)).isEqualTo(expected);
    }

    @Test
    void shouldGenerateSixteenDigitPanWithRequestedBin() {
        String pan = validator.generatePan("400000");

        assertThat(pan).startsWith("400000").hasSize(16);
        assertThat(validator.isValid(pan)).isTrue();
    }
}
