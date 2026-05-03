package de.crazypandas.fmmbedrockbridge.bridge;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concurrent set of EliteMobs BossBar UUIDs we've captured for suppression on
 * Bedrock players. Populated heuristically by {@link PacketInterceptor} when a
 * BOSS_EVENT(ADD) packet's title matches an active {@link BedrockBossBarController}.
 */
public final class BossBarRegistry {

    private final Set<UUID> emManagedUuids = ConcurrentHashMap.newKeySet();

    public void add(UUID uuid) {
        emManagedUuids.add(uuid);
    }

    public boolean contains(UUID uuid) {
        return emManagedUuids.contains(uuid);
    }

    public void remove(UUID uuid) {
        emManagedUuids.remove(uuid);
    }

    public void clear() {
        emManagedUuids.clear();
    }

    public int size() {
        return emManagedUuids.size();
    }
}
