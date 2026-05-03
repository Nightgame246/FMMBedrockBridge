package de.crazypandas.fmmbedrockbridge.bridge;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.StaticEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.tracker.FMMEntityTracker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Bridges FMM modeled entities to Bedrock clients via fake PacketEntities + GeyserUtils.
 *
 * Approach (based on GeyserModelEngine):
 * - For each FMM entity, create a fake PIG entity (packet-only, random ID 300-400M)
 * - For each Bedrock player in range:
 *   1. hideEntity(realEntity) — hide the vanilla mob
 *   2. setCustomEntity(fakeId, bedrockId) — populate GeyserUtils cache
 *   3. sendSpawnPacket(fakeEntity) — Geyser intercepts, replaces PIG with custom entity
 * - A sync task runs every tick to update positions and manage viewers
 */
public class BedrockEntityBridge {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();
    private final boolean geyserUtilsAvailable;
    private final FMMEntityTracker entityTracker;

    /** All active FMM entity data, keyed by ModeledEntity */
    private final Map<ModeledEntity, IBridgeEntityData> entityDataMap = new ConcurrentHashMap<>();

    /** Packet suppression + interact redirect */
    private final PacketInterceptor packetInterceptor = new PacketInterceptor();

    /** Bedrock player tracking */
    private final ViewerManager viewerManager;

    /** Cached animation names per bedrock entity ID (loaded from converter output) */
    private final Map<String, List<String>> animationNamesCache = new ConcurrentHashMap<>();

    /** Phase 7.1a — active BossBar controllers, keyed by real-entity UUID */
    private final Map<UUID, BedrockBossBarController> activeControllers = new ConcurrentHashMap<>();

    /** Phase 7.1a — captured EM BossBar UUIDs for suppression on Bedrock players */
    private final BossBarRegistry bossBarRegistry = new BossBarRegistry();

    private BukkitTask syncTask;

    // Cached reflection field for ModeledEntity.underlyingEntity
    private static Field underlyingEntityField;

    static {
        try {
            underlyingEntityField = ModeledEntity.class.getDeclaredField("underlyingEntity");
            underlyingEntityField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            underlyingEntityField = null;
        }
    }

    public BedrockEntityBridge(boolean floodgateAvailable, boolean geyserUtilsAvailable, FMMEntityTracker entityTracker) {
        this.geyserUtilsAvailable = geyserUtilsAvailable;
        this.entityTracker = entityTracker;
        this.viewerManager = new ViewerManager(floodgateAvailable);

        viewerManager.setOnPlayerReady(player -> {
            if (!geyserUtilsAvailable) return;
            for (IBridgeEntityData data : entityDataMap.values()) {
                if (!data.isAlive()) continue;
                Location loc = data.getLocation();
                if (loc != null && ViewerManager.isInRange(player, loc)) {
                    data.addViewer(player);
                }
            }
        });

        viewerManager.setOnPlayerLeave(player -> {
            for (IBridgeEntityData data : entityDataMap.values()) {
                data.removeViewer(player);
            }
        });
    }

    /**
     * Starts the per-tick sync task and registers the packet suppression listener.
     */
    public void start() {
        if (syncTask != null) return;

        // Run every 2 ticks (100ms) — good balance between smoothness and performance
        syncTask = Bukkit.getScheduler().runTaskTimer(FMMBedrockBridge.getInstance(), this::tick, 20L, 2L);
        log.info("[BRIDGE] Sync task started (every 2 ticks)");

        packetInterceptor.register();
    }

    /**
     * Stops the sync task and cleans up all entity data.
     */
    public void shutdown() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        packetInterceptor.unregister();

        for (IBridgeEntityData data : entityDataMap.values()) {
            data.destroy();
        }
        entityDataMap.clear();
        activeControllers.clear();
        bossBarRegistry.clear();
        viewerManager.clear();
        packetInterceptor.clear();

