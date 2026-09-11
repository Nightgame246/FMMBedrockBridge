package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static de.crazypandas.fmmbedrockbridge.bridge.BedrockAbilityGesture.Outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EliteMobs binds its three ability slots to an F-chord that Bedrock clients cannot produce.
 * This mirrors that chord onto sneak. The state machine is the only part with real logic, so
 * it is kept free of Bukkit types and covered here rather than in-game.
 */
class BedrockAbilityGestureTest {

    private static final long MAX = 40L;

    @Test
    void firstSneakOpensTheChordWithoutFiring() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);

        assertEquals(Outcome.NONE, gesture.sneakStart(100L), "opening must not fire an ability");
        assertTrue(gesture.isOpen(100L));
    }

    @Test
    void secondSneakInsideTheWindowFiresMobility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(110L));
    }

    @Test
    void attackInsideTheWindowFiresSignature() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(105L));
    }

    @Test
    void useInsideTheWindowFiresUtility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.UTILITY, gesture.use(105L));
    }

    @Test
    void firingClosesTheChordSoTheNextInputDoesNotFireAgain() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(105L));
        assertEquals(Outcome.NONE, gesture.attack(106L), "a closed chord must stay quiet");
        assertFalse(gesture.isOpen(106L));
    }

    @Test
    void inputAfterTheWindowExpiredDoesNotFire() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        // 41 ticks later: the cap (40) has passed.
        assertEquals(Outcome.NONE, gesture.attack(141L));
        assertFalse(gesture.isOpen(141L));
    }

    @Test
    void theWindowBoundaryItselfStillCounts() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(140L), "tick 100+40 is still inside");
    }

    @Test
    void sneakingAgainAfterExpiryReopensRatherThanFiring() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.NONE, gesture.sneakStart(200L), "expired chord reopens, never fires");
        assertTrue(gesture.isOpen(200L));
    }

    @Test
    void closeSilencesAnOpenChord() {
        // Used on death, world change and quit.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        gesture.close();

        assertFalse(gesture.isOpen(101L));
        assertEquals(Outcome.NONE, gesture.attack(101L));
    }
}
