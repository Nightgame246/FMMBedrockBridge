package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static de.crazypandas.fmmbedrockbridge.bridge.BedrockAbilityGesture.Outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EliteMobs binds its three ability slots to an F-chord that Bedrock clients cannot produce.
 * This mirrors it onto sneak — but as a held modifier, not as a chord that gets spent.
 *
 * <p>All three in-game failures from 11.09.2026 are pinned down here, so they cannot come back:
 * aiming that takes longer than a fixed window, a second ability after a first one, and crouches
 * that armed across each other and fired MOBILITY ten times in six seconds.
 */
class BedrockAbilityGestureTest {

    private static final boolean SNEAKING = true;
    private static final boolean RELEASED = false;

    @Test
    void firstSneakArmsTheControlsWithoutFiring() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertEquals(Outcome.NONE, gesture.sneakStart(100L), "arming must not fire an ability");
        assertTrue(gesture.isArmed(SNEAKING));
    }

    @Test
    void crouchingAgainQuicklyFiresMobility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);
        gesture.sneakEnd(105L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(110L));
    }

    @Test
    void leftClickWhileSneakingFiresSignature() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING));
    }

    @Test
    void rightClickWhileSneakingFiresUtility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);

        assertEquals(Outcome.UTILITY, gesture.use(SNEAKING));
    }

    @Test
    void severalAbilitiesInOneCrouchAllFire() {
        // The second in-game failure: after UTILITY fired, six left clicks in a row were
        // rejected with "no open chord (sneaking=true)". Holding sneak must stay armed.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);

        assertEquals(Outcome.UTILITY, gesture.use(SNEAKING));
        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING), "still crouched, still armed");
        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING));
        assertEquals(Outcome.UTILITY, gesture.use(SNEAKING));
    }

    @Test
    void armingSurvivesAnyAmountOfAiming() {
        // The first in-game failure: a fixed two-second window expired while the player aimed.
        // There is no timer any more, so this holds no matter how long it takes.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);

        assertTrue(gesture.isArmed(SNEAKING));
        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING));
    }

    @Test
    void releasingSneakDisarms() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);

        assertFalse(gesture.isArmed(RELEASED));
        assertEquals(Outcome.NONE, gesture.attack(RELEASED));
    }

    @Test
    void aReleaseIsRememberedEvenWithoutItsOwnEvent() {
        // The listener does not get a "sneak end" of its own, so seeing sneaking == false once
        // has to disarm for good — otherwise a later click would still fire.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);
        gesture.sneakEnd(150L);

        assertEquals(Outcome.NONE, gesture.attack(SNEAKING),
                "crouching again must go through sneakStart, not sneak back in through a click");
    }

    @Test
    void clicksBeforeAnySneakDoNothing() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertEquals(Outcome.NONE, gesture.attack(SNEAKING));
        assertEquals(Outcome.NONE, gesture.use(SNEAKING));
    }

    @Test
    void closeDisarmsExplicitly() {
        // Used on death, world change and quit.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);

        gesture.close();

        assertFalse(gesture.isArmed(SNEAKING));
        assertEquals(Outcome.NONE, gesture.attack(SNEAKING));
    }

    @Test
    void reArmingAfterAReleaseWorks() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);
        gesture.sneakEnd(150L);

        assertEquals(Outcome.NONE, gesture.sneakStart(400L), "a late crouch arms, it does not fire");
        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING));
    }

    @Test
    void eachCrouchOnItsOwnOnlyArms() {
        // The third in-game failure: ignoring the sneak end left the controls armed across
        // crouches, so every new crouch read as a second tap. MOBILITY fired ten times in six
        // seconds. Crouches far apart must only ever arm.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertEquals(Outcome.NONE, gesture.sneakStart(100L));
        gesture.sneakEnd(140L);
        assertEquals(Outcome.NONE, gesture.sneakStart(200L), "60 ticks later is not a double tap");
        gesture.sneakEnd(240L);
        assertEquals(Outcome.NONE, gesture.sneakStart(400L));
    }

    @Test
    void oneReleaseCannotFeedTwoMobilities() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);
        gesture.sneakEnd(105L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(110L));
        assertEquals(Outcome.NONE, gesture.sneakStart(112L), "the release was already spent");
    }

    @Test
    void aSlowSecondCrouchIsNotADoubleTap() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);
        gesture.sneakEnd(105L);

        assertEquals(Outcome.NONE, gesture.sneakStart(126L), "21 ticks is past the 20-tick window");
    }

    @Test
    void theDoubleTapBoundaryItselfCounts() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);
        gesture.sneakEnd(105L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(125L), "exactly 20 ticks is inside");
    }

    @Test
    void abilitiesStillWorkAfterAMobility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart(100L);
        gesture.sneakEnd(105L);
        gesture.sneakStart(110L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING), "a double tap also arms");
        assertEquals(Outcome.UTILITY, gesture.use(SNEAKING));
    }
}
