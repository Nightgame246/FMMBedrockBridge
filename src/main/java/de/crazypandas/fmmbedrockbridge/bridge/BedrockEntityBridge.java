package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import me.zimzaza4.geyserutils.spigot.api.EntityUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.lang.reflect.Field;
import java.util.logging.Logger;

/**
 * Bridges FMM DynamicEntity spawns to Bedrock clients via GeyserUtils.
 * Phase 2 PoC: uses vanilla "minecraft:wolf" as placeholder entity definition.
 */
public class BedrockEntityBridge {

    // Placeholder: will be replaced with real custom entity IDs in Phase 3
    private static final String PLACEHOLDER_ENTITY = "minecraft:wolf";

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();
    private final boolean floodgateAvailable;
    private final boolean geyserUtilsAvailable;

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

    public BedrockEntityBridge(boolean floodgateAvailable, boolean geyserUtilsAvailable) {
        this.floodgateAvailable = floodgateAvailable;
        this.geyserUtilsAvailable = geyserUtilsAvailable;
    }

    public void onEntitySpawn(ModeledEntity modeledEntity) {
        if (!(modeledEntity instanceof DynamicEntity)) return;

        Entity underlying = getUnderlyingEntity(modeledEntity);
        if (underlying == null) return;

        int javaEntityId = underlying.getEntityId();
        String entityId = modeledEntity.getEntityID();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isBedrockPlayer(player)) continue;

            EntityUtils.setCustomEntity(player, javaEntityId, PLACEHOLDER_ENTITY);
            log.info("[BRIDGE] Sent custom entity '" + PLACEHOLDER_ENTITY
                    + "' for FMM model '" + entityId
                    + "' to Bedrock player " + player.getName());
        }
    }

    public void onEntityDespawn(ModeledEntity modeledEntity) {
        // GeyserUtils handles despawn automatically when the Java entity is removed
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
}
