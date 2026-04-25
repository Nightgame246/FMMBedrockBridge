package de.crazypandas.fmmbedrockbridge.bridge;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Set;

public interface IBridgeEntityData {
    void addViewer(Player player);
    void removeViewer(Player player);
    /** Position sync, animation sync, etc. No-op for static entities. */
    void tick();
    void destroy();
    boolean isDestroyed();
    /** False if the underlying entity died/was removed (DynamicEntity). Static entities are always alive. */
    boolean isAlive();
    Set<Player> getViewers();
    PacketEntity getPacketEntity();
    Location getLocation();
}
