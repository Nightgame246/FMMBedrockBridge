package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static de.crazypandas.fmmbedrockbridge.bridge.BedrockAbilityGesture.Outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EliteMobs binds its three ability slots to an F-chord that Bedrock clients cannot produce.
 * This mirrors it onto sneak.
 *
 * <p>Every case here comes from a real in-game failure on 11.09.2026. The decisive measurement
 * was the last one: Geyser delivers Bedrock's crouch as a rapid flutter of toggle events — five
 * "armed" against twenty "disarmed" within seconds. Everything built on that event stream broke,
 * so the clicks now read the sneak STATE and only MOBILITY still looks at the events, with a
 * floor that filters the flutter out.
 */
class BedrockAbilityGestureTest {

    private static final boolean SNEAKING = true;
    private static final boolean UPRIGHT = false;

    @Test
    void clickingWhileCrouchedFires() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING));
        assertEquals(Outcome.UTILITY, gesture.use(SNEAKING));
    }

    @Test
    void clickingUprightDoesNothing() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertEquals(Outcome.NONE, gesture.attack(UPRIGHT));
        assertEquals(Outcome.NONE, gesture.use(UPRIGHT));
    }

    @Test
    void clicksNeverDependOnTheEventStream() {
        // The flutter used to disarm the controls between crouch and click. The state is asked
        // at the moment of the click, so any amount of toggling in between is irrelevant.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakEnd(100L);
        gesture.sneakStart(101L);
        gesture.sneakEnd(102L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING), "still crouched, still fires");
    }

    @Test
    void severalAbilitiesInOneCrouchAllFire() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertEquals(Outcome.UTILITY, gesture.use(SNEAKING));
        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING));
        assertEquals(Outcome.SIGNATURE, gesture.attack(SNEAKING));
        assertEquals(Outcome.UTILITY, gesture.use(SNEAKING));
    }

    @Test
    void aDeliberateDoubleTapFiresMobility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakEnd(100L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(110L), "10 ticks is a human double tap");
    }

    @Test
    void geyserFlutterDoesNotFireMobility() {
        // THE failure that shaped this class: on/off/on within the same second fired MOBILITY
        // over and over. A release shorter than MIN_RELEASE_TICKS is not a human letting go.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakEnd(100L);

        assertEquals(Outcome.NONE, gesture.sneakStart(101L), "1 tick apart is flutter");
        gesture.sneakEnd(102L);
        assertEquals(Outcome.NONE, gesture.sneakStart(105L), "3 ticks is still flutter");
    }

    @Test
    void theFlutterFloorItselfCounts() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakEnd(100L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(104L), "exactly 4 ticks is deliberate");
    }

    @Test
    void aSlowSecondCrouchIsNotADoubleTap() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakEnd(100L);

        assertEquals(Outcome.NONE, gesture.sneakStart(121L), "21 ticks is past the window");
    }

    @Test
    void theDoubleTapCeilingItselfCounts() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakEnd(100L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(120L), "exactly 20 ticks is inside");
    }

    @Test
    void oneReleaseCannotFeedTwoMobilities() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakEnd(100L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(110L));
        assertEquals(Outcome.NONE, gesture.sneakStart(112L), "the release was already spent");
    }

    @Test
    void crouchingWithoutAnyPriorReleaseOnlyStartsThings() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertEquals(Outcome.NONE, gesture.sneakStart(100L));
    }

    @Test
    void closeForgetsAPendingRelease() {
        // Used on death, world change and quit.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakEnd(100L);

        gesture.close();

        assertEquals(Outcome.NONE, gesture.sneakStart(110L));
    }

    // --- Hotbar keys (27.09.2026) -------------------------------------------------------------
    // Right click into the air sends no packet at all with an empty hand — measured in-game: only
    // RIGHT_CLICK_BLOCK ever arrived, so UTILITY needed a block in the crosshair. EliteMobs itself
    // has a second binding for the chord window, and we copy its numbers exactly:
    // 1/7 mobility, 2/8 signature, 3/9 utility (ClassAbilityGestureState.selectHotbar, 10.9.x).

    @Test
    void hotbarKeysWhileCrouchedFollowEliteMobs() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertEquals(Outcome.MOBILITY, gesture.hotbar(SNEAKING, 0));
        assertEquals(Outcome.SIGNATURE, gesture.hotbar(SNEAKING, 1));
        assertEquals(Outcome.UTILITY, gesture.hotbar(SNEAKING, 2));
        // 7/8/9 mirror 1/2/3: a client reports no slot change for the slot already held.
        assertEquals(Outcome.MOBILITY, gesture.hotbar(SNEAKING, 6));
        assertEquals(Outcome.SIGNATURE, gesture.hotbar(SNEAKING, 7));
        assertEquals(Outcome.UTILITY, gesture.hotbar(SNEAKING, 8));
    }

    @Test
    void middleSlotsStayOrdinaryItemSwitches() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertEquals(Outcome.NONE, gesture.hotbar(SNEAKING, 3));
        assertEquals(Outcome.NONE, gesture.hotbar(SNEAKING, 4));
        assertEquals(Outcome.NONE, gesture.hotbar(SNEAKING, 5));
    }

    @Test
    void hotbarKeysUprightDoNothing() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        for (int slot = 0; slot < 9; slot++) {
            assertEquals(Outcome.NONE, gesture.hotbar(UPRIGHT, slot), "slot " + slot);
        }
    }

    // --- Controls hint ------------------------------------------------------------------------
    // Shown once per deliberate crouch. Geyser's flutter must not re-show it, or it would paint
    // over the "▶ ability" confirmation right after a click.

    @Test
    void firstCrouchShowsTheHint() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();

        assertTrue(gesture.isDeliberateCrouch(100L));
    }

    @Test
    void crouchAfterARealReleaseShowsTheHint() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakEnd(100L);

        assertTrue(gesture.isDeliberateCrouch(100L + BedrockAbilityGesture.MIN_RELEASE_TICKS));
    }

    @Test
    void flutterDoesNotShowTheHintAgain() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture();
        gesture.sneakEnd(100L);

        assertFalse(gesture.isDeliberateCrouch(101L));
    }
}
