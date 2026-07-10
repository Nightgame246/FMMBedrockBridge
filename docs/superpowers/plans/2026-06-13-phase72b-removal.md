# Phase 7.2b Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Phase 7.2b (EM 2D-UI-icon Bedrock bridge) and its maintenance subsystem in full, now that RPM 2.0.2 maps `minecraft:emerald`+CMD → `elitemobs:ui/*` natively.

**Architecture:** Pure deletion in an existing Spigot/Paper plugin. Edit consumers top-down (command → main class → packet interceptor → config) so the Maven build stays green at every commit, then delete the now-unreferenced 7.2b classes and their tests. Phases 7.1 (BossBar/nametag/combat) and 7.3/7.3b (dialog reroute) are untouched. A hard operator-driven de-risk gate (Phase A) runs first and blocks all code changes.

**Tech Stack:** Java 21, Maven (`/usr/share/idea/plugins/maven/lib/maven3/bin/mvn`), Paper 1.21.10 API, PacketEvents 2.12.1, JUnit. Branch: `refactor/remove-phase72b`.

---

## Reference: exact 7.2b surface (current HEAD, for orientation)

Files deleted entirely (10 source + 5 tests):
`bedrock/BedrockItemPackBuilder.java`(+Test), `bedrock/GeyserMappingsWriter.java`(+Test),
`bridge/EliteMobsItemScanner.java`, `bridge/EMCustomItem.java`,
`bridge/BedrockInventoryRefresher.java`, `maintenance/MaintenanceState.java`,
`maintenance/MaintenanceStateStore.java`(+Test), `maintenance/MaintenanceTracker.java`(+Test),
`maintenance/OpDriftNotifier.java`, `maintenance/PackHashCalculator.java`(+Test).

Files edited (kept): `commands/FMMBridgeCommand.java`, `FMMBedrockBridge.java`,
`bridge/PacketInterceptor.java`, `src/main/resources/config.yml`.

Build command used throughout:
```bash
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package -q
```
A green run prints no `BUILD FAILURE` and produces `target/FMMBedrockBridge-*.jar`.

---

## Task A: De-Risk Gate (operator-executed, HARD BLOCK)

**No code changes. Do not start Task 1 until step 5 passes.**

- [ ] **Step 1: Disable the inject on TestServer01**

Edit `plugins/FMMBedrockBridge/config.yml`, set:
```yaml
elite-items:
  enabled: false
```

- [ ] **Step 2: Remove the stale bridge artifacts on Proxy01**

```bash
rm /home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/custom_mappings/em_bridge_mappings.json
rm /home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/packs/em_bridge_pack.mcpack
```

- [ ] **Step 3: Restart backend + proxy** (Fabi, via AMP — full stop/start, not `/reload`).

- [ ] **Step 4: Bedrock render check**

