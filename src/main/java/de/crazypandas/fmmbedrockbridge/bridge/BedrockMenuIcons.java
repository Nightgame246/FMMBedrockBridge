package de.crazypandas.fmmbedrockbridge.bridge;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 7.7 — decides which EliteMobs menu icons are rebased onto paper for Bedrock.
 *
 * <p>EliteMobs puts its menu icons on arbitrary base items (emerald, redstone, barrier, banners) and
 * draws them through the {@code item_model} component. Geyser maps custom items per Java base item,
 * and ResourcePackManager can only guess that base item — for these icons it registers paper (among
 * others), never emerald/barrier, and Geyser cannot override redstone or banners at all. Rebasing the
 * icon onto paper for Bedrock makes RPM's existing mapping apply. Java never sees this.
 *
 * <p>Pure on purpose — no Bukkit, no PacketEvents.
 */
public final class BedrockMenuIcons {

    public static final String PAPER = "minecraft:paper";

    private BedrockMenuIcons() {}

    /**
     * @param baseItemId Java base item, e.g. {@code minecraft:emerald}
     * @param itemModel  the item's {@code item_model}, or {@code null} if it has none
     * @param prefixes   model prefixes that mark a menu icon, e.g. {@code elitemobs:ui/}
     * @param excludes   models mapped under a different base by RPM (crowns under helmets)
     * @return true if the item should be sent to Bedrock with {@link #PAPER} as base item
     */
    public static boolean shouldRebase(String baseItemId, String itemModel,
                                       List<String> prefixes, Set<String> excludes) {
        if (baseItemId == null || itemModel == null) return false;
        if (PAPER.equals(baseItemId.toLowerCase(Locale.ROOT))) return false;
        String model = itemModel.toLowerCase(Locale.ROOT);
        for (String exclude : excludes) {
            if (model.equals(exclude.toLowerCase(Locale.ROOT))) return false;
        }
        for (String prefix : prefixes) {
            if (model.startsWith(prefix.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
