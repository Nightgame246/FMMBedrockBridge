package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * PacketEvents listener that:
 *  - Phase 7.1a: suppresses EM's "Evoker | 2" BossBar packet for Bedrock players
 *    via first-match heuristic, leaving our styled bridge BossBar visible.
 *  - Phase 7.1d: hides the mob's own name for Bedrock where FMM shows a nameplate.
 *  - Phase 7.6: rewrites EliteMobs' menu titles for Bedrock (background markers).
 *  - Phase 7.7: puts EliteMobs' menu icons on paper for Bedrock (Geyser custom-item mapping).
 */
public class PacketInterceptor {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();


    private PacketListenerAbstract listener;
    private BedrockEntityBridge bridge;
    // Phase 7.1d — removable in one piece, see BedrockNameFix.
    private final BedrockNameFix nameFix = new BedrockNameFix();
    // Phase 7.6 — keeps hex colours (§x§r§r§g§g§b§b) intact across the legacy round trip.
    private static final LegacyComponentSerializer MENU_TITLE_LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    public void setBridge(BedrockEntityBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * The same detection EliteMobs uses, via {@link ViewerManager} (cached per player — this runs
     * for every entity packet). {@code null} while no bridge is set: then nobody is classified,
     * and the Bedrock-only handling stays off, as they did before without Floodgate.
     */
    private Boolean isBedrock(Player player) {
        return bridge == null ? null : bridge.getViewerManager().isBedrockPlayer(player);
    }

    public void register() {
        listener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                Object eventPlayer = event.getPlayer();
                if (!(eventPlayer instanceof Player playerObj)) return;

                // Phase 7.1d — one name above modelled bosses on Bedrock, not two
                if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA
                        && Boolean.TRUE.equals(isBedrock(playerObj))) {
                    nameFix.onMetadata(event);
                }

                // Phase 7.6 — EliteMobs menu backgrounds for Bedrock
                if (event.getPacketType() == PacketType.Play.Server.OPEN_WINDOW
                        && Boolean.TRUE.equals(isBedrock(playerObj))) {
                    rewriteMenuTitle(event);
                }

                // Phase 7.7 — EliteMobs menu icons for Bedrock
                if ((event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS
                        || event.getPacketType() == PacketType.Play.Server.SET_SLOT)
                        && Boolean.TRUE.equals(isBedrock(playerObj))) {
                    rebaseMenuIcons(event);
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

    /**
     * Phase 7.6 — replaces EliteMobs' background glyphs in the chest title by the markers the
     * FMMBridge-EliteMobsMenus pack recognises. Untouched titles are not re-encoded at all.
     */
    private void rewriteMenuTitle(PacketSendEvent event) {
        if (!FMMBedrockBridge.isPhase76MenuBackgroundsEnabled()) return;
        try {
            WrapperPlayServerOpenWindow wrapper = new WrapperPlayServerOpenWindow(event);
            Component title = wrapper.getTitle();
            if (title == null) return;
            String legacy = MENU_TITLE_LEGACY.serialize(title);
            String rewritten = BedrockMenuTitle.rewrite(legacy, FMMBedrockBridge.isPhase76HideSlotBackgrounds());
            if (rewritten == legacy) return;
            wrapper.setTitle(MENU_TITLE_LEGACY.deserialize(rewritten));
            event.markForReEncode(true);
            FMMBedrockBridge.debugLog("[PHASE76] menu title rewritten for Bedrock: '" + rewritten + "'");
        } catch (Throwable t) {
            FMMBedrockBridge.debugLog("[PHASE76] title rewrite failed: " + t);
        }
    }

    /**
     * Phase 7.7 — puts EliteMobs' menu icons on paper for Bedrock, so ResourcePackManager's Geyser
     * mapping (registered under paper) applies. Only container windows; the player's own inventory
     * (window 0) and the cursor (-1) are never touched. Unchanged packets are not re-encoded.
     */
    private void rebaseMenuIcons(PacketSendEvent event) {
        if (!FMMBedrockBridge.isPhase77MenuIconsEnabled()) return;
        List<String> prefixes = FMMBedrockBridge.getPhase77Prefixes();
        Set<String> excludes = FMMBedrockBridge.getPhase77Excludes();
        try {
            if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
                if (wrapper.getWindowId() <= 0) return;
                List<ItemStack> items = wrapper.getItems();
                List<ItemStack> out = new ArrayList<>(items.size());
                int changed = 0;
                for (ItemStack item : items) {
                    ItemStack rebased = MenuIconRebaser.rebase(item, prefixes, excludes);
                    if (rebased != item) changed++;
                    out.add(rebased);
                }
                if (changed == 0) return;
                wrapper.setItems(out);
                event.markForReEncode(true);
                FMMBedrockBridge.debugLog("[PHASE77] rebased " + changed
                        + " menu icon(s) for Bedrock in window " + wrapper.getWindowId());
            } else {
                WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
                if (wrapper.getWindowId() <= 0) return;
                ItemStack item = wrapper.getItem();
                ItemStack rebased = MenuIconRebaser.rebase(item, prefixes, excludes);
                if (rebased == item) return;
                wrapper.setItem(rebased);
                event.markForReEncode(true);
                FMMBedrockBridge.debugLog("[PHASE77] rebased 1 menu icon(s) for Bedrock in window "
                        + wrapper.getWindowId());
            }
        } catch (Throwable t) {
            FMMBedrockBridge.debugLog("[PHASE77] icon rebase failed: " + t);
        }
    }

    public BedrockNameFix getNameFix() {
        return nameFix;
    }

    public void clear() {
        nameFix.clear();
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
    private void handleBossEvent(PacketSendEvent event, Player playerObj) {
        if (bridge == null) return;
        if (!isSuppressEnabled()) return;
        if (!Boolean.TRUE.equals(isBedrock(playerObj))) return;

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
