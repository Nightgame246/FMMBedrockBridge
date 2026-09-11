package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * EliteMobs' advancedcombat package is internal and marked Alpha. The bridge must survive it
 * disappearing, so availability is probed by name and never by import.
 *
 * <p>In the test JVM EliteMobs is on the classpath as a provided dependency, but the pom still
 * points at 10.8.0, which predates the Advanced Combat System — so the probe must report false
 * here. That is exactly the "class is gone" case this guard exists for.
 */
class AdvancedCombatSupportTest {

    @Test
    void reportsAbsentWhenTheAlphaPackageIsNotOnTheClasspath() {
        assertFalse(AdvancedCombatSupport.isPresent(),
                "EliteMobs 10.8.0 has no advancedcombat package — the probe must not claim otherwise");
    }

    @Test
    void alwaysExplainsWhyItIsUnavailable() {
        assertNotNull(AdvancedCombatSupport.missingReason());
        assertFalse(AdvancedCombatSupport.missingReason().isBlank(),
                "the startup log needs a usable reason");
    }
}
