package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.converter.BedrockAnimationControllerGenerator;
import me.zimzaza4.geyserutils.spigot.api.EntityUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
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

    // Animation tracking
    private List<String> sortedAnimationNames;  // sorted list of animation names for this model
    private String lastAnimationName = null;    // last sent animation state

    public FMMEntityData(ModeledEntity modeledEntity, Entity realEntity, String bedrockEntityId, BedrockEntityBridge bridge) {
        this.modeledEntity = modeledEntity;
        this.realEntity = realEntity;
        this.bedrockEntityId = bedrockEntityId;
        this.bridge = bridge;
        this.packetEntity = new PacketEntity(realEntity.getLocation());
        this.sortedAnimationNames = bridge.getAnimationNames(bedrockEntityId);
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
        bridge.getPacketInterceptor().hideEntity(realEntityId, player);

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
            // Send custom name so Bedrock players see the EliteMobs name, not the mob type
            sendNameToViewer(player);
            // Send initial animation state (properties already registered at startup by Extension)
            sendInitialAnimation(player);
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
        bridge.getPacketInterceptor().unhideEntity(realEntity.getEntityId(), player);

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
     * Syncs the fake entity position and animation state to the real entity.
     */
    public void syncPosition() {
        if (destroyed || viewers.isEmpty()) return;

        Location realLoc = realEntity.getLocation();
        packetEntity.teleport(realLoc, viewers);

        // Sync animation state
        syncAnimation();
    }

    /**
     * Sends the initial animation state to a new Bedrock viewer.
     * Properties are already registered at startup by the Geyser Extension.
     */
    private void sendInitialAnimation(Player player) {
        if (sortedAnimationNames == null || sortedAnimationNames.isEmpty()) return;

        String currentAnim = AnimationStateTracker.getCurrentAnimationName(modeledEntity);
        if (currentAnim == null) return;

        int animIndex = sortedAnimationNames.indexOf(currentAnim);
        if (animIndex < 0) return;

        int[] bitmask = BedrockAnimationControllerGenerator.getAnimationBitmask(animIndex);
        String propertyId = "fmmbridge:anim" + bitmask[0];
        EntityUtils.sendIntProperty(player, packetEntity.getEntityId(), propertyId, bitmask[1]);
        lastAnimationName = currentAnim;
        log.info("[BRIDGE] Sent initial animation '" + currentAnim + "' (bitmask=" + bitmask[1] + ") for " + bedrockEntityId);
    }

    /**
     * Checks if the FMM animation state changed and sends property updates to Bedrock viewers.
     * Uses GeyserUtils bitmask properties (same approach as GeyserModelEngine).
     */
    private void syncAnimation() {
        if (sortedAnimationNames == null || sortedAnimationNames.isEmpty()) return;

        String currentAnim = AnimationStateTracker.getCurrentAnimationName(modeledEntity);
        if (currentAnim == null) return;

        // Only send update when state actually changes
        if (currentAnim.equals(lastAnimationName)) return;

        // Find animation index in sorted list
        int animIndex = sortedAnimationNames.indexOf(currentAnim);
        if (animIndex < 0) return;

        // Calculate bitmask for new animation
        int[] newBitmask = BedrockAnimationControllerGenerator.getAnimationBitmask(animIndex);

        // Clear old animation bitmask, then set new one
        int[] oldBitmask = null;
        if (lastAnimationName != null) {
            int oldIndex = sortedAnimationNames.indexOf(lastAnimationName);
            if (oldIndex >= 0) {
                oldBitmask = BedrockAnimationControllerGenerator.getAnimationBitmask(oldIndex);
            }
        }

        for (Player viewer : viewers) {
            if (!viewer.isOnline()) continue;
            int fakeId = packetEntity.getEntityId();
            // Stop old animation
            if (oldBitmask != null) {
                EntityUtils.sendIntProperty(viewer, fakeId, "fmmbridge:anim" + oldBitmask[0], 0);
            }
            // Start new animation
            EntityUtils.sendIntProperty(viewer, fakeId, "fmmbridge:anim" + newBitmask[0], newBitmask[1]);
        }

        log.info("[BRIDGE] Animation changed: " + lastAnimationName + " → " + currentAnim
                + " (bitmask=" + newBitmask[1] + ") for " + bedrockEntityId);
        lastAnimationName = currentAnim;
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
                bridge.getPacketInterceptor().unhideEntity(realEntityId, player);
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

    /**
     * Sends the custom name to a viewer. If no name is available yet (EliteMobs may not
     * have set it), retries once after 20 ticks (1 second).
     */
    private void sendNameToViewer(Player player) {
        Component name = getCustomName();
        if (name != null) {
            packetEntity.sendNameMetadata(name, true, player);
            log.info("[BRIDGE] Sent nametag for " + bedrockEntityId + " to " + player.getName());
        } else {
            // EliteMobs might set the name after a delay — retry once after 1 second
            Bukkit.getScheduler().runTaskLater(FMMBedrockBridge.getInstance(), () -> {
                if (destroyed || !player.isOnline() || !viewers.contains(player)) return;
                Component retryName = getCustomName();
                if (retryName != null) {
                    packetEntity.sendNameMetadata(retryName, true, player);
                    log.info("[BRIDGE] Sent nametag (retry) for " + bedrockEntityId + " to " + player.getName());
                } else {
                    log.warning("[BRIDGE] No custom name found for " + bedrockEntityId + " after retry");
                }
            }, 20L);
        }
    }

    /**
     * Gets the custom name — tries the real Bukkit entity first (EliteMobs sets this),
     * then falls back to FMM's display name.
     */
    private Component getCustomName() {
        // 1. Try Bukkit entity custom name (EliteMobs sets this on the living entity)
        try {
            Component name = realEntity.customName();
            if (name != null) {
                log.fine("[BRIDGE] Got name from realEntity.customName() for " + bedrockEntityId);
                return name;
            }
        } catch (Exception ignored) {}

        // 2. Fallback: FMM's display name (used for TextDisplay nametags)
        try {
            String fmmName = modeledEntity.getDisplayName();
            if (fmmName != null && !fmmName.isEmpty()) {
                log.fine("[BRIDGE] Got name from FMM displayName for " + bedrockEntityId + ": " + fmmName);
                return Component.text(fmmName);
            }
        } catch (Exception ignored) {}

        log.fine("[BRIDGE] No custom name found for " + bedrockEntityId);
        return null;
    }
}
