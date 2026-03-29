package de.crazypandas.fmmbedrockbridge.tracker;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.StaticEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.bridge.BedrockEntityBridge;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Tracks FMM ModeledEntity spawn/despawn via periodic polling.
 * FMM has no spawn/despawn events, so we diff against ModeledEntityManager.getAllEntities() each tick.
 */
public class FMMEntityTracker extends BukkitRunnable {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();
    private final Set<ModeledEntity> knownEntities = new HashSet<>();
    private final BedrockEntityBridge bridge;

    public FMMEntityTracker(BedrockEntityBridge bridge) {
        this.bridge = bridge;
    }

    public void start() {
        // Poll every 20 ticks (1 second)
        this.runTaskTimer(FMMBedrockBridge.getInstance(), 20L, 20L);
        log.info("FMMEntityTracker started (polling every 1s).");
    }

    @Override
    public void run() {
        Set<ModeledEntity> currentEntities = ModeledEntityManager.getAllEntities();

        // Detect new spawns
        for (ModeledEntity entity : currentEntities) {
            if (!knownEntities.contains(entity)) {
                onEntitySpawn(entity);
            }
        }

        // Detect despawns
        for (ModeledEntity entity : knownEntities) {
            if (!currentEntities.contains(entity)) {
                onEntityDespawn(entity);
            }
        }

        knownEntities.clear();
        knownEntities.addAll(currentEntities);
    }

    private void onEntitySpawn(ModeledEntity entity) {
        String type = getEntityType(entity);
        log.info("[FMM SPAWN] type=" + type
                + " entityID=" + entity.getEntityID()
                + " location=" + formatLocation(entity));
        bridge.onEntitySpawn(entity);
    }

    private void onEntityDespawn(ModeledEntity entity) {
        String type = getEntityType(entity);
        log.info("[FMM DESPAWN] type=" + type
                + " entityID=" + entity.getEntityID());
        bridge.onEntityDespawn(entity);
    }

    private String getEntityType(ModeledEntity entity) {
        if (entity instanceof DynamicEntity) return "DYNAMIC";
        if (entity instanceof StaticEntity) return "STATIC";
        return entity.getClass().getSimpleName();
    }

    private String formatLocation(ModeledEntity entity) {
        try {
            var loc = entity.getLocation();
            if (loc == null) return "null";
            return loc.getWorld().getName()
                    + " " + String.format("%.1f", loc.getX())
                    + " " + String.format("%.1f", loc.getY())
                    + " " + String.format("%.1f", loc.getZ());
        } catch (Exception e) {
            return "unknown";
        }
    }

    public void shutdown() {
        if (!isCancelled()) cancel();
        knownEntities.clear();
        log.info("FMMEntityTracker stopped.");
    }
}
