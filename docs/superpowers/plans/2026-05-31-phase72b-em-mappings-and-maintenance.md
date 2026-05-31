# Phase 7.2b Reimplementation + Wartungs-Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 7.2b (EM 2D-Items auf Bedrock) reparieren weil der bisherige `geyser_custom:em_*` Identifier seit RPM 2.0.0 tot ist, und dabei ein selbst-bewusstes Wartungs-System einbauen das Admins per Console-Log + Op-Chat-Nachricht (deutsch) erinnert wenn das EM-Resource-Pack sich ändert und Bridge-Outputs auf dem Proxy stale sind.

**Architecture:** Die Bridge generiert beim Boot zwei Artefakte unter `plugins/FMMBedrockBridge/bedrock-pack/` — (1) ein eigenständiges Mini-Bedrock-Pack `em_bridge_pack.mcpack` das pro 2D-EM-Item ein Custom Bedrock-Item mit `bridge_em:<key>` Identifier definiert (manifest.json + textures/items/em_<key>.png + textures/item_texture.json), und (2) eine Geyser custom-item-v2 Mappings-Datei `em_bridge_mappings.json` die jeden (`minecraft:emerald`, `custom_model_data=<cmd>`) Pair an den korrespondierenden Bedrock-Identifier mapped. Diese werden per SCP nach `Proxy01/Geyser-Velocity/packs/` bzw. `custom_mappings/` deployed; Geyser registriert sie beim Boot. Der `PacketInterceptor` inject dann `item_model = bridge_em:<key>` in SET_SLOT/WINDOW_ITEMS/ENTITY_METADATA für Bedrock-Spieler. Parallel berechnet eine `PackHashCalculator`-Klasse einen deterministischen SHA-256 über alle gescannten EM-Items (sortiert nach `(material, cmd)`, inkl. PNG-Bytes), persistiert ihn in `plugins/FMMBedrockBridge/maintenance-state.json` als "deployed hash", und vergleicht beim nächsten Boot — wenn diff, wird Drift-Flag gesetzt, Console-WARN ausgegeben (4-zeilig markant), und beim Op-Join eine deutsche Chat-Component mit ClickEvent geschickt. Wenn der Hash beim Boot bereits dem deployed Hash entspricht, wird stille auto-mark Logik angewendet (kein Warn, kein Chat).

**Tech Stack:** Java 21, Paper API 1.21.4, Gson (für JSON-State + Mappings + Pack-Manifest), PacketEvents 2.12.1, Floodgate API, JUnit 5 (Tests), java.util.zip (Mini-Pack-Zip), java.security.MessageDigest (SHA-256), Adventure API (Component mit ClickEvent für Op-Chat-Notify), Bukkit Listener API (PlayerJoinEvent).

---

## File Structure

**Neu (Backend Bridge):**
- `src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/PackHashCalculator.java` — SHA-256 über sortierte Items + PNG-Bytes
- `src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceState.java` — Record (deployedHash, deployedAt, deployedEmVersion, lastChecked)
- `src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceStateStore.java` — Gson load/save für `maintenance-state.json`
- `src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceTracker.java` — Drift-Evaluation, Auto-Mark, State-Mutation
- `src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/OpDriftNotifier.java` — Bukkit-Listener für PlayerJoinEvent → deutsche Chat-Component
- `src/main/java/de/crazypandas/fmmbedrockbridge/bedrock/BedrockItemPackBuilder.java` — Mini-Bedrock-Pack `.mcpack` Zip-Generator
- `src/main/java/de/crazypandas/fmmbedrockbridge/bedrock/GeyserMappingsWriter.java` — Geyser custom-item-v2 JSON writer

**Modifiziert:**
- `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java` — inject `bridge_em:<key>` statt `geyser_custom:em_<key>`
- `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java` — Wire-up: Pack-Build + Mappings-Write + State-Load + Drift-Flag + Listener-Register
- `src/main/java/de/crazypandas/fmmbedrockbridge/commands/FMMBridgeCommand.java` — Neue Subcommands `maintenance status`/`redeploy-instructions`/`mark-deployed` (deutsche Output-Strings)
- `src/main/resources/config.yml` — Neue Section `maintenance:` mit `auto-mark-first-run`, `op-chat-notify`, `pack-output-path`

**Tests:**
- `src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/PackHashCalculatorTest.java`
- `src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceStateStoreTest.java`
- `src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceTrackerTest.java`
- `src/test/java/de/crazypandas/fmmbedrockbridge/bedrock/BedrockItemPackBuilderTest.java`
- `src/test/java/de/crazypandas/fmmbedrockbridge/bedrock/GeyserMappingsWriterTest.java`

**Build & Deploy-Doc:**
- Update `README.md` Sektion Deployment (em_bridge_pack.mcpack + em_bridge_mappings.json SCP-Schritte ergänzen)
- Update `CLAUDE_SESSION.md` Session-Log
- Update `CLAUDE.md` Architektur-Sektion (`bridge_em:<key>` Identifier-Convention)

---

## Task 1: PackHashCalculator

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/PackHashCalculator.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/PackHashCalculatorTest.java`

- [ ] **Step 1: Write failing tests**

`src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/PackHashCalculatorTest.java`:

```java
package de.crazypandas.fmmbedrockbridge.maintenance;

import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PackHashCalculatorTest {

    @Test
    void emptyListProducesStableHash(@TempDir Path tmp) {
        String h1 = PackHashCalculator.compute(List.of());
        String h2 = PackHashCalculator.compute(List.of());
        assertEquals(h1, h2);
        assertEquals(64, h1.length()); // SHA-256 hex
    }

    @Test
    void orderIndependent(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("a.png");
        Files.write(png, new byte[]{1, 2, 3});
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, png.toString(), "em_a");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 2, png.toString(), "em_b");

        String h1 = PackHashCalculator.compute(List.of(a, b));
        String h2 = PackHashCalculator.compute(List.of(b, a));
        assertEquals(h1, h2, "Hash must be order-independent (sort inside compute)");
    }

    @Test
    void cmdDifferenceChangesHash(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("a.png");
        Files.write(png, new byte[]{1, 2, 3});
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, png.toString(), "em_a");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 2, png.toString(), "em_a");

        assertNotEquals(PackHashCalculator.compute(List.of(a)),
                        PackHashCalculator.compute(List.of(b)));
    }

    @Test
    void pngBytesDifferenceChangesHash(@TempDir Path tmp) throws Exception {
        Path p1 = tmp.resolve("a.png");
        Path p2 = tmp.resolve("b.png");
        Files.write(p1, new byte[]{1, 2, 3});
        Files.write(p2, new byte[]{4, 5, 6});

        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, p1.toString(), "em_a");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 1, p2.toString(), "em_a");

        assertNotEquals(PackHashCalculator.compute(List.of(a)),
                        PackHashCalculator.compute(List.of(b)),
                "PNG content change must change hash even when key stays same");
    }

    @Test
    void missingPngFileTreatedAsEmptyBytes(@TempDir Path tmp) {
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, tmp.resolve("nope.png").toString(), "em_a");
        // Should not throw, just treat missing as empty bytes
        String h = PackHashCalculator.compute(List.of(a));
        assertEquals(64, h.length());
    }
}
```

- [ ] **Step 2: Run test to verify FAIL**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q -Dtest=PackHashCalculatorTest test`
Expected: COMPILATION FAILURE (`PackHashCalculator` not found)

