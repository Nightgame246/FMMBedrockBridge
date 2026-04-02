package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A fake packet-only entity (PIG) used to trigger Bedrock custom entity replacement.
 * The entity exists only as packets — no server-side entity is created.
 *
 * GeyserUtils intercepts the spawn packet for this fake entity ID,
 * finds it in the CUSTOM_ENTITIES cache, and replaces it with the Bedrock custom entity.
 */
public class PacketEntity {

    private final int id;
    private final UUID uuid;
    private final EntityType type;
    private Location location;
    private boolean removed = false;

    public PacketEntity(Location location) {
        this.id = ThreadLocalRandom.current().nextInt(300_000_000, 400_000_000);
        this.uuid = UUID.randomUUID();
        this.type = EntityTypes.PIG;
        this.location = location.clone();
    }

    public int getEntityId() {
        return id;
    }

    public Location getLocation() {
        return location;
    }

    public boolean isDead() {
        return removed;
    }

    /**
     * Updates position and sends teleport packet if changed.
     * Returns true if a packet was sent.
     */
    public boolean teleport(Location newLocation, Collection<Player> viewers) {
        if (newLocation.getWorld() == null) return false;

        boolean changed = location.getWorld() != newLocation.getWorld()
                || location.distanceSquared(newLocation) > 0.000001
                || location.getYaw() != newLocation.getYaw()
                || location.getPitch() != newLocation.getPitch();

        this.location = newLocation.clone();
        if (changed && !viewers.isEmpty()) {
            sendLocationPacket(viewers);
        }
        return changed;
    }

    public void sendSpawnPacket(Collection<Player> players) {
        WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(
                id, uuid, type,
                SpigotConversionUtil.fromBukkitLocation(location),
                location.getYaw(), 0, null
        );
        for (Player player : players) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        }
    }

    public void sendSpawnPacket(Player player) {
        sendSpawnPacket(Collections.singletonList(player));
    }

    public void sendLocationPacket(Collection<Player> players) {
        EntityPositionData data = new EntityPositionData(
                SpigotConversionUtil.fromBukkitLocation(location).getPosition(),
                Vector3d.zero(),
                location.getYaw(),
                location.getPitch()
        );

        PacketWrapper<?> packet;
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_21_2)) {
            packet = new WrapperPlayServerEntityPositionSync(id, data, false);
        } else {
            packet = new WrapperPlayServerEntityTeleport(id, data, RelativeFlag.NONE, false);
        }

        for (Player player : players) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        }
    }

    public void sendDestroyPacket(Collection<Player> players) {
        WrapperPlayServerDestroyEntities packet = new WrapperPlayServerDestroyEntities(id);
        for (Player player : players) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        }
    }

    public void sendDestroyPacket(Player player) {
        sendDestroyPacket(Collections.singletonList(player));
    }

    public void remove(Collection<Player> viewers) {
        removed = true;
        if (!viewers.isEmpty()) {
            sendDestroyPacket(viewers);
        }
    }
}
