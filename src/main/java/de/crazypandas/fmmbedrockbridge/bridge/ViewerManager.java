package de.crazypandas.fmmbedrockbridge.bridge;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Tracks Bedrock players and determines which FMM entities they can see.
 */
public class ViewerManager implements Listener {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    private final boolean floodgateAvailable;
    private final Set<Player> readyPlayers = ConcurrentHashMap.newKeySet();
    private Consumer<Player> onPlayerReady;
    private Consumer<Player> onPlayerLeave;

    public ViewerManager(boolean floodgateAvailable) {
        this.floodgateAvailable = floodgateAvailable;
    }

    public void setOnPlayerReady(Consumer<Player> callback) {
        this.onPlayerReady = callback;
    }

    public void setOnPlayerLeave(Consumer<Player> callback) {
        this.onPlayerLeave = callback;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isBedrockPlayer(player)) return;

        Bukkit.getScheduler().runTaskLater(FMMBedrockBridge.getInstance(), () -> {
            if (!player.isOnline()) return;
            readyPlayers.add(player);
            log.fine("[BRIDGE] Bedrock player " + player.getName() + " is now ready");
            if (onPlayerReady != null) onPlayerReady.accept(player);
        }, 60L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        readyPlayers.remove(player);
        if (onPlayerLeave != null) onPlayerLeave.accept(player);
    }

    public Set<Player> getReadyPlayers() {
        return Collections.unmodifiableSet(readyPlayers);
    }

    public void removeOffline() {
        readyPlayers.removeIf(p -> !p.isOnline());
    }

    public boolean isBedrockPlayer(Player player) {
        if (!floodgateAvailable) return false;
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    public static boolean isInRange(Player player, Entity entity) {
        return isInRange(player, entity.getLocation());
    }

    public static boolean isInRange(Player player, Location entityLoc) {
        if (entityLoc == null) return false;
        Location playerLoc = player.getLocation();
        if (playerLoc.getWorld() != entityLoc.getWorld()) return false;

        double dx = playerLoc.getX() - entityLoc.getX();
        double dz = playerLoc.getZ() - entityLoc.getZ();
        double horizontalDistSq = dx * dx + dz * dz;

        int viewDist = player.getSendViewDistance();
        double maxDistSq = (double) viewDist * viewDist * 48;
        return horizontalDistSq <= maxDistSq;
    }

    public void clear() {
        readyPlayers.clear();
    }
}
