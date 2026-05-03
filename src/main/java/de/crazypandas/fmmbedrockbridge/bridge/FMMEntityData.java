package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.converter.BedrockAnimationControllerGenerator;
import de.crazypandas.fmmbedrockbridge.elite.EliteMobsHook;
import me.zimzaza4.geyserutils.spigot.api.EntityUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Wraps a FMM ModeledEntity with its corresponding fake PacketEntity and viewer tracking.
 *
 * Critical ordering in addViewer:
 * 1. hideEntity(realEntity)         — hide the vanilla mob from Bedrock player
 * 2. setCustomEntity(fakeId, ...)   — populate GeyserUtils CUSTOM_ENTITIES cache
 * 3. sendSpawnPacket(fakeEntity)    — Geyser intercepts this, finds cache entry, replaces with custom entity
 */
public class FMMEntityData implements IBridgeEntityData {

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

    // Phase 7.1a — null if this entity is not an EliteMobs boss
    private final BedrockBossBarController bossBarController;

    // Phase 7.1b — null if the entity has no custom-name (no nametag)
    private final BedrockNametagController bedrockNametagController;

    public FMMEntityData(ModeledEntity modeledEntity, Entity realEntity, String bedrockEntityId, BedrockEntityBridge bridge) {
        this.modeledEntity = modeledEntity;
        this.realEntity = realEntity;
        this.bedrockEntityId = bedrockEntityId;
        this.bridge = bridge;
        this.packetEntity = new PacketEntity(realEntity.getLocation());
        this.sortedAnimationNames = bridge.getAnimationNames(bedrockEntityId);
        this.bossBarController = createBossBarControllerIfElite();
        this.bedrockNametagController = createNametagControllerIfNamed();
    }

    /**
     * Phase 7.1a — if the real entity is an EliteMobs boss with a styled name,
     * create a BossBar controller and register it on the bridge for title-match
     * lookup by the PacketInterceptor. Returns null otherwise.
     */
    private BedrockBossBarController createBossBarControllerIfElite() {
        if (!(realEntity instanceof LivingEntity living)) {
            FMMBedrockBridge.debugLog("[BRIDGE] BossBar skip — realEntity not LivingEntity for " + bedrockEntityId);
            return null;
        }
        if (bridge.getActiveControllers().containsKey(living.getUniqueId())) {
            // FMM re-spawn without a corresponding despawn — skip to avoid orphaning
            // the previous controller's BossBar.
            log.warning("[BRIDGE] Duplicate FMMEntityData for UUID " + living.getUniqueId()
                    + " (" + bedrockEntityId + ") — skipping second BossBar controller creation.");
            return null;
        }
        String styledName = EliteMobsHook.getStyledName(living);
        if (styledName == null) {
            FMMBedrockBridge.debugLog("[BRIDGE] BossBar skip — no styled name for " + bedrockEntityId
                    + " (not an EliteMobs boss or name not yet set)");
            return null;
        }
        BedrockBossBarController controller;
        try {
            controller = new BedrockBossBarController(living, styledName);
        } catch (Exception e) {
            log.warning("[BRIDGE] Failed to create BossBar for " + bedrockEntityId
                    + ": " + e.getMessage() + " — entity will bridge without BossBar.");
            return null;
        }
        bridge.getActiveControllers().put(living.getUniqueId(), controller);
        FMMBedrockBridge.debugLog("[BRIDGE] Created BossBar controller for " + bedrockEntityId
                + " (title='" + styledName + "')");
        return controller;
    }

