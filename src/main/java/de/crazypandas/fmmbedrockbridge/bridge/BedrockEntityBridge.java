package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.tracker.FMMEntityTracker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages the BossBar + Nametag controllers for FMM modeled entities (DynamicEntity only).
 *
 * Mob/static rendering is FMM 2.6.0 native — this bridge only adds the EM UX layer:
 * Phase 7.1a/c styled BossBar + Phase 7.1b/c combat-triggered HP nametag overlay.
 */
public class BedrockEntityBridge {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();
    private final FMMEntityTracker entityTracker;

    /** Active FMM entity data (BossBar + Nametag holders), keyed by ModeledEntity */
    private final Map<ModeledEntity, FMMEntityData> entityDataMap = new ConcurrentHashMap<>();

    /** Packet inject / BossBar suppression */
    private final PacketInterceptor packetInterceptor = new PacketInterceptor();

    /** Bedrock player tracking */
    private final ViewerManager viewerManager;

    /** Phase 7.1a — active BossBar controllers, keyed by real-entity UUID */
    private final Map<UUID, BedrockBossBarController> activeControllers = new ConcurrentHashMap<>();

    /** Phase 7.1a — captured EM BossBar UUIDs for suppression on Bedrock players */
    private final BossBarRegistry bossBarRegistry = new BossBarRegistry();

    /** Phase 7.1b — active Nametag controllers, keyed by real-entity UUID */
    private final Map<UUID, BedrockNametagController> activeNametags = new ConcurrentHashMap<>();

    private BukkitTask syncTask;

    // Reflection to read DynamicEntity.underlyingEntity (still protected in FMM 2.6.0)
    private static Field underlyingEntityField;
    static {
        try {
            underlyingEntityField = ModeledEntity.class.getDeclaredField("underlyingEntity");
            underlyingEntityField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            underlyingEntityField = null;
        }
    }

    public BedrockEntityBridge(boolean floodgateAvailable, FMMEntityTracker entityTracker) {
        this.entityTracker = entityTracker;
        this.viewerManager = new ViewerManager(floodgateAvailable);
        packetInterceptor.setBridge(this);

        viewerManager.setOnPlayerReady(player -> {
            for (FMMEntityData data : entityDataMap.values()) {
                if (!data.isAlive()) continue;
                Location loc = data.getLocation();
                if (loc != null && ViewerManager.isInRange(player, loc)) {
                    data.addViewer(player);
                }
            }
        });

        viewerManager.setOnPlayerLeave(player -> {
            for (FMMEntityData data : entityDataMap.values()) {
                data.removeViewer(player);
            }
        });
    }

    public void start() {
        if (syncTask != null) return;
        syncTask = Bukkit.getScheduler().runTaskTimer(FMMBedrockBridge.getInstance(), this::tick, 20L, 2L);
        log.info("[BRIDGE] Sync task started (every 2 ticks)");
        packetInterceptor.register();
        log.info("[BRIDGE] PacketInterceptor registered");
    }

    public void shutdown() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        packetInterceptor.unregister();

        for (FMMEntityData data : entityDataMap.values()) {
            data.destroy();
        }
        entityDataMap.clear();
        activeControllers.clear();
        bossBarRegistry.clear();
        activeNametags.clear();
        viewerManager.clear();
        packetInterceptor.clear();
        log.info("[BRIDGE] Shutdown complete");
    }

    /** Called by FMMEntityTracker when a new FMM DynamicEntity is detected. */
    public void onEntitySpawn(ModeledEntity modeledEntity) {
        // Only DynamicEntity — static props have no BossBar/Nametag and are handled by FMM nativ.
        if (!(modeledEntity instanceof DynamicEntity)) return;

        Entity underlying = getUnderlyingEntity(modeledEntity);
        if (underlying == null) return;

        FMMEntityData data = new FMMEntityData(modeledEntity, underlying, this);
        entityDataMap.put(modeledEntity, data);

        // Immediately add ready Bedrock viewers in range
        Location spawnLoc = data.getLocation();
        if (spawnLoc != null) {
            for (Player player : viewerManager.getReadyPlayers()) {
                if (player.isOnline() && ViewerManager.isInRange(player, spawnLoc)) {
                    data.addViewer(player);
                }
            }
        }
    }

    /** Called by FMMEntityTracker when an FMM entity is no longer detected. */
    public void onEntityDespawn(ModeledEntity modeledEntity) {
        FMMEntityData data = entityDataMap.remove(modeledEntity);
        if (data != null) data.destroy();
    }

    /** Per-tick: range-check viewers + tick controllers. */
    private void tick() {
        for (FMMEntityData data : entityDataMap.values()) {
            if (data.isDestroyed() || !data.isAlive()) continue;
            Location loc = data.getLocation();
            if (loc == null) continue;

            data.tick();

            for (Player player : viewerManager.getReadyPlayers()) {
                if (!player.isOnline()) continue;
                boolean inRange = ViewerManager.isInRange(player, loc);
                boolean isViewer = data.getViewers().contains(player);
                if (inRange && !isViewer) {
                    data.addViewer(player);
                } else if (!inRange && isViewer) {
                    data.removeViewer(player);
                }
            }
        }
        viewerManager.removeOffline();
    }

    private Entity getUnderlyingEntity(ModeledEntity modeledEntity) {
        if (underlyingEntityField == null) return null;
        try {
            return (Entity) underlyingEntityField.get(modeledEntity);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    public PacketInterceptor getPacketInterceptor() {
        return packetInterceptor;
    }

    public ViewerManager getViewerManager() {
        return viewerManager;
    }

    public Map<ModeledEntity, FMMEntityData> getEntityDataMap() {
        return Collections.unmodifiableMap(entityDataMap);
    }

    /** Phase 7.1a — accessors for BossBar subsystem. */
    public Map<UUID, BedrockBossBarController> getActiveControllers() {
        return activeControllers;
    }

    public BossBarRegistry getBossBarRegistry() {
        return bossBarRegistry;
    }

    /** Phase 7.1b — accessor for Nametag subsystem. */
    public Map<UUID, BedrockNametagController> getActiveNametags() {
        return activeNametags;
    }
}