        log.info("[BRIDGE] Shutdown complete");
    }

    /**
     * Called by FMMEntityTracker when a new FMM entity is detected.
     */
    public void onEntitySpawn(ModeledEntity modeledEntity) {
        if (!geyserUtilsAvailable) return;

        String bedrockId = getBedrockEntityId(modeledEntity);
        IBridgeEntityData data;

        if (modeledEntity instanceof DynamicEntity) {
            Entity underlying = getUnderlyingEntity(modeledEntity);
            if (underlying == null) return;

            FMMEntityData fmmData = new FMMEntityData(modeledEntity, underlying, bedrockId, this);
            packetInterceptor.mapFakeToReal(fmmData.getPacketEntity().getEntityId(), underlying.getEntityId());
            data = fmmData;

            log.fine("[BRIDGE] Registered dynamic entity " + bedrockId
                    + " (realId=" + underlying.getEntityId()
                    + ", fakeId=" + data.getPacketEntity().getEntityId() + ")");
        } else if (modeledEntity instanceof StaticEntity) {
            Location loc = modeledEntity.getLocation();
            if (loc == null) return;

            data = new StaticEntityData(modeledEntity, loc, bedrockId);

            log.fine("[BRIDGE] Registered static entity " + bedrockId
                    + " at " + loc.getWorld().getName()
                    + " " + String.format("%.1f %.1f %.1f", loc.getX(), loc.getY(), loc.getZ())
                    + " (fakeId=" + data.getPacketEntity().getEntityId() + ")");
        } else {
            return;
        }

        entityDataMap.put(modeledEntity, data);

        // Immediately add all ready Bedrock players as viewers if in range
        Location spawnLoc = data.getLocation();
        for (Player player : viewerManager.getReadyPlayers()) {
            if (player.isOnline() && ViewerManager.isInRange(player, spawnLoc)) {
                data.addViewer(player);
            }
        }
    }

    /**
     * Called by FMMEntityTracker when an FMM entity is no longer detected.
     */
    public void onEntityDespawn(ModeledEntity modeledEntity) {
        IBridgeEntityData data = entityDataMap.remove(modeledEntity);
        if (data != null) {
            packetInterceptor.unmapFake(data.getPacketEntity().getEntityId());
            data.destroy();
        }
    }

    /**
     * Per-tick sync: check viewer distance and update positions.
     */
    private void tick() {
        for (IBridgeEntityData data : entityDataMap.values()) {
            if (data.isDestroyed()) continue;
            if (!data.isAlive()) continue;

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
        if (underlyingEntityField == null) {
            log.warning("[BRIDGE] Could not access ModeledEntity.underlyingEntity via reflection.");
            return null;
        }
        try {
            return (Entity) underlyingEntityField.get(modeledEntity);
        } catch (IllegalAccessException e) {
            log.warning("[BRIDGE] Reflection access failed: " + e.getMessage());
            return null;
        }
    }

    private String getBedrockEntityId(ModeledEntity modeledEntity) {
        return "fmmbridge:" + modeledEntity.getEntityID().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the sorted animation names for a bedrock entity ID.
     * Reads from the converter output (animations.json) and caches the result.
     */
    public List<String> getAnimationNames(String bedrockEntityId) {
        return animationNamesCache.computeIfAbsent(bedrockEntityId, id -> {
            String modelId = id.replace("fmmbridge:", "");
            String configPath = FMMBedrockBridge.getInstance().getConfig().getString("converter.output-path", "bedrock-skins");
            File animFile = new File(FMMBedrockBridge.getInstance().getDataFolder(),
                    configPath + File.separator + modelId + File.separator + "animations.json");
            if (!animFile.exists()) return List.of();

            try {
                String json = Files.readString(animFile.toPath());
                Map<?, ?> parsed = new Gson().fromJson(json, Map.class);
                Map<?, ?> animations = (Map<?, ?>) parsed.get("animations");
                if (animations == null) return List.of();

                List<String> names = new ArrayList<>();
                for (Object key : animations.keySet()) {
                    String fullId = (String) key;
                    String[] parts = fullId.split("\\.");
                    if (parts.length >= 4) {
                        names.add(parts[parts.length - 1]);
                    }
                }
                Collections.sort(names);
                log.fine("[BRIDGE] Loaded " + names.size() + " animation names for " + modelId);
                return names;
            } catch (Exception e) {
                log.warning("[BRIDGE] Could not read animation names for " + modelId + ": " + e.getMessage());
                return List.of();
            }
        });
    }

    public PacketInterceptor getPacketInterceptor() {
        return packetInterceptor;
    }

    public ViewerManager getViewerManager() {
        return viewerManager;
    }

    public Map<ModeledEntity, IBridgeEntityData> getEntityDataMap() {
        return Collections.unmodifiableMap(entityDataMap);
    }

    /** Phase 7.1a — accessors for BossBar subsystem. */
    public Map<UUID, BedrockBossBarController> getActiveControllers() {
        return activeControllers;
    }

    public BossBarRegistry getBossBarRegistry() {
        return bossBarRegistry;
    }
}
