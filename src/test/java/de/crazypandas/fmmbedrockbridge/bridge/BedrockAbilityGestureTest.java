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
 *
 * <p>The "still sneaking" tests exist because of a real failure: the first in-game run on
 * 11.09.2026 could only reach MOBILITY. The chord was a fixed 2s stopwatch that ignored whether
 * the player was still crouched, and sneaking-then-striking takes longer than that on a
 * controller. The chord must live as long as the sneak does.
 */
class BedrockAbilityGestureTest {

    private static final long MAX = 200L;
    private static final boolean SNEAKING = true;
    private static final boolean RELEASED = false;

    @Test
    void firstSneakOpensTheChordWithoutFiring() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);

        assertEquals(Outcome.NONE, gesture.sneakStart(100L), "opening must not fire an ability");
        assertTrue(gesture.isOpen(100L, SNEAKING));
    }

    @Test
    void secondSneakFiresMobility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(110L));
    }

    @Test
    void attackWhileStillSneakingFiresSignature() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(105L, SNEAKING));
    }

    @Test
    void useWhileStillSneakingFiresUtility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.UTILITY, gesture.use(105L, SNEAKING));
    }

    @Test
    void aimingTakesTimeAndTheChordMustSurviveIt() {
        // The regression that broke the first in-game test: sneak, aim, strike. Three seconds
        // pass, which the old fixed 2s window silently swallowed.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(160L, SNEAKING), "3s of aiming is normal");
    }

    @Test
    void releasingSneakClosesTheChord() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertFalse(gesture.isOpen(105L, RELEASED));
        assertEquals(Outcome.NONE, gesture.attack(105L, RELEASED),
                "a player who stopped crouching is not holding the modifier any more");
    }

    @Test
    void firingClosesTheChordSoTheNextInputDoesNotFireAgain() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(105L, SNEAKING));
        assertEquals(Outcome.NONE, gesture.attack(106L, SNEAKING), "a closed chord must stay quiet");
        assertFalse(gesture.isOpen(106L, SNEAKING));
    }

    @Test
    void mobilityAlsoClosesTheChord() {
        // Same invariant as above, for the path that fires through sneakStart.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(110L));
        assertFalse(gesture.isOpen(111L, SNEAKING));
        assertEquals(Outcome.NONE, gesture.attack(111L, SNEAKING));
    }

    @Test
    void theSafetyNetStillExpiresAnEndlessCrouch() {
        // Someone walking around crouched must not stay armed forever.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.NONE, gesture.attack(301L, SNEAKING), "200 ticks is the cap");
        assertFalse(gesture.isOpen(301L, SNEAKING));
    }

    @Test
    void theWindowBoundaryItselfStillCounts() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(300L, SNEAKING), "tick 100+200 is inside");
    }

    @Test
    void sneakingAgainAfterExpiryReopensRatherThanFiring() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.NONE, gesture.sneakStart(400L), "expired chord reopens, never fires");
        assertTrue(gesture.isOpen(400L, SNEAKING));
    }

    @Test
    void closeSilencesAnOpenChord() {
        // Used on death, world change and quit.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        gesture.close();

        assertFalse(gesture.isOpen(101L, SNEAKING));
        assertEquals(Outcome.NONE, gesture.attack(101L, SNEAKING));
    }
}