As a Bedrock client, open an EM menu containing UI icons (e.g. the one showing **BagOfCoin**).
Expected: the custom icon renders (via RPM's native `minecraft:emerald`+CMD mapping), not a raw
vanilla emerald.

- [ ] **Step 5: GATE decision**

- ✅ Icons render → proceed to Task 1.
- ❌ Icons broken → **STOP**. Re-enable `elite-items.enabled: true`, restore the proxy files,
  restart, file an RPM render bug with MagmaGuy. Do not remove any code.

**GATE OUTCOME (evaluated 2026-06-14): GREEN-with-known-gap → proceed.**
10/12 EM UI items render natively via RPM 2.0.2 (verified at mappings + pack-sprite level, no
collisions). The 2 banner-based items — `green_banner`+CMD31173 → `boxinput` and
`red_banner`+CMD31173 → `boxoutput` (Verzauberer/enchantment + elite-scroll menu frame icons) —
do **not** render: Bedrock/Geyser cannot apply custom-item-v2 to banner base items (banners are
block-entity/pattern-rendered, not flat icons). RPM's mapping is correct; the limit is in
Geyser/Bedrock. Accepted as a known temporary upstream-pending gap (see Task 6 Step 7); 7.2b is
removed in full regardless. Root cause + mechanism: memory `rpm-banner-customitem-limit`.

---

## Task 1: Strip the `maintenance` subtree from FMMBridgeCommand

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/commands/FMMBridgeCommand.java`

Done first so that when Task 2 deletes the maintenance accessors on the main class, nothing
still calls them.

- [ ] **Step 1: Remove the maintenance command surface**

In `FMMBridgeCommand.java`:
- Change the usage/help strings from `/fmmbridge <debug|maintenance>` to `/fmmbridge <debug>`
  (both the no-arg branch and the `default` branch).
- In the top-level `switch`, remove the `case "maintenance" -> runMaintenance(sender, args);`
  line.
- Delete the methods `runMaintenance`, `showMaintenanceStatus`, `showRedeployInstructions`,
  `markDeployed` in their entirety.
- In tab-complete: change `if (args.length == 1) return List.of("debug", "maintenance");` to
  `if (args.length == 1) return List.of("debug");` and delete the
  `if (args.length == 2 && args[0].equalsIgnoreCase("maintenance")) return List.of("status", "redeploy-instructions", "mark-deployed");`
  branch.

After this, the file must reference none of: `getMaintenanceStatus`, `getMaintenanceArtifactPaths`,
`markMaintenanceDeployed`, `runMaintenance`, `showMaintenanceStatus`, `showRedeployInstructions`,
`markDeployed`.

- [ ] **Step 2: Verify no maintenance references remain in the command**

Run:
```bash
grep -nE 'maintenance|Maintenance|redeploy|mark-deployed' src/main/java/de/crazypandas/fmmbedrockbridge/commands/FMMBridgeCommand.java
```
Expected: no output.

- [ ] **Step 3: Build**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package -q`
Expected: green (the main class still *defines* the maintenance accessors, so this compiles).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/commands/FMMBridgeCommand.java
git commit -m "refactor(7.2b): drop maintenance subcommand from /fmmbridge"
```

---

## Task 2: Strip all 7.2b + maintenance wiring from FMMBedrockBridge

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java`

- [ ] **Step 1: Remove imports**

Delete these import lines:
```java
import de.crazypandas.fmmbedrockbridge.bedrock.BedrockItemPackBuilder;
import de.crazypandas.fmmbedrockbridge.bedrock.GeyserMappingsWriter;
import de.crazypandas.fmmbedrockbridge.bridge.EMCustomItem;
import de.crazypandas.fmmbedrockbridge.bridge.EliteMobsItemScanner;
import de.crazypandas.fmmbedrockbridge.maintenance.MaintenanceState;
import de.crazypandas.fmmbedrockbridge.maintenance.MaintenanceStateStore;
import de.crazypandas.fmmbedrockbridge.maintenance.MaintenanceTracker;
import de.crazypandas.fmmbedrockbridge.maintenance.OpDriftNotifier;
import de.crazypandas.fmmbedrockbridge.maintenance.PackHashCalculator;
```
Also drop the now-unused `java.io.File`, `java.nio.file.Path`, `java.util.ArrayList`,
`java.util.List`, `java.util.concurrent.atomic.AtomicBoolean` imports **only if** no remaining
code uses them (the compiler/Step 5 build will confirm; if a build error says "cannot find
symbol", restore the specific import).

- [ ] **Step 2: Remove fields**

Delete these instance fields:
```java
private final AtomicBoolean driftActive = new AtomicBoolean(false);
private String currentPackHash = "";
private String currentEmVersion = "";
private MaintenanceTracker maintenanceTracker;
private java.nio.file.Path mcpackPath;
private java.nio.file.Path mappingsJsonPath;
```

- [ ] **Step 3: Remove the 7.2b block in onEnable**

Delete the entire span from the comment `// Phase 7.2b — EliteMobs 2D Custom Items (Map+Inject)`
through the end of the `BedrockInventoryRefresher` registration block (the current lines
106–139): the `emItemModelMap`/`emItems` locals, the `elite-items.enabled` scan, the
`buildPackAndMappings`/`evaluateMaintenance` call, the
`bridge.getPacketInterceptor().setEmItemModelMap(...)` call, and the
`BedrockInventoryRefresher` registration.

- [ ] **Step 4: Reword the PacketEvents-missing warning**

Change:
```java
log.warning("PacketEvents not found — Phase 7.1a BossBar suppression + Phase 7.2b 2D-item inject disabled");
```
to:
```java
log.warning("PacketEvents not found — Phase 7.1a BossBar suppression disabled");
```

- [ ] **Step 5: Remove the helper + maintenance methods and records**

Delete in full: `writeEmItemsJson(...)`, `buildPackAndMappings(...)`, `evaluateMaintenance(...)`,
`pluginVersionOrUnknown(...)`, `shortHash(...)`, `getMaintenanceStatus()`,
`getMaintenanceArtifactPaths()`, `markMaintenanceDeployed()`, and the two records
`MaintenanceStatusSnapshot` and `MaintenanceArtifactPaths`.

Also remove the `* - Phase 7.2b: 2D EM UI icons …` bullet from the class Javadoc.

- [ ] **Step 6: Verify no 7.2b/maintenance references remain in the main class**

Run:
```bash
grep -nE 'EMCustomItem|EliteMobsItemScanner|BedrockItemPackBuilder|GeyserMappingsWriter|BedrockInventoryRefresher|Maintenance|PackHashCalculator|OpDriftNotifier|driftActive|mcpathPath|mcpackPath|mappingsJsonPath|elite-items|setEmItemModelMap' src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java
```
Expected: no output.

- [ ] **Step 7: Build**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package -q`
Expected: green. (The 7.2b classes still exist but are now referenced only by their own tests;
`PacketInterceptor` still has the now-unused inject members — both compile.)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java
git commit -m "refactor(7.2b): remove item-scan/inject wiring + maintenance subsystem from main class"
```

---

## Task 3: Strip the item_model inject from PacketInterceptor

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java`

Keep 7.1a BOSS_EVENT suppression and 7.1b entity-hiding intact.

- [ ] **Step 1: Remove the inject members**

Delete:
- the field `private volatile Map<String, Map<Integer, String>> emItemModelMap = Collections.emptyMap();`
- the field `private final AtomicInteger injectCount = ...;`
- the method `setEmItemModelMap(...)` in full
- the method `injectItemModelIfNeeded(ItemStack item)` in full

- [ ] **Step 2: Remove the inject branch in onPacketSend**

Delete the entire `// Phase 7.2b — inject item_model on inventory packets for Bedrock players`
block (the `if (!emItemModelMap.isEmpty() && floodgateAvailable && …)` covering
`SET_SLOT` / `WINDOW_ITEMS` / `ENTITY_METADATA`, current lines 118–157). Leave the following
`// Phase 7.1b` entity-hiding block and `// Phase 7.1a` BOSS_EVENT block untouched.

- [ ] **Step 3: Remove now-unused imports**

Delete these import lines (all used only by the inject):
```java
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;
```
Keep `WrapperPlayServerBossBar`, `WrapperPlayServerEntityMetadata`, the entity-position
wrappers, `WrapperPlayServerSpawnEntity`, `Map`, `Set`, `UUID`, `ConcurrentHashMap`.
(If the build flags `Collections` or `Map` as still-used, restore that one import.)

- [ ] **Step 4: Verify the inject is gone and 7.1 is intact**

Run:
```bash
grep -nE 'emItemModelMap|injectItemModelIfNeeded|setEmItemModelMap|GeyserMappingsWriter|item_model|injectCount' src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java
```
Expected: no output.
Run:
```bash
grep -cE 'handleBossEvent|javaHiddenEntityIds|BOSS_BAR' src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java
```
Expected: a non-zero count (7.1 logic still present).

- [ ] **Step 5: Build**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package -q`
Expected: green. (`GeyserMappingsWriter` is no longer referenced from `bridge/`, so it can be
deleted in Task 5.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java
git commit -m "refactor(7.2b): remove item_model inject from PacketInterceptor (keep 7.1a/7.1b)"
```

---

## Task 4: Remove the elite-items config section

**Files:**
- Modify: `src/main/resources/config.yml`

- [ ] **Step 1: Delete the `elite-items:` block**

Remove the entire `elite-items:` section (its comment header, `enabled:`, and
`resource-pack-path:`). Keep `enabled` (top-level), `debug`, `phase71c`, `phase73`.

- [ ] **Step 2: Verify**

Run: `grep -nE 'elite-items|resource-pack-path' src/main/resources/config.yml`
Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/config.yml
git commit -m "refactor(7.2b): drop elite-items config section"
```

---

## Task 5: Delete the orphaned 7.2b classes and tests

**Files:**
- Delete: 10 source + 5 test files (listed below).

After Tasks 1–3 nothing references these; this task removes them and their tests together as a
closed set.

- [ ] **Step 1: Delete the files**

```bash
git rm \
  src/main/java/de/crazypandas/fmmbedrockbridge/bedrock/BedrockItemPackBuilder.java \
  src/main/java/de/crazypandas/fmmbedrockbridge/bedrock/GeyserMappingsWriter.java \
  src/main/java/de/crazypandas/fmmbedrockbridge/bridge/EliteMobsItemScanner.java \
  src/main/java/de/crazypandas/fmmbedrockbridge/bridge/EMCustomItem.java \
  src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockInventoryRefresher.java \
  src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceState.java \
  src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceStateStore.java \
  src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceTracker.java \
  src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/OpDriftNotifier.java \
  src/main/java/de/crazypandas/fmmbedrockbridge/maintenance/PackHashCalculator.java \
  src/test/java/de/crazypandas/fmmbedrockbridge/bedrock/BedrockItemPackBuilderTest.java \
  src/test/java/de/crazypandas/fmmbedrockbridge/bedrock/GeyserMappingsWriterTest.java \
  src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceStateStoreTest.java \
  src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/MaintenanceTrackerTest.java \
  src/test/java/de/crazypandas/fmmbedrockbridge/maintenance/PackHashCalculatorTest.java
```

- [ ] **Step 2: Verify no source references any deleted symbol**

Run:
```bash
grep -rnE 'EMCustomItem|EliteMobsItemScanner|BedrockItemPackBuilder|GeyserMappingsWriter|BedrockInventoryRefresher|PackHashCalculator|MaintenanceTracker|MaintenanceState|MaintenanceStateStore|OpDriftNotifier' src/
```
Expected: no output.

- [ ] **Step 3: Confirm empty packages are gone**

Run: `find src -type d -empty`
Expected: prints `src/.../bedrock` and `src/.../maintenance` if Git left empty dirs — remove
them: `find src -type d -empty -delete`. (Git does not track empty dirs, so this is cleanup
only.)

- [ ] **Step 4: Full build + test**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package`
Expected: `BUILD SUCCESS`; `Tests run:` reflects the remaining suite (McVersions,
MenuRerouteRegistry, RerouteDecision, QuestMenuContext) with 0 failures, 0 errors.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(7.2b): delete EM item-scan/pack/mappings classes + maintenance subsystem"
```

---

## Task 6: Docs update + final verification

**Files:**
- Modify: `README.md`, `CLAUDE_SESSION.md`, `CLAUDE.md`

- [ ] **Step 1: README — remove 7.2b feature rows**

In `README.md` remove the Phase 7.2b status-table row and the `## Phase 7.2b — …` section, plus
the `bridge/EliteMobsItemScanner`/`bedrock/*`/`maintenance` entries in the class table and any
`/fmmbridge maintenance` references. Add a one-line note that EM 2D UI items are now handled
natively by RPM 2.0.2.

- [ ] **Step 2: CLAUDE_SESSION — record the removal**

Append a `## Session: 2026-06-13 — Phase 7.2b Rückbau` entry: what was removed, why (RPM 2.0.2
native `minecraft:emerald`+CMD), the de-risk gate result, and the proxy/backend cleanup.

- [ ] **Step 3: CLAUDE.md — update the Phase 7.2b note**

In `CLAUDE.md`, mark the "Phase 7.2b — bridge_em Namespace" section as superseded/removed by RPM
2.0.2 (keep a one-line historical pointer; do not delete the architectural history).

- [ ] **Step 4: Final grep sweep across the whole tree**

Run:
```bash
grep -rnE 'em_bridge|bridge_em|elite-items|7\.2b|EMCustomItem|Maintenance(Tracker|State|StateStore)|OpDriftNotifier|PackHashCalculator' src/ README.md
```
Expected: only intentional historical mentions in README prose (no live code/config). Verify
each remaining hit is documentation, not code.

- [ ] **Step 5: Build once more**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add README.md CLAUDE_SESSION.md CLAUDE.md
git commit -m "docs(7.2b): record removal; EM UI items now native via RPM 2.0.2"
```

- [ ] **Step 7: Draft upstream report for the banner gap** (operator posts later)

Add `docs/upstream-bugs/em-banner-ui-items-bedrock.md` describing: EM's `boxInput`/`boxOutput`
(Verzauberer/enchantment + elite-scroll menus) use `green_banner`/`red_banner` + CMD 31173 as
base items; Bedrock/Geyser cannot render custom-item-v2 on banner base items, so these icons are
invisible to Bedrock clients under RPM's native conversion. Suggested fix: EM moves these two
models onto a flat-icon base item (e.g. `paper`/`emerald`) like the other 10 UI icons, OR RPM
re-bases banner-based legacy overrides onto a flat-icon carrier during Bedrock conversion.
Commit with: `git commit -m "docs(upstream): EM banner UI items invisible on Bedrock (boxinput/boxoutput)"`.

---

## Task 7: Deploy + server cleanup (operator-assisted)

- [ ] **Step 1: SCP the new JAR to TestServer01**

```bash
scp target/FMMBedrockBridge-0.1.0-SNAPSHOT-*.jar \
  amp@mc.crazypandas.de:/home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge.jar
```

- [ ] **Step 2: Delete the orphaned generated dir on TestServer01**

```bash
ssh amp@mc.crazypandas.de "rm -rf /home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge/bedrock-pack"
```
(The Proxy01 artifacts were already removed in Task A.)

- [ ] **Step 3: Restart TestServer01** (Fabi, via AMP — full stop/start).

- [ ] **Step 4: Boot-log verification**

```bash
ssh amp@mc.crazypandas.de "grep -iE 'FMMBedrockBridge|ItemScanner|Phase 7.2b|Maintenance|Phase 7.3|Phase 7.1c' /home/amp/.ampdata/instances/TestServer01/Minecraft/logs/latest.log"
```
Expected: bridge enables; **no** `[ItemScanner]`, `[Phase 7.2b]`, or maintenance lines;
`Phase 7.1c` and `Phase 7.3 … reroute registered` still present; no exceptions.

- [ ] **Step 5: Bedrock smoke (Fabi)**

As a Bedrock client confirm: EM UI icons still render (RPM native), an EM boss still shows the
7.1 combat BossBar/nametag, and `/em` + a quest NPC still open native forms (7.3/7.3b). All
unchanged from before the removal.

- [ ] **Step 6: Merge + push**

After a green smoke test, merge `refactor/remove-phase72b` into `main` and push (use the
finishing-a-development-branch skill for the merge flow).

---

## Self-Review

- **Spec coverage:** Phase A gate → Task A. Delete 10+5 files → Task 5. Edit PacketInterceptor
  (keep 7.1) → Task 3. Edit main class → Task 2. Edit command → Task 1. Edit config → Task 4.
  Server cleanup → Task A (proxy) + Task 7 (backend dir). Testing/boot-log → Tasks 5–7. Docs →
  Task 6. All spec sections covered.
- **Placeholders:** none — every edit names exact members/files; verification greps have
  expected output.
- **Ordering/type consistency:** consumers (command, main, interceptor) edited before the
  classes they reference are deleted, so the build is green at every commit; symbol names match
  the source verified at planning time (`setEmItemModelMap`, `getMaintenanceStatus`,
  `injectItemModelIfNeeded`, `emItemModelMap`, `GeyserMappingsWriter.BEDROCK_NAMESPACE`).