- [ ] **Step 3: Implement PackHashCalculator**

`src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/PackHashCalculator.java`:

```java
package de.crazypandas.fmmbedrockbridge.maintenance;

import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class PackHashCalculator {

    private PackHashCalculator() {}

    public static String compute(List<EMCustomItem> items) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        items.stream()
                .sorted(Comparator
                        .comparing(EMCustomItem::javaMaterial)
                        .thenComparingInt(EMCustomItem::customModelData)
                        .thenComparing(EMCustomItem::bedrockTextureKey))
                .forEach(item -> {
                    md.update((item.javaMaterial() + "|" + item.customModelData()
                            + "|" + item.bedrockTextureKey() + "|").getBytes(StandardCharsets.UTF_8));
                    md.update(readPngBytes(item.sourceTexturePath()));
                    md.update((byte) '\n');
                });

        return HexFormat.of().formatHex(md.digest());
    }

    private static byte[] readPngBytes(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
```

- [ ] **Step 4: Run test to verify PASS**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q -Dtest=PackHashCalculatorTest test`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/PackHashCalculator.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/PackHashCalculatorTest.java
git commit -m "Phase 7.2b/maintenance: PackHashCalculator (SHA-256 über EM-Items + PNG-Bytes)"
```

---

## Task 2: MaintenanceState + MaintenanceStateStore

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceState.java`
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceStateStore.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceStateStoreTest.java`

- [ ] **Step 1: Write failing tests**

`src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceStateStoreTest.java`:

```java
package de.crazypandas.fmmbedrockbridge.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertEquals("abc123def456", loaded.deployedHash());
        assertEquals(now, loaded.deployedAt());
        assertEquals("10.4.0", loaded.deployedEmVersion());
    }

    @Test
    void corruptedFileLoadsAsNull(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("maintenance-state.json");
        java.nio.file.Files.writeString(file, "{ this is not valid json }");
        assertNull(MaintenanceStateStore.load(tmp));
    }
}
```

- [ ] **Step 2: Run test to verify FAIL**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q -Dtest=MaintenanceStateStoreTest test`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Implement MaintenanceState (record)**

`src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceState.java`:

```java
package de.crazypandas.fmmbedrockbridge.maintenance;

import java.time.Instant;

