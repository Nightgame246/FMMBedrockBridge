package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule;
import com.magmaguy.elitemobs.advancedcombat.abilities.AbilityResult;
import com.magmaguy.elitemobs.advancedcombat.classes.AbilitySlot;
import com.magmaguy.elitemobs.combatsystem.combattag.DungeonCombatRuntime;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Phase 7.4 — the only class allowed to touch EliteMobs' internal {@code advancedcombat}
 * package. Instantiate it ONLY after {@link AdvancedCombatSupport#isPresent()} returned true.
 *
 * <p>Every call into EliteMobs is wrapped: the package is Alpha and carries no API guarantee,
 * and a LinkageError must degrade this one feature rather than the whole bridge.
 */
public final class AdvancedCombatHook {

    private final Logger log;

    public AdvancedCombatHook(Logger log) {
        this.log = log;
    }

    /**
     * Whether the player is in combat or inside a dungeon/match — the bridge only arms the
     * sneak controls then, so ordinary sneaking never fires an ability.
     */
    public boolean isInCombat(Player player) {
        try {
            if (DungeonCombatRuntime.isEligiblePlayer(player)) return true;
            DungeonCombatRuntime runtime = DungeonCombatRuntime.getInstance();
            return runtime != null && runtime.isInCombat(player.getUniqueId());
        } catch (Throwable t) {
            FMMBedrockBridge.debugLog("[PHASE74] combat probe failed: " + t);
            return false;
        }
    }

    /**
     * Fires the ability bound to the outcome.
     *
     * @return action-bar text to show, or {@code null} for "say nothing"
     */
    public String fire(Player player, BedrockAbilityGesture.Outcome outcome) {
        AbilitySlot slot = toSlot(outcome);
        if (slot == null) return null;

        try {
            AdvancedCombatModule module = AdvancedCombatModule.get();
            if (module == null) return null;

            AbilityResult result = module.useAbility(player, slot);
            if (result == null) return null;

            if (result.successful()) {
                return AbilityFeedback.forSuccess(module.abilityName(player, slot));
            }
            String reason = result.failureReason() == null ? null : result.failureReason().name();
            FMMBedrockBridge.debugLog("[PHASE74] " + player.getName() + " " + slot + " failed: " + reason);
            return AbilityFeedback.forFailure(reason);
        } catch (Throwable t) {
            // Alpha package: degrade this feature, never the plugin.
            log.warning("[PHASE74] useAbility failed, disabling feedback for this attempt: " + t);
            return null;
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
