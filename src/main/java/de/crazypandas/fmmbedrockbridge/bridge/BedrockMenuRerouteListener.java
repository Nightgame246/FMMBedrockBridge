package de.crazypandas.fmmbedrockbridge.bridge;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.elite.EliteMobsHook;
import de.crazypandas.fmmbedrockbridge.elite.QuestMenuContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Phase 7.3 / 7.3b — reroutes EliteMobs chest menus to EM's native MC dialog for Bedrock
 * players. When a Bedrock player opens an EM menu the bridge recognises, the chest is
 * cancelled and EM's dialog is fired next tick; Geyser renders it as a native Bedrock form
 * and the dialog's sub-pages cascade natively.
 *
 * <p>Two recognition mechanisms:
 * <ul>
 *   <li><b>Status menu (7.3):</b> {@link MenuRerouteRegistry} title match → {@code openNativeStatusDialog}.</li>
 *   <li><b>NPC quest menu (7.3b):</b> {@link EliteMobsHook#tryRecoverQuestMenu} holder-map lookup
 *       (titles are dynamic) → {@code openNativeQuestDialog}.</li>
 * </ul>
 * Status takes precedence; per-flag gating lives in {@link RerouteDecision}.
 *
 * <p>Java players are never intercepted. Registered only on MC >= 1.21.6 (see
 * {@link FMMBedrockBridge#onEnable()}), so the dialog API is always present here.
 */
public final class BedrockMenuRerouteListener implements Listener {

    private final FMMBedrockBridge plugin;
    private final Logger log;
    private final MenuRerouteRegistry registry = new MenuRerouteRegistry();
    private final ViewerManager viewers;

    public BedrockMenuRerouteListener(FMMBedrockBridge plugin, ViewerManager viewers) {
        this.plugin = plugin;
        this.viewers = viewers;
        this.log = plugin.getLogger();
        // Phase 7.3 ships one entry: EM's /em status index menu.
        registry.register(EliteMobsHook::statusIndexMenuTitle,
                p -> EliteMobsHook.openNativeStatusDialog(p));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        // Same detection as EliteMobs: it forces exactly these players onto the chest menu.
        if (!viewers.isBedrockPlayer(player)) return;

        String title = safeTitle(event);

        if (plugin.getConfig().getBoolean("debug", false)) {
            log.info("[Phase7.3] Bedrock " + player.getName() + " opened inventory title='" + title + "'");
        }

        boolean statusEnabled = plugin.getConfig().getBoolean("phase73.bedrock-dialog-reroute", true);
        boolean questEnabled = plugin.getConfig().getBoolean("phase73.bedrock-quest-reroute", true);

        // Status menu (Phase 7.3): title-registry match.
        Optional<Consumer<Player>> statusInvoker =
                (statusEnabled && title != null) ? registry.findInvoker(title) : Optional.empty();

        // Quest menu (Phase 7.3b): holder-map recovery. Only attempted when status did NOT match,
        // so we never disturb EM's quest maps for a status-menu open. tryRecoverQuestMenu has a
        // side effect (removes the entry), hence the guard.
        Optional<QuestMenuContext> questCtx;
        if (statusInvoker.isPresent() || !questEnabled) {
            questCtx = Optional.empty();
        } else {
            questCtx = EliteMobsHook.tryRecoverQuestMenu(event.getInventory());
        }

        RerouteDecision.Action action = RerouteDecision.resolve(
                statusEnabled, statusInvoker.isPresent(), questEnabled, questCtx.isPresent());

        switch (action) {
            case STATUS -> {
                event.setCancelled(true);
                scheduleReroute(player, () -> statusInvoker.get().accept(player), "status");
            }
            case QUEST -> {
                event.setCancelled(true);
                QuestMenuContext ctx = questCtx.get();
                scheduleReroute(player,
                        () -> EliteMobsHook.openNativeQuestDialog(player, ctx.quests(), ctx.npcEntity()),
                        "quest");
            }
            case NONE -> {
                if (questEnabled && statusInvoker.isEmpty() && EliteMobsHook.isOpenedByQuestMenu()) {
                    // EM >= 10.9.8 registers the quest chest only after this event returns — and
                    // only if the open succeeded, so it must NOT be cancelled here.
                    rerouteQuestNextTick(player, event.getInventory());
                }
                // Otherwise not an EM menu we reroute; leave the inventory untouched.
            }
        }
    }

    /**
     * Phase 7.3b for EliteMobs >= 10.9.8: the chest is open and EM has put it into its quest maps
     * by now ({@code if (openInventory(...) != null) questDirectories.put(...)} — a cancelled open
     * is never registered). Recover it, close the chest and show the dialog; if that fails, the
     * chest simply stays open.
     */
    private void rerouteQuestNextTick(Player player, Inventory inventory) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (player.getOpenInventory().getTopInventory() != inventory) return; // already gone
            Optional<QuestMenuContext> ctx = EliteMobsHook.tryRecoverQuestMenu(inventory);
            boolean shown = false;
            try {
                if (ctx.isPresent()) {
                    player.closeInventory();
                    shown = EliteMobsHook.openNativeQuestDialog(player, ctx.get().quests(), ctx.get().npcEntity());
                }
            } catch (Throwable t) {
                log.warning("[Phase7.3] quest reroute failed for " + player.getName() + ": " + t);
            }
            if (plugin.getConfig().getBoolean("debug", false)) {
                log.info("[Phase7.3b] " + player.getName() + " quest chest -> "
                        + (shown ? "dialog" : "chest (not recoverable)"));
            }
            if (ctx.isPresent() && !shown) player.openInventory(inventory);
        });
    }

    private void scheduleReroute(Player player, Runnable invoke, String kind) {
        // Fire after the open-event call stack unwinds and the backend inventory state has settled.
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                invoke.run();
            } catch (Throwable t) {
                log.warning("[Phase7.3] " + kind + " reroute failed for " + player.getName() + ": " + t);
            }
        });
    }

    private static String safeTitle(InventoryOpenEvent event) {
        try {
            return event.getView().getTitle();
        } catch (Throwable t) {
            return null;
        }
    }
}