public record MaintenanceState(
        String deployedHash,
        Instant deployedAt,
        String deployedEmVersion,
        Instant lastChecked
) {
}
```

- [ ] **Step 4: Implement MaintenanceStateStore**

`src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceStateStore.java`:

```java
package de.crazypandas.fmmbedrockbridge.maintenance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class MaintenanceStateStore {

    private static final String FILE_NAME = "maintenance-state.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MaintenanceStateStore() {}

    public static MaintenanceState load(Path dataFolder) {
        Path file = dataFolder.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return null;
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return new MaintenanceState(
                    root.get("deployedHash").getAsString(),
                    Instant.parse(root.get("deployedAt").getAsString()),
                    root.get("deployedEmVersion").getAsString(),
                    Instant.parse(root.get("lastChecked").getAsString())
            );
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(Path dataFolder, MaintenanceState state) {
        try {
            Files.createDirectories(dataFolder);
            JsonObject root = new JsonObject();
            root.addProperty("deployedHash", state.deployedHash());
            root.addProperty("deployedAt", state.deployedAt().toString());
            root.addProperty("deployedEmVersion", state.deployedEmVersion());
            root.addProperty("lastChecked", state.lastChecked().toString());
            Files.writeString(dataFolder.resolve(FILE_NAME), GSON.toJson(root));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save maintenance state: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: Run test to verify PASS**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q -Dtest=MaintenanceStateStoreTest test`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceState.java \
        src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceStateStore.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceStateStoreTest.java
git commit -m "Phase 7.2b/maintenance: MaintenanceState record + JSON store"
```

---

## Task 3: MaintenanceTracker

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceTracker.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceTrackerTest.java`

- [ ] **Step 1: Write failing tests**

`src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceTrackerTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify FAIL**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q -Dtest=MaintenanceTrackerTest test`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Implement MaintenanceTracker**

`src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceTracker.java`:

```java
package de.crazypandas.fmmbedrockbridge.maintenance;

import java.nio.file.Path;
import java.time.Instant;

public final class MaintenanceTracker {

    private final Path dataFolder;

    public MaintenanceTracker(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    public record Result(
            boolean firstRun,
            boolean driftActive,
            String currentHash,
            String deployedHash,
            String deployedEmVersion,
            Instant deployedAt
    ) {}

    public Result evaluate(String currentHash, String currentEmVersion) {
        Instant now = Instant.now();
        MaintenanceState state = MaintenanceStateStore.load(dataFolder);

        if (state == null) {
            MaintenanceState fresh = new MaintenanceState(currentHash, now, currentEmVersion, now);
            MaintenanceStateStore.save(dataFolder, fresh);
            return new Result(true, false, currentHash, currentHash, currentEmVersion, now);
        }

        boolean drift = !currentHash.equals(state.deployedHash());

        MaintenanceState updated = new MaintenanceState(
                state.deployedHash(), state.deployedAt(), state.deployedEmVersion(), now);
        MaintenanceStateStore.save(dataFolder, updated);

        return new Result(false, drift, currentHash, state.deployedHash(),
                state.deployedEmVersion(), state.deployedAt());
    }

    public void markDeployed(String hash, String emVersion) {
        Instant now = Instant.now();
        MaintenanceStateStore.save(dataFolder,
                new MaintenanceState(hash, now, emVersion, now));
    }
}
```

- [ ] **Step 4: Run test to verify PASS**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q -Dtest=MaintenanceTrackerTest test`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceTracker.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceTrackerTest.java
git commit -m "Phase 7.2b/maintenance: MaintenanceTracker (drift detect + auto-mark + mark-deployed)"
```

---

## Task 4: BedrockItemPackBuilder (Mini-Pack .mcpack)

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/bedrock/BedrockItemPackBuilder.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/bedrock/BedrockItemPackBuilderTest.java`

**Context:** Erzeugt ein eigenständiges Bedrock-Resource-Pack als `.mcpack`-Zip mit:
- `manifest.json` — UUIDs (deterministisch aus Pack-Hash gewählt, damit Bedrock-Cache nicht über-aggressiv invalidiert), pack_format 2, modules + dependencies
- `pack_icon.png` — 16×16 transparenter Placeholder
- `textures/items/em_<key>.png` — pro 2D-Item kopierte EM-PNG
- `textures/item_texture.json` — Mapping `em_<key>` → `textures/items/em_<key>` (kein `.png` Suffix!)

- [ ] **Step 1: Write failing tests**

`src/test/java/de/crazypandas/fmmbedrockbridge/bedrock/BedrockItemPackBuilderTest.java`:

```java
package de.crazypandas.fmmbedrockbridge.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockItemPackBuilderTest {

    @Test
    void writesValidMcpackZip(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("source.png");
        Files.write(png, new byte[]{(byte)0x89, 'P', 'N', 'G'});
        EMCustomItem item = new EMCustomItem("minecraft:emerald", 31173, png.toString(), "em_bagofcoins");

        Path outZip = tmp.resolve("em_bridge_pack.mcpack");
        BedrockItemPackBuilder.build(List.of(item), "abc123def4", outZip);

        assertTrue(Files.exists(outZip));
        Map<String, byte[]> contents = readZip(outZip);

        assertTrue(contents.containsKey("manifest.json"), "Must contain manifest.json");
        assertTrue(contents.containsKey("pack_icon.png"), "Must contain pack_icon.png");
        assertTrue(contents.containsKey("textures/items/em_bagofcoins.png"), "Must contain item PNG");
        assertTrue(contents.containsKey("textures/item_texture.json"), "Must contain item_texture.json");
    }

    @Test
    void manifestHasResourcesModuleAndStableUuidsFromHash(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("source.png");
        Files.write(png, new byte[]{(byte)0x89, 'P', 'N', 'G'});
        EMCustomItem item = new EMCustomItem("minecraft:emerald", 1, png.toString(), "em_a");

        Path outZip = tmp.resolve("em_bridge_pack.mcpack");
        BedrockItemPackBuilder.build(List.of(item), "deadbeef12", outZip);
        Path outZip2 = tmp.resolve("em_bridge_pack2.mcpack");
        BedrockItemPackBuilder.build(List.of(item), "deadbeef12", outZip2);

        String mf1 = new String(readZip(outZip).get("manifest.json"), StandardCharsets.UTF_8);
        String mf2 = new String(readZip(outZip2).get("manifest.json"), StandardCharsets.UTF_8);
        // Same hash → same UUID derivation, but timestamp version may differ.
        JsonObject m1 = JsonParser.parseString(mf1).getAsJsonObject();
        JsonObject m2 = JsonParser.parseString(mf2).getAsJsonObject();

        String headerUuid1 = m1.getAsJsonObject("header").get("uuid").getAsString();
        String headerUuid2 = m2.getAsJsonObject("header").get("uuid").getAsString();
        assertEquals(headerUuid1, headerUuid2, "Same input hash → same header UUID");

        assertTrue(m1.getAsJsonArray("modules").size() >= 1);
        assertEquals("resources", m1.getAsJsonArray("modules").get(0).getAsJsonObject()
                .get("type").getAsString());
    }

    @Test
    void itemTextureJsonMapsKeysCorrectly(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("a.png");
        Files.write(png, new byte[]{1});
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, png.toString(), "em_bagofcoins");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 2, png.toString(), "em_anvilhammer");

        Path outZip = tmp.resolve("p.mcpack");
        BedrockItemPackBuilder.build(List.of(a, b), "h", outZip);

        String json = new String(readZip(outZip).get("textures/item_texture.json"), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("fmmbridge_em", root.get("resource_pack_name").getAsString());
        JsonObject data = root.getAsJsonObject("texture_data");
        assertEquals("textures/items/em_bagofcoins",
                data.getAsJsonObject("em_bagofcoins").get("textures").getAsString());
        assertEquals("textures/items/em_anvilhammer",
                data.getAsJsonObject("em_anvilhammer").get("textures").getAsString());
    }

    private Map<String, byte[]> readZip(Path zipPath) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream in = zf.getInputStream(e); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    in.transferTo(bos);
                    out.put(e.getName(), bos.toByteArray());
                }
            }
        }
        return out;
    }
}
```

- [ ] **Step 2: Run test to verify FAIL**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q -Dtest=BedrockItemPackBuilderTest test`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Implement BedrockItemPackBuilder**

`src/main/java/de/crazypandas/fmmbedrockbridge/bedrock/BedrockItemPackBuilder.java`:

```java
package de.crazypandas.fmmbedrockbridge.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BedrockItemPackBuilder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 16x16 fully transparent PNG used as pack_icon.png placeholder.
    // Hex dump of a minimal transparent PNG; embedded so we don't ship a binary resource.
    private static final byte[] TRANSPARENT_PNG_16 = java.util.HexFormat.of().parseHex(
            "89504e470d0a1a0a0000000d49484452000000100000001008060000001ff3ff61"
          + "0000000d49444154789c63f8ffff3f0303000700026100087fcafe0000000049454e44ae426082"
    );

    private BedrockItemPackBuilder() {}

    public static void build(List<EMCustomItem> items, String packHashHex, Path outputMcpack)
            throws IOException {
        Files.createDirectories(outputMcpack.getParent());

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outputMcpack))) {
            // manifest.json
            writeEntry(zip, "manifest.json", buildManifest(packHashHex).getBytes(StandardCharsets.UTF_8));

            // pack_icon.png
            writeEntry(zip, "pack_icon.png", TRANSPARENT_PNG_16);

            // textures/items/<key>.png — copy each unique key once (dedup by bedrockTextureKey)
            java.util.Set<String> writtenKeys = new java.util.HashSet<>();
            for (EMCustomItem item : items) {
                if (!writtenKeys.add(item.bedrockTextureKey())) continue;
                byte[] png = Files.exists(Path.of(item.sourceTexturePath()))
                        ? Files.readAllBytes(Path.of(item.sourceTexturePath()))
                        : TRANSPARENT_PNG_16;
                writeEntry(zip, "textures/items/" + item.bedrockTextureKey() + ".png", png);
            }

            // textures/item_texture.json
            writeEntry(zip, "textures/item_texture.json",
                    buildItemTextureJson(items, writtenKeys).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String buildManifest(String packHashHex) {
        UUID headerUuid = deterministicUuid("header:" + packHashHex);
        UUID moduleUuid = deterministicUuid("module:" + packHashHex);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);

        JsonObject header = new JsonObject();
        header.addProperty("name", "FMMBedrockBridge EM Items");
        header.addProperty("description", "2D EliteMobs custom_model_data items for Bedrock clients");
        header.addProperty("uuid", headerUuid.toString());
        JsonArray version = new JsonArray();
        version.add(1); version.add(0);
        // System.currentTimeMillis() lo-16-bits → bumps on every rebuild so Bedrock cache invalidates
        version.add((int) (System.currentTimeMillis() & 0xFFFF));
        header.add("version", version);
        JsonArray minEngine = new JsonArray();
        minEngine.add(1); minEngine.add(20); minEngine.add(0);
        header.add("min_engine_version", minEngine);
        root.add("header", header);

        JsonArray modules = new JsonArray();
        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        module.addProperty("uuid", moduleUuid.toString());
        module.add("version", version);
        modules.add(module);
        root.add("modules", modules);

        return GSON.toJson(root);
    }

    private static String buildItemTextureJson(List<EMCustomItem> items, java.util.Set<String> writtenKeys) {
        JsonObject root = new JsonObject();
        root.addProperty("resource_pack_name", "fmmbridge_em");
        root.addProperty("texture_name", "atlas.items");

        JsonObject data = new JsonObject();
        for (EMCustomItem item : items) {
            if (!writtenKeys.contains(item.bedrockTextureKey())) continue;
            if (data.has(item.bedrockTextureKey())) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("textures", "textures/items/" + item.bedrockTextureKey());
            data.add(item.bedrockTextureKey(), entry);
        }
        root.add("texture_data", data);
        return GSON.toJson(root);
    }

    private static UUID deterministicUuid(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            long msb = 0L, lsb = 0L;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (hash[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (hash[i] & 0xff);
            // RFC 4122 version 4 stamp
            msb &= ~(0xfL << 12); msb |= (0x4L << 12);
            lsb &= ~(0xcL << 60); lsb |= (0x8L << 60);
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }
}
```

- [ ] **Step 4: Run test to verify PASS**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q -Dtest=BedrockItemPackBuilderTest test`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bedrock/BedrockItemPackBuilder.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bedrock/BedrockItemPackBuilderTest.java
git commit -m "Phase 7.2b: BedrockItemPackBuilder writes mini .mcpack mit manifest+textures+item_texture.json"
```

---

## Task 5: GeyserMappingsWriter

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/bedrock/GeyserMappingsWriter.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/bedrock/GeyserMappingsWriterTest.java`

**Context:** Schreibt Geyser custom-item-v2 Mappings im selben Format wie RPM's `rspm_geyser_mappings.json`. Pro EM-Item ein definition-Entry mit `bedrock_identifier: bridge_em:<key>`, `bedrock_options.icon: <key>`, und `model: bridge_em:<key>` (wir injecten denselben Pfad als item_model im PacketInterceptor). Strukturiert nach `items.minecraft:<base>[]` Liste.

- [ ] **Step 1: Write failing tests**

`src/test/java/de/crazypandas/fmmbedrockbridge/bedrock/GeyserMappingsWriterTest.java`:

```java
package de.crazypandas.fmmbedrockbridge.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeyserMappingsWriterTest {

    @Test
    void writesFormatVersion2WithItems(@TempDir Path tmp) throws Exception {
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 31173, "/x/a.png", "em_bagofcoins");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 31174, "/x/b.png", "em_anvilhammer");
        EMCustomItem c = new EMCustomItem("minecraft:paper", 99, "/x/c.png", "em_scroll");

        Path out = tmp.resolve("em_bridge_mappings.json");
        GeyserMappingsWriter.write(List.of(a, b, c), out);

        assertTrue(Files.exists(out));
        JsonObject root = JsonParser.parseString(Files.readString(out)).getAsJsonObject();
        assertEquals(2, root.get("format_version").getAsInt());

        JsonObject items = root.getAsJsonObject("items");
        JsonArray emerald = items.getAsJsonArray("minecraft:emerald");
        assertEquals(2, emerald.size());

        JsonObject first = emerald.get(0).getAsJsonObject();
        assertEquals("definition", first.get("type").getAsString());
        assertEquals("bridge_em:em_bagofcoins", first.get("bedrock_identifier").getAsString());
        assertEquals("bridge_em:em_bagofcoins", first.get("model").getAsString());
        assertEquals("em_bagofcoins",
                first.getAsJsonObject("bedrock_options").get("icon").getAsString());

        // Predicate must match legacy custom_model_data
        JsonArray predicates = first.getAsJsonArray("predicate");
        assertEquals(1, predicates.size());
        JsonObject p = predicates.get(0).getAsJsonObject();
        assertEquals("match", p.get("type").getAsString());
        assertEquals("custom_model_data", p.get("property").getAsString());
        assertEquals("31173", p.get("value").getAsString());
    }

    @Test
    void deduplicatesSameMaterialAndCmd(@TempDir Path tmp) throws Exception {
        EMCustomItem a = new EMCustomItem("minecraft:emerald", 1, "/x/a.png", "em_x");
        EMCustomItem b = new EMCustomItem("minecraft:emerald", 1, "/x/a.png", "em_x");
        Path out = tmp.resolve("m.json");
        GeyserMappingsWriter.write(List.of(a, b), out);
        JsonObject root = JsonParser.parseString(Files.readString(out)).getAsJsonObject();
        assertEquals(1, root.getAsJsonObject("items").getAsJsonArray("minecraft:emerald").size());
    }
}
```

- [ ] **Step 2: Run test to verify FAIL**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q -Dtest=GeyserMappingsWriterTest test`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Implement GeyserMappingsWriter**

`src/main/java/de/crazypandas/fmmbedrockbridge/bedrock/GeyserMappingsWriter.java`:

```java
package de.crazypandas.fmmbedrockbridge.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GeyserMappingsWriter {

    public static final String BEDROCK_NAMESPACE = "bridge_em";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private GeyserMappingsWriter() {}

    public static void write(List<EMCustomItem> items, Path outFile) throws IOException {
        Files.createDirectories(outFile.getParent());

        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);

        Map<String, JsonArray> byBase = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>(); // base|cmd dedupe

        for (EMCustomItem item : items) {
            String key = item.javaMaterial() + "|" + item.customModelData();
            if (!seen.add(key)) continue;

            String bedrockId = BEDROCK_NAMESPACE + ":" + item.bedrockTextureKey();

            JsonObject definition = new JsonObject();
            definition.addProperty("type", "definition");
            definition.addProperty("bedrock_identifier", bedrockId);
            definition.addProperty("model", bedrockId);

            JsonObject options = new JsonObject();
            options.addProperty("icon", item.bedrockTextureKey());
            definition.add("bedrock_options", options);

            JsonArray predicates = new JsonArray();
            JsonObject predicate = new JsonObject();
            predicate.addProperty("type", "match");
            predicate.addProperty("property", "custom_model_data");
            predicate.addProperty("operator", "==");
            predicate.addProperty("value", String.valueOf(item.customModelData()));
            predicate.addProperty("index", 0);
            predicates.add(predicate);
            definition.add("predicate", predicates);

            byBase.computeIfAbsent(item.javaMaterial(), k -> new JsonArray()).add(definition);
        }

        JsonObject itemsObj = new JsonObject();
        for (var e : byBase.entrySet()) itemsObj.add(e.getKey(), e.getValue());
        root.add("items", itemsObj);

        Files.writeString(outFile, GSON.toJson(root));
    }
}
```

- [ ] **Step 4: Run test to verify PASS**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q -Dtest=GeyserMappingsWriterTest test`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bedrock/GeyserMappingsWriter.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bedrock/GeyserMappingsWriterTest.java
git commit -m "Phase 7.2b: GeyserMappingsWriter writes custom-item-v2 mappings with bridge_em namespace"
```

---

## Task 6: PacketInterceptor — switch to bridge_em namespace

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java:96-99`

- [ ] **Step 1: Read current inject line**

Run: `grep -n "geyser_custom" src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java`
Expected: Line 98-99 referencing `new ResourceLocation("geyser_custom", bedrockKey)`

- [ ] **Step 2: Replace namespace literal**

Edit `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java`:

Replace:
```java
        item.setComponent(ComponentTypes.ITEM_MODEL,
                new ItemModel(new ResourceLocation("geyser_custom", bedrockKey)));
        int n = injectCount.incrementAndGet();
        if (n <= 10 || n % 100 == 0) {
            log.info("[BRIDGE] Injected item_model=geyser_custom:" + bedrockKey
                    + " for " + typeName + " cmd=" + cmd + " (n=" + n + ")");
        }
```

With:
```java
        item.setComponent(ComponentTypes.ITEM_MODEL,
                new ItemModel(new ResourceLocation(
                        de.crazypandas.fmmbedrockbridge.bedrock.GeyserMappingsWriter.BEDROCK_NAMESPACE,
                        bedrockKey)));
        int n = injectCount.incrementAndGet();
        if (n <= 10 || n % 100 == 0) {
            log.info("[BRIDGE] Injected item_model="
                    + de.crazypandas.fmmbedrockbridge.bedrock.GeyserMappingsWriter.BEDROCK_NAMESPACE
                    + ":" + bedrockKey + " for " + typeName + " cmd=" + cmd + " (n=" + n + ")");
        }
```

- [ ] **Step 3: Build verifies it still compiles**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java
git commit -m "Phase 7.2b: switch packet inject namespace geyser_custom → bridge_em (matches new Geyser mappings)"
```

---

## Task 7: OpDriftNotifier (Deutsche Chat-Component)

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/OpDriftNotifier.java`

**Context:** Bukkit-Listener für `PlayerJoinEvent`. Nur Ops bekommen die Nachricht — und nur wenn das vom Plugin gesetzte Drift-Flag `true` ist. Component ist mehrzeilig auf Deutsch mit Click-Action `/fmmbridge maintenance status`. Kein Test — pure Bukkit-Integration, wird in Task 11 manuell verifiziert.

- [ ] **Step 1: Implement OpDriftNotifier**

`src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/OpDriftNotifier.java`:

```java
package de.crazypandas.fmmbedrockbridge.maintenance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.function.Supplier;

public final class OpDriftNotifier implements Listener {

    private final Supplier<Boolean> driftActive;

    public OpDriftNotifier(Supplier<Boolean> driftActive) {
        this.driftActive = driftActive;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().isOp()) return;
        if (!driftActive.get()) return;

        Component header = Component.text("⚠ FMMBedrockBridge — Wartung erforderlich", NamedTextColor.GOLD, TextDecoration.BOLD);
        Component line1 = Component.text("Das EliteMobs-Resource-Pack hat sich geändert, die Bridge-Mappings auf dem Proxy sind nicht mehr aktuell.", NamedTextColor.YELLOW);
        Component line2 = Component.text("Bedrock-Spieler sehen aktuell keine Custom-Icons (BagOfCoin etc.).", NamedTextColor.YELLOW);
        Component button = Component.text("[ Details + Re-Deploy-Anleitung ]", NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand("/fmmbridge maintenance status"))
                .hoverEvent(HoverEvent.showText(Component.text("Klick öffnet /fmmbridge maintenance status", NamedTextColor.GRAY)));

        event.getPlayer().sendMessage(Component.empty());
        event.getPlayer().sendMessage(header);
        event.getPlayer().sendMessage(line1);
        event.getPlayer().sendMessage(line2);
        event.getPlayer().sendMessage(button);
        event.getPlayer().sendMessage(Component.empty());
    }
}
```

- [ ] **Step 2: Build compiles**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/OpDriftNotifier.java
git commit -m "Phase 7.2b/maintenance: OpDriftNotifier shows German chat warning + click-to-status on op join"
```

---

## Task 8: FMMBridgeCommand — `maintenance` Subcommands (Deutsch)

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/commands/FMMBridgeCommand.java`

**Context:** Erweitert den bestehenden Command um `/fmmbridge maintenance status|redeploy-instructions|mark-deployed`. Output auf Deutsch. Beim mark-deployed wird der MaintenanceTracker.markDeployed() aufgerufen und das Bridge-Drift-Flag (vom Plugin verwaltet) zurückgesetzt.

- [ ] **Step 1: Add maintenance dispatch + helpers**

Edit `src/main/java/de/crazypandas/fmmbedrockbridge/commands/FMMBridgeCommand.java`:

Replace lines 36-43:
```java
        if (args.length == 0 || !args[0].equalsIgnoreCase("debug")) {
            sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge debug");
            return true;
        }

        runDebug(sender);
        return true;
    }
```

With:
```java
        if (args.length == 0) {
            sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge <debug|maintenance>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "debug" -> runDebug(sender);
            case "maintenance" -> runMaintenance(sender, args);
            default -> sender.sendMessage("§6FMMBedrockBridge §7— /fmmbridge <debug|maintenance>");
        }
        return true;
    }

    private void runMaintenance(CommandSender sender, String[] args) {
        FMMBedrockBridge plugin = FMMBedrockBridge.getInstance();
        if (args.length < 2) {
            sender.sendMessage("§6Wartung §7— Subcommands: §estatus§7, §eredeploy-instructions§7, §emark-deployed");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "status" -> showMaintenanceStatus(sender, plugin);
            case "redeploy-instructions" -> showRedeployInstructions(sender, plugin);
            case "mark-deployed" -> markDeployed(sender, plugin);
            default -> sender.sendMessage("§cUnbekannter Subcommand: " + args[1]);
        }
    }

    private void showMaintenanceStatus(CommandSender sender, FMMBedrockBridge plugin) {
        var status = plugin.getMaintenanceStatus();
        sender.sendMessage("§6── Wartungs-Status ──");
        sender.sendMessage("§7Drift aktiv: " + (status.driftActive() ? "§cJA — Re-Deploy nötig" : "§aNein — alles im Lot"));
        sender.sendMessage("§7Aktueller Pack-Hash: §f" + shortHash(status.currentHash()));
        sender.sendMessage("§7Deployed-Hash:        §f" + shortHash(status.deployedHash()));
        sender.sendMessage("§7Deployed EM-Version:  §f" + status.deployedEmVersion());
        sender.sendMessage("§7Deployed am:          §f" + status.deployedAt());
        if (status.driftActive()) {
            sender.sendMessage("§7Nächster Schritt: §e/fmmbridge maintenance redeploy-instructions");
        }
    }

    private void showRedeployInstructions(CommandSender sender, FMMBedrockBridge plugin) {
        var paths = plugin.getMaintenanceArtifactPaths();
        sender.sendMessage("§6── Re-Deploy-Anleitung ──");
        sender.sendMessage("§71. SCP Mini-Pack auf Proxy01:");
        sender.sendMessage("§f   scp " + paths.mcpackPath() + " amp@<proxy>:" + paths.proxyPackTargetDir() + "/");
        sender.sendMessage("§72. SCP Geyser-Mappings auf Proxy01:");
        sender.sendMessage("§f   scp " + paths.mappingsJsonPath() + " amp@<proxy>:" + paths.proxyMappingsTargetDir() + "/");
        sender.sendMessage("§73. Proxy01 neu starten (Geyser scannt mappings beim Boot)");
        sender.sendMessage("§74. Auf Backend: §e/fmmbridge maintenance mark-deployed");
    }

    private void markDeployed(CommandSender sender, FMMBedrockBridge plugin) {
        plugin.markMaintenanceDeployed();
        sender.sendMessage("§a✓ Aktueller Pack-Hash als deployed markiert. Drift-Flag gelöscht.");
    }

    private String shortHash(String hash) {
        if (hash == null) return "(none)";
        return hash.length() > 12 ? hash.substring(0, 12) + "…" : hash;
    }
```

- [ ] **Step 2: Update TabCompleter**

Replace lines 107-111:
```java
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return List.of("debug");
        return List.of();
    }
}
```

With:
```java
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return List.of("debug", "maintenance");
        if (args.length == 2 && args[0].equalsIgnoreCase("maintenance"))
            return List.of("status", "redeploy-instructions", "mark-deployed");
        return List.of();
    }
}
```

- [ ] **Step 3: Build (will fail at FMMBedrockBridge.getMaintenanceStatus etc — Task 9 wires this)**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q compile`
Expected: COMPILATION FAILURE (missing methods on FMMBedrockBridge — fixed in Task 9)

- [ ] **Step 4: Don't commit yet — wait for Task 9**

(Continue to Task 9; commit happens after wire-up compiles.)

---

## Task 9: FMMBedrockBridge — Wire everything together

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java`

**Context:** Im `onEnable()` nach dem `EliteMobsItemScanner.scan()` werden Pack + Mappings geschrieben, der Hash berechnet, der MaintenanceTracker konsultiert, das Drift-Flag gesetzt, Console-WARN bei Drift ausgegeben, und der OpDriftNotifier registriert. Plus drei neue public-Methoden für den FMMBridgeCommand-Aufruf: `getMaintenanceStatus()`, `getMaintenanceArtifactPaths()`, `markMaintenanceDeployed()`.

- [ ] **Step 1: Add imports + fields**

Edit `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java`:

After existing imports, add:
```java
import de.crazypandas.fmmbedrockbridge.bedrock.BedrockItemPackBuilder;
import de.crazypandas.fmmbedrockbridge.bedrock.GeyserMappingsWriter;
import de.crazypandas.fmmbedrockbridge.maintenance.MaintenanceState;
import de.crazypandas.fmmbedrockbridge.maintenance.MaintenanceStateStore;
import de.crazypandas.fmmbedrockbridge.maintenance.MaintenanceTracker;
import de.crazypandas.fmmbedrockbridge.maintenance.OpDriftNotifier;
import de.crazypandas.fmmbedrockbridge.maintenance.PackHashCalculator;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
```

After existing fields (around line 41), add:
```java
    private final AtomicBoolean driftActive = new AtomicBoolean(false);
    private String currentPackHash = "";
    private String currentEmVersion = "";
    private MaintenanceTracker maintenanceTracker;
    private java.nio.file.Path mcpackPath;
    private java.nio.file.Path mappingsJsonPath;
```

- [ ] **Step 2: Insert maintenance + pack write block in onEnable**

Right after `EliteMobsItemScanner.scan()` at the existing Phase 7.2b block (around line 97-105), replace the existing block:

Replace:
```java
        // Phase 7.2b — EliteMobs 2D Custom Items (Map+Inject)
        java.util.Map<String, java.util.Map<Integer, String>> emItemModelMap = new java.util.HashMap<>();
        if (getConfig().getBoolean("elite-items.enabled", false)) {
            String emPackPath = getConfig().getString("elite-items.resource-pack-path", "plugins/EliteMobs/resource_pack");
            Path emPackRoot = getServer().getWorldContainer().toPath().resolve(emPackPath);
            EliteMobsItemScanner itemScanner = new EliteMobsItemScanner(emPackRoot);
            List<EMCustomItem> emItems = itemScanner.scan();
            if (!emItems.isEmpty()) {
                writeEmItemsJson(emItems);
                for (EMCustomItem item : emItems) {
                    emItemModelMap.computeIfAbsent(item.javaMaterial(), k -> new java.util.HashMap<>())
                            .put(item.customModelData(), item.bedrockTextureKey());
                }
            }
        }
```

With:
```java
        // Phase 7.2b — EliteMobs 2D Custom Items (Map+Inject)
        java.util.Map<String, java.util.Map<Integer, String>> emItemModelMap = new java.util.HashMap<>();
        java.util.List<EMCustomItem> emItems = java.util.Collections.emptyList();
        if (getConfig().getBoolean("elite-items.enabled", false)) {
            String emPackPath = getConfig().getString("elite-items.resource-pack-path", "plugins/EliteMobs/resource_pack");
            Path emPackRoot = getServer().getWorldContainer().toPath().resolve(emPackPath);
            EliteMobsItemScanner itemScanner = new EliteMobsItemScanner(emPackRoot);
            emItems = itemScanner.scan();
            if (!emItems.isEmpty()) {
                writeEmItemsJson(emItems);
                for (EMCustomItem item : emItems) {
                    emItemModelMap.computeIfAbsent(item.javaMaterial(), k -> new java.util.HashMap<>())
                            .put(item.customModelData(), item.bedrockTextureKey());
                }
            }
        }

        // Phase 7.2b/maintenance — Build pack + mappings, run drift detection
        if (!emItems.isEmpty()) {
            buildPackAndMappings(emItems);
            evaluateMaintenance(emItems);
        }
```

- [ ] **Step 3: Add the helper methods**

Append these methods inside `FMMBedrockBridge` class (before the closing brace):

```java
    private void buildPackAndMappings(java.util.List<EMCustomItem> emItems) {
        java.nio.file.Path packDir = new File(getDataFolder(), "bedrock-pack").toPath();
        mcpackPath = packDir.resolve("em_bridge_pack.mcpack");
        mappingsJsonPath = packDir.resolve("em_bridge_mappings.json");
        currentPackHash = PackHashCalculator.compute(emItems);
        try {
            BedrockItemPackBuilder.build(emItems, currentPackHash, mcpackPath);
            GeyserMappingsWriter.write(emItems, mappingsJsonPath);
            log.info("[BRIDGE] Wrote em_bridge_pack.mcpack ("
                    + emItems.size() + " items) and em_bridge_mappings.json");
        } catch (Exception e) {
            log.warning("[BRIDGE] Failed to build pack/mappings: " + e.getMessage());
        }
    }

    private void evaluateMaintenance(java.util.List<EMCustomItem> emItems) {
        currentEmVersion = pluginVersionOrUnknown("EliteMobs");
        maintenanceTracker = new MaintenanceTracker(getDataFolder().toPath());
        MaintenanceTracker.Result r = maintenanceTracker.evaluate(currentPackHash, currentEmVersion);
        driftActive.set(r.driftActive());

        if (r.firstRun()) {
            log.info("[BRIDGE] Wartung: Erst-Boot — Hash als Baseline gespeichert (" + shortHash(r.currentHash()) + ")");
        } else if (r.driftActive()) {
            log.warning("[BRIDGE] ⚠══════════════════════════════════════════");
            log.warning("[BRIDGE] ⚠ EM-RESOURCE-PACK HAT SICH GEÄNDERT — Proxy-Mappings sind STALE");
            log.warning("[BRIDGE] ⚠ Deployed: " + shortHash(r.deployedHash()) + "  EM " + r.deployedEmVersion());
            log.warning("[BRIDGE] ⚠ Current:  " + shortHash(r.currentHash()) + "  EM " + currentEmVersion);
            log.warning("[BRIDGE] ⚠ Bedrock-Spieler sehen keine Custom-Icons bis Re-Deploy.");
            log.warning("[BRIDGE] ⚠ /fmmbridge maintenance redeploy-instructions  für Schritt-für-Schritt.");
            log.warning("[BRIDGE] ⚠══════════════════════════════════════════");
        } else {
            log.info("[BRIDGE] Wartung: Pack-Hash matched deployed state (kein Drift, " + shortHash(r.currentHash()) + ")");
        }

        getServer().getPluginManager().registerEvents(new OpDriftNotifier(driftActive::get), this);
    }

    private String pluginVersionOrUnknown(String pluginName) {
        var p = getServer().getPluginManager().getPlugin(pluginName);
        return p != null ? p.getDescription().getVersion() : "unknown";
    }

    private static String shortHash(String h) {
        if (h == null || h.isEmpty()) return "(none)";
        return h.length() > 12 ? h.substring(0, 12) + "…" : h;
    }

    public MaintenanceStatusSnapshot getMaintenanceStatus() {
        MaintenanceState s = MaintenanceStateStore.load(getDataFolder().toPath());
        return new MaintenanceStatusSnapshot(
                driftActive.get(),
                currentPackHash,
                s != null ? s.deployedHash() : "(none)",
                s != null ? s.deployedEmVersion() : "(none)",
                s != null ? s.deployedAt().toString() : "(none)");
    }

    public MaintenanceArtifactPaths getMaintenanceArtifactPaths() {
        return new MaintenanceArtifactPaths(
                mcpackPath != null ? mcpackPath.toAbsolutePath().toString() : "(not generated)",
                mappingsJsonPath != null ? mappingsJsonPath.toAbsolutePath().toString() : "(not generated)",
                "/home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/packs",
                "/home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/custom_mappings"
        );
    }

    public void markMaintenanceDeployed() {
        if (maintenanceTracker != null) {
            maintenanceTracker.markDeployed(currentPackHash, currentEmVersion);
            driftActive.set(false);
        }
    }

    public record MaintenanceStatusSnapshot(
            boolean driftActive,
            String currentHash,
            String deployedHash,
            String deployedEmVersion,
            String deployedAt) {}

    public record MaintenanceArtifactPaths(
            String mcpackPath,
            String mappingsJsonPath,
            String proxyPackTargetDir,
            String proxyMappingsTargetDir) {}
```

- [ ] **Step 4: Build compiles**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Run all tests still pass**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q test`
Expected: PASS

- [ ] **Step 6: Commit (Tasks 6+7+8+9 together)**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java \
        src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/OpDriftNotifier.java \
        src/main/java/de/crazypandas/fmmbedrockbridge/commands/FMMBridgeCommand.java \
        src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java
git commit -m "Phase 7.2b: wire pack-build, mappings-write, drift-detection, op-notify, /fmmbridge maintenance"
```

---

## Task 10: Config defaults + Doc-Updates

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: config.yml — add maintenance section**

Edit `src/main/resources/config.yml`. Find the `elite-items:` section and append below it:

```yaml

# Phase 7.2b/maintenance — Drift-Detection für EM-Resource-Pack
maintenance:
  # Wenn der EM-Resource-Pack sich seit dem letzten deployed-Stand geändert hat,
  # gibt die Bridge beim Boot eine markante 7-zeilige WARN-Sequenz im Console-Log aus
  # und schickt jedem Op beim Join eine deutsche Chat-Nachricht mit Click-Action
  # zu /fmmbridge maintenance status. Verwende /fmmbridge maintenance mark-deployed
  # nach jedem Re-Deploy auf den Proxy.
  op-chat-notify: true
```

- [ ] **Step 2: README.md — append deployment section**

Edit `README.md`. Add a new section (after the existing Deployment/Build section):

```markdown
## Phase 7.2b — EM-Items Mini-Pack + Mappings (Re-Deploy bei EM-Updates)

Die Bridge generiert beim Boot zwei Artefakte unter `plugins/FMMBedrockBridge/bedrock-pack/`:
- `em_bridge_pack.mcpack` — eigenständiges Mini-Bedrock-Pack mit Custom-Items (`bridge_em:<key>`)
- `em_bridge_mappings.json` — Geyser custom-item-v2 Mappings (EM-CMD → Bedrock-Identifier)

**Initial-Deploy + bei jedem EM-Update neu deployen:**

```bash
# Pack zum Proxy
scp /home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge/bedrock-pack/em_bridge_pack.mcpack \
    amp@mc.crazypandas.de:/home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/packs/

# Mappings zum Proxy
scp /home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge/bedrock-pack/em_bridge_mappings.json \
    amp@mc.crazypandas.de:/home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/custom_mappings/

# Proxy neustarten (Geyser registriert Custom-Items beim Boot)
# Anschließend auf Backend:
/fmmbridge maintenance mark-deployed
```

**Drift-Detection:** Wenn das EM-Pack sich ändert (Update, neue Items), zeigt die Bridge beim nächsten Boot eine WARN-Sequenz und schickt Ops beim Join eine Chat-Nachricht. `/fmmbridge maintenance status` zeigt den aktuellen Drift-Status, `/fmmbridge maintenance redeploy-instructions` printet die exakten SCP-Befehle.
```

- [ ] **Step 3: CLAUDE.md — update Architecture section**

Edit `CLAUDE.md`. In der "Bekannte Probleme & Erkenntnisse" Sektion, append a new subsection:

```markdown
### Phase 7.2b — bridge_em Namespace (seit 2026-05-31)

Bis 28.05. wurde `item_model = geyser_custom:em_<key>` injected, aber dieser Identifier ist seit RPM 2.0.0 Switch tot (Geyser kennt ihn nicht mehr; vorher kam er aus RPM 1.8.0-generierten Mappings auf Proxy01). Jetzt: Bridge generiert eigenen Bedrock-Pack (`em_bridge_pack.mcpack`) + Geyser custom-item-v2 Mappings-Datei (`em_bridge_mappings.json`) mit `bridge_em:<key>` Namespace. Beides muss nach Initial-Deploy + bei jedem EM-Update per SCP auf Proxy01 (siehe README "Phase 7.2b" Sektion). Bridge erkennt EM-Pack-Drift via SHA-256 Hash und warnt Ops im Chat (deutsch) + Console-WARN beim Boot. `/fmmbridge maintenance status` / `redeploy-instructions` / `mark-deployed` für Wartung.
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/config.yml README.md CLAUDE.md
git commit -m "Phase 7.2b: docs + config defaults für Bridge-Items-Pack + Wartungs-Workflow"
```

---

## Task 11: Build, Deploy & Manual Verify

**Files:** keine — Deployment-Schritte

- [ ] **Step 1: Full clean build**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package`
Expected: BUILD SUCCESS, JAR in `target/FMMBedrockBridge-0.1.0-SNAPSHOT-*.jar`

- [ ] **Step 2: All tests pass**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q test`
Expected: All tests green (PackHashCalculatorTest, MaintenanceStateStoreTest, MaintenanceTrackerTest, BedrockItemPackBuilderTest, GeyserMappingsWriterTest)

- [ ] **Step 3: Backup current proxy mappings**

Run:
```bash
ssh amp@mc.crazypandas.de "cp /home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/custom_mappings/rspm_geyser_mappings.json /home/amp/jar-backups/2026-05-31-pre-update/"
```

- [ ] **Step 4: SCP Bridge JAR to Backend**

Run:
```bash
scp target/FMMBedrockBridge-0.1.0-SNAPSHOT-*.jar amp@mc.crazypandas.de:/home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge.jar
```

- [ ] **Step 5: Ask Fabi to restart TestServer01**

Bridge generiert beim Boot:
- `plugins/FMMBedrockBridge/bedrock-pack/em_bridge_pack.mcpack`
- `plugins/FMMBedrockBridge/bedrock-pack/em_bridge_mappings.json`
- `plugins/FMMBedrockBridge/maintenance-state.json` (Baseline)

- [ ] **Step 6: Verify generated files**

Run:
```bash
ssh amp@mc.crazypandas.de "ls -la /home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge/bedrock-pack/em_bridge*"
```
Expected: beide Files vorhanden, Pack non-zero, JSON ≥ 1 KB

- [ ] **Step 7: Inspect generated mappings**

Run:
```bash
ssh amp@mc.crazypandas.de "python3 -c \"
import json
d = json.load(open('/home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge/bedrock-pack/em_bridge_mappings.json'))
print('format_version:', d['format_version'])
emerald = d['items'].get('minecraft:emerald', [])
print('emerald entries:', len(emerald))
for e in emerald[:3]:
    print(' ', e['bedrock_identifier'], 'cmd=' + e['predicate'][0]['value'])
\""
```
Expected: format_version 2, ≥ 12 emerald entries (BagOfCoin, AnvilHammer, WhiteAnvil, em_lockedchain, em_lockedcoin, em_unlocked, em_keygold, em_keygray, em_keychain, em_keycoin, em_keystar = 11; plus coin2 → 12)

- [ ] **Step 8: SCP Pack + Mappings to Proxy**

Run:
```bash
scp amp@mc.crazypandas.de:/home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge/bedrock-pack/em_bridge_pack.mcpack /tmp/
scp amp@mc.crazypandas.de:/home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge/bedrock-pack/em_bridge_mappings.json /tmp/
scp /tmp/em_bridge_pack.mcpack amp@mc.crazypandas.de:/home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/packs/
scp /tmp/em_bridge_mappings.json amp@mc.crazypandas.de:/home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/custom_mappings/
```

- [ ] **Step 9: Ask Fabi to restart Proxy01**

Geyser registriert beim Boot alle Custom-Items aus `custom_mappings/em_bridge_mappings.json`.

Verify Proxy Boot Log:
```bash
ssh amp@mc.crazypandas.de "grep -iE 'em_bridge|bridge_em|custom item' /home/amp/.ampdata/instances/Proxy01/Minecraft/logs/latest.log | head -20"
```
Expected: Geyser meldet erfolgreiche Registration der `bridge_em:*` Identifiers

- [ ] **Step 10: Bedrock-Client Visual-Verify**

Fabi (oder Tester) loggt mit Bedrock-Client ein:
- ✓ BagOfCoin im EM-Shop zeigt Custom-Icon (nicht mehr Vanilla-Emerald)
- ✓ AnvilHammer + WhiteAnvil zeigen Custom-Icons
- ✓ Key-Icons (em_keygold, em_keychain, etc.) zeigen Custom

Wenn alles ✓: dann auf Backend:
```
/fmmbridge maintenance mark-deployed
```
Output erwartet: `§a✓ Aktueller Pack-Hash als deployed markiert. Drift-Flag gelöscht.`

- [ ] **Step 11: Op-Drift-Test (manuelle Simulation)**

Drift simulieren um Op-Chat-Notification zu testen:
```bash
ssh amp@mc.crazypandas.de "python3 -c \"
import json
p = '/home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge/maintenance-state.json'
d = json.load(open(p))
d['deployedHash'] = 'simulated_old_hash_aabbccdd'
json.dump(d, open(p, 'w'), indent=2)
print('Drift simuliert')
\""
```
Fabi startet TestServer01 → erwartet im Backend-Log:
```
[BRIDGE] ⚠══════════════════════════════════════════
[BRIDGE] ⚠ EM-RESOURCE-PACK HAT SICH GEÄNDERT — Proxy-Mappings sind STALE
...
```
Fabi loggt als OP ein → erwartet Chat-Nachricht (deutsch, gold/yellow header + click-button)
Click auf `[ Details + Re-Deploy-Anleitung ]` → `/fmmbridge maintenance status` Output

Fabi tippt `/fmmbridge maintenance mark-deployed` → restored

- [ ] **Step 12: Commit final docs + memory update**

```bash
git add CLAUDE_SESSION.md
git commit -m "Phase 7.2b: session log — bridge_em namespace + maintenance tracking deployed"
```

Optional: Memory update via `MEMORY.md` + neue Memory-Datei für `bridge_em_namespace_history.md`.

---

## Notes (Outside Plan Scope)

**RPM Bug-Report bei MagmaGuy ausstehend:** RPM 2.0.1's `BaseItemResolver` errät für `elitemobs:ui/bagofcoins` 10 base items, aber kein `minecraft:emerald` — EM nutzt für UI-Icons noch legacy `models/item/emerald.json` overrides die RPM ignoriert. Wenn MagmaGuy das fixt, ist die Bridge-Phase-7.2b obsolet.

**GeyserUtils loadSkin Bug bleibt ungelöst:** Lebt orthogonal weiter; ist nicht Teil dieses Plans.

**pom.xml FMM-Version-Drift:** pom.xml hat FMM 2.5.0, Server hat 2.7.0. Sollte bei Gelegenheit gebumped werden — nicht Teil dieses Plans (kein direktes Issue für 7.2b).
