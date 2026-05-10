package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.elitemobs.api.EliteMobEnterCombatEvent;
import com.magmaguy.elitemobs.api.EliteMobExitCombatEvent;
import com.magmaguy.elitemobs.mobconstructor.EliteEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * Phase 7.1c — Bukkit listener that toggles combat-state on existing Phase 7.1a/b
 * controllers in response to EliteMobs combat events.
 *
 * <p>Priority HIGHEST on {@link EliteMobEnterCombatEvent} ensures our handler runs
 * BEFORE EM's own {@code BossHealthDisplay} (MONITOR). This guarantees our
 * {@code bossBar.addPlayer} ADD-packet leaves the server first; the
 * PacketInterceptor's first-match heuristic claims that UUID as ours and suppresses
 * EM's subsequent ADD-packet for Bedrock players.
 *
 * <p>Only registered when EliteMobs is loaded AND
 * {@code phase71c.combat-enabled = true}. When the toggle is off, this class is
 * never instantiated and Phase 7.1a (always-visible BossBar) + Phase 7.1b (1-line
 * nametag) behavior is preserved.
 */
public final class BedrockCombatTrigger implements Listener {

    private final BedrockEntityBridge bridge;

    public BedrockCombatTrigger(BedrockEntityBridge bridge) {
        this.bridge = bridge;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnterCombat(EliteMobEnterCombatEvent event) {
        UUID uuid = extractUuid(event.getEliteMobEntity());
        if (uuid == null) return;

        BedrockBossBarController bossBar = bridge.getActiveControllers().get(uuid);
        if (bossBar != null) {
            bossBar.enterCombat();
            FMMBedrockBridge.debugLog("[BRIDGE] BossBar enterCombat for " + uuid);
        }

        BedrockNametagController nametag = bridge.getActiveNametags().get(uuid);
        if (nametag != null) {
            nametag.setCombatState(true);
            FMMBedrockBridge.debugLog("[BRIDGE] Nametag setCombatState(true) for " + uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExitCombat(EliteMobExitCombatEvent event) {
        UUID uuid = extractUuid(event.getEliteMobEntity());
        if (uuid == null) return;

        BedrockBossBarController bossBar = bridge.getActiveControllers().get(uuid);
        if (bossBar != null) {
            bossBar.exitCombat();
            FMMBedrockBridge.debugLog("[BRIDGE] BossBar exitCombat for " + uuid);
        }

        BedrockNametagController nametag = bridge.getActiveNametags().get(uuid);
        if (nametag != null) {
            nametag.setCombatState(false);
            FMMBedrockBridge.debugLog("[BRIDGE] Nametag setCombatState(false) for " + uuid);
        }
    }

    private static UUID extractUuid(EliteEntity eliteEntity) {
        if (eliteEntity == null) return null;
        try {
            LivingEntity living = eliteEntity.getLivingEntity();
            return living != null ? living.getUniqueId() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
