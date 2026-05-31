package de.crazypandas.fmmbedrockbridge.bridge;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Phase 7.2c — re-sends a Bedrock player's inventory after they move items.
 *
 * <p>When a Bedrock client moves an item between slots or switches the held
 * hotbar slot, the change is client-predicted and the backend sends no
 * SET_SLOT / WINDOW_ITEMS packet. {@link PacketInterceptor} therefore never
 * gets a chance to re-inject {@code item_model}, and Geyser falls back to the
 * vanilla item (or a foreign resource-pack mapping).
 *
 * <p>This listener schedules a {@link Player#updateInventory()} one tick after
 * such events for Floodgate (Bedrock) players. That forces a WINDOW_ITEMS
 * resend which the interceptor re-injects — no change to the server-side
 * ItemStacks themselves.
 */
public class BedrockInventoryRefresher implements Listener {

    private final FMMBedrockBridge plugin;

    public BedrockInventoryRefresher(FMMBedrockBridge plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) scheduleRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) scheduleRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(PlayerItemHeldEvent event) {
        scheduleRefresh(event.getPlayer());
    }

    private void scheduleRefresh(Player player) {
        if (!FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) return;
        // Next tick: the backend inventory state has settled after the move.
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }
}
