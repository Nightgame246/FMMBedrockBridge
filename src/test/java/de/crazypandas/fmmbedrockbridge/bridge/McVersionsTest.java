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

    /**
     * Mojang dropped the "1.x" scheme in 2026 for year-based versions: 26.1 ("Tiny
     * Takeover"), 26.2 ("Chaos Cubed"). There is no 1.22. Every 26.x is newer than
     * 1.21.6, so the >= 1.21.6 gate (dialog API, Phase 7.3) must stay open there —
     * a false here would silently disable the Bedrock menu reroute on a 26.x server.
     */
    @Test
    void yearBasedVersionsAreAboveThreshold() {
        assertTrue(McVersions.isAtLeast("26.1", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("26.2", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("26.1.2", 1, 21, 6));          // drop.hotfix
        assertTrue(McVersions.isAtLeast("26.2-R0.1-SNAPSHOT", 1, 21, 6));
    }

    /**
     * Paper's API artifact carries a build/channel suffix ("26.2.build.87-stable").
     * The segment after the patch is non-numeric, which used to abort parsing and
     * fail the whole check closed. Trailing junk must be ignored, not fatal.
     */
    @Test
    void nonNumericTrailingSegmentsAreIgnored() {
        assertTrue(McVersions.isAtLeast("26.2.build.87-stable", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("1.21.10.build.4", 1, 21, 6));
        assertFalse(McVersions.isAtLeast("1.21.4.build.9", 1, 21, 6));  // still below
    }

    /** A leading non-numeric segment means we genuinely cannot tell — stay closed. */
    @Test
    void leadingNonNumericIsFalse() {
        assertFalse(McVersions.isAtLeast("v26.2", 1, 21, 6));
        assertFalse(McVersions.isAtLeast("build.87", 1, 21, 6));
    }
}
