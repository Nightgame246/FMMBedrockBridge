package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Phase 7.4 — sneak-based replacement for EliteMobs' F-chord, which Bedrock clients cannot
 * produce (Bedrock has no offhand-swap control).
 *
 * <p><b>Sneak is a modifier you hold.</b> While the player crouches the controls are armed, and
 * left/right click fire SIGNATURE/UTILITY as often as the player likes — EliteMobs polices its
 * own cooldowns. Releasing disarms.
 *
 * <p><b>MOBILITY is a double tap</b>, because holding sneak cannot express it: crouch, release,
 * crouch again within {@link #DOUBLE_TAP_TICKS}. That mirrors EliteMobs' own F,F, and it needs
 * its own signal precisely because arming happens on every crouch.
 *
 * <p>Three in-game runs on 11.09.2026 shaped this. A fixed two-second window let only "sneak,
 * sneak" through, because aiming takes longer. Consuming the chord on the first ability blocked
 * every follow-up while the player was still crouched. And ignoring the sneak <i>end</i> left the
 * controls armed across crouches, so every new crouch read as the second tap and fired MOBILITY
 * ten times in six seconds.
 *
 * <p>No Bukkit types here — tick and sneak state are passed in, so this is unit-testable
 * without a server.
 */
public final class BedrockAbilityGesture {

    public enum Outcome { NONE, MOBILITY, SIGNATURE, UTILITY }

    /**
     * How quickly the player has to crouch again for it to count as a double tap. 20 ticks = 1s,
     * deliberately more generous than EliteMobs' 12 — a controller is slower than a keyboard.
     */
    public static final long DOUBLE_TAP_TICKS = 20L;

    private boolean armed;
    private long releasedAtTick = Long.MIN_VALUE;

    /**
     * Sneak start: arms the controls, and fires MOBILITY when it follows a release closely
     * enough to read as a double tap.
     */
    public Outcome sneakStart(long tick) {
        boolean doubleTap = releasedAtTick != Long.MIN_VALUE
                && tick - releasedAtTick <= DOUBLE_TAP_TICKS;
        armed = true;
        if (doubleTap) {
            // Spend the release so a third crouch does not fire again off the same one.
            releasedAtTick = Long.MIN_VALUE;
            return Outcome.MOBILITY;
        }
        return Outcome.NONE;
    }

    /** Sneak end: disarms and remembers when, so the next crouch can be judged. */
    public void sneakEnd(long tick) {
        armed = false;
        releasedAtTick = tick;
    }

    /** @param sneaking whether the player is still crouched at this moment */
    public Outcome attack(boolean sneaking) {
        return fire(sneaking, Outcome.SIGNATURE);
    }

    /** @param sneaking whether the player is still crouched at this moment */
    public Outcome use(boolean sneaking) {
        return fire(sneaking, Outcome.UTILITY);
    }

    private Outcome fire(boolean sneaking, Outcome outcome) {
        if (!isArmed(sneaking)) return Outcome.NONE;
        return outcome;
    }

    /**
     * The controls are armed while the player crouches. Seeing {@code sneaking == false} also
     * disarms, so a release is caught even if its event never reaches us.
     */
    public boolean isArmed(boolean sneaking) {
        if (!sneaking) armed = false;
        return armed;
    }

    /** Disarms and forgets the last release — used on death, world change and quit. */
    public void close() {
        armed = false;
        releasedAtTick = Long.MIN_VALUE;
    }
}
