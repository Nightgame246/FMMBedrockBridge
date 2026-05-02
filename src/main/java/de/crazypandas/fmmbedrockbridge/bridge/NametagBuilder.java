package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * Builds the multi-line custom-name component shown above bridged entities on Bedrock.
 *
 * Layout (top to bottom):
 *   Line 1: "current / max" HP, two decimals
 *   Line 2: 10-char healthbar, color tier by HP fraction
 *   Line 3: real entity custom name (set by EliteMobs) or FMM display name fallback
 *
 * For non-LivingEntity sources (Static entities) only the name line is produced.
 */
public final class NametagBuilder {

    private NametagBuilder() {}

    public static Component build(Entity realEntity, ModeledEntity modeledEntity) {
        Component hpLine = buildHpLine(realEntity);
        // Healthbar + name added in later tasks; for now return HP line only or empty
        if (hpLine == null) return Component.empty();
        return hpLine;
    }

    private static Component buildHpLine(Entity realEntity) {
        if (!(realEntity instanceof LivingEntity living)) return null;
        double max = maxHealth(living);
        if (max <= 0) return null;
        double current = Math.max(0, living.getHealth());
        String text = String.format("%.2f / %.2f", current, max);
        return Component.text(text, NamedTextColor.WHITE);
    }

    private static double maxHealth(LivingEntity living) {
        try {
            var attr = living.getAttribute(Attribute.MAX_HEALTH);
            return attr != null ? attr.getValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
