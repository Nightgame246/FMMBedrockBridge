package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * PacketEvents listener that:
 * 1. Suppresses spawn/metadata packets for real entities hidden from Bedrock players
 * 2. Redirects interact packets from fake entities to real entities
 */
public class PacketInterceptor {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    private final Map<Integer, Set<Player>> hiddenEntityIds = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> fakeToRealEntityId = new ConcurrentHashMap<>();

    // Phase 7.2: (javaMaterial, cmd) → bedrockKey mapping. For Bedrock players we
    // inject item_model = geyser_custom:<bedrockKey> on matching items so Geyser's
    // CustomItemTranslator finds our v2 CustomItemDefinition (registered with
    // def.model() = bedrockId, no predicates). 1:1 deterministic mapping; no
    // predicate evaluation needed on the Geyser side.
    private volatile Map<String, Map<Integer, String>> emItemModelMap = Collections.emptyMap();
    // Phase 7.2c: (javaMaterial, item_model identifier) → bedrockKey for 3D gear.
    // EM gear items have item_model = elitemobs:gear/<name> but no custom_model_data,
    // so the CMD-based path doesn't apply. We overwrite item_model with our bedrockId
    // by matching on the EM identifier.
    private volatile Map<String, Map<String, String>> emGearModelMap = Collections.emptyMap();
    private final AtomicInteger injectCount = new AtomicInteger();
    private final AtomicInteger gearInjectCount = new AtomicInteger();

    /**
     * Phase 7.1b — entity IDs that must be hidden from ALL Java (non-Floodgate) players.
     * Used for our auxiliary TextDisplay nametags: Java players already see the vanilla
     * custom-name nametag on the real entity, so showing them our TextDisplay would create
     * a visible duplicate.
     */
    private final Set<Integer> javaHiddenEntityIds = ConcurrentHashMap.newKeySet();

    private PacketListenerAbstract listener;

    // Phase 7.1a — back-reference for BossBar suppress logic. Set by BedrockEntityBridge.
    private BedrockEntityBridge bridge;
    private boolean floodgateAvailable = false;

    public void setBridge(BedrockEntityBridge bridge) {
        this.bridge = bridge;
        this.floodgateAvailable = Bukkit.getPluginManager().getPlugin("floodgate") != null;
    }

    public void setEmItemModelMap(Map<String, Map<Integer, String>> map) {
        java.util.Map<String, java.util.Map<Integer, String>> copy = new java.util.HashMap<>();
        for (var e : map.entrySet()) copy.put(e.getKey(), java.util.Map.copyOf(e.getValue()));
        this.emItemModelMap = java.util.Map.copyOf(copy);
        int total = map.values().stream().mapToInt(Map::size).sum();
        log.info("[BRIDGE] EM item_model injection map loaded: " + total + " entries across "
                + map.size() + " materials");
    }

    public void setEmGearModelMap(Map<String, Map<String, String>> map) {
        java.util.Map<String, java.util.Map<String, String>> copy = new java.util.HashMap<>();
        for (var e : map.entrySet()) copy.put(e.getKey(), java.util.Map.copyOf(e.getValue()));
        this.emGearModelMap = java.util.Map.copyOf(copy);
        int total = map.values().stream().mapToInt(Map::size).sum();
        log.info("[BRIDGE] EM gear item_model map loaded: " + total + " entries across "
                + map.size() + " materials");
    }

    /**
     * For Bedrock players, set item_model = javaId on EliteMobs items so Geyser's
     * CustomItemTranslator finds the matching CustomItemDefinition (registered with
     * model() = javaId in the geyser-extension). Geyser's lookup returns null
     * immediately if item_model is absent — Paper omits the component when it equals
     * the item's default, so we must set it explicitly. Skips items that already have
     * an item_model set, and items without CMD (Geyser would not match anyway).
     */
    private boolean injectItemModelIfNeeded(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        String typeName = item.getType().getName().toString();

        // 2D path: (material, cmd) → bedrockKey
        Map<Integer, String> cmdMap = emItemModelMap.get(typeName);
        if (cmdMap != null) {
            java.util.Optional<com.github.retrooper.packetevents.protocol.component.builtin.item.ItemCustomModelData> cmdLists =
                    item.getComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS);
            java.util.Optional<Integer> cmdLegacy = item.getComponent(ComponentTypes.CUSTOM_MODEL_DATA);
            Integer cmd = null;
            if (cmdLists.isPresent() && !cmdLists.get().getFloats().isEmpty()) {
                cmd = cmdLists.get().getFloats().get(0).intValue();
            } else if (cmdLegacy.isPresent()) {
                cmd = cmdLegacy.get();
            }
            if (cmd != null) {
                String bedrockKey = cmdMap.get(cmd);
                if (bedrockKey != null) {
                    item.setComponent(ComponentTypes.ITEM_MODEL,
                            new ItemModel(new ResourceLocation("geyser_custom", bedrockKey)));
                    int n = injectCount.incrementAndGet();
                    if (n <= 10 || n % 100 == 0) {
                        log.info("[BRIDGE] Injected item_model=geyser_custom:" + bedrockKey
                                + " for " + typeName + " cmd=" + cmd + " (n=" + n + ")");
                    }
                    return true;
                }
            }
        }

