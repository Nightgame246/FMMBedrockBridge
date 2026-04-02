package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import me.zimzaza4.geyserutils.spigot.api.EntityUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Wraps a FMM ModeledEntity with its corresponding fake PacketEntity and viewer tracking.
 *
 * Critical ordering in addViewer:
 * 1. hideEntity(realEntity)         — hide the vanilla mob from Bedrock player
 * 2. setCustomEntity(fakeId, ...)   — populate GeyserUtils CUSTOM_ENTITIES cache
 * 3. sendSpawnPacket(fakeEntity)    — Geyser intercepts this, finds cache entry, replaces with custom entity
 */
public class FMMEntityData {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    private final ModeledEntity modeledEntity;
    private final Entity realEntity;
    private final PacketEntity packetEntity;
    private final String bedrockEntityId;
    private final BedrockEntityBridge bridge;
    private final Set<Player> viewers = ConcurrentHashMap.newKeySet();
    private boolean destroyed = false;

    public FMMEntityData(ModeledEntity modeledEntity, Entity realEntity, String bedrockEntityId, BedrockEntityBridge bridge) {
        this.modeledEntity = modeledEntity;
        this.realEntity = realEntity;
        this.bedrockEntityId = bedrockEntityId;
        this.bridge = bridge;
        this.packetEntity = new PacketEntity(realEntity.getLocation());
    }

    /**
     * Adds a Bedrock player as viewer. Follows the critical ordering:
     * 1. Hide real entity (vanilla mob disappears)
     * 2. Set custom entity in GeyserUtils cache (BEFORE spawn packet!)
     * 3. Send fake PIG spawn packet (Geyser replaces with custom entity)
     */
    public void addViewer(Player player) {
        if (destroyed || viewers.contains(player)) return;

        // 1. Hide the real entity (wolf/zombie/etc) from this player
        //    Register with packet suppressor to block ALL future spawn/metadata packets
        int realEntityId = realEntity.getEntityId();
        bridge.hideRealEntity(realEntityId, player);

        // Send destroy packet to immediately remove the entity client-side
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerDestroyEntities(realEntityId));

        // 2. Populate GeyserUtils cache BEFORE spawn packet
        EntityUtils.setCustomEntity(player, packetEntity.getEntityId(), bedrockEntityId);
        viewers.add(player);

        log.info("[BRIDGE] Added viewer " + player.getName()
                + " for " + bedrockEntityId
                + " (fakeId=" + packetEntity.getEntityId()
                + ", realId=" + realEntityId + " destroyed)");

        // 3. Send fake PIG spawn packet AFTER a delay so the plugin message
        //    (setCustomEntity) reaches Geyser's CUSTOM_ENTITIES cache first.
        //    Without this delay, the spawn packet may arrive before the cache is populated.
        Bukkit.getScheduler().runTaskLater(FMMBedrockBridge.getInstance(), () -> {
            if (destroyed || !player.isOnline()) return;
            packetEntity.sendSpawnPacket(player);
            // Use real entity's hitbox dimensions so Bedrock players can interact
            float hitboxHeight = (float) Math.max(realEntity.getHeight(), 0.5);
            float hitboxWidth = (float) Math.max(realEntity.getWidth(), 0.5);
            EntityUtils.sendCustomHitBox(player, packetEntity.getEntityId(), hitboxHeight, hitboxWidth);
            log.info("[BRIDGE] Sent spawn packet + hitbox for " + bedrockEntityId
                    + " (fakeId=" + packetEntity.getEntityId() + ") to " + player.getName());
        }, 2L);
    }

    /**
     * Removes a Bedrock player as viewer. Destroys fake entity and shows real one again.
     */
    public void removeViewer(Player player) {
        if (!viewers.remove(player)) return;

        // Un-hide the real entity from packet suppressor
        bridge.unhideRealEntity(realEntity.getEntityId(), player);

        // Destroy fake entity for this player
        packetEntity.sendDestroyPacket(player);

        // Show real entity again
        try {
            player.showEntity(FMMBedrockBridge.getInstance(), realEntity);
        } catch (Exception e) {
            // Entity might have been removed
        }

        log.info("[BRIDGE] Removed viewer " + player.getName()
                + " for " + bedrockEntityId);
    }

    /**
     * Syncs the fake entity position to the real entity's current location.
     */
    public void syncPosition() {
        if (destroyed || viewers.isEmpty()) return;

        Location realLoc = realEntity.getLocation();
        packetEntity.teleport(realLoc, viewers);
    }

    /**
     * Destroys this entity data — removes fake entity for all viewers and shows real entity.
     */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;

        int realEntityId = realEntity.getEntityId();

        if (!viewers.isEmpty()) {
            packetEntity.remove(viewers);

            for (Player player : viewers) {
                // Un-hide from packet suppressor
                bridge.unhideRealEntity(realEntityId, player);
                try {
                    player.showEntity(FMMBedrockBridge.getInstance(), realEntity);
                } catch (Exception e) {
                    // Entity might already be gone
                }
            }
        }

        viewers.clear();
        log.info("[BRIDGE] Destroyed entity data for " + bedrockEntityId);
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

    public PacketEntity getPacketEntity() {
        return packetEntity;
    }

    public String getBedrockEntityId() {
        return bedrockEntityId;
    }

    public Set<Player> getViewers() {
        return Collections.unmodifiableSet(viewers);
    }

    public boolean hasViewers() {
        return !viewers.isEmpty();
    }
}
