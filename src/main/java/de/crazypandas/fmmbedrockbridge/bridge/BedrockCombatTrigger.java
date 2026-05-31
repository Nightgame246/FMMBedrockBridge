package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.elitemobs.api.EliteMobDamagedByPlayerEvent;
import com.magmaguy.elitemobs.api.EliteMobEnterCombatEvent;
import com.magmaguy.elitemobs.api.EliteMobExitCombatEvent;
import com.magmaguy.elitemobs.config.MobCombatSettingsConfig;
import com.magmaguy.elitemobs.mobconstructor.EliteEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<UUID, BukkitTask> damageTimeouts = new ConcurrentHashMap<>();

    public BedrockCombatTrigger(BedrockEntityBridge bridge) {
        this.bridge = bridge;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnterCombat(EliteMobEnterCombatEvent event) {
        UUID uuid = extractUuid(event.getEliteMobEntity());
        if (uuid == null) return;

        setCombatState(uuid, true, "EliteMobEnterCombatEvent");
        scheduleDisplayTimeout(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExitCombat(EliteMobExitCombatEvent event) {
        UUID uuid = extractUuid(event.getEliteMobEntity());
        if (uuid == null) return;

        if (FMMBedrockBridge.isPhase71cHideOnExitEnabled()) {
            cancelDisplayTimeout(uuid);
            setCombatState(uuid, false, "EliteMobExitCombatEvent");
        } else {
            scheduleDisplayTimeout(uuid);
        }
    }

    /**
     * Mirrors EliteMobs' own Java health display trigger. This is more reliable than
     * raw Bukkit damage because EM may cancel/replace vanilla damage while still firing
     * this event after its combat formula has run.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEliteMobDamaged(EliteMobDamagedByPlayerEvent event) {
        if (!FMMBedrockBridge.isPhase71cDamageRefreshEnabled()) return;

        UUID uuid = extractUuid(event.getEliteMobEntity());
        if (uuid == null) return;

        setCombatState(uuid, true, "EliteMobDamagedByPlayerEvent");
        scheduleDisplayTimeout(uuid);
    }

    private void setCombatState(UUID uuid, boolean inCombat, String reason) {
        BedrockBossBarController bossBar = bridge.getActiveControllers().get(uuid);
        if (bossBar != null) {
            if (inCombat) {
                bossBar.enterCombat();
            } else {
                bossBar.exitCombat();
            }
            FMMBedrockBridge.debugLog("[BRIDGE] BossBar setCombatState(" + inCombat
                    + ") for " + uuid + " via " + reason);
        }

        BedrockNametagController nametag = bridge.getActiveNametags().get(uuid);
        if (nametag != null) {
            nametag.setCombatState(inCombat);
            FMMBedrockBridge.debugLog("[BRIDGE] Nametag setCombatState(" + inCombat
                    + ") for " + uuid + " via " + reason);
        }
    }

    private void scheduleDisplayTimeout(UUID uuid) {
        cancelDisplayTimeout(uuid);

        long timeoutTicks = resolveDisplayTimeoutTicks();
        if (timeoutTicks <= 0) return;

        BukkitTask task = Bukkit.getScheduler().runTaskLater(FMMBedrockBridge.getInstance(), () -> {
            damageTimeouts.remove(uuid);
            setCombatState(uuid, false, "display-timeout");
        }, timeoutTicks);
        damageTimeouts.put(uuid, task);
    }

    private void cancelDisplayTimeout(UUID uuid) {
        BukkitTask oldTask = damageTimeouts.remove(uuid);
        if (oldTask != null) {
            oldTask.cancel();
        }
    }

    private long resolveDisplayTimeoutTicks() {
        long configured = FMMBedrockBridge.getPhase71cDamageTimeoutTicks();
        if (configured > 0) return configured;

        try {
            return Math.max(1, MobCombatSettingsConfig.getCombatDisplayTimeoutSeconds()) * 20L;
        } catch (Throwable t) {
            return 600L;
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
