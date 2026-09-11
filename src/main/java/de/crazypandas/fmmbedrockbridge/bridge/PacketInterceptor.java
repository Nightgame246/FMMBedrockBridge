package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * PacketEvents listener that:
 *  - Phase 7.1a: suppresses EM's "Evoker | 2" BossBar packet for Bedrock players
 *    via first-match heuristic, leaving our styled bridge BossBar visible.
 *  - Phase 7.1b: suppresses our auxiliary TextDisplay nametag entity for Java
 *    players (only Bedrock players see it — Java has FMM's vanilla nametag).
 */
public class PacketInterceptor {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    // Phase 7.1b — entity IDs hidden from ALL Java (non-Floodgate) players (our TextDisplay nametags)
    private final Set<Integer> javaHiddenEntityIds = ConcurrentHashMap.newKeySet();

    private PacketListenerAbstract listener;
    private BedrockEntityBridge bridge;
    private boolean floodgateAvailable = false;

    /**
     * Phase 7.5 self-disable. A filter that fails once will almost always keep failing — a
     * swapped jar, a renamed PacketEvents wrapper — and it runs on every action-bar packet.
     * EliteMobs re-sends its HUD about five times a second, so an unguarded failure floods the
     * log with thousands of identical lines (observed on 11.09.2026 after a live jar swap).
     * After {@link #GLYPH_FILTER_FAILURE_LIMIT} failures the filter switches itself off, logs
     * once at WARNING, and leaves the rest of the interceptor untouched.
     */
    private static final int GLYPH_FILTER_FAILURE_LIMIT = 5;
    private final java.util.concurrent.atomic.AtomicInteger glyphFilterFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean glyphFilterDisabled = false;

    /**
     * Above this many glyphs a packet is EliteMobs' HUD rather than a message. Its bars run to
     * several hundred glyphs; decorative ones in a sentence stay in the low single digits.
     */
    private static final int HUD_GLYPH_THRESHOLD = 20;

    /** Set when EliteMobs' Advanced Combat System is available — used to build our own HUD. */
    private AdvancedCombatHook hook;

    public void setAdvancedCombatHook(AdvancedCombatHook hook) {
        this.hook = hook;
    }

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
                    return;
                }

                // Phase 7.5 — strip Java resource-pack font glyphs from Bedrock action bars
                if (FMMBedrockBridge.isPhase75Enabled()
                        && (event.getPacketType() == PacketType.Play.Server.ACTION_BAR
                            || event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT_MESSAGE)) {
                    handleActionBarGlyphs(event, playerObj);
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
     * Phase 7.1a — identify + suppress EliteMobs BossBar packets to Bedrock players so only our
     * styled bridge bar remains.
     *
     * <p><b>Identifying our own packets.</b> The BOSS_EVENT packet leaves the server on a Netty
     * IO thread rather than the Bukkit main thread that called {@code bossBar.addPlayer()}, so a
     * ThreadLocal marker does not survive the hand-off. Each controller therefore resolves its
     * bar's wire UUID up front ({@link BossBarUuidResolver}); everything else with a matching
     * title is EliteMobs'. Where that resolution is unavailable we fall back to the original
     * heuristic — first title-matching ADD per controller is ours.
     *
     * <p><b>EliteMobs 10.8.0 pooling.</b> {@code BossHealthBarManager} keeps up to four reusable
     * bars per player and re-titles them for whichever boss holds a slot, while
     * {@code BossBarOrderManager} re-sends bars (removePlayer + addPlayer) just to enforce order.
     * A suppressed UUID is therefore <b>not</b> a permanent property of a boss: the same UUID
     * later carries a different boss, possibly one we do not draw at all. Suppression entries are
     * consequently evicted as soon as a slot is released (REMOVE) or re-used for a title we do not
     * own — otherwise we would keep swallowing that slot's updates and leave Bedrock players with
     * a frozen or undismissable bar.
     */
    /**
     * Phase 7.5 — removes Private Use Area glyphs from action-bar text sent to Bedrock players.
     *
     * <p>EliteMobs draws its combat HUD from a Java resource-pack font. Bedrock cannot resolve
     * that font and maps the same codepoints onto its own symbol sheet, turning every bar segment
     * into an item icon — hundreds of them, re-sent on a keepalive loop, which lags the client
     * out. See {@link BedrockGlyphFilter} for the measurements behind this.
     *
     * <p>Java players are never touched. Runs on the Netty thread, so everything is wrapped.
     */
    /**
     * Decides what a Bedrock player should see instead of glyph-laden text.
     *
     * <p>EliteMobs' combat HUD is hundreds of font glyphs forming bars; stripping them leaves
     * unreadable rubble like "56/6092/100". So a packet with that many glyphs is treated as the
     * HUD and <b>replaced</b> with a line we build from EliteMobs' own values — Java players keep
     * the graphical version untouched. A packet with only a few glyphs is an ordinary message
     * ("Class controls are not active here") and merely gets them stripped.
     *
     * @return the text to send, or {@code null} to suppress the packet entirely
     */
    private String replacementFor(String legacy, Player playerObj) {
        if (BedrockGlyphFilter.countGlyphs(legacy) >= HUD_GLYPH_THRESHOLD && hook != null) {
            String own = hook.hudLine(playerObj);
            if (own != null) return own;
            // No class data to show — better nothing than rubble.
            return null;
        }
        String stripped = BedrockGlyphFilter.strip(legacy);
        return stripped.isEmpty() ? null : stripped;
    }

    private void handleActionBarGlyphs(PacketSendEvent event, Player playerObj) {
        if (!floodgateAvailable || glyphFilterDisabled) return;
        try {
            if (!FloodgateApi.getInstance().isFloodgatePlayer(playerObj.getUniqueId())) return;

            LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();

            if (event.getPacketType() == PacketType.Play.Server.ACTION_BAR) {
                WrapperPlayServerActionBar wrapper = new WrapperPlayServerActionBar(event);
                String legacy = serializer.serialize(wrapper.getActionBarText());
                if (!BedrockGlyphFilter.containsGlyphs(legacy)) return;

                String replacement = replacementFor(legacy, playerObj);
                if (replacement == null) {
                    event.setCancelled(true);
                } else {
                    wrapper.setActionBarText(serializer.deserialize(replacement));
                }
                FMMBedrockBridge.debugLog("[PHASE75] action bar for " + playerObj.getName()
                        + " -> '" + (replacement == null ? "(suppressed)" : replacement) + "'");
                return;
            }

            // Spigot's sendMessage(ACTION_BAR, …) travels as a system chat with overlay=true
            // on modern servers, so the same treatment has to cover that shape too.
            WrapperPlayServerSystemChatMessage wrapper = new WrapperPlayServerSystemChatMessage(event);
            if (!wrapper.isOverlay()) return;

            String legacy = serializer.serialize(wrapper.getMessage());
            if (!BedrockGlyphFilter.containsGlyphs(legacy)) return;

            String replacement = replacementFor(legacy, playerObj);
            if (replacement == null) {
                event.setCancelled(true);
            } else {
                wrapper.setMessage(serializer.deserialize(replacement));
            }
            FMMBedrockBridge.debugLog("[PHASE75] overlay for " + playerObj.getName()
                    + " -> '" + (replacement == null ? "(suppressed)" : replacement) + "'");
        } catch (Throwable t) {
            // Never let a malformed packet take the interceptor down — and never let a
            // permanent failure flood the log, since this runs on every action-bar packet.
            if (glyphFilterFailures.incrementAndGet() >= GLYPH_FILTER_FAILURE_LIMIT) {
                glyphFilterDisabled = true;
                Bukkit.getLogger().warning("[FMMBedrockBridge] Phase 7.5 disabled itself after "
                        + GLYPH_FILTER_FAILURE_LIMIT + " failures — Bedrock action bars are no"
                        + " longer filtered. Last cause: " + t
                        + " (a jar swapped while the server was running causes exactly this;"
                        + " restart the server)");
            } else {
                FMMBedrockBridge.debugLog("[PHASE75] glyph filter skipped a packet: " + t);
            }
        }
    }

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

        if (action != WrapperPlayServerBossBar.Action.ADD) {
            if (!bridge.getBossBarRegistry().contains(uuid)) return;
            event.setCancelled(true);
            if (action == WrapperPlayServerBossBar.Action.REMOVE) {
                // The slot is being released. Keeping the entry would make us swallow the
                // packets of whichever boss EliteMobs assigns to this pooled bar next.
                bridge.getBossBarRegistry().remove(uuid);
                FMMBedrockBridge.debugLog("[BRIDGE] Released suppressed BossBar UUID " + uuid
                        + " on REMOVE for " + playerObj.getName());
            }
            return;
        }

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
                            + " (title='" + packetTitle + "') for " + playerObj.getName()
                            + " via legacy heuristic");
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

        // No controller owns this title. If the UUID is still marked as suppressed it is a pooled
        // EliteMobs bar that has been recycled for an unrelated boss — let it through and forget it.
        if (bridge.getBossBarRegistry().remove(uuid)) {
            FMMBedrockBridge.debugLog("[BRIDGE] Released recycled BossBar UUID " + uuid
                    + " (now title='" + packetTitle + "') for " + playerObj.getName());
        }
        FMMBedrockBridge.debugLog("[BRIDGE] Unmatched BOSS_EVENT(ADD) uuid=" + uuid
                + " title='" + packetTitle + "' for " + playerObj.getName() + " (pass-through)");
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
