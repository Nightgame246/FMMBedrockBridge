package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EliteMobs' advancedcombat package is internal and marked Alpha. The bridge must survive it
 * disappearing, so availability is probed by name and never by import.
 *
 * <p>In the test JVM EliteMobs is on the classpath as a provided dependency. The pom now points
 * at 10.9.0, which does carry the Advanced Combat System — so the probe finds both classes and
 * must report true here. This is the positive counterpart to the "class is gone" case the guard
 * exists for: the probe has to work in both directions, by name, without ever importing the
 * package itself.
 */
class AdvancedCombatSupportTest {

    @Test
    void reportsPresentWhenTheAlphaPackageIsOnTheClasspath() {
        assertTrue(AdvancedCombatSupport.isPresent(),
                "EliteMobs 10.9.0 has the advancedcombat package — the probe must find it");
    }

    @Test
    void missingReasonClearsOnceThePackageIsPresent() {
        assertTrue(AdvancedCombatSupport.isPresent());
        assertEquals("", AdvancedCombatSupport.missingReason(),
                "once the probe succeeds there is nothing left to explain");
    }
}