    /**
     * Phase 7.1b — if a name source is available, spawn a TextDisplay above the mob's
     * head and register a controller. Returns null if no name is available (plugin-agnostic
     * gating).
     *
     * <p><b>Name source priority:</b>
     * <ol>
     *   <li>{@code modeledEntity.getDisplayName()} — set by EM via
     *       {@code CustomModelFMM.setName} which calls {@code dynamicEntity.setDisplayName(...)}.
     *       This is the same string EM uses for FMM's own internal nametag bones (what Java
     *       renders), so for EliteMobs CustomBosses it carries the styled YAML name like
     *       "Tier 13 &9Ice Elemental".</li>
     *   <li>{@code realEntity.customName()} — fallback for non-FMM-named entities (Vanilla
     *       mobs with custom-name, other plugins).</li>
     * </ol>
     *
     * <p>The {@link BedrockNametagController#getTextDisplayEntityId()} is registered with
     * the PacketInterceptor INSIDE the {@code world.spawn} consumer, before Bukkit broadcasts
     * the SPAWN_ENTITY packet. This avoids a race where Java players see the TextDisplay
     * for a single tick before the suppress kicks in.
     */
    private BedrockNametagController createNametagControllerIfNamed() {
        // If Floodgate isn't available there are no Bedrock viewers — spawning a TextDisplay
        // would only result in Java players seeing a duplicate floating text. Skip entirely.
        if (!FMMBedrockBridge.getInstance().isFloodgateAvailable()) {
            FMMBedrockBridge.debugLog("[BRIDGE] Nametag skip — Floodgate unavailable for " + bedrockEntityId);
            return null;
        }

        // Supplier resolves the current name on every tick — FMM displayName is primary
        // (set by EM with the styled YAML name), realEntity.customName() is fallback.
        Supplier<Component> textSource = () -> {
            try {
                String fmmName = modeledEntity.getDisplayName();
                if (fmmName != null && !fmmName.isEmpty()) {
                    return LegacyComponentSerializer.legacySection().deserialize(fmmName);
                }
            } catch (Throwable ignored) {
                // FMM API failure — fall through to customName fallback
            }
            try {
                return realEntity.customName();
            } catch (Throwable ignored) {
                return null;
            }
        };

        Component initialName = textSource.get();
        if (initialName == null) {
            FMMBedrockBridge.debugLog("[BRIDGE] Nametag skip — no name source for " + bedrockEntityId);
            return null;
        }
        if (bridge.getActiveNametags().containsKey(realEntity.getUniqueId())) {
            log.warning("[BRIDGE] Duplicate FMMEntityData for UUID " + realEntity.getUniqueId()
                    + " (" + bedrockEntityId + ") — skipping second Nametag controller creation.");
            return null;
        }

        Location spawnLoc = realEntity.getLocation().clone()
                .add(0, realEntity.getHeight() + BedrockNametagController.Y_OFFSET_PADDING, 0);

        Component finalInitialName = initialName;
        TextDisplay textDisplay;
        try {
            // The lambda runs BEFORE Bukkit fires the spawn packet — so registering the
            // entity-id as java-hidden here means the very first SPAWN_ENTITY broadcast
            // is already filtered for Java players.
            textDisplay = realEntity.getWorld().spawn(spawnLoc, TextDisplay.class, td -> {
                td.text(finalInitialName);
                td.setBillboard(Display.Billboard.CENTER);
                td.setSeeThrough(true);
                td.setDefaultBackground(true);
                bridge.getPacketInterceptor().hideFromJava(td.getEntityId());
            });
        } catch (Exception e) {
            log.warning("[BRIDGE] Failed to spawn TextDisplay nametag for " + bedrockEntityId
                    + ": " + e.getMessage() + " — entity will bridge without nametag.");
            return null;
        }

        BedrockNametagController controller = new BedrockNametagController(
                realEntity, textDisplay, initialName, textSource);
        bridge.getActiveNametags().put(realEntity.getUniqueId(), controller);
        String plainText = PlainTextComponentSerializer.plainText().serialize(initialName);
        FMMBedrockBridge.debugLog("[BRIDGE] Created Nametag controller for " + bedrockEntityId
                + " (text='" + plainText + "', textDisplayId=" + textDisplay.getEntityId() + ")");
        return controller;
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

        log.fine("[BRIDGE] Added viewer " + player.getName()
                + " for " + bedrockEntityId
                + " (fakeId=" + packetEntity.getEntityId()
                + ", realId=" + realEntityId + " destroyed)");

        // 3. Send fake PIG spawn packet AFTER a delay so the plugin message
        //    (setCustomEntity) reaches Geyser's CUSTOM_ENTITIES cache first.
        //    Without this delay, the spawn packet may arrive before the cache is populated.
        Bukkit.getScheduler().runTaskLater(FMMBedrockBridge.getInstance(), () -> {
            if (destroyed || !player.isOnline()) return;
            packetEntity.sendSpawnPacket(player);
            float hitboxHeight = (float) Math.max(realEntity.getHeight(), 0.5);
            float hitboxWidth = (float) Math.max(realEntity.getWidth(), 0.5);
            EntityUtils.sendCustomHitBox(player, packetEntity.getEntityId(), hitboxHeight, hitboxWidth);
            sendNameToViewer(player);
            log.fine("[BRIDGE] Sent spawn packet + hitbox for " + bedrockEntityId
                    + " (fakeId=" + packetEntity.getEntityId() + ") to " + player.getName());

            // Phase 7.1a — add Bedrock viewer to BossBar (if this is an EM boss).
            // Check viewers.contains because removeViewer may have run during the 2-tick
            // delay between the outer addViewer call and this lambda firing — without
            // the check we'd add a phantom viewer to the BossBar that's no longer tracked.
            if (bossBarController != null && viewers.contains(player)) {
                bossBarController.addViewer(player);
            }

            // Send animation property with a second delay: Bedrock must finish loading the entity
            // and initializing its property system before it can process property updates.
            Bukkit.getScheduler().runTaskLater(FMMBedrockBridge.getInstance(), () -> {
                if (destroyed || !player.isOnline() || !viewers.contains(player)) return;
                sendInitialAnimation(player);
            }, 10L);
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

        // Phase 7.1a — remove Bedrock viewer from BossBar
        if (bossBarController != null) {
            bossBarController.removeViewer(player);
        }

        log.fine("[BRIDGE] Removed viewer " + player.getName()
                + " for " + bedrockEntityId);
    }

    @Override
    public void tick() {
        if (destroyed) return;
        syncPosition();
        if (bossBarController != null) {
            bossBarController.tickUpdate();
        }
        if (bedrockNametagController != null) {
            bedrockNametagController.tickUpdate();
        }
    }

    @Override
    public boolean isAlive() {
        return realEntity != null && !realEntity.isDead();
    }

    @Override
    public Location getLocation() {
        return realEntity != null ? realEntity.getLocation() : null;
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
        if (currentAnim == null) {
            // Fallback for NPCs where FMM animation state is unavailable — start idle or first anim
            currentAnim = sortedAnimationNames.contains("idle") ? "idle" : sortedAnimationNames.get(0);
        }

        int animIndex = sortedAnimationNames.indexOf(currentAnim);
        if (animIndex < 0) return;

        int[] bitmask = BedrockAnimationControllerGenerator.getAnimationBitmask(animIndex);
        String propertyId = "fmmbridge:anim" + bitmask[0];
        EntityUtils.sendIntProperty(player, packetEntity.getEntityId(), propertyId, bitmask[1]);
        lastAnimationName = currentAnim;
        log.info("[BRIDGE] Sent initial animation '" + currentAnim + "' idx=" + animIndex
                + " prop=" + propertyId + " val=" + bitmask[1] + " entity=" + bedrockEntityId);
    }

    /**
     * Checks if the FMM animation state changed and sends property updates to Bedrock viewers.
     * Uses GeyserUtils bitmask properties (same approach as GeyserModelEngine).
     */
    private void syncAnimation() {
        if (sortedAnimationNames == null || sortedAnimationNames.isEmpty()) return;

        String currentAnim = AnimationStateTracker.getCurrentAnimationName(modeledEntity);
        if (currentAnim == null) return; // NPCs: syncAnimation skipped, initial state set by sendInitialAnimation

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

        log.fine("[BRIDGE] Animation changed: " + lastAnimationName + " → " + currentAnim
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

        // Phase 7.1a — cleanup BossBar and remove from active controllers map
        if (bossBarController != null) {
            bossBarController.cleanup();
            bridge.getActiveControllers().remove(realEntity.getUniqueId());
            // Note: we don't remove this controller's captured UUIDs from BossBarRegistry
            // here — they're harmless leftover entries that simply suppress packets which
            // EM no longer sends. The registry is fully cleared on plugin shutdown.
        }

        // Phase 7.1b — cleanup Nametag TextDisplay + unregister from Java-suppress
        if (bedrockNametagController != null) {
            bridge.getPacketInterceptor().unhideFromJava(bedrockNametagController.getTextDisplayEntityId());
            bedrockNametagController.cleanup();
            bridge.getActiveNametags().remove(realEntity.getUniqueId());
        }

        viewers.clear();
        log.fine("[BRIDGE] Destroyed entity data for " + bedrockEntityId);
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
            log.fine("[BRIDGE] Sent nametag for " + bedrockEntityId + " to " + player.getName());
        } else {
            // EliteMobs might set the name after a delay — retry once after 1 second
            Bukkit.getScheduler().runTaskLater(FMMBedrockBridge.getInstance(), () -> {
                if (destroyed || !player.isOnline() || !viewers.contains(player)) return;
                Component retryName = getCustomName();
                if (retryName != null) {
                    packetEntity.sendNameMetadata(retryName, true, player);
                    log.fine("[BRIDGE] Sent nametag (retry) for " + bedrockEntityId + " to " + player.getName());
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
