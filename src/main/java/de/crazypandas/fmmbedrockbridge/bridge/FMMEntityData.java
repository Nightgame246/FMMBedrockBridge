package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.elite.EliteMobsHook;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.function.Supplier;

/**
 * Holds the BossBar controller and the Bedrock name fix for a single FMM modeled entity.
 *
 * <p>Mob/static rendering is FMM 2.6.0 native — this class no longer spawns fake entities
 * or hides the real entity from Bedrock viewers. It only manages the UX layer
 * (BossBar suppression + replacement) that FMM doesn't
 * provide natively.
 */
public class FMMEntityData {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    private final ModeledEntity modeledEntity;
    private final Entity realEntity;
    private final BedrockEntityBridge bridge;
    private final Set<Player> viewers = ConcurrentHashMap.newKeySet();
    private boolean destroyed = false;

    // Phase 7.1a — null if this entity is not an EliteMobs boss
    private final BedrockBossBarController bossBarController;


    public FMMEntityData(ModeledEntity modeledEntity, Entity realEntity, BedrockEntityBridge bridge) {
        this.modeledEntity = modeledEntity;
        this.realEntity = realEntity;
        this.bridge = bridge;
        this.bossBarController = createBossBarControllerIfElite();
        refreshBedrockName();
    }

    /**
     * Phase 7.1d — keeps {@link BedrockNameFix} on the FMM display name and pushes it to Bedrock
     * players who already see the mob: on registration (the tracker polls, so they got the spawn
     * metadata first) and whenever EliteMobs renames the model later.
     */
    private void refreshBedrockName() {
        if (!(realEntity instanceof LivingEntity living)) return;
        String desired;
        try {
            desired = modeledEntity.getDisplayName();
        } catch (Throwable t) {
            return;
        }
        BedrockNameFix nameFix = bridge.getPacketInterceptor().getNameFix();
        if (!nameFix.update(living.getEntityId(), desired)) return;
        Component customName = living.customName();
        String current = customName == null ? null
                : net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                        .serialize(customName);
        for (Player viewer : living.getTrackedBy()) {
            if (bridge.getViewerManager().isBedrockPlayer(viewer)) {
                nameFix.sendTo(viewer, living.getEntityId(), current);
            }
        }
    }

    private BedrockBossBarController createBossBarControllerIfElite() {
        if (!(realEntity instanceof LivingEntity living)) return null;
        if (bridge.getActiveControllers().containsKey(living.getUniqueId())) {
            log.warning("[BRIDGE] Duplicate FMMEntityData for UUID " + living.getUniqueId()
                    + " — skipping second BossBar controller creation.");
            return null;
        }

        Supplier<String> titleSource = () -> resolveBossBarTitle(living);
        String styledName = titleSource.get();
        if (styledName == null) return null;

        BedrockBossBarController controller;
        try {
            controller = new BedrockBossBarController(living, styledName, titleSource);
        } catch (Exception e) {
            log.warning("[BRIDGE] Failed to create BossBar: " + e.getMessage());
            return null;
        }
        // Even when FMM's styled displayName is available immediately, EM may still emit
        // its vanilla-style bossbar title ("Evoker | 2"). Keep that fallback title as a
        // suppression alias so PacketInterceptor can block the stale EM bar.
        controller.addTitleAlias(EliteMobsHook.getStyledName(living));
        bridge.getActiveControllers().put(living.getUniqueId(), controller);
        FMMBedrockBridge.debugLog("[BRIDGE] Created BossBar controller (title='" + styledName + "')");
        return controller;
    }

    private String resolveBossBarTitle(LivingEntity living) {
        // Primary: FMM displayName — set by EM via CustomModelFMM.setName, this is the YAML
        // styled name (e.g. "§3[13]§e Eis-Elementar") and what Java renders as Mob-Nametag.
        // It may arrive a few ticks after the ModeledEntity is first detected, so the
        // BossBar controller keeps polling this supplier and updates the Bukkit BossBar.
        try {
            String fmmName = modeledEntity.getDisplayName();
            if (fmmName != null && !fmmName.isEmpty()) return fmmName;
        } catch (Throwable ignored) {}

        // Fallback: EliteMobsHook for non-FMM-named entities. EM 10.3.1 EVOKER-based
        // CustomBosses can still return "Evoker | 2" here, so this value is treated as a
        // temporary title until FMM's displayName becomes available.
        return EliteMobsHook.getStyledName(living);
    }

    public void addViewer(Player player) {
        if (destroyed || viewers.contains(player)) return;
        viewers.add(player);
        if (bossBarController != null) {
            bossBarController.addViewer(player);
        }
    }

    public void removeViewer(Player player) {
        if (!viewers.remove(player)) return;
        if (bossBarController != null) {
            bossBarController.removeViewer(player);
        }
    }

    public void tick() {
        if (destroyed) return;
        if (bossBarController != null) bossBarController.tickUpdate();
        refreshBedrockName();
    }

    public boolean isAlive() {
        return realEntity != null && !realEntity.isDead();
    }

    public Location getLocation() {
        return realEntity != null ? realEntity.getLocation() : null;
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;

        if (bossBarController != null) {
            bossBarController.cleanup();
            bridge.getActiveControllers().remove(realEntity.getUniqueId());
        }

        bridge.getPacketInterceptor().getNameFix().unregister(realEntity.getEntityId());


        viewers.clear();
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public ModeledEntity getModeledEntity() {
        return modeledEntity;
    }

    public Entity getRealEntity() {
        return realEntity;
    }

    public Set<Player> getViewers() {
        return Collections.unmodifiableSet(viewers);
    }
}
