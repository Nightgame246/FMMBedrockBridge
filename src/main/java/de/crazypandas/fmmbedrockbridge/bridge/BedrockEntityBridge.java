package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.tracker.FMMEntityTracker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.floodgate.api.FloodgateApi;

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
public class BedrockEntityBridge implements Listener {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();
    private final boolean floodgateAvailable;
    private final boolean geyserUtilsAvailable;
    private final FMMEntityTracker entityTracker;

    /** All active FMM entity data, keyed by ModeledEntity */
    private final Map<ModeledEntity, FMMEntityData> entityDataMap = new ConcurrentHashMap<>();

    /** Bedrock players that have been online long enough for GeyserUtils to be ready */
    private final Set<Player> readyBedrockPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Real entity IDs that are hidden for Bedrock players.
     * Key = entity ID, Value = set of players for whom this entity is hidden.
     * Used by the packet listener to suppress ALL outgoing packets for these entities.
     */
    private final Map<Integer, Set<Player>> hiddenEntityIds = new ConcurrentHashMap<>();

    /** Maps fake entity IDs to real entity IDs for interact redirection */
    private final Map<Integer, Integer> fakeToRealEntityId = new ConcurrentHashMap<>();

    /** Cached animation names per bedrock entity ID (loaded from converter output) */
    private final Map<String, List<String>> animationNamesCache = new ConcurrentHashMap<>();

