package de.crazypandas.fmmbedrockbridge.bridge;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuRerouteRegistryTest {

    @Test
    void normalizeStripsColorCodesTrimAndLowercase() {
        assertEquals("elitemobs index", MenuRerouteRegistry.normalize("§2EliteMobs Index"));
        assertEquals("elitemobs index", MenuRerouteRegistry.normalize("&2EliteMobs Index"));
        assertEquals("elitemobs index", MenuRerouteRegistry.normalize("  EliteMobs Index  "));
        assertEquals("", MenuRerouteRegistry.normalize(null));
    }

    @Test
    void findInvokerMatchesAcrossColorFormatting() {
        AtomicBoolean fired = new AtomicBoolean(false);
        MenuRerouteRegistry registry = new MenuRerouteRegistry();
        registry.register(() -> "§2EliteMobs Index", p -> fired.set(true));

        Optional<Consumer<Player>> hit = registry.findInvoker("&2elitemobs index");
        assertTrue(hit.isPresent());
        hit.get().accept(null); // invoker body sets the flag; player unused here
        assertTrue(fired.get());
    }

    @Test
    void findInvokerReturnsEmptyOnNoMatchOrNull() {
        MenuRerouteRegistry registry = new MenuRerouteRegistry();
        registry.register(() -> "EliteMobs Index", p -> {});
        assertFalse(registry.findInvoker("Some Other Menu").isPresent());
        assertFalse(registry.findInvoker(null).isPresent());
    }

    @Test
    void nullTitleSupplierValueIsSkipped() {
        MenuRerouteRegistry registry = new MenuRerouteRegistry();
        registry.register(() -> null, p -> {});
        assertFalse(registry.findInvoker("anything").isPresent());
    }
}
