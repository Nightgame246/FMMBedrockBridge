package de.crazypandas.fmmbedrockbridge.elite;

import com.magmaguy.elitemobs.entitytracker.EntityTracker;
import com.magmaguy.elitemobs.mobconstructor.EliteEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;

import java.util.logging.Logger;

/**
 * Soft-dependency wrapper around EliteMobs API. The only class in this codebase
 * that imports {@code com.magmaguy.elitemobs.*}. All public methods return null
 * (or false) when EliteMobs is not installed or its API broke between versions —
 * callers must null-check rather than relying on exceptions.
 */
public final class EliteMobsHook {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    private static final boolean PLUGIN_PRESENT;
    private static volatile boolean apiBroken = false;

    static {
        PLUGIN_PRESENT = Bukkit.getPluginManager().getPlugin("EliteMobs") != null;
        if (!PLUGIN_PRESENT) {
            log.info("[BRIDGE] EliteMobs not detected — BossBar replacement disabled.");
        }
    }

    private EliteMobsHook() {}

    /** True if EliteMobs is installed and its API has not failed yet. */
    public static boolean isAvailable() {
        return PLUGIN_PRESENT && !apiBroken;
    }

    /**
     * Returns the EliteEntity wrapper for a LivingEntity, or null if the entity
     * is not an EliteMobs-tracked elite, or if EliteMobs is unavailable.
     */
    public static EliteEntity getEliteEntity(LivingEntity entity) {
        if (!isAvailable() || entity == null) return null;
        try {
            return EntityTracker.getEliteMobEntity(entity);
        } catch (Throwable t) {
            markApiBroken(t);
            return null;
        }
    }

    /**
     * Returns the styled name (e.g. "Tier 5 Elder Alphawolf") for an EliteMobs
     * boss, or null if the entity is not EM-tracked or EM is unavailable.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code eliteEntity.getName()} — EliteMobs' canonical styled name from the
     *       YAML config. EM 10.3.1 (verified 2026-05-24) returns the proper styled name
     *       for all CustomBoss types via this API.</li>
     *   <li>{@code livingEntity.getCustomName()} — fallback for non-EM-tracked entities
     *       (vanilla / non-EM-named).</li>
     * </ol>
     *
     * <p><b>History:</b> Phase 7.1a (2026-05-03, EM 10.2.0) had customName as primary
     * after observing EVOKER-based CustomBosses returning "Evoker | 2" from eliteEntity.getName().
     * Reversed 2026-05-24 (EM 10.3.1) because customName now returns the Vanilla "Evoker | 2"
     * format for the same EVOKER-based bosses, while eliteEntity.getName() returns the styled
     * YAML name. customName remains as fallback for non-EM-tracked entities.
     */
    public static String getStyledName(LivingEntity entity) {
        if (entity == null) return null;

        // Primary: EliteMobs canonical styled name
        if (isAvailable()) {
            EliteEntity elite = getEliteEntity(entity);
            if (elite != null) {
                try {
                    String name = elite.getName();
                    if (name != null && !name.isEmpty()) return name;
                } catch (Throwable t) {
                    markApiBroken(t);
                }
            }
        }

        // Fallback: LivingEntity customName (non-EM-tracked entities)
        String customName = entity.getCustomName();
        return (customName == null || customName.isEmpty()) ? null : customName;
    }

    private static void markApiBroken(Throwable t) {
        if (apiBroken) return;
        apiBroken = true;
        log.warning("[BRIDGE] EliteMobs API call failed — disabling BossBar replacement. Cause: " + t);
    }
}
