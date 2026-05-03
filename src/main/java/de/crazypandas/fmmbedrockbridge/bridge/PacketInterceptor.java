package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private PacketListenerAbstract listener;

    // Phase 7.1a — back-reference for BossBar suppress logic. Set by BedrockEntityBridge.
    private BedrockEntityBridge bridge;
    private boolean floodgateAvailable = false;

    public void setBridge(BedrockEntityBridge bridge) {
        this.bridge = bridge;
        this.floodgateAvailable = Bukkit.getPluginManager().getPlugin("floodgate") != null;
    }

    public void register() {
        listener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                Object eventPlayer = event.getPlayer();
                if (!(eventPlayer instanceof Player playerObj)) return;

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
