package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.tracker.FMMEntityTracker;
import me.zimzaza4.geyserutils.spigot.api.EntityUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.geysermc.floodgate.api.FloodgateApi;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Bridges FMM modeled entities to Bedrock clients via GeyserUtils.
 */
public class BedrockEntityBridge implements Listener {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();
    private final boolean floodgateAvailable;
    private final boolean geyserUtilsAvailable;
    private final FMMEntityTracker entityTracker;

    // Cached reflection field for ModeledEntity.underlyingEntity (no public getter in FMM API)
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

    public void onEntitySpawn(ModeledEntity modeledEntity) {
        if (!(modeledEntity instanceof DynamicEntity)) return;
        if (!geyserUtilsAvailable) return;

        Entity underlying = getUnderlyingEntity(modeledEntity);
        if (underlying == null) return;

        int javaEntityId = underlying.getEntityId();
        String entityId = getBedrockEntityId(modeledEntity);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isBedrockPlayer(player)) continue;

            EntityUtils.setCustomEntity(player, javaEntityId, entityId);
            log.info("[BRIDGE] Sent custom entity '" + entityId
                    + "' for Java entity " + javaEntityId
                    + " to Bedrock player " + player.getName());
        }
    }

    public void onEntityDespawn(ModeledEntity modeledEntity) {
        // GeyserUtils handles despawn automatically when the Java entity is removed
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!geyserUtilsAvailable) return;

        Player player = event.getPlayer();
        if (!isBedrockPlayer(player)) return;

        for (ModeledEntity entity : entityTracker.getKnownEntities()) {
            Entity underlying = getUnderlyingEntity(entity);
            if (underlying == null) continue;

            String entityId = getBedrockEntityId(entity);
            EntityUtils.setCustomEntity(player, underlying.getEntityId(), entityId);
            log.info("[BRIDGE] Synced custom entity '" + entityId
                    + "' for Java entity " + underlying.getEntityId()
                    + " to Bedrock player " + player.getName());
        }
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
}
