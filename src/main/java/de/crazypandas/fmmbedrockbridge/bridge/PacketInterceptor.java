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
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
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
    private BossBarTitleResolver bossBarResolver;
    private ViewerManager viewerManager;
    private PacketListenerAbstract listener;

    public void register() {
        listener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                Object eventPlayer = event.getPlayer();
                if (!(eventPlayer instanceof Player)) return;

                if (event.getPacketType() == PacketType.Play.Server.BOSS_BAR) {
                    handleBossEvent(event);
                    return;
                }

                if (hiddenEntityIds.isEmpty()) return;

                int entityId = -1;
                if (event.getPacketType() == PacketType.Play.Server.SPAWN_ENTITY) {
                    entityId = new WrapperPlayServerSpawnEntity(event).getEntityId();
                } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
                    entityId = new WrapperPlayServerEntityMetadata(event).getEntityId();
                }

                if (entityId > 0 && isHiddenFor(entityId, eventPlayer)) {
                    event.setCancelled(true);
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

    private void handleBossEvent(PacketSendEvent event) {
        if (bossBarResolver == null || viewerManager == null) return;
        if (!(event.getPlayer() instanceof Player viewer)) return;
        if (!viewerManager.getReadyPlayers().contains(viewer)) return; // Java players: pass through

        WrapperPlayServerBossBar wrapper = new WrapperPlayServerBossBar(event);
        java.util.UUID uuid = wrapper.getUUID();
        if (uuid == null) return;

        WrapperPlayServerBossBar.Action action = wrapper.getAction();
        if (action == WrapperPlayServerBossBar.Action.REMOVE) {
            bossBarResolver.onBossBarRemoved(uuid);
            return;
        }

        // For ADD and UPDATE_TITLE we attempt to substitute the title.
        if (action != WrapperPlayServerBossBar.Action.ADD && action != WrapperPlayServerBossBar.Action.UPDATE_TITLE) {
            return;
        }

        net.kyori.adventure.text.Component replacement = bossBarResolver.resolveTitle(uuid, viewer);
        if (replacement == null) return;

        try {
            wrapper.setTitle(replacement);
        } catch (Throwable t) {
            // Setter not present in this PacketEvents version — leave packet untouched.
        }
    }

    private boolean isHiddenFor(int entityId, Object player) {
        Set<Player> players = hiddenEntityIds.get(entityId);
        return players != null && players.contains(player);
    }

    public void clear() {
        hiddenEntityIds.clear();
        fakeToRealEntityId.clear();
    }

    public void setBossBarResolver(BossBarTitleResolver resolver) {
        this.bossBarResolver = resolver;
    }

    public void setViewerManager(ViewerManager manager) {
        this.viewerManager = manager;
    }
}
