package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * PacketEvents listener that:
 *  - Phase 7.2b: injects item_model = geyser_custom:&lt;bedrockKey&gt; on legacy
 *    custom_model_data items (Smaragd + CMD 31173 → BagOfCoin etc.) so Geyser/RPM
 *    can route them to the correct Bedrock identifier. Fills RPM 1.8.0's gap which
 *    only scans the 1.21.4+ items/ namespace.
 *  - Phase 7.1a: suppresses EM's "Evoker | 2" BossBar packet for Bedrock players
 *    via first-match heuristic, leaving our styled bridge BossBar visible.
 *  - Phase 7.1b: suppresses our auxiliary TextDisplay nametag entity for Java
 *    players (only Bedrock players see it — Java has FMM's vanilla nametag).
 */
public class PacketInterceptor {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    // Phase 7.2b — (javaMaterial, cmd) → bedrockKey for legacy custom_model_data items
    private volatile Map<String, Map<Integer, String>> emItemModelMap = Collections.emptyMap();
    private final AtomicInteger injectCount = new AtomicInteger();

    // Phase 7.1b — entity IDs hidden from ALL Java (non-Floodgate) players (our TextDisplay nametags)
    private final Set<Integer> javaHiddenEntityIds = ConcurrentHashMap.newKeySet();

    private PacketListenerAbstract listener;
    private BedrockEntityBridge bridge;
    private boolean floodgateAvailable = false;

    public void setBridge(BedrockEntityBridge bridge) {
        this.bridge = bridge;
        this.floodgateAvailable = Bukkit.getPluginManager().getPlugin("floodgate") != null;
    }

    public void setEmItemModelMap(Map<String, Map<Integer, String>> map) {
        Map<String, Map<Integer, String>> copy = new java.util.HashMap<>();
        for (var e : map.entrySet()) copy.put(e.getKey(), Map.copyOf(e.getValue()));
        this.emItemModelMap = Map.copyOf(copy);
        int total = map.values().stream().mapToInt(Map::size).sum();
        log.info("[BRIDGE] EM item_model injection map loaded: " + total + " entries across "
                + map.size() + " materials");
    }

    private boolean injectItemModelIfNeeded(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        String typeName = item.getType().getName().toString();
        Map<Integer, String> cmdMap = emItemModelMap.get(typeName);
        if (cmdMap == null) return false;

        java.util.Optional<com.github.retrooper.packetevents.protocol.component.builtin.item.ItemCustomModelData> cmdLists =
                item.getComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS);
        java.util.Optional<Integer> cmdLegacy = item.getComponent(ComponentTypes.CUSTOM_MODEL_DATA);
        Integer cmd = null;
        if (cmdLists.isPresent() && !cmdLists.get().getFloats().isEmpty()) {
            cmd = cmdLists.get().getFloats().get(0).intValue();
        } else if (cmdLegacy.isPresent()) {
            cmd = cmdLegacy.get();
        }
        if (cmd == null) return false;

        String bedrockKey = cmdMap.get(cmd);
        if (bedrockKey == null) return false;

        item.setComponent(ComponentTypes.ITEM_MODEL,
                new ItemModel(new ResourceLocation("geyser_custom", bedrockKey)));
        int n = injectCount.incrementAndGet();
        if (n <= 10 || n % 100 == 0) {
            log.info("[BRIDGE] Injected item_model=geyser_custom:" + bedrockKey
                    + " for " + typeName + " cmd=" + cmd + " (n=" + n + ")");
        }
        return true;
    }

    public void register() {
        listener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                Object eventPlayer = event.getPlayer();
                if (!(eventPlayer instanceof Player playerObj)) return;

                // Phase 7.2b — inject item_model on inventory packets for Bedrock players
                if (!emItemModelMap.isEmpty() && floodgateAvailable
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

                // Phase 7.1b — Java-only suppress for our auxiliary TextDisplay nametags
                if (!javaHiddenEntityIds.isEmpty() && floodgateAvailable
                        && !FloodgateApi.getInstance().isFloodgatePlayer(playerObj.getUniqueId())) {
                    int entityId = -1;
                    if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
                        entityId = new WrapperPlayServerSpawnEntity(event).getEntityId();
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
                        entityId = new WrapperPlayServerEntityMetadata(event).getEntityId();
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_TELEPORT) {
                        try { entityId = new WrapperPlayServerEntityTeleport(event).getEntityId(); } catch (Throwable t) {}
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
                        try { entityId = new WrapperPlayServerEntityRelativeMove(event).getEntityId(); } catch (Throwable t) {}
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
                        try { entityId = new WrapperPlayServerEntityRelativeMoveAndRotation(event).getEntityId(); } catch (Throwable t) {}
                    } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                        try { entityId = new WrapperPlayServerEntityPositionSync(event).getId(); } catch (Throwable t) {}
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
        };

        PacketEvents.getAPI().getEventManager().registerListener(listener);
    }

    public void unregister() {
        if (listener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            listener = null;
        }
    }

    public void hideFromJava(int entityId) {
        javaHiddenEntityIds.add(entityId);
    }

    public void unhideFromJava(int entityId) {
        javaHiddenEntityIds.remove(entityId);
    }

    public void clear() {
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
            return;
        }

        UUID uuid = wrapper.getUUID();
        WrapperPlayServerBossBar.Action action = wrapper.getAction();

        for (BedrockBossBarController ctrl : bridge.getActiveControllers().values()) {
            if (ctrl.isOwnUuid(uuid)) return;
        }

        if (action != WrapperPlayServerBossBar.Action.ADD && bridge.getBossBarRegistry().contains(uuid)) {
            event.setCancelled(true);
            return;
        }

        if (action == WrapperPlayServerBossBar.Action.ADD) {
            String packetTitle = extractTitleString(wrapper);
            if (packetTitle == null) return;

            for (BedrockBossBarController ctrl : bridge.getActiveControllers().values()) {
                if (!ctrl.hasViewer(playerObj)) continue;

                boolean currentTitleMatch = titlesMatch(ctrl.getTitle(), packetTitle);
                boolean knownTitleMatch = currentTitleMatch || ctrl.getTitleAliases().stream()
                        .anyMatch(alias -> titlesMatch(alias, packetTitle));

                if (currentTitleMatch) {
                    if (!ctrl.hasOwnUuid()) {
                        ctrl.registerOwnUuid(uuid);
                        FMMBedrockBridge.debugLog("[BRIDGE] Claimed own BossBar UUID " + uuid
                                + " (title='" + packetTitle + "') for " + playerObj.getName());
                    } else {
                        bridge.getBossBarRegistry().add(uuid);
                        event.setCancelled(true);
                        FMMBedrockBridge.debugLog("[BRIDGE] Suppressed EM BossBar UUID " + uuid
                                + " (title='" + packetTitle + "') for " + playerObj.getName());
                    }
                    return;
                }

                if (knownTitleMatch) {
                    bridge.getBossBarRegistry().add(uuid);
                    event.setCancelled(true);
                    FMMBedrockBridge.debugLog("[BRIDGE] Suppressed stale-title EM BossBar UUID " + uuid
                            + " (title='" + packetTitle + "', current='" + ctrl.getTitle()
                            + "') for " + playerObj.getName());
                    return;
                }
            }
            FMMBedrockBridge.debugLog("[BRIDGE] Unmatched BOSS_EVENT(ADD) uuid=" + uuid
                    + " title='" + packetTitle + "' for " + playerObj.getName() + " (pass-through)");
        }
    }

    private String extractTitleString(WrapperPlayServerBossBar wrapper) {
        try {
            Component title = wrapper.getTitle();
            if (title == null) return null;
            return PlainTextComponentSerializer.plainText().serialize(title);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean titlesMatch(String controllerTitle, String packetTitle) {
        if (controllerTitle == null || packetTitle == null) return false;
        String a = stripLegacyCodes(controllerTitle);
        return a.equals(packetTitle);
    }

    private String stripLegacyCodes(String input) {
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
