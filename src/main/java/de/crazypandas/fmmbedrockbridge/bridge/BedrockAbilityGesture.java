package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Phase 7.4 — sneak-based replacement for EliteMobs' F-chord, which Bedrock clients cannot
 * produce (Bedrock has no offhand-swap control).
 *
 * <p><b>Sneak is a modifier you hold, not a chord you spend.</b> EliteMobs opens a short window
 * with a key <i>press</i> and consumes it with the next input — sensible for a keypress. Sneak is
 * a <i>state</i>, and a state cannot be spent: a player who is still crouched still means it.
 *
 * <p>Two in-game runs on 11.09.2026 made that concrete. First a fixed two-second stopwatch let
 * only "sneak, sneak" through, because aiming takes longer than that on a controller. Then, with
 * the timer relaxed, the log showed six left clicks in a row rejected with
 * {@code no open chord (sneaking=true)} — the player was still crouched, but an earlier UTILITY
 * had already consumed the chord. Both times the cause was the same borrowed metaphor.
 *
 * <p>So the rule is simply: while the player sneaks, the controls are armed.
 *
 * <table>
 *   <tr><td>left click</td><td>SIGNATURE, as often as the player likes</td></tr>
 *   <tr><td>right click</td><td>UTILITY, as often as the player likes</td></tr>
 *   <tr><td>release and sneak again</td><td>MOBILITY</td></tr>
 *   <tr><td>release</td><td>controls disarmed</td></tr>
 * </table>
 *
 * <p>Firing no longer closes anything; EliteMobs enforces its own cooldowns and resource costs,
 * so repeat presses cost nothing we would have to police here.
 *
 * <p>No Bukkit types here — tick and sneak state are passed in, so this is unit-testable
 * without a server.
 */
public final class BedrockAbilityGesture {

    public enum Outcome { NONE, MOBILITY, SIGNATURE, UTILITY }

    /** True between a sneak start and the moment the player is seen no longer sneaking. */
    private boolean armed;

    /**
     * Sneak start: arms the controls, or fires MOBILITY when they are armed already.
     *
     * <p>Staying armed afterwards is deliberate — releasing and crouching again is simply
     * MOBILITY once more.
     */
    public Outcome sneakStart() {
        if (armed) return Outcome.MOBILITY;
        armed = true;
        return Outcome.NONE;
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
     * disarms them, so a release is noticed even without a dedicated event.
     *
     * @param sneaking whether the player is crouched right now
     */
    public boolean isArmed(boolean sneaking) {
        if (!sneaking) armed = false;
        return armed;
    }

    /** Disarms explicitly — used on death, world change and quit. */
    public void close() {
        armed = false;
    }
}
