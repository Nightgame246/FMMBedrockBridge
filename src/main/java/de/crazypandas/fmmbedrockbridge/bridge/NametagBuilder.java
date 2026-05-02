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
        Component barLine = buildBarLine(realEntity);
        Component nameLine = buildNameLine(realEntity, modeledEntity);

        // Compose with newlines, skipping null lines so static entities still get a clean name.
        Component out = null;
        if (hpLine != null)   out = hpLine;
        if (barLine != null)  out = out == null ? barLine  : out.append(Component.newline()).append(barLine);
        if (nameLine != null) out = out == null ? nameLine : out.append(Component.newline()).append(nameLine);

        return out != null ? out : Component.empty();
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

    /** Total length of the bar in characters. */
    private static final int BAR_LENGTH = 10;
    /** Filled glyph (vertical block). Renders on Bedrock + Java without extra fonts. */
    private static final char GLYPH_FILLED = '█';
    /** Empty glyph (light shade). */
    private static final char GLYPH_EMPTY = '░';

    /**
     * Builds the healthbar line. Returns null if HP information isn't available
     * (skips that line entirely without breaking the rest of the nametag).
     */
    private static Component buildBarLine(Entity realEntity) {
        if (!(realEntity instanceof LivingEntity living)) return null;
        double max = maxHealth(living);
        if (max <= 0) return null;
        double current = Math.max(0, living.getHealth());
        double fraction = Math.min(1.0, current / max);

        int filled = (int) Math.round(fraction * BAR_LENGTH);
        if (current > 0 && filled == 0) filled = 1; // never show fully empty unless dead

        NamedTextColor filledColor;
        if (fraction >= 0.66) filledColor = NamedTextColor.GREEN;
        else if (fraction >= 0.33) filledColor = NamedTextColor.YELLOW;
        else filledColor = NamedTextColor.RED;

        StringBuilder filledPart = new StringBuilder(filled);
        for (int i = 0; i < filled; i++) filledPart.append(GLYPH_FILLED);
        StringBuilder emptyPart = new StringBuilder(BAR_LENGTH - filled);
        for (int i = 0; i < BAR_LENGTH - filled; i++) emptyPart.append(GLYPH_EMPTY);

        return Component.text(filledPart.toString(), filledColor)
                .append(Component.text(emptyPart.toString(), NamedTextColor.DARK_GRAY));
    }

    /**
     * Returns the bottom name line. Priority: real entity custom name (set by EliteMobs)
     * → FMM display name → null (no line).
     */
    private static Component buildNameLine(Entity realEntity, ModeledEntity modeledEntity) {
        if (realEntity != null) {
            try {
                Component name = realEntity.customName();
                if (name != null) return name;
            } catch (Throwable ignored) {}
        }
        if (modeledEntity != null) {
            try {
                String fmmName = modeledEntity.getDisplayName();
                if (fmmName != null && !fmmName.isEmpty()) {
                    return Component.text(fmmName);
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
