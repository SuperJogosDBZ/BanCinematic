package br.com.bancinematic.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InputLimitsTest {
    @Test
    void configuredLimitsHaveExpectedBoundaries() {
        assertEquals(256, InputLimits.MAX_REASON_LENGTH);
        assertEquals(1000, InputLimits.MAX_HISTORY_PAGE);
    }
}
