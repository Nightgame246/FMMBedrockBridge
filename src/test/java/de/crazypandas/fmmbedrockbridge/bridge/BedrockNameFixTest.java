package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7.1d. FMM draws its own nameplate above the model for Bedrock viewers too, and Bedrock
 * additionally shows the custom name of the mob underneath. Measured on 30.09.2026: renaming the
 * mob only turned "Evoker" + real name into the real name twice. So the mob's own name is hidden
 * for Bedrock whenever the model carries a real name of its own.
 */
class BedrockNameFixTest {

    private static final String MODEL_NAME = "§4『§c13§4』§f §9Eis-Elementar";

    @Test
    void theMobNameGoesWhenTheModelShowsItsOwn() {
        assertTrue(BedrockNameFix.hideMobName("§fEvoker §7| §b2", MODEL_NAME));
    }

    @Test
    void itAlsoGoesWhenBothSayTheSame() {
        // Two identical nametags are exactly what the Bedrock player saw on 30.09.
        assertTrue(BedrockNameFix.hideMobName(MODEL_NAME, MODEL_NAME));
    }

    @Test
    void fmmsPlaceholderIsNotARealName() {
        // A model nobody named: FMM reports "Default Name" — the mob name must stay.
        assertFalse(BedrockNameFix.hideMobName("§fHusk §7| §b1", "Default Name"));
        assertFalse(BedrockNameFix.hideMobName("§fHusk §7| §b1", "§7Default Name"));
    }

    @Test
    void withoutAModelNameTheMobKeepsItsName() {
        assertFalse(BedrockNameFix.hideMobName("§fHusk §7| §b1", null));
        assertFalse(BedrockNameFix.hideMobName("§fHusk §7| §b1", "  "));
    }

    @Test
    void aMobWithoutANameNeedsNothing() {
        assertFalse(BedrockNameFix.hideMobName(null, MODEL_NAME));
    }
}