    private BukkitTask syncTask;
    private PacketListenerAbstract packetListener;

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
        this.floodgateAvailable = floodgateAvailable;
        this.geyserUtilsAvailable = geyserUtilsAvailable;
        this.entityTracker = entityTracker;
    }

    /**
     * Starts the per-tick sync task and registers the packet suppression listener.
     */
    public void start() {
        if (syncTask != null) return;

        // Run every 2 ticks (100ms) — good balance between smoothness and performance
        syncTask = Bukkit.getScheduler().runTaskTimer(FMMBedrockBridge.getInstance(), this::tick, 20L, 2L);
        log.info("[BRIDGE] Sync task started (every 2 ticks)");

        // Register packet listener that blocks ALL packets for hidden real entities.
        // This is necessary because Paper's entity tracker continuously sends
        // position/metadata/spawn packets that would re-show the hidden mob.
        registerPacketSuppressor();
    }

    /**
     * Stops the sync task and cleans up all entity data.
     */
    public void shutdown() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            packetListener = null;
        }

        for (FMMEntityData data : entityDataMap.values()) {
            data.destroy();
        }
        entityDataMap.clear();
        readyBedrockPlayers.clear();
        hiddenEntityIds.clear();
        fakeToRealEntityId.clear();

        log.info("[BRIDGE] Shutdown complete");
    }

    /**
     * Called by FMMEntityTracker when a new FMM entity is detected.
     */
    public void onEntitySpawn(ModeledEntity modeledEntity) {
        if (!(modeledEntity instanceof DynamicEntity)) return;
        if (!geyserUtilsAvailable) return;

        Entity underlying = getUnderlyingEntity(modeledEntity);
        if (underlying == null) return;

        String bedrockId = getBedrockEntityId(modeledEntity);
        FMMEntityData data = new FMMEntityData(modeledEntity, underlying, bedrockId, this);
        entityDataMap.put(modeledEntity, data);
        fakeToRealEntityId.put(data.getPacketEntity().getEntityId(), underlying.getEntityId());

        log.info("[BRIDGE] Registered entity " + bedrockId
                + " (realId=" + underlying.getEntityId()
                + ", fakeId=" + data.getPacketEntity().getEntityId() + ")");

        // Immediately add all ready Bedrock players as viewers if in range
        for (Player player : readyBedrockPlayers) {
            if (player.isOnline() && isInRange(player, underlying)) {
                data.addViewer(player);
            }
        }
    }

    /**
     * Called by FMMEntityTracker when an FMM entity is no longer detected.
     */
    public void onEntityDespawn(ModeledEntity modeledEntity) {
        FMMEntityData data = entityDataMap.remove(modeledEntity);
        if (data != null) {
            fakeToRealEntityId.remove(data.getPacketEntity().getEntityId());
            data.destroy();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!geyserUtilsAvailable) return;

        Player player = event.getPlayer();
        if (!isBedrockPlayer(player)) return;

        // Delay by 60 ticks (3s) to allow GeyserUtils downstream listener to initialize
        Bukkit.getScheduler().runTaskLater(FMMBedrockBridge.getInstance(), () -> {
            if (!player.isOnline()) return;

            readyBedrockPlayers.add(player);
            log.info("[BRIDGE] Bedrock player " + player.getName() + " is now ready");

            // Add as viewer for all existing entities in range
            for (FMMEntityData data : entityDataMap.values()) {
                Entity real = data.getRealEntity();
                if (real != null && !real.isDead() && isInRange(player, real)) {
                    data.addViewer(player);
                }
            }
        }, 60L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        readyBedrockPlayers.remove(player);

        // Remove from all entity viewer lists
        for (FMMEntityData data : entityDataMap.values()) {
            data.removeViewer(player);
        }
    }

    /**
     * Per-tick sync: check viewer distance and update positions.
     */
    private void tick() {
        for (FMMEntityData data : entityDataMap.values()) {
            if (data.isDestroyed()) continue;

            Entity real = data.getRealEntity();
            if (real == null || real.isDead()) {
                // Entity died — will be cleaned up by FMMEntityTracker's next poll
                continue;
            }

            // Sync position of fake entity to real entity
            data.syncPosition();

            // Check viewer distance for all ready Bedrock players
            for (Player player : readyBedrockPlayers) {
                if (!player.isOnline()) continue;

                boolean inRange = isInRange(player, real);
                boolean isViewer = data.getViewers().contains(player);

                if (inRange && !isViewer) {
                    data.addViewer(player);
                } else if (!inRange && isViewer) {
                    data.removeViewer(player);
                }
            }
        }

        // Clean up offline players from ready set
        readyBedrockPlayers.removeIf(p -> !p.isOnline());
    }

    /**
     * Checks if a player is within view distance of an entity.
     * Uses the same formula as GeyserModelEngine.
     */
    private boolean isInRange(Player player, Entity entity) {
        Location playerLoc = player.getLocation();
        Location entityLoc = entity.getLocation();

        if (playerLoc.getWorld() != entityLoc.getWorld()) return false;

        // Horizontal distance only (ignore Y), same as GeyserModelEngine
        double dx = playerLoc.getX() - entityLoc.getX();
        double dz = playerLoc.getZ() - entityLoc.getZ();
        double horizontalDistSq = dx * dx + dz * dz;

        int viewDist = player.getSendViewDistance();
        double maxDistSq = (double) viewDist * viewDist * 48;

        return horizontalDistSq <= maxDistSq;
    }

    private boolean isBedrockPlayer(Player player) {
        if (!floodgateAvailable) return false;
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
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
                log.info("[BRIDGE] Loaded " + names.size() + " animation names for " + modelId);
                return names;
            } catch (Exception e) {
                log.warning("[BRIDGE] Could not read animation names for " + modelId + ": " + e.getMessage());
                return List.of();
            }
        });
    }

    /**
     * Marks a real entity as hidden for a specific Bedrock player.
     * The packet suppressor will block all outgoing packets for this entity to that player.
     */
    public void hideRealEntity(int entityId, Player player) {
        hiddenEntityIds.computeIfAbsent(entityId, k -> ConcurrentHashMap.newKeySet()).add(player);
    }

    /**
     * Un-hides a real entity for a specific Bedrock player.
     */
    public void unhideRealEntity(int entityId, Player player) {
        Set<Player> players = hiddenEntityIds.get(entityId);
        if (players != null) {
            players.remove(player);
            if (players.isEmpty()) {
                hiddenEntityIds.remove(entityId);
            }
        }
    }

    /**
     * Checks if a real entity is hidden for a specific player.
     */
    private boolean isEntityHiddenFor(int entityId, Object player) {
        Set<Player> players = hiddenEntityIds.get(entityId);
        return players != null && players.contains(player);
    }

    /**
     * Registers a PacketEvents listener that suppresses ALL outgoing packets
     * for real entities that have been replaced by fake entities for Bedrock players.
     * This prevents Paper's entity tracker from re-showing hidden mobs.
     */
    private void registerPacketSuppressor() {
        packetListener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (hiddenEntityIds.isEmpty()) return;

                Object eventPlayer = event.getPlayer();
                if (!(eventPlayer instanceof Player)) return;

                int entityId = -1;

                // Block SPAWN_ENTITY — prevents Paper from re-creating the mob client-side
                if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
                    entityId = new WrapperPlayServerSpawnEntity(event).getEntityId();
                }
                // Block ENTITY_METADATA — prevents metadata updates from re-showing the entity
                else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
                    entityId = new WrapperPlayServerEntityMetadata(event).getEntityId();
                }

                if (entityId > 0 && isEntityHiddenFor(entityId, eventPlayer)) {
                    event.setCancelled(true);
                }
            }

            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                // Redirect interact packets from fake entity to real entity
                if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
                if (fakeToRealEntityId.isEmpty()) return;

                WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
                Integer realId = fakeToRealEntityId.get(wrapper.getEntityId());
                if (realId != null) {
                    wrapper.setEntityId(realId);
                }
            }
        };

        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
        log.info("[BRIDGE] Packet suppressor registered — blocking spawn/metadata packets for hidden real entities");
    }
}
