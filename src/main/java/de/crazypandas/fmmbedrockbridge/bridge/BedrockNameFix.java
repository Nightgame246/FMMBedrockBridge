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
 * Phase 7.1d — one name above a modelled boss on Bedrock, not two.
 *
 * <p><b>The duplicate.</b> FMM draws its own nameplate above the model, and its {@code
 * StackedText} does so for Bedrock viewers as well (per-viewer rows, Bedrock only gets a different
 * scale). FMM also binds the Bedrock model to the mob underneath ({@code bindToUnderlyingEntity}),
 * and Bedrock shows that mob's custom name on top. For an EliteMobs custom boss that name is the
 * generic one from the mob type ("Evoker | 2"), because EliteMobs hands the real name to the model
 * only ({@code setName(name, false)}).
 *
 * <p>The first version of this fix (27.09.2026) renamed the mob — and turned "real name + Evoker"
 * into the real name twice, measured in-game on 30.09. The mob name is therefore <b>hidden</b>
 * for Bedrock viewers whenever the model carries a real name of its own; FMM's nameplate is then
 * the only one left, exactly as on Java, where the mob itself is invisible.
 *
 * <p>This stays right if EliteMobs ever writes the real name onto the mob too: both would still
 * show. It becomes wrong only if FMM stops drawing its nameplate for Bedrock — then switch it off
 * ({@code phase71d.bedrock-name-fix: false}) or remove it: delete this class, the {@code nameFix}
 * field and its one call in {@link PacketInterceptor}, and the {@code refreshBedrockName} lines in
 * {@link FMMEntityData}.
 */
public final class BedrockNameFix {

    /** Entity base metadata: custom name, {@code Optional<Component>}. */
    static final int CUSTOM_NAME_INDEX = 2;

    /** What FMM reports for a model nobody named ({@code SkeletonBlueprint.modelName}). */
    static final String FMM_PLACEHOLDER = "Default Name";

    private static final Pattern FORMATTING = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** Entity id → the model's name. Written on the main thread, read on Netty. */
    private final Map<Integer, String> modelNames = new ConcurrentHashMap<>();
    /** Debug: one line per mob, not one per packet. */
    private final Set<Integer> logged = ConcurrentHashMap.newKeySet();

    /**
     * Pure decision: hide the mob's own name for Bedrock?
     *
     * @param mobName   the mob's custom name (legacy §-format), {@code null} if none
     * @param modelName the FMM display name, i.e. what FMM's nameplate shows
     */
    static boolean hideMobName(String mobName, String modelName) {
        if (mobName == null) return false;             // nothing there, nothing doubled
        if (modelName == null) return false;
        String model = plain(modelName);
        return !model.isEmpty() && !model.equals(FMM_PLACEHOLDER);
    }

    private static String plain(String legacy) {
        return FORMATTING.matcher(legacy).replaceAll("").trim();
    }

    public static boolean isEnabled() {
        return FMMBedrockBridge.getInstance() != null
                && FMMBedrockBridge.getInstance().getConfig().getBoolean("phase71d.bedrock-name-fix", true);
    }

    /**
     * Records the model name for a mob; returns true when it changed, so the caller can push the
     * result to players who already see the mob.
     */
    public boolean update(int entityId, String modelName) {
        if (modelName == null || modelName.isBlank()) return modelNames.remove(entityId) != null;
        return !modelName.equals(modelNames.put(entityId, modelName));
    }

    public void unregister(int entityId) {
        modelNames.remove(entityId);
        logged.remove(entityId);
    }

    public void clear() {
        modelNames.clear();
        logged.clear();
    }

    /** Netty thread. Only called for Bedrock viewers. */
    void onMetadata(PacketSendEvent event) {
        if (modelNames.isEmpty() || !isEnabled()) return;
        WrapperPlayServerEntityMetadata wrapper;
        try {
            wrapper = new WrapperPlayServerEntityMetadata(event);
        } catch (Throwable t) {
            return;
        }
        String modelName = modelNames.get(wrapper.getEntityId());
        if (modelName == null) return;

        List<EntityData<?>> data = wrapper.getEntityMetadata();
        boolean changed = false;
        for (EntityData<?> entry : data) {
            if (entry.getIndex() != CUSTOM_NAME_INDEX) continue;
            if (!(entry.getValue() instanceof Optional<?> value)) continue;
            if (value.isEmpty() || !(value.get() instanceof Component name)) continue;
            String mobName = LEGACY.serialize(name);
            if (!hideMobName(mobName, modelName)) continue;
            @SuppressWarnings("unchecked")
            EntityData<Optional<Component>> nameEntry = (EntityData<Optional<Component>>) entry;
            nameEntry.setValue(Optional.empty());
            changed = true;
            if (logged.add(wrapper.getEntityId())) {
                FMMBedrockBridge.debugLog("[PHASE71D] entity " + wrapper.getEntityId() + ": hid '"
                        + mobName + "' for Bedrock (model shows '" + modelName + "')");
            }
        }
        if (changed) {
            wrapper.setEntityMetadata(data);
            event.markForReEncode(true);
        }
    }

    /**
     * Clears the mob name for one Bedrock viewer directly — for viewers who got the spawn metadata
     * before the tracker registered the mob. The packet then passes {@link #onMetadata} like any
     * other.
     */
    public void sendTo(Player player, int entityId, String mobName) {
        String modelName = modelNames.get(entityId);
        if (modelName == null || !isEnabled()) return;
        if (!hideMobName(mobName, modelName)) return;
        try {
            WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(entityId,
                    List.of(new EntityData<>(CUSTOM_NAME_INDEX, EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                            Optional.<Component>empty())));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
            FMMBedrockBridge.debugLog("[PHASE71D] hid mob name of entity " + entityId
                    + " for " + player.getName());
        } catch (Throwable t) {
            FMMBedrockBridge.debugLog("[PHASE71D] clear failed: " + t);
        }
    }
}
