package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Locale;

/**
 * Phase 7.1c — Pure utility for composing the nametag Component shown above
 * a bridged FMM mob on Bedrock.
 *
 * <p>Layout depends on {@code inCombat}:
 * <ul>
 *   <li>{@code inCombat == false}: 1 line — {@link #buildNameLine name only}</li>
 *   <li>{@code inCombat == true}: 3 lines — HP-number / health-bar / name</li>
 * </ul>
 *
 * <p>Name source (same as Phase 7.1b): {@code modeledEntity.getDisplayName()}
 * primary (parsed via legacy §-codes), {@code realEntity.customName()} fallback.
 *
 * <p>Health-bar: 10 chars, '█' filled (green ≥66% / yellow ≥33% / red &lt;33%),
 * '░' empty (dark-gray). Never fully empty unless dead.
 *
 * <p>HP number: "current / max" formatted to 2 decimals, white.
 */
public final class NametagTextBuilder {

    private static final int BAR_LENGTH = 10;
    private static final char GLYPH_FILLED = '█';
    private static final char GLYPH_EMPTY = '░';
    private static final double GREEN_THRESHOLD = 0.66;
    private static final double YELLOW_THRESHOLD = 0.33;

    private NametagTextBuilder() {}

    /**
     * Composes the Component for the TextDisplay text. Never returns null —
     * empty Component if everything is missing.
     */
    public static Component compose(Entity realEntity, ModeledEntity modeledEntity, boolean inCombat) {
        Component nameLine = buildNameLine(realEntity, modeledEntity);

        if (!inCombat || !(realEntity instanceof LivingEntity living)) {
            return nameLine != null ? nameLine : Component.empty();
        }

        Component hpLine = buildHpLine(living);
        Component barLine = buildBarLine(living);
        if (hpLine == null || barLine == null) {
            // No health attribute — fall back to name-only
            return nameLine != null ? nameLine : Component.empty();
        }

        Component out = hpLine.append(Component.newline()).append(barLine);
        if (nameLine != null) {
            out = out.append(Component.newline()).append(nameLine);
        }
        return out;
    }

    private static Component buildNameLine(Entity realEntity, ModeledEntity modeledEntity) {
        // FMM displayName primary (set by EM via customModel.setName → dynamicEntity.setDisplayName)
        try {
            String fmmName = modeledEntity.getDisplayName();
            if (fmmName != null && !fmmName.isEmpty()) {
                return LegacyComponentSerializer.legacySection().deserialize(fmmName);
            }
        } catch (Throwable ignored) {}
        // Fallback: realEntity customName (vanilla path / non-FMM-named entities)
        try {
            return realEntity.customName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Component buildHpLine(LivingEntity living) {
        double max = maxHealth(living);
        if (max <= 0) return null;
        double current = Math.max(0, living.getHealth());
        String text = String.format(Locale.ROOT, "%.2f / %.2f", current, max);
        return Component.text(text, NamedTextColor.WHITE);
    }

    private static Component buildBarLine(LivingEntity living) {
        double max = maxHealth(living);
        if (max <= 0) return null;
        double current = Math.max(0, living.getHealth());
        double fraction = Math.min(1.0, current / max);

        int filled = (int) Math.round(fraction * BAR_LENGTH);
        if (current > 0 && filled == 0) filled = 1; // never fully empty unless dead

        NamedTextColor filledColor;
        if (fraction >= GREEN_THRESHOLD) filledColor = NamedTextColor.GREEN;
        else if (fraction >= YELLOW_THRESHOLD) filledColor = NamedTextColor.YELLOW;
        else filledColor = NamedTextColor.RED;

        StringBuilder filledPart = new StringBuilder(filled);
        for (int i = 0; i < filled; i++) filledPart.append(GLYPH_FILLED);
        StringBuilder emptyPart = new StringBuilder(BAR_LENGTH - filled);
        for (int i = 0; i < BAR_LENGTH - filled; i++) emptyPart.append(GLYPH_EMPTY);

        return Component.text(filledPart.toString(), filledColor)
                .append(Component.text(emptyPart.toString(), NamedTextColor.DARK_GRAY));
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
