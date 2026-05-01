package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A fake packet-only entity (ARMOR_STAND) used to trigger Bedrock custom entity replacement.
 * The entity exists only as packets — no server-side entity is created.
 *
 * GeyserUtils intercepts the spawn packet for this fake entity ID,
 * finds it in the CUSTOM_ENTITIES cache, and replaces it with the Bedrock custom entity.
 *
 * ARMOR_STAND is used because it has no client-side AI (no head-tracking, no body-tracking).
 * The 180° orientation correction is handled by the virtual fmmbridge_root bone in the geometry
 * (rotation=[0,180,0]), so entity yaw is sent as-is without any correction here.
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
        this.type = EntityTypes.ARMOR_STAND;
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
        // The geometry has UV faces swapped (north↔south, east↔west) so the model's "front"
        // textures end up on the SOUTH face. Bedrock then needs the entity rotated 180° so
        // that face direction matches the configured NPC yaw. body_yaw and head_yaw are kept
        // equal to prevent any body-tracking from the underlying ARMOR_STAND.
        float correctedYaw = location.getYaw() + 180f;
        Location spawnLoc = location.clone();
        spawnLoc.setYaw(correctedYaw);
        WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(
                id, uuid, type,
                SpigotConversionUtil.fromBukkitLocation(spawnLoc),
                correctedYaw, 0, null
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
                location.getYaw() + 180f,
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

    /**
     * Sends entity metadata with custom name to the specified players.
     * Geyser translates Java entity metadata to Bedrock nametag automatically.
     *
     * @param customName The display name component, or null to clear
     * @param visible    Whether the nametag should be visible
     * @param players    Target players
     */
    public void sendNameMetadata(Component customName, boolean visible, Collection<Player> players) {
        List<EntityData<?>> metadata = new ArrayList<>();
        // Entity metadata index 2: Custom Name (Optional<Component>)
        metadata.add(new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                customName != null ? Optional.of(customName) : Optional.empty()));
        // Entity metadata index 3: Is Custom Name Visible (Boolean)
        metadata.add(new EntityData<>(3, EntityDataTypes.BOOLEAN, visible));

        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(id, metadata);
        for (Player player : players) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        }
    }

    public void sendNameMetadata(Component customName, boolean visible, Player player) {
        sendNameMetadata(customName, visible, Collections.singletonList(player));
    }

    public void remove(Collection<Player> viewers) {
        removed = true;
        if (!viewers.isEmpty()) {
            sendDestroyPacket(viewers);
        }
    }
}
