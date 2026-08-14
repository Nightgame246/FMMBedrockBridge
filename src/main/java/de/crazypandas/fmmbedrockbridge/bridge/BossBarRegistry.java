package de.crazypandas.fmmbedrockbridge.bridge;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concurrent set of EliteMobs BossBar UUIDs currently suppressed for Bedrock players.
 * Populated by {@link PacketInterceptor} when a BOSS_EVENT(ADD) packet's title matches an
 * active {@link BedrockBossBarController}.
 *
 * <p>Membership is <b>temporary, not permanent</b>. Since EliteMobs 10.8.0 these UUIDs belong
 * to a small pool of reusable bars that get re-titled for whichever boss currently occupies a
 * slot, so an entry is only valid while that slot still shows the boss we replaced. The
 * interceptor evicts entries via {@link #remove(UUID)} when a slot is released or recycled;
 * treating the set as write-only would freeze unrelated bars on Bedrock clients.
 */
public final class BossBarRegistry {

    private final Set<UUID> emManagedUuids = ConcurrentHashMap.newKeySet();

    public void add(UUID uuid) {
        emManagedUuids.add(uuid);
    }

    public boolean contains(UUID uuid) {
        return emManagedUuids.contains(uuid);
    }

    /** @return true if the UUID was suppressed until now. */
    public boolean remove(UUID uuid) {
        return emManagedUuids.remove(uuid);
    }

    public void clear() {
        emManagedUuids.clear();
    }

    public int size() {
        return emManagedUuids.size();
    }
}
