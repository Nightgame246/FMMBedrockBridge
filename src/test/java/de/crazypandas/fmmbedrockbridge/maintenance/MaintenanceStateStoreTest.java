package de.crazypandas.fmmbedrockbridge.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceStateStoreTest {

    @Test
    void loadReturnsNullWhenFileMissing(@TempDir Path tmp) {
        MaintenanceState loaded = MaintenanceStateStore.load(tmp);
        assertNull(loaded, "Missing state file → null (signals first-run)");
    }

    @Test
    void saveAndLoadRoundtrip(@TempDir Path tmp) {
        Instant now = Instant.parse("2026-05-31T14:00:00Z");
        MaintenanceState s = new MaintenanceState(
                "abc123def456", now, "10.4.0", now);
        MaintenanceStateStore.save(tmp, s);

        assertTrue(tmp.resolve("maintenance-state.json").toFile().exists());

        MaintenanceState loaded = MaintenanceStateStore.load(tmp);
        assertEquals(s, loaded, "Round-trip must preserve all 4 record fields");
    }

    @Test
    void corruptedFileLoadsAsNull(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("maintenance-state.json");
        Files.writeString(file, "{ this is not valid json }");
        assertNull(MaintenanceStateStore.load(tmp));
    }
}
