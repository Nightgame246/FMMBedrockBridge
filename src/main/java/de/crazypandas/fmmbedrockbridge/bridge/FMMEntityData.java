package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.elite.EliteMobsHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Holds the BossBar + Nametag controllers for a single FMM modeled entity.
 *
 * <p>Mob/static rendering is FMM 2.6.0 native — this class no longer spawns fake entities
 * or hides the real entity from Bedrock viewers. It only manages the UX layer
 * (BossBar suppression + replacement, combat-triggered 3-line nametag) that FMM doesn't
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

    // Phase 7.1b — null if the entity has no custom-name (no nametag)
    private final BedrockNametagController bedrockNametagController;

    public FMMEntityData(ModeledEntity modeledEntity, Entity realEntity, BedrockEntityBridge bridge) {
        this.modeledEntity = modeledEntity;
        this.realEntity = realEntity;
        this.bridge = bridge;
        this.bossBarController = createBossBarControllerIfElite();
        this.bedrockNametagController = createNametagControllerIfNamed();
    }

    private BedrockBossBarController createBossBarControllerIfElite() {
        if (!(realEntity instanceof LivingEntity living)) return null;
        if (bridge.getActiveControllers().containsKey(living.getUniqueId())) {
            log.warning("[BRIDGE] Duplicate FMMEntityData for UUID " + living.getUniqueId()
                    + " — skipping second BossBar controller creation.");
            return null;
        }

        // Primary: FMM displayName — set by EM via CustomModelFMM.setName, this is the YAML
        // styled name (e.g. "§3[13]§e Eis-Elementar") and what Java renders as Mob-Nametag.
        // Fallback: EliteMobsHook (eliteEntity.getName() → customName) for non-FMM-named entities.
        //
        // EM 10.3.1 EVOKER-based CustomBosses (e.g. Ice Elemental) return "Evoker | 2" from BOTH
        // eliteEntity.getName() AND customName, but FMM displayName has the correct styled name.
        String styledName = null;
        try {
            String fmmName = modeledEntity.getDisplayName();
            if (fmmName != null && !fmmName.isEmpty()) styledName = fmmName;
        } catch (Throwable ignored) {}
        if (styledName == null) styledName = EliteMobsHook.getStyledName(living);
        if (styledName == null) return null;

        BedrockBossBarController controller;
        try {
            controller = new BedrockBossBarController(living, styledName);
        } catch (Exception e) {
            log.warning("[BRIDGE] Failed to create BossBar: " + e.getMessage());
            return null;
        }
        bridge.getActiveControllers().put(living.getUniqueId(), controller);
        FMMBedrockBridge.debugLog("[BRIDGE] Created BossBar controller (title='" + styledName + "')");
        return controller;
    }

    private BedrockNametagController createNametagControllerIfNamed() {
        if (!FMMBedrockBridge.getInstance().isFloodgateAvailable()) return null;

        // Gate on name-source (not on initial text — initial is empty by design out-of-combat).
        // Mobs without a displayable name get no Bridge nametag overlay at all.
        if (!NametagTextBuilder.hasNameSource(realEntity, modeledEntity)) return null;
        if (bridge.getActiveNametags().containsKey(realEntity.getUniqueId())) {
            log.warning("[BRIDGE] Duplicate FMMEntityData nametag — skipping.");
            return null;
        }

        // Initial text = empty: out-of-combat the TextDisplay shows nothing and FMM's
        // native nametag is the sole name visible. On combat-enter the controller
        // switches to a 2-line HP/Bar layout above FMM's name.
        Component initialName = NametagTextBuilder.compose(realEntity, modeledEntity, false);

        Location spawnLoc = realEntity.getLocation().clone()
                .add(0, realEntity.getHeight() + BedrockNametagController.Y_OFFSET_PADDING, 0);

        Component finalInitialName = initialName;
        TextDisplay textDisplay;
        try {
            textDisplay = realEntity.getWorld().spawn(spawnLoc, TextDisplay.class, td -> {
                td.text(finalInitialName);
                td.setBillboard(Display.Billboard.CENTER);
                td.setSeeThrough(true);
                td.setDefaultBackground(true);
                bridge.getPacketInterceptor().hideFromJava(td.getEntityId());
            });
        } catch (Exception e) {
            log.warning("[BRIDGE] Failed to spawn TextDisplay nametag: " + e.getMessage());
            return null;
        }

        BedrockNametagController controller = new BedrockNametagController(
                realEntity, modeledEntity, textDisplay, initialName);
        bridge.getActiveNametags().put(realEntity.getUniqueId(), controller);
        FMMBedrockBridge.debugLog("[BRIDGE] Created Nametag controller (textDisplayId="
                + textDisplay.getEntityId() + ", initial=empty until combat)");
        return controller;
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
        if (bedrockNametagController != null) bedrockNametagController.tickUpdate();
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

        if (bedrockNametagController != null) {
            bridge.getPacketInterceptor().unhideFromJava(bedrockNametagController.getTextDisplayEntityId());
            bedrockNametagController.cleanup();
            bridge.getActiveNametags().remove(realEntity.getUniqueId());
        }

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
