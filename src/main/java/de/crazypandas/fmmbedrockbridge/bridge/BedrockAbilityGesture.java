package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Phase 7.4 — sneak-based replacement for EliteMobs' F-chord, which Bedrock clients cannot
 * produce (Bedrock has no offhand-swap control).
 *
 * <p><b>The sneak STATE decides, never the sneak events.</b> Measured in-game on 11.09.2026:
 * Geyser delivers Bedrock's crouch as a rapid flutter of toggles — five "armed" against twenty
 * "disarmed" inside a few seconds, on/off/on/off within the same second. Anything built on that
 * event stream is unusable: the controls were disarmed almost whenever a click arrived, and every
 * "on" following an "off" looked like a double tap and fired MOBILITY.
 *
 * <p>So SIGNATURE and UTILITY ask {@code player.isSneaking()} at the moment of the click and
 * nothing else. There is no arming to lose.
 *
 * <p>MOBILITY still needs a deliberate double tap, and that is the one place where the flutter
 * has to be filtered out: a release only counts once it has lasted at least
 * {@link #MIN_RELEASE_TICKS}, and the second crouch has to follow within
 * {@link #MAX_DOUBLE_TAP_TICKS}. Flutter is far quicker than a human finger, so the floor
 * separates the two reliably.
 */
public final class BedrockAbilityGesture {

    public enum Outcome { NONE, MOBILITY, SIGNATURE, UTILITY }

    /**
     * A release shorter than this is Geyser's flutter, not a human letting go. Four ticks is
     * 0.2s — no player releases and re-crouches faster than that.
     */
    public static final long MIN_RELEASE_TICKS = 4L;

    /** And a deliberate double tap is not slower than a second. */
    public static final long MAX_DOUBLE_TAP_TICKS = 20L;

    private long releasedAtTick = Long.MIN_VALUE;

    /**
     * Sneak start: fires MOBILITY when it completes a deliberate double tap.
     *
     * <p>There is nothing to arm — see the class docs.
     */
    public Outcome sneakStart(long tick) {
        if (releasedAtTick == Long.MIN_VALUE) return Outcome.NONE;

        long releaseLength = tick - releasedAtTick;
        if (releaseLength < MIN_RELEASE_TICKS || releaseLength > MAX_DOUBLE_TAP_TICKS) {
            return Outcome.NONE;
        }
        // Spend the release so one letting-go cannot feed a second MOBILITY.
        releasedAtTick = Long.MIN_VALUE;
        return Outcome.MOBILITY;
    }

    /** Sneak end: only remembers when, so the next crouch can be judged. */
    public void sneakEnd(long tick) {
        releasedAtTick = tick;
    }

    /** @param sneaking {@code player.isSneaking()} at the moment of the click */
    public Outcome attack(boolean sneaking) {
        return sneaking ? Outcome.SIGNATURE : Outcome.NONE;
    }

    /** @param sneaking {@code player.isSneaking()} at the moment of the click */
    public Outcome use(boolean sneaking) {
        return sneaking ? Outcome.UTILITY : Outcome.NONE;
    }

    /** Forgets a pending release — used on death, world change and quit. */
    public void close() {
        releasedAtTick = Long.MIN_VALUE;
    }
}
