package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Phase 7.4 — sneak-based replacement for EliteMobs' F-chord, which Bedrock clients cannot
 * produce (Bedrock has no offhand-swap control).
 *
 * <p><b>Sneak is held, not tapped.</b> EliteMobs' {@code CHORD_WINDOW_TICKS = 12} is tuned for a
 * key <i>press</i>; sneak is a <i>state</i>. The chord therefore stays open for as long as the
 * player keeps sneaking, and {@code maxOpenTicks} is only a safety net against someone walking
 * around crouched with the controls permanently armed.
 *
 * <p>The first in-game test (11.09.2026) showed why this matters: with a fixed 2s window and no
 * sneak check, only "sneak, sneak" was reachable — sneaking, aiming and then striking regularly
 * takes longer than two seconds on a controller, so SIGNATURE and UTILITY never fired.
 *
 * <p>Sneak <i>end</i> is deliberately not an input of its own: closing on it would make
 * "sneak, sneak" impossible, since the second start requires releasing first. The chord simply
 * stops being open once {@code sneaking} is false.
 *
 * <p>No Bukkit types here — tick and sneak state are passed in, so this is unit-testable
 * without a server.
 */
public final class BedrockAbilityGesture {

    public enum Outcome { NONE, MOBILITY, SIGNATURE, UTILITY }

    private final long maxOpenTicks;

    private boolean open;
    private long openedAtTick;

    public BedrockAbilityGesture(long maxOpenTicks) {
        this.maxOpenTicks = maxOpenTicks;
    }

    /**
     * Sneak start: opens the chord, or fires MOBILITY when one is already open.
     *
     * <p>No sneak flag needed — a sneak start <i>is</i> the proof that the player is sneaking.
     */
    public Outcome sneakStart(long tick) {
        if (isOpen(tick, true)) {
            close();
            return Outcome.MOBILITY;
        }
        open = true;
        openedAtTick = tick;
        return Outcome.NONE;
    }

    /** @param sneaking whether the player is still crouched at this moment */
    public Outcome attack(long tick, boolean sneaking) {
        return fire(tick, sneaking, Outcome.SIGNATURE);
    }

    /** @param sneaking whether the player is still crouched at this moment */
    public Outcome use(long tick, boolean sneaking) {
        return fire(tick, sneaking, Outcome.UTILITY);
    }

    private Outcome fire(long tick, boolean sneaking, Outcome outcome) {
        if (!isOpen(tick, sneaking)) return Outcome.NONE;
        close();
        return outcome;
    }

    /** The window this gesture was built with — lets the listener spot a config change. */
    public long maxOpenTicks() {
        return maxOpenTicks;
    }

    /**
     * A chord counts as open while the player still sneaks and the safety net has not run out.
     *
     * @param sneaking whether the player is crouched right now
     */
    public boolean isOpen(long tick, boolean sneaking) {
        return open && sneaking && tick - openedAtTick <= maxOpenTicks;
    }

    /** Drops an open chord — used on death, world change and quit. */
    public void close() {
        open = false;
    }
}
