package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Phase 7.4 — sneak-based replacement for EliteMobs' F-chord, which Bedrock clients cannot
 * produce (Bedrock has no offhand-swap control).
 *
 * <p>Deliberate deviation from EliteMobs: EM's {@code CHORD_WINDOW_TICKS = 12} is tuned for a
 * key <i>press</i>. Sneak is a <i>state</i>, and 0.6s is tight on a controller, so the chord
 * stays open while the player sneaks, capped at {@code maxOpenTicks}.
 *
 * <p>Sneak <i>end</i> is intentionally not an input: closing on it would make
 * "sneak, sneak" impossible, since the second start requires releasing first.
 *
 * <p>No Bukkit types here — the tick is passed in, so this is unit-testable without a server.
 */
public final class BedrockAbilityGesture {

    public enum Outcome { NONE, MOBILITY, SIGNATURE, UTILITY }

    private final long maxOpenTicks;

    private boolean open;
    private long openedAtTick;

    public BedrockAbilityGesture(long maxOpenTicks) {
        this.maxOpenTicks = maxOpenTicks;
    }

    /** Sneak start: opens the chord, or fires MOBILITY when one is already open. */
    public Outcome sneakStart(long tick) {
        if (isOpen(tick)) {
            close();
            return Outcome.MOBILITY;
        }
        open = true;
        openedAtTick = tick;
        return Outcome.NONE;
    }

    public Outcome attack(long tick) {
        return fire(tick, Outcome.SIGNATURE);
    }

    public Outcome use(long tick) {
        return fire(tick, Outcome.UTILITY);
    }

    private Outcome fire(long tick, Outcome outcome) {
        if (!isOpen(tick)) return Outcome.NONE;
        close();
        return outcome;
    }

    public boolean isOpen(long tick) {
        return open && tick - openedAtTick <= maxOpenTicks;
    }

    /** Drops an open chord — used on death, world change and quit. */
    public void close() {
        open = false;
    }
}
