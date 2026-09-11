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
 * <p>Both of the in-game failures from 11.09.2026 are pinned down here, so they cannot come
 * back: aiming that takes longer than a fixed window, and a second ability after a first one.
 */
class BedrockAbilityGestureTest {

    private static final boolean SNEAKING = true;
    private static final boolean RELEASED = false;

    @Test
    void firstSneakArmsTheControlsWithoutFiring() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertEquals(Outcome.NONE, gesture.sneakStart(), "arming must not fire an ability");
        assertTrue(gesture.isArmed(SNEAKING));
    }

    @Test
    void sneakingAgainWhileArmedFiresMobility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart();

        assertEquals(Outcome.MOBILITY, gesture.sneakStart());
    }

    @Test
    void leftClickWhileSneakingFiresSignature() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart();

        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING));
    }

    @Test
    void rightClickWhileSneakingFiresUtility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart();

        assertEquals(Outcome.UTILITY, gesture.use(SNEAKING));
    }

    @Test
    void severalAbilitiesInOneCrouchAllFire() {
        // The second in-game failure: after UTILITY fired, six left clicks in a row were
        // rejected with "no open chord (sneaking=true)". Holding sneak must stay armed.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart();

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
        gesture.sneakStart();

        assertTrue(gesture.isArmed(SNEAKING));
        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING));
    }

    @Test
    void releasingSneakDisarms() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart();

        assertFalse(gesture.isArmed(RELEASED));
        assertEquals(Outcome.NONE, gesture.attack(RELEASED));
    }

    @Test
    void aReleaseIsRememberedEvenWithoutItsOwnEvent() {
        // The listener does not get a "sneak end" of its own, so seeing sneaking == false once
        // has to disarm for good — otherwise a later click would still fire.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart();
        gesture.isArmed(RELEASED);

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
        gesture.sneakStart();

        gesture.close();

        assertFalse(gesture.isArmed(SNEAKING));
        assertEquals(Outcome.NONE, gesture.attack(SNEAKING));
    }

    @Test
    void reArmingAfterAReleaseWorks() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakStart();
        gesture.isArmed(RELEASED);

        assertEquals(Outcome.NONE, gesture.sneakStart(), "a fresh crouch arms, it does not fire");
        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING));
    }
}
