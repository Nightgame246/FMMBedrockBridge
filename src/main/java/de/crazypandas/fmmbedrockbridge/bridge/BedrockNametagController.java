package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import java.util.UUID;

/**
 * Phase 7.1b/7.1c — Lifecycle controller for one FMM mob's Bedrock-only nametag.
 *
 * <p>Holds a single Bukkit {@link TextDisplay} entity spawned above the FMM mob.
 * Position and text are synced each tick via {@link #tickUpdate()}. Java players
 * never see the TextDisplay because {@link PacketInterceptor#hideFromJava(int)}
 * suppresses its packets for non-Floodgate players — Java already shows the
 * vanilla custom-name nametag on the real entity.
 *
 * <p>Phase 7.1c — text content depends on {@link #isInCombat}:
 * <ul>
 *   <li>out-of-combat: empty (FMM renders the name natively)</li>
 *   <li>in-combat: 2 lines (HP-number / health-bar)</li>
 * </ul>
 * Combat state is toggled by {@code BedrockCombatTrigger} via {@link #setCombatState}.
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
    private final ModeledEntity modeledEntity;
    private final TextDisplay textDisplay;
    private Component lastText;
    private boolean isInCombat = false;

    /**
     * @param realEntity the FMM mob whose position the nametag tracks
     * @param modeledEntity the FMM ModeledEntity wrapper (used for displayName resolution)
     * @param textDisplay the Bukkit TextDisplay entity already spawned by the caller
     * @param initialText the initial text (also used as the first {@code lastText} cache value)
     */
    public BedrockNametagController(Entity realEntity, ModeledEntity modeledEntity,
                                    TextDisplay textDisplay, Component initialText) {
        this.realEntityUuid = realEntity.getUniqueId();
        this.realEntity = realEntity;
        this.modeledEntity = modeledEntity;
        this.textDisplay = textDisplay;
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

    /** Phase 7.1c — true if currently in combat (HP overlay visible). */
    public boolean isInCombat() {
        return isInCombat;
    }

    /**
     * Phase 7.1c — toggle combat-state. {@code tickUpdate} re-evaluates the text on the
     * next tick, so the UI catches up within ~2 ticks of state change.
     */
    public void setCombatState(boolean inCombat) {
        this.isInCombat = inCombat;
    }

    /**
     * Each tick:
     * <ul>
     *   <li>Skip if the real entity or the TextDisplay is dead/invalid.</li>
     *   <li>Teleport the TextDisplay to {@code realEntity.location + (0, height + 0.3, 0)}.
     *       Bukkit deduplicates ENTITY_TELEPORT packets when position is unchanged.</li>
     *   <li>Recompute text via {@link NametagTextBuilder#compose}; update the TextDisplay
     *       only when it differs from the last sent value.</li>
     * </ul>
     */
    public void tickUpdate() {
        if (realEntity == null || realEntity.isDead() || !realEntity.isValid()) return;
        if (!textDisplay.isValid()) return;

        // Position sync
        Location target = realEntity.getLocation().clone().add(0, realEntity.getHeight() + Y_OFFSET_PADDING, 0);
        textDisplay.teleport(target);

        // Text sync — only when changed, to avoid pointless METADATA churn.
        // Defensive try/catch: compose() reaches into FMM/Bukkit APIs, and an unhandled
        // throw here would otherwise propagate into the scheduled tick task and spam
        // a stacktrace per tick. Fall back to lastText on failure.
        Component current;
        try {
            current = NametagTextBuilder.compose(realEntity, modeledEntity, isInCombat);
        } catch (Throwable t) {
            current = lastText;
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
