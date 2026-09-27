package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase 7.1d. EliteMobs gives a custom boss with an FMM model its real name only through the
 * model; the mob underneath keeps the generic one ("Evoker"). Bedrock sees that mob, so the
 * bridge swaps the name for Bedrock viewers — and must step aside by itself the day EliteMobs
 * writes the right name onto the mob.
 */
class BedrockNameFixTest {

    private static final String REAL = "§4『§c13§4』§f §9Eis-Elementar";

    @Test
    void theGenericNameIsReplaced() {
        assertEquals(REAL, BedrockNameFix.replacement("§fLvl §213 §fElite §2Evoker", REAL));
        assertEquals(REAL, BedrockNameFix.replacement("Evoker | 2", REAL));
    }

    @Test
    void stepsAsideOnceEliteMobsSendsTheRightName() {
        // The upstream fix: nothing left to do.
        assertNull(BedrockNameFix.replacement(REAL, REAL));
    }

    @Test
    void onlyTheTextCountsNotTheColours() {
        assertNull(BedrockNameFix.replacement("§c『13』 Eis-Elementar", "§4『§c13§4』 §9Eis-Elementar"));
    }

    @Test
    void neverAddsANameWhereThereIsNone() {
        assertNull(BedrockNameFix.replacement(null, REAL));
    }

    @Test
    void withoutAModelNameThereIsNothingToSwapIn() {
        assertNull(BedrockNameFix.replacement("Evoker | 2", null));
        assertNull(BedrockNameFix.replacement("Evoker | 2", "  "));
    }
}
