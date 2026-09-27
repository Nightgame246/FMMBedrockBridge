package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bedrock cannot render EliteMobs' font-based combat HUD, so the bridge falls back to plain
 * action-bar text. Only player-relevant failures are shown; technical ones would be noise.
 */
class AbilityFeedbackTest {

    @Test
    void successShowsTheAbilityName() {
        assertEquals("§a▶ Schattenschritt", AbilityFeedback.forSuccess("Schattenschritt"));
    }

    @Test
    void playerRelevantFailuresGetAText() {
        assertEquals("§7Kein Ziel", AbilityFeedback.forFailure("NO_VALID_TARGET"));
        assertEquals("§7Weg blockiert", AbilityFeedback.forFailure("PATH_BLOCKED"));
        assertEquals("§7Weg blockiert", AbilityFeedback.forFailure("UNSAFE_DESTINATION"));
        assertEquals("§7Fähigkeit noch nicht freigeschaltet",
                AbilityFeedback.forFailure("INVALID_LEVEL"));
    }

    @Test
    void technicalFailuresStaySilent() {
        // These say nothing a player could act on.
        assertNull(AbilityFeedback.forFailure("WRONG_THREAD"));
        assertNull(AbilityFeedback.forFailure("ENGINE_CLOSED"));
        assertNull(AbilityFeedback.forFailure("INVALID_PLAYER"));
        assertNull(AbilityFeedback.forFailure("ABILITY_NOT_REGISTERED"));
        assertNull(AbilityFeedback.forFailure("NONE"));
    }

    @Test
    void unknownReasonsStaySilentInsteadOfLeakingEnumNames() {
        // EliteMobs is Alpha and may add values; a raw enum name must never reach a player.
        assertNull(AbilityFeedback.forFailure("SOME_FUTURE_REASON"));
        assertNull(AbilityFeedback.forFailure(null));
    }

    @Test
    void successTextSurvivesAnEmptyName() {
        // abilityName() can return null or blank if EM has no display name for the slot.
        assertTrue(AbilityFeedback.forSuccess("").endsWith("▶"));
        assertTrue(AbilityFeedback.forSuccess(null).endsWith("▶"));
    }

    @Test
    void controlsHintUsesEliteMobsNumbersAndOurGestures() {
        assertEquals("§7[1/2×Ducken] §fDash  §7[2/Links] §fWirbel  §7[3/Rechts] §fSchild",
                AbilityFeedback.controlsHint(4, "Dash", "Wirbel", "Schild"));
    }

    @Test
    void controlsHintAdvertisesTheMirrorKeyForTheHeldSlot() {
        // Pressing the digit of the slot already held sends nothing — same rule as EliteMobs.
        assertEquals("§7[7/2×Ducken] §fDash  §7[2/Links] §fWirbel  §7[3/Rechts] §fSchild",
                AbilityFeedback.controlsHint(0, "Dash", "Wirbel", "Schild"));
        assertEquals("§7[1/2×Ducken] §fDash  §7[8/Links] §fWirbel  §7[3/Rechts] §fSchild",
                AbilityFeedback.controlsHint(1, "Dash", "Wirbel", "Schild"));
        assertEquals("§7[1/2×Ducken] §fDash  §7[2/Links] §fWirbel  §7[9/Rechts] §fSchild",
                AbilityFeedback.controlsHint(2, "Dash", "Wirbel", "Schild"));
    }

    @Test
    void controlsHintFallsBackToSlotNames() {
        assertEquals("§7[1/2×Ducken] §fMobility  §7[2/Links] §fSignature  §7[3/Rechts] §fUtility",
                AbilityFeedback.controlsHint(4, null, " ", null));
    }
}
