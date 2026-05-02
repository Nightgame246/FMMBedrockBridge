package de.crazypandas.fmmbedrockbridge.bridge;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Maps Vanilla BossBar UUIDs to FMM-tracked entities so we can rewrite
 * the BossBar title for Bedrock viewers using the entity's customName.
 *
 * UUIDs are not exposed by EliteMobs in any stable API; we therefore use a
 * heuristic: when a BossBar packet arrives at a Bedrock player and the source
 * UUID is unknown, the closest FMM-tracked entity within {@link #MATCH_RADIUS}
 * blocks is registered as the bar's source.
 */
public class BossBarTitleResolver {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    /** Max distance (blocks) between player and entity for heuristic match. */
    private static final double MATCH_RADIUS = 80.0;
    private static final double MATCH_RADIUS_SQ = MATCH_RADIUS * MATCH_RADIUS;

    private final BedrockEntityBridge bridge;

    /** UUID of the bossbar -> entity id of the matched real entity. */
    private final Map<UUID, Integer> uuidToRealEntityId = new ConcurrentHashMap<>();

    public BossBarTitleResolver(BedrockEntityBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Returns the replacement title for a BossBar packet, or null if the packet
     * should be passed through unchanged.
     *
     * @param uuid    BossBar UUID from the packet
     * @param viewer  Player receiving the packet (used as anchor for the heuristic)
     */
    public Component resolveTitle(UUID uuid, Player viewer) {
        Integer realEntityId = uuidToRealEntityId.get(uuid);
        if (realEntityId == null) {
            // First time we see this UUID for any Bedrock viewer — try heuristic match.
            realEntityId = matchClosestFmmEntity(viewer);
            if (realEntityId == null) return null;
            uuidToRealEntityId.put(uuid, realEntityId);
            log.fine("[BRIDGE] BossBar " + uuid + " heuristically matched to entity id " + realEntityId);
        }

        Entity entity = lookupEntity(realEntityId);
        if (entity == null) {
            uuidToRealEntityId.remove(uuid);
            return null;
        }
        try {
            return entity.customName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called on BOSS_EVENT action=REMOVE to drop the cache entry. */
    public void onBossBarRemoved(UUID uuid) {
        uuidToRealEntityId.remove(uuid);
    }

    /** Called when an FMM entity despawns to drop any UUIDs still pointing at it. */
    public void onEntityDespawn(int realEntityId) {
        uuidToRealEntityId.entrySet().removeIf(e -> e.getValue() == realEntityId);
    }

    private Integer matchClosestFmmEntity(Player viewer) {
        Location pl = viewer.getLocation();
        Integer bestId = null;
        double bestDistSq = MATCH_RADIUS_SQ;

        for (IBridgeEntityData data : bridge.getEntityDataMap().values()) {
            if (data.isDestroyed() || !data.isAlive()) continue;
            if (!(data instanceof FMMEntityData fmmData)) continue;
            Entity real = fmmData.getRealEntity();
            if (real == null || real.getWorld() != pl.getWorld()) continue;
            double dSq = real.getLocation().distanceSquared(pl);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestId = real.getEntityId();
            }
        }
        return bestId;
    }

    private Entity lookupEntity(int entityId) {
        for (IBridgeEntityData data : bridge.getEntityDataMap().values()) {
            if (!(data instanceof FMMEntityData fmmData)) continue;
            Entity real = fmmData.getRealEntity();
            if (real != null && real.getEntityId() == entityId) return real;
        }
        return null;
    }

    public void clear() {
        uuidToRealEntityId.clear();
    }
}
