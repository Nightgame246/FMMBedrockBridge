package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule;
import com.magmaguy.elitemobs.advancedcombat.abilities.AbilityResult;
import com.magmaguy.elitemobs.advancedcombat.classes.AbilitySlot;
import com.magmaguy.elitemobs.combatsystem.combattag.DungeonCombatRuntime;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.entity.Player;

/**
 * Phase 7.4 — the only class allowed to touch EliteMobs' internal {@code advancedcombat}
 * package. Instantiate it ONLY after {@link AdvancedCombatSupport#isPresent()} returned true.
 *
 * <p>Every call into EliteMobs is wrapped: the package is Alpha and carries no API guarantee,
 * and a LinkageError must degrade this one feature rather than the whole bridge.
 */
public final class AdvancedCombatHook {

    /**
     * Outcome of one {@link #fire} attempt: whether EliteMobs actually took the input, plus the
     * optional action-bar text. The caller may only consume (cancel) the triggering event when
     * {@code handled} is true — otherwise the player loses a swing or an interaction for nothing.
     */
    public record FireResult(boolean handled, String message) {
    }

    private static final FireResult NOT_HANDLED = new FireResult(false, null);

    /**
     * Whether EliteMobs would act on an ability input right now. Deliberately mirrors EM's own
     * preconditions instead of being broader than them:
     *
     * <ul>
     *   <li>{@code AdvancedCombatModule.isInitialized()} — EM only builds the module when
     *       {@code AdvancedCombatSystemConfig.isEnabled()}, but starts {@code DungeonCombatRuntime}
     *       unconditionally. The combat tag alone therefore proves nothing about the module.
     *       {@code AdvancedCombatModule.get()} throws IllegalStateException when uninitialised,
     *       so this check has to come first.</li>
     *   <li>{@code mechanicsActive(player)} — the very first thing EM's own {@code useAbility}
     *       asks. It requires an active class AND enabled control mode; outside dungeons and
     *       matches the latter means the player joined {@code ClassControlMode.outsideEnabled},
     *       which is an F-double-tap opt-in Bedrock cannot perform. Without this check we would
     *       arm in ordinary open-world elite combat and then call into a no-op.</li>
     *   <li>Dungeon/match eligibility or an active combat tag — our own scope limit, so ordinary
     *       sneaking while building never fires anything.</li>
     * </ul>
     */
    public boolean canUseAbilities(Player player) {
        try {
            if (!AdvancedCombatModule.isInitialized()) return false;
            if (!AdvancedCombatModule.get().mechanicsActive(player)) return false;
            return DungeonCombatRuntime.isEligiblePlayer(player)
                    || DungeonCombatRuntime.getInstance().isInCombat(player.getUniqueId());
        } catch (Throwable t) {
            FMMBedrockBridge.debugLog("[PHASE74] combat probe failed: " + t);
            return false;
        }
    }

    /**
     * Fires the ability bound to the outcome.
     *
     * @return a {@link FireResult} whose {@code handled} flag says whether EliteMobs took the
     *         input, and whose {@code message} is the action-bar text or {@code null}
     */
    public FireResult fire(Player player, BedrockAbilityGesture.Outcome outcome) {
        try {
            // Inside the try on purpose: toSlot reads AbilitySlot constants, and a constant
            // renamed upstream throws NoSuchFieldError — that must not escape into Bukkit.
            AbilitySlot slot = toSlot(outcome);
            if (slot == null) return NOT_HANDLED;

            AdvancedCombatModule module = AdvancedCombatModule.get();
            AbilityResult result = module.useAbility(player, slot);
            if (result == null) return NOT_HANDLED;

            if (result.successful()) {
                return new FireResult(true, AbilityFeedback.forSuccess(module.abilityName(player, slot)));
            }
            String reason = result.failureReason() == null ? null : result.failureReason().name();
            FMMBedrockBridge.debugLog("[PHASE74] " + player.getName() + " " + slot + " failed: " + reason);
            // EliteMobs saw the input and rejected it — that still counts as handled.
            return new FireResult(true, AbilityFeedback.forFailure(reason));
        } catch (Throwable t) {
            // Alpha package: degrade this feature, never the plugin. Debug-only, because a
            // broken Alpha API would otherwise spam the console once per input.
            FMMBedrockBridge.debugLog("[PHASE74] useAbility failed: " + t);
            return NOT_HANDLED;
        }
    }

    private static AbilitySlot toSlot(BedrockAbilityGesture.Outcome outcome) {
        return switch (outcome) {
            case MOBILITY -> AbilitySlot.MOBILITY;
            case SIGNATURE -> AbilitySlot.SIGNATURE;
            case UTILITY -> AbilitySlot.UTILITY;
            case NONE -> null;
        };
    }
}
