package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import me.zimzaza4.geyserutils.spigot.api.EntityUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Bridges a FMM StaticEntity (prop/furniture) to Bedrock clients.
 *
 * Simpler than FMMEntityData: no underlying LivingEntity to hide, no animations,
 * no combat redirect. Just spawn a fake PIG at the static location and let
 * Geyser replace it with the custom model.
 */
public class StaticEntityData implements IBridgeEntityData {

    private static final Logger log = FMMBedrockBridge.getInstance().getLogger();

    private final ModeledEntity modeledEntity;
    private final Location location;
    private final PacketEntity packetEntity;
    private final String bedrockEntityId;
    private final Set<Player> viewers = ConcurrentHashMap.newKeySet();
    private boolean destroyed = false;

    public StaticEntityData(ModeledEntity modeledEntity, Location location, String bedrockEntityId) {
        this.modeledEntity = modeledEntity;
        this.location = location.clone();
        this.bedrockEntityId = bedrockEntityId;
        this.packetEntity = new PacketEntity(location);
    }

    @Override
    public void addViewer(Player player) {
        if (destroyed || viewers.contains(player)) return;

        EntityUtils.setCustomEntity(player, packetEntity.getEntityId(), bedrockEntityId);
        viewers.add(player);

        Bukkit.getScheduler().runTaskLater(FMMBedrockBridge.getInstance(), () -> {
            if (destroyed || !player.isOnline()) return;
            packetEntity.sendSpawnPacket(player);
            log.info("[BRIDGE] Static entity " + bedrockEntityId
                    + " (fakeId=" + packetEntity.getEntityId() + ") spawned for " + player.getName());
        }, 2L);
    }

    @Override
    public void removeViewer(Player player) {
        if (!viewers.remove(player)) return;
        packetEntity.sendDestroyPacket(player);
    }

    @Override
    public void tick() {
        // Static entities don't move
    }

    @Override
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        if (!viewers.isEmpty()) {
            packetEntity.remove(viewers);
        }
        viewers.clear();
        log.info("[BRIDGE] Destroyed static entity data for " + bedrockEntityId);
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    @Override
    public boolean isAlive() {
        return !destroyed;
    }

    @Override
    public Set<Player> getViewers() {
        return Collections.unmodifiableSet(viewers);
    }

    @Override
    public PacketEntity getPacketEntity() {
        return packetEntity;
    }

    @Override
    public Location getLocation() {
        return location;
    }
}
