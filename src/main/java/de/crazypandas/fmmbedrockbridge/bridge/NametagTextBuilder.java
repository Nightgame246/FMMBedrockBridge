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
 *   <li>{@code inCombat == false}: empty — FMM 2.6.0 renders the name natively for Bedrock,
 *       no need for our overlay (avoids the double-name we'd otherwise see)</li>
 *   <li>{@code inCombat == true}: 2 lines — HP-number / health-bar (name is FMM-native)</li>
 * </ul>
 *
 * <p>{@link #hasNameSource} reports whether the mob has any displayable name — used
 * by FMMEntityData to decide whether to spawn a TextDisplay at all (we only need one
 * if there's HP to show in combat, and that's gated by the mob being a LivingEntity).
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
     * empty Component when out-of-combat (FMM renders the name natively).
     */
    public static Component compose(Entity realEntity, ModeledEntity modeledEntity, boolean inCombat) {
        if (!inCombat || !(realEntity instanceof LivingEntity living)) {
            return Component.empty();
        }
        Component hpLine = buildHpLine(living);
        Component barLine = buildBarLine(living);
        if (hpLine == null || barLine == null) {
            return Component.empty();
        }
        return hpLine.append(Component.newline()).append(barLine);
    }

    /**
     * True if the mob has a displayable name (FMM displayName or vanilla customName).
     * Used by FMMEntityData to gate TextDisplay spawn — un-named mobs (e.g. neutral
     * villagers) get no Bridge nametag.
     */
    public static boolean hasNameSource(Entity realEntity, ModeledEntity modeledEntity) {
        Component name = buildNameLine(realEntity, modeledEntity);
        return name != null && !name.equals(Component.empty());
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
