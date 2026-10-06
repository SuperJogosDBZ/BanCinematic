package br.com.bancinematic.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeUtilTest {
    @Test
    void parsesSupportedUnitsAndRejectsInvalidValues() {
        assertEquals(30L, TimeUtil.parse("30s"));
        assertEquals(600L, TimeUtil.parse("10m"));
        assertEquals(7200L, TimeUtil.parse("2h"));
        assertEquals(604800L, TimeUtil.parse("1w"));
        assertEquals(86400L, TimeUtil.parse("1D"));

        assertNull(TimeUtil.parse(null));
        assertNull(TimeUtil.parse(""));
        assertNull(TimeUtil.parse("0s"));
        assertNull(TimeUtil.parse("-1m"));
        assertNull(TimeUtil.parse("10x"));
        assertNull(TimeUtil.parse("9223372036854775807s"));
        assertNull(TimeUtil.parse("9223372036854775s"));
        assertNull(TimeUtil.parse("999999999999999999999w"));
    }

    @Test
    void formatsRemainingTimeWithoutRoundingDown() {
        assertEquals("permanente", TimeUtil.formatRemaining(null));
        assertEquals("menos de 1 segundo", TimeUtil.formatRemaining(Instant.now().minusSeconds(1)));

        String remaining = TimeUtil.formatRemaining(Instant.now().plusSeconds(90));
        assertTrue(remaining.startsWith("1 minuto, 30 segundos"), remaining);
    }
}
