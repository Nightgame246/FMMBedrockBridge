package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the eviction contract the Phase 7.1a suppression relies on since EliteMobs 10.8.0
 * pools and re-titles its boss bars. The interceptor itself needs a live Bukkit/PacketEvents
 * stack and is verified in-game; what is testable here is that the registry actually supports
 * being drained rather than only filled.
 */
class BossBarRegistryTest {

    @Test
    void addThenContains() {
        BossBarRegistry registry = new BossBarRegistry();
        UUID uuid = UUID.randomUUID();

        assertFalse(registry.contains(uuid));
        registry.add(uuid);
        assertTrue(registry.contains(uuid));
        assertEquals(1, registry.size());
    }

    @Test
    void removeReportsWhetherItWasSuppressed() {
        BossBarRegistry registry = new BossBarRegistry();
        UUID uuid = UUID.randomUUID();
        registry.add(uuid);

        // The interceptor logs a slot release only when it really released something.
        assertTrue(registry.remove(uuid), "removing a suppressed UUID must report true");
        assertFalse(registry.remove(uuid), "removing an unknown UUID must report false");
        assertFalse(registry.contains(uuid));
    }

    @Test
    void recycledSlotCanBeSuppressedAgainLater() {
        // A pooled EliteMobs bar: suppressed for boss A, released when the slot is freed,
        // then suppressed again once the slot shows another boss we also draw.
        BossBarRegistry registry = new BossBarRegistry();
        UUID pooledSlot = UUID.randomUUID();

        registry.add(pooledSlot);
        assertTrue(registry.remove(pooledSlot));
        assertFalse(registry.contains(pooledSlot));

        registry.add(pooledSlot);
        assertTrue(registry.contains(pooledSlot));
        assertEquals(1, registry.size());
    }

    @Test
    void independentUuidsDoNotInterfere() {
        BossBarRegistry registry = new BossBarRegistry();
        UUID slotOne = UUID.randomUUID();
        UUID slotTwo = UUID.randomUUID();

        registry.add(slotOne);
        registry.add(slotTwo);
        registry.remove(slotOne);

        assertFalse(registry.contains(slotOne));
        assertTrue(registry.contains(slotTwo), "releasing one pooled slot must not free the others");
        assertEquals(1, registry.size());
    }

    @Test
    void clearDropsEverything() {
        BossBarRegistry registry = new BossBarRegistry();
        registry.add(UUID.randomUUID());
        registry.add(UUID.randomUUID());

        registry.clear();

        assertEquals(0, registry.size());
    }
}
