package de.crazypandas.fmmbedrockbridge.bridge;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.elite.EliteMobsHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Phase 7.3 — reroutes EliteMobs chest menus to EM's native MC dialog for Bedrock
 * players. When a Bedrock player opens a chest whose title matches a registered EM
 * menu, the chest is cancelled and EM's dialog is fired next tick; Geyser renders it
 * as a native Bedrock form and the dialog's sub-pages cascade natively.
 *
 * <p>Java players are never intercepted. Registered only on MC >= 1.21.6 (see
 * {@link FMMBedrockBridge#onEnable()}), so the dialog API is always present here.
 */
public final class BedrockMenuRerouteListener implements Listener {

    private final FMMBedrockBridge plugin;
    private final Logger log;
    private final MenuRerouteRegistry registry = new MenuRerouteRegistry();

    public BedrockMenuRerouteListener(FMMBedrockBridge plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        // Phase 7.3 ships one entry: EM's /em status index menu.
        registry.register(EliteMobsHook::statusIndexMenuTitle,
                p -> EliteMobsHook.openNativeStatusDialog(p));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) return;

        String title = safeTitle(event);
        if (title == null) return;

        if (plugin.getConfig().getBoolean("debug", false)) {
            log.info("[Phase7.3] Bedrock " + player.getName() + " opened inventory title='" + title + "'");
        }

        Optional<Consumer<Player>> invoker = registry.findInvoker(title);
        if (invoker.isEmpty()) return;

        // Suppress the Bedrock chest; fire EM's native dialog next tick (the backend
        // inventory state has settled and we are out of the open-event call stack).
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                invoker.get().accept(player);
            } catch (Throwable t) {
                log.warning("[Phase7.3] dialog reroute failed for " + player.getName() + ": " + t);
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
