package de.crazypandas.fmmbedrockbridge.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceTrackerTest {

    @Test
    void firstRunAutoMarksAsBaseline(@TempDir Path tmp) {
        MaintenanceTracker t = new MaintenanceTracker(tmp);
        MaintenanceTracker.Result r = t.evaluate("hash1", "10.4.0");
        assertFalse(r.driftActive());
        assertTrue(r.firstRun());
        assertNotNull(MaintenanceStateStore.load(tmp));
        assertEquals("hash1", MaintenanceStateStore.load(tmp).deployedHash());
    }

    @Test
    void noDriftWhenHashesMatch(@TempDir Path tmp) {
        new MaintenanceTracker(tmp).evaluate("hash1", "10.4.0"); // baseline
        MaintenanceTracker.Result r = new MaintenanceTracker(tmp).evaluate("hash1", "10.4.0");
        assertFalse(r.driftActive());
        assertFalse(r.firstRun());
    }

    @Test
    void driftWhenHashesDiffer(@TempDir Path tmp) {
        new MaintenanceTracker(tmp).evaluate("hash1", "10.4.0"); // baseline
        MaintenanceTracker.Result r = new MaintenanceTracker(tmp).evaluate("hash2", "10.4.1");
        assertTrue(r.driftActive());
        assertEquals("hash1", r.deployedHash());
        assertEquals("hash2", r.currentHash());
        assertEquals("10.4.0", r.deployedEmVersion());
    }

    @Test
    void markDeployedClearsDrift(@TempDir Path tmp) {
        MaintenanceTracker t = new MaintenanceTracker(tmp);
        t.evaluate("hash1", "10.4.0"); // baseline
        t.evaluate("hash2", "10.4.1"); // drift now active
        t.markDeployed("hash2", "10.4.1");
        MaintenanceTracker.Result r = new MaintenanceTracker(tmp).evaluate("hash2", "10.4.1");
        assertFalse(r.driftActive());
    }
}
