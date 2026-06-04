package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McVersionsTest {

    @Test
    void atOrAboveThreshold() {
        assertTrue(McVersions.isAtLeast("1.21.10", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("1.21.6", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("1.22.0", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("2.0.0", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("1.21.10-R0.1-SNAPSHOT", 1, 21, 6));
    }

    @Test
    void belowThreshold() {
        assertFalse(McVersions.isAtLeast("1.21.4", 1, 21, 6));
        assertFalse(McVersions.isAtLeast("1.21", 1, 21, 6));   // patch 0 < 6
        assertFalse(McVersions.isAtLeast("1.20.10", 1, 21, 6));
    }

    @Test
    void malformedIsFalse() {
        assertFalse(McVersions.isAtLeast(null, 1, 21, 6));
        assertFalse(McVersions.isAtLeast("garbage", 1, 21, 6));
        assertFalse(McVersions.isAtLeast("", 1, 21, 6));
    }
}
