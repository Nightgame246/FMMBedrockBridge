package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Phase 7.1d — the real boss name on the Bedrock nametag instead of "Evoker".
 *
 * <p><b>The gap.</b> EliteMobs spawns every elite with a generic custom name derived from its
 * type ({@code setDefaultName}). For a custom boss with an FMM model it then hands the real YAML
 * name to the model only — {@code setName(name, false)}, the {@code false} meaning "not onto the
 * mob". Java players read it off the model's nametag bone. FMM binds the Bedrock model to the
 * mob underneath ({@code bindToUnderlyingEntity}), so Bedrock shows that mob's custom name, which
 * is still the generic one. Same root as the BossBar title, which the bridge already builds from
 * the FMM display name.
 *
 * <p><b>The fix.</b> Rewrites the custom-name entry (index 2) of the mob's metadata packets for
 * Bedrock viewers only, and sends the name once when a mob is registered — the tracker polls,
 * so players already nearby received the spawn metadata before we knew the mob.
 *
 * <p><b>Built to be removed.</b> Once EliteMobs writes the right name onto the mob, {@link
 * #replacement} sees matching text and changes nothing, so the fix goes quiet by itself. It can
 * also be switched off ({@code phase71d.bedrock-name-fix}). Removing it for good: delete this
 * class, the {@code nameFix} field and its one call in {@link PacketInterceptor}, and the
 * register/update/unregister lines in {@link FMMEntityData}.
 */
public final class BedrockNameFix {

    /** Entity base metadata: custom name, {@code Optional<Component>}. */
    static final int CUSTOM_NAME_INDEX = 2;

    private static final Pattern FORMATTING = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** Entity id → the name Bedrock should see. Written on the main thread, read on Netty. */
    private final Map<Integer, String> names = new ConcurrentHashMap<>();
    /** Debug: one line per mob, not one per packet. */
    private final Set<Integer> logged = ConcurrentHashMap.newKeySet();

    /**
     * Pure decision: what to put on the nametag, or {@code null} to leave the packet alone.
     *
     * @param current the custom name in the packet (legacy §-format), {@code null} if none
     * @param desired the FMM display name, i.e. what Java players see
     */
    static String replacement(String current, String desired) {
        if (current == null) return null;              // never add a name that is not there
        if (desired == null || desired.isBlank()) return null;
        if (plain(current).equals(plain(desired))) return null; // right already — upstream fixed
        return desired;
    }

    private static String plain(String legacy) {
        return FORMATTING.matcher(legacy).replaceAll("").trim();
    }

    public static boolean isEnabled() {
        return FMMBedrockBridge.getInstance() != null
                && FMMBedrockBridge.getInstance().getConfig().getBoolean("phase71d.bedrock-name-fix", true);
    }

    /**
     * Records the name for a mob; returns true when it changed, so the caller can push it to
     * players who already see the mob.
     */
    public boolean update(int entityId, String desired) {
        if (desired == null || desired.isBlank()) return names.remove(entityId) != null;
        return !desired.equals(names.put(entityId, desired));
    }

    public void unregister(int entityId) {
        names.remove(entityId);
        logged.remove(entityId);
    }

    public void clear() {
        names.clear();
        logged.clear();
    }

    /** Netty thread. Only called for Bedrock viewers. */
    void onMetadata(PacketSendEvent event) {
        if (names.isEmpty() || !isEnabled()) return;
        WrapperPlayServerEntityMetadata wrapper;
        try {
            wrapper = new WrapperPlayServerEntityMetadata(event);
        } catch (Throwable t) {
            return;
        }
        String desired = names.get(wrapper.getEntityId());
        if (desired == null) return;

        List<EntityData<?>> data = wrapper.getEntityMetadata();
        boolean changed = false;
        for (EntityData<?> entry : data) {
            if (entry.getIndex() != CUSTOM_NAME_INDEX) continue;
            if (!(entry.getValue() instanceof Optional<?> value)) continue;
            if (value.isEmpty() || !(value.get() instanceof Component name)) continue;
            String replacement = replacement(LEGACY.serialize(name), desired);
            if (replacement == null) continue;
            @SuppressWarnings("unchecked")
            EntityData<Optional<Component>> nameEntry = (EntityData<Optional<Component>>) entry;
            nameEntry.setValue(Optional.of(LEGACY.deserialize(replacement)));
            changed = true;
            if (logged.add(wrapper.getEntityId())) {
                FMMBedrockBridge.debugLog("[PHASE71D] entity " + wrapper.getEntityId() + ": '"
                        + LEGACY.serialize(name) + "' -> '" + replacement + "' for Bedrock");
            }
        }
        if (changed) {
            wrapper.setEntityMetadata(data);
            event.markForReEncode(true);
        }
    }

    /**
     * Sends the name to one Bedrock viewer directly. For viewers who got the spawn metadata before
     * the mob was registered, and after the name changes; the packet then passes {@link
     * #onMetadata} like any other, so the upstream check still applies there.
     */
    public void sendTo(Player player, int entityId, String currentCustomName) {
        String desired = names.get(entityId);
        if (desired == null || !isEnabled()) return;
        if (replacement(currentCustomName, desired) == null) return;
        try {
            WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(entityId,
                    List.of(new EntityData<>(CUSTOM_NAME_INDEX, EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                            Optional.of(LEGACY.deserialize(desired)))));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
            FMMBedrockBridge.debugLog("[PHASE71D] sent '" + desired + "' for entity " + entityId
                    + " to " + player.getName());
        } catch (Throwable t) {
            FMMBedrockBridge.debugLog("[PHASE71D] name send failed: " + t);
        }
    }
}