        // 3D gear path: (material, item_model identifier) → bedrockKey
        Map<String, String> gearMap = emGearModelMap.get(typeName);
        if (gearMap != null) {
            java.util.Optional<ItemModel> existing = item.getComponent(ComponentTypes.ITEM_MODEL);
            if (existing.isPresent()) {
                String existingId = existing.get().getModelLocation().toString();
                String bedrockKey = gearMap.get(existingId);
                if (bedrockKey != null) {
                    item.setComponent(ComponentTypes.ITEM_MODEL,
                            new ItemModel(new ResourceLocation("geyser_custom", bedrockKey)));
                    int n = gearInjectCount.incrementAndGet();
                    if (n <= 10 || n % 100 == 0) {
                        log.info("[BRIDGE] Injected gear item_model=geyser_custom:" + bedrockKey
                                + " for " + typeName + " was=" + existingId + " (n=" + n + ")");
                    }
                    return true;
                }
            }
        }

        return false;
    }

    public void register() {
        listener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                Object eventPlayer = event.getPlayer();
                if (!(eventPlayer instanceof Player playerObj)) return;

                // Phase 7.2: inject item_model on inventory packets for Bedrock players
                // so Geyser's CustomItemTranslator can locate our v2 custom item definitions.
                if ((!emItemModelMap.isEmpty() || !emGearModelMap.isEmpty()) && floodgateAvailable
                        && FloodgateApi.getInstance().isFloodgatePlayer(playerObj.getUniqueId())) {
                    if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                        try {
                            WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
                            if (injectItemModelIfNeeded(wrapper.getItem())) {
                                event.markForReEncode(true);
                            }
                        } catch (Throwable t) {
                            // Wrapper API mismatch — fail soft
                        }
                    } else if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                        try {
                            WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
                            boolean modified = false;
                            for (ItemStack item : wrapper.getItems()) {
                                if (injectItemModelIfNeeded(item)) modified = true;
                            }
                            wrapper.getCarriedItem().ifPresent(ci -> injectItemModelIfNeeded(ci));
                            if (modified) event.markForReEncode(true);
                        } catch (Throwable t) {
                            // Wrapper API mismatch — fail soft
                        }
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
                        // Dropped items reach Bedrock as item-entity metadata, not via an
                        // inventory packet — without this branch the ground item keeps its
                        // original item_model and Geyser renders vanilla / a foreign mapping.
                        try {
                            WrapperPlayServerEntityMetadata wrapper = new WrapperPlayServerEntityMetadata(event);
                            boolean modified = false;
                            for (EntityData<?> data : wrapper.getEntityMetadata()) {
                                if (data.getValue() instanceof ItemStack stack
                                        && injectItemModelIfNeeded(stack)) {
                                    modified = true;
                                }
                            }
                            if (modified) event.markForReEncode(true);
                        } catch (Throwable t) {
                            // Wrapper API mismatch — fail soft
                        }
                    }
                }

                // Existing: suppress spawn/metadata packets for hidden entities
                if (!hiddenEntityIds.isEmpty()) {
                    int entityId = -1;
                    if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
                        entityId = new WrapperPlayServerSpawnEntity(event).getEntityId();
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
                        entityId = new WrapperPlayServerEntityMetadata(event).getEntityId();
                    }
                    if (entityId > 0 && isHiddenFor(entityId, eventPlayer)) {
                        event.setCancelled(true);
                        return;
                    }
                }

                // Phase 7.1b — Java-only suppress for our auxiliary TextDisplay nametags
                if (!javaHiddenEntityIds.isEmpty() && floodgateAvailable
                        && !FloodgateApi.getInstance().isFloodgatePlayer(playerObj.getUniqueId())) {
                    int entityId = -1;
                    if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
                        entityId = new WrapperPlayServerSpawnEntity(event).getEntityId();
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
                        entityId = new WrapperPlayServerEntityMetadata(event).getEntityId();
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_TELEPORT) {
                        try {
                            entityId = new WrapperPlayServerEntityTeleport(event).getEntityId();
                        } catch (Throwable t) {
                            // Wrapper API mismatch — fall through, packet passes
                        }
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
                        try {
                            entityId = new WrapperPlayServerEntityRelativeMove(event).getEntityId();
                        } catch (Throwable t) {
                            // Wrapper API mismatch — fall through
                        }
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
                        try {
                            entityId = new WrapperPlayServerEntityRelativeMoveAndRotation(event).getEntityId();
                        } catch (Throwable t) {
                            // Wrapper API mismatch — fall through
                        }
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                        try {
                            // POSITION_SYNC uses getId() (not getEntityId()) — confirmed from PE 2.12.1 source.
                            entityId = new WrapperPlayServerEntityPositionSync(event).getId();
                        } catch (Throwable t) {
                            // Wrapper might be missing or use a different getter on older PE — fail soft.
                        }
                    }
                    if (entityId > 0 && javaHiddenEntityIds.contains(entityId)) {
                        event.setCancelled(true);
                        return;
                    }
                }

                // Phase 7.1a — BOSS_EVENT suppress for Bedrock players
                if (event.getPacketType() == PacketType.Play.Server.BOSS_BAR) {
                    handleBossEvent(event, playerObj);
                }
            }

            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
                if (fakeToRealEntityId.isEmpty()) return;

                WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
                Integer realId = fakeToRealEntityId.get(wrapper.getEntityId());
                if (realId != null) {
                    wrapper.setEntityId(realId);
                }
            }
        };

        PacketEvents.getAPI().getEventManager().registerListener(listener);
        log.info("[BRIDGE] PacketInterceptor registered");
    }

    public void unregister() {
        if (listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            listener = null;
        }
    }

    public void hideEntity(int entityId, Player player) {
        hiddenEntityIds.computeIfAbsent(entityId, k -> ConcurrentHashMap.newKeySet()).add(player);
    }

    public void unhideEntity(int entityId, Player player) {
        Set<Player> players = hiddenEntityIds.get(entityId);
        if (players != null) {
            players.remove(player);
            if (players.isEmpty()) hiddenEntityIds.remove(entityId);
        }
    }

    /**
     * Phase 7.1b — register an entity ID to be suppressed for ALL Java (non-Floodgate)
     * players. SPAWN_ENTITY, ENTITY_METADATA, and ENTITY_TELEPORT packets for this ID
     * will be cancelled before reaching Java clients.
     */
    public void hideFromJava(int entityId) {
        javaHiddenEntityIds.add(entityId);
    }

    /** Phase 7.1b — unregister an entity from Java-suppress. */
    public void unhideFromJava(int entityId) {
        javaHiddenEntityIds.remove(entityId);
    }

    public void mapFakeToReal(int fakeId, int realId) {
        fakeToRealEntityId.put(fakeId, realId);
    }

    public void unmapFake(int fakeId) {
        fakeToRealEntityId.remove(fakeId);
    }

    private boolean isHiddenFor(int entityId, Object player) {
        Set<Player> players = hiddenEntityIds.get(entityId);
        return players != null && players.contains(player);
    }

    public void clear() {
        hiddenEntityIds.clear();
        fakeToRealEntityId.clear();
        javaHiddenEntityIds.clear();
    }

    /**
     * Phase 7.1a — heuristic capture + suppress for EliteMobs BossBar packets to Bedrock players.
     *
     * <p>The BOSS_EVENT packet leaves the server on a Netty IO thread (not the Bukkit main
     * thread that calls {@code bossBar.addPlayer()}), so ThreadLocal-based identification of
     * "our own" packets doesn't survive the hand-off. We use a timing heuristic instead: the
     * FIRST title-matching ADD per controller is ours (we always {@code addPlayer} at boss
     * spawn, well before EM's own BossBar appears at combat-enter). Subsequent matching ADDs
     * are EM's duplicates and get suppressed.
     *
     * <p>Strategy summary:
     * <ul>
     *   <li>Non-ADD with an own-UUID → pass through (our progress/color updates)</li>
     *   <li>Non-ADD with a captured EM UUID → cancel</li>
     *   <li>ADD with title match, controller has no own-UUID yet → claim as ours, pass through</li>
     *   <li>ADD with title match, controller already has an own-UUID → EM duplicate, suppress</li>
     *   <li>Anything else (vanilla Wither/Dragon, other plugins) → pass through untouched</li>
     * </ul>
     */
    private void handleBossEvent(PacketSendEvent event, Player playerObj) {
        if (bridge == null) return;
        if (!isSuppressEnabled()) return;
        if (!floodgateAvailable) return;
        if (!FloodgateApi.getInstance().isFloodgatePlayer(playerObj.getUniqueId())) return;

        WrapperPlayServerBossBar wrapper;
        try {
            wrapper = new WrapperPlayServerBossBar(event);
        } catch (Throwable t) {
            // PacketEvents wrapper API break — fail silent, packet passes through
            return;
        }

        UUID uuid = wrapper.getUUID();
        WrapperPlayServerBossBar.Action action = wrapper.getAction();

        // Pass-through for any UUID we've previously claimed as ours (progress / style updates).
        for (BedrockBossBarController ctrl : bridge.getActiveControllers().values()) {
            if (ctrl.isOwnUuid(uuid)) {
                return;
            }
        }

        // Already-captured EM UUID: drop all subsequent updates for this Bedrock player.
        if (action != WrapperPlayServerBossBar.Action.ADD && bridge.getBossBarRegistry().contains(uuid)) {
            event.setCancelled(true);
            return;
        }

        // ADD action: title-match against active controllers. First match per controller is ours.
        if (action == WrapperPlayServerBossBar.Action.ADD) {
            String packetTitle = extractTitleString(wrapper);
            if (packetTitle == null) return;

            for (BedrockBossBarController ctrl : bridge.getActiveControllers().values()) {
                if (ctrl.hasViewer(playerObj) && titlesMatch(ctrl.getTitle(), packetTitle)) {
                    if (!ctrl.hasOwnUuid()) {
                        // First matching ADD for this controller — claim it as our own.
                        ctrl.registerOwnUuid(uuid);
                        FMMBedrockBridge.debugLog("[BRIDGE] Claimed own BossBar UUID " + uuid
                                + " (title='" + packetTitle + "') for " + playerObj.getName());
                    } else {
                        // Controller already has an own-UUID — this is EM's duplicate, suppress.
                        bridge.getBossBarRegistry().add(uuid);
                        event.setCancelled(true);
                        FMMBedrockBridge.debugLog("[BRIDGE] Suppressed EM BossBar UUID " + uuid
                                + " (title='" + packetTitle + "') for " + playerObj.getName());
                    }
                    return;
                }
            }
            FMMBedrockBridge.debugLog("[BRIDGE] Unmatched BOSS_EVENT(ADD) uuid=" + uuid
                    + " title='" + packetTitle + "' for " + playerObj.getName() + " (pass-through)");
        }
    }

    /** Extracts the title string from a BOSS_EVENT(ADD) wrapper, or null on failure. */
    private String extractTitleString(WrapperPlayServerBossBar wrapper) {
        try {
            Component title = wrapper.getTitle();
            if (title == null) return null;
            return PlainTextComponentSerializer.plainText().serialize(title);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Compares the controller's title (set via Bukkit.createBossBar with a String) to the
     * packet's plain-text title. EliteMobs uses ChatColorConverter.convert which produces
     * legacy §-coded strings; both Bukkit's BossBar title and the BOSS_EVENT packet title
     * carry the same source text. We compare plain-text (color codes stripped) for robustness.
     */
    private boolean titlesMatch(String controllerTitle, String packetTitle) {
        if (controllerTitle == null || packetTitle == null) return false;
        String a = stripLegacyCodes(controllerTitle);
        String b = packetTitle;  // already plain text
        return a.equals(b);
    }

    private String stripLegacyCodes(String input) {
        // Use Adventure's legacy serializer to convert §-coded string → Component → plain text.
        try {
            Component c = LegacyComponentSerializer.legacySection().deserialize(input);
            return PlainTextComponentSerializer.plainText().serialize(c);
        } catch (Throwable t) {
            return input;
        }
    }

    private boolean isSuppressEnabled() {
        return FMMBedrockBridge.getInstance().getConfig()
                .getBoolean("phase71a.suppress-em-bossbar", true);
    }
}
