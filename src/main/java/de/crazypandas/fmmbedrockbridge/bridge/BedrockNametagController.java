package de.crazypandas.fmmbedrockbridge.bridge;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Phase 7.1b — Lifecycle controller for one FMM mob's Bedrock-only nametag.
 *
 * <p>Holds a single Bukkit {@link TextDisplay} entity spawned above the FMM mob.
 * Position and text are synced each tick via {@link #tickUpdate()}. Java players
 * never see the TextDisplay because {@link PacketInterceptor#hideFromJava(int)}
 * suppresses its packets for non-Floodgate players — Java already shows the
 * vanilla custom-name nametag on the real entity, so a second display would
 * just produce visual duplication.
 *
 * <p>Threading: all public methods must be called from the Bukkit main thread.
 */
public final class BedrockNametagController {

    /**
     * Vertical offset above the entity's feet for the nametag (above the head).
     * Public so {@code FMMEntityData.createNametagControllerIfNamed} can use the
     * same value when computing the initial spawn location.
     */
    public static final double Y_OFFSET_PADDING = 0.3;

    private final UUID realEntityUuid;
    private final Entity realEntity;
    private final TextDisplay textDisplay;
    private final Supplier<Component> textSource;
    private Component lastText;

    /**
     * @param realEntity the FMM mob whose position the nametag tracks
     * @param textDisplay the Bukkit TextDisplay entity already spawned by the caller
     * @param initialText the initial text (also used as the first {@code lastText} cache value)
     * @param textSource a supplier called each tick to get the current desired text. Returns
     *                   null if no text source is available — the supplier is the source of
     *                   truth for the styled name. The caller wires this to
     *                   {@code modeledEntity.getDisplayName()} (FMM's own nametag pipeline,
     *                   set by EM via CustomModelFMM.setName) with a fallback to
     *                   {@code realEntity.customName()}.
     */
    public BedrockNametagController(Entity realEntity, TextDisplay textDisplay,
                                    Component initialText, Supplier<Component> textSource) {
        this.realEntityUuid = realEntity.getUniqueId();
        this.realEntity = realEntity;
        this.textDisplay = textDisplay;
        this.textSource = textSource;
        this.lastText = initialText;
    }

    public UUID getRealEntityUuid() {
        return realEntityUuid;
    }

    /** The Bukkit entity-id of the TextDisplay — used by PacketInterceptor for Java-suppress. */
    public int getTextDisplayEntityId() {
        return textDisplay.getEntityId();
    }

    /** Current text on the TextDisplay (last sent value). */
    public Component getCurrentText() {
        return lastText;
    }

    /**
     * Each tick:
     * <ul>
     *   <li>Skip if the real entity or the TextDisplay is dead/invalid.</li>
     *   <li>Teleport the TextDisplay to {@code realEntity.location + (0, height + 0.3, 0)}.
     *       Bukkit deduplicates ENTITY_TELEPORT packets when position is unchanged.</li>
     *   <li>Fetch the current text via {@code textSource.get()} (typically
     *       {@code modeledEntity.getDisplayName()}); update the TextDisplay only when it
     *       differs from the last sent value. Null/empty supplier results render as
     *       {@link Component#empty()}.</li>
     * </ul>
     */
    public void tickUpdate() {
        if (realEntity == null || realEntity.isDead() || !realEntity.isValid()) return;
        if (!textDisplay.isValid()) return;

        // Position sync
        Location target = realEntity.getLocation().clone().add(0, realEntity.getHeight() + Y_OFFSET_PADDING, 0);
        textDisplay.teleport(target);

        // Text sync — only when changed, to avoid pointless METADATA churn
        Component current;
        try {
            current = textSource.get();
        } catch (Throwable t) {
            current = null;
        }
        if (current == null) {
            current = Component.empty();
        }
        if (!current.equals(lastText)) {
            textDisplay.text(current);
            lastText = current;
        }
    }

    /** Removes the TextDisplay entity. Caller is responsible for unregistering from PacketInterceptor. */
    public void cleanup() {
        if (textDisplay.isValid()) {
            textDisplay.remove();
        }
    }
}
