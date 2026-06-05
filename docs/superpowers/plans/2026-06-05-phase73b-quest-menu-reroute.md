# Phase 7.3b — NPC Quest-Menu Dialog-Reroute Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reroute the Bedrock EliteMobs NPC quest chest menu to EM's native MC quest dialog (which Geyser renders as a native Bedrock form), reusing EM's own dialog code.

**Architecture:** A Bukkit `InventoryOpenEvent` listener detects a Bedrock player opening an EM quest chest by looking the inventory up in EM's internal `QuestInventoryMenu` static maps (reflection, isolated in `EliteMobsHook`). On a hit it cancels the chest and next-tick invokes `QuestMenu.generateDialogMenu(quests, player, npc)`. Branch precedence (status vs quest) and per-flag gating are extracted into a pure `RerouteDecision` resolver so they are unit-testable without Bukkit/Floodgate/EM; the reflection itself is integration-level (manual Bedrock test), matching the Phase 7.3 boundary.

**Tech Stack:** Java 21, Maven, Bukkit/Paper API, Floodgate API, JUnit 5, Java reflection into EliteMobs internals.

---

## Conventions

- **Maven binary** (this project): `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn`. In commands below it is written as `mvn` — substitute the full path.
- Run a single test class: `mvn -Dtest=ClassName test`
- Full build + all tests: `mvn clean package`
- All new Java files use package `de.crazypandas.fmmbedrockbridge.*` and Java Logger (never `System.out`), per CLAUDE.md.

## File Structure

- **Create** `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/RerouteDecision.java` — pure precedence/gating resolver (status vs quest vs none).
- **Create** `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/RerouteDecisionTest.java` — unit tests for the resolver.
- **Create** `src/main/java/de/crazypandas/fmmbedrockbridge/elite/QuestMenuContext.java` — opaque carrier record `{ List<?> quests, Object npcEntity }`.
- **Create** `src/test/java/de/crazypandas/fmmbedrockbridge/elite/QuestMenuContextTest.java` — carrier unit test.
- **Modify** `src/main/java/de/crazypandas/fmmbedrockbridge/elite/EliteMobsHook.java` — add quest reflection (`tryRecoverQuestMenu`, `openNativeQuestDialog`, `initQuestReflection`). Reflection = integration-level, manual test.
- **Modify** `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuRerouteListener.java` — add quest path via `RerouteDecision` + `EliteMobsHook.tryRecoverQuestMenu`.
- **Modify** `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java:141-156` — register the listener when EITHER reroute flag is on; read both flags.
- **Modify** `src/main/resources/config.yml:70-77` — add `phase73.bedrock-quest-reroute: true`.
- **Modify** `README.md`, `CLAUDE_SESSION.md` — document Phase 7.3b (per CLAUDE.md "Vor jedem git push").

---

## Task 1: `RerouteDecision` pure resolver (precedence + flag gating)

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/RerouteDecision.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/RerouteDecisionTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/RerouteDecisionTest.java`:

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static de.crazypandas.fmmbedrockbridge.bridge.RerouteDecision.Action;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RerouteDecisionTest {

    @Test
    void statusMatchWinsWhenEnabled() {
        assertEquals(Action.STATUS, RerouteDecision.resolve(true, true, true, true));
        assertEquals(Action.STATUS, RerouteDecision.resolve(true, true, false, false));
    }

    @Test
    void questMatchUsedWhenNoStatusMatch() {
        assertEquals(Action.QUEST, RerouteDecision.resolve(true, false, true, true));
        assertEquals(Action.QUEST, RerouteDecision.resolve(false, false, true, true));
    }

    @Test
    void statusDisabledFallsThroughToQuest() {
        // status flag off but title would match → status is skipped, quest taken
        assertEquals(Action.QUEST, RerouteDecision.resolve(false, true, true, true));
    }

    @Test
    void questDisabledIsSkippedEvenIfMatched() {
        assertEquals(Action.NONE, RerouteDecision.resolve(true, false, false, true));
    }

    @Test
    void noMatchOrBothDisabledIsNone() {
        assertEquals(Action.NONE, RerouteDecision.resolve(true, false, true, false));
        assertEquals(Action.NONE, RerouteDecision.resolve(false, true, false, true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=RerouteDecisionTest test`
Expected: FAIL — compilation error, `RerouteDecision` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/RerouteDecision.java`:

```java
package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Pure precedence/gating logic for the Bedrock EliteMobs menu reroute.
 *
 * <p>The status menu (Phase 7.3) takes precedence over the quest menu (Phase 7.3b),
 * and each is independently gated by its own config flag. Extracted from
 * {@link BedrockMenuRerouteListener} so the branch selection is unit-testable
 * without Bukkit, Floodgate, or EliteMobs on the classpath.
 */
public final class RerouteDecision {

    public enum Action { NONE, STATUS, QUEST }

    private RerouteDecision() {}

    /**
     * @param statusEnabled config flag {@code phase73.bedrock-dialog-reroute}
     * @param statusMatch   the opened inventory matched a registered status menu title
     * @param questEnabled  config flag {@code phase73.bedrock-quest-reroute}
     * @param questMatch    the opened inventory was recovered as an EM quest menu
     */
    public static Action resolve(boolean statusEnabled, boolean statusMatch,
                                 boolean questEnabled, boolean questMatch) {
        if (statusEnabled && statusMatch) return Action.STATUS;
        if (questEnabled && questMatch) return Action.QUEST;
        return Action.NONE;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=RerouteDecisionTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/RerouteDecision.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bridge/RerouteDecisionTest.java
git commit -m "Phase 7.3b: RerouteDecision pure resolver (status>quest precedence + flag gating)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `QuestMenuContext` carrier record

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/elite/QuestMenuContext.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/elite/QuestMenuContextTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/crazypandas/fmmbedrockbridge/elite/QuestMenuContextTest.java`:

```java
package de.crazypandas.fmmbedrockbridge.elite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class QuestMenuContextTest {

    @Test
    void carriesQuestsAndNpcWithoutInspectingThem() {
        Object npc = new Object();
        List<?> quests = List.of("quest-a", "quest-b");

        QuestMenuContext ctx = new QuestMenuContext(quests, npc);

        assertEquals(quests, ctx.quests());
        assertSame(npc, ctx.npcEntity());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=QuestMenuContextTest test`
Expected: FAIL — compilation error, `QuestMenuContext` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/de/crazypandas/fmmbedrockbridge/elite/QuestMenuContext.java`:

```java
package de.crazypandas.fmmbedrockbridge.elite;

import java.util.List;

/**
 * Opaque carrier for the EliteMobs context recovered from a Bedrock quest chest:
 * the quests being offered and the originating NPC. Phase 7.3b.
 *
 * <p>The bridge never inspects these EM objects — it only passes them straight back
 * into EM's {@code QuestMenu.generateDialogMenu(...)}. Kept as raw types ({@code List<?>},
 * {@code Object}) so this codebase does not compile-depend on EM's {@code Quest}/
 * {@code NPCEntity} classes (they are resolved by reflection in {@link EliteMobsHook}).
 */
public record QuestMenuContext(List<?> quests, Object npcEntity) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=QuestMenuContextTest test`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/elite/QuestMenuContext.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/elite/QuestMenuContextTest.java
git commit -m "Phase 7.3b: QuestMenuContext carrier record (opaque quests + npc)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `EliteMobsHook` quest reflection (recover context + invoke dialog)

This task adds reflection into EM internals. **It has no unit test** — reflection against `com.magmaguy.*` requires EliteMobs + a running server, so it is integration-level and verified manually in the rollout (consistent with the Phase 7.3 `EliteMobsHook` boundary). The verification here is that the project still compiles.

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/elite/EliteMobsHook.java`

**Reflection targets (verified against EliteMobs 10.4.0 source):**
- `com.magmaguy.elitemobs.quests.menus.QuestInventoryMenu` — `private static final HashMap<Inventory, QuestDirectory> questDirectories`, `private static final HashMap<Inventory, QuestInventory> questInventories`.
- `QuestInventoryMenu$QuestDirectory` — `HashMap<Integer, Quest> questMap`, `NPCEntity npcEntity`.
- `QuestInventoryMenu$QuestInventory` — `Quest quest`, `NPCEntity npcEntity`.
- `com.magmaguy.elitemobs.quests.menus.QuestMenu#generateDialogMenu(java.util.List, org.bukkit.entity.Player, com.magmaguy.elitemobs.npcs.NPCEntity)`.

- [ ] **Step 1: Add the quest reflection block**

In `EliteMobsHook.java`, insert the following **after** the `openNativeStatusDialog(...)` method (i.e. after the current line 154 `}`) and **before** the `markApiBroken` method:

```java
    // --- Phase 7.3b: native NPC quest dialog reroute (EM internals via reflection) ---

    private static volatile boolean questReflectionInit = false;
    private static java.lang.reflect.Field questDirectoriesField; // static Map<Inventory, QuestDirectory>
    private static java.lang.reflect.Field questInventoriesField; // static Map<Inventory, QuestInventory>
    private static java.lang.reflect.Field directoryQuestMapField; // QuestDirectory.questMap : Map<Integer,Quest>
    private static java.lang.reflect.Field directoryNpcField;      // QuestDirectory.npcEntity
    private static java.lang.reflect.Field inventoryQuestField;    // QuestInventory.quest
    private static java.lang.reflect.Field inventoryNpcField;      // QuestInventory.npcEntity
    private static java.lang.reflect.Method generateDialogMenuMethod;

    private static synchronized void initQuestReflection() {
        if (questReflectionInit) return;
        questReflectionInit = true;
        try {
            Class<?> menu = Class.forName("com.magmaguy.elitemobs.quests.menus.QuestInventoryMenu");
            questDirectoriesField = menu.getDeclaredField("questDirectories");
            questDirectoriesField.setAccessible(true);
            questInventoriesField = menu.getDeclaredField("questInventories");
            questInventoriesField.setAccessible(true);

            Class<?> dir = Class.forName(
                    "com.magmaguy.elitemobs.quests.menus.QuestInventoryMenu$QuestDirectory");
            directoryQuestMapField = dir.getDeclaredField("questMap");
            directoryQuestMapField.setAccessible(true);
            directoryNpcField = dir.getDeclaredField("npcEntity");
            directoryNpcField.setAccessible(true);

            Class<?> inv = Class.forName(
                    "com.magmaguy.elitemobs.quests.menus.QuestInventoryMenu$QuestInventory");
            inventoryQuestField = inv.getDeclaredField("quest");
            inventoryQuestField.setAccessible(true);
            inventoryNpcField = inv.getDeclaredField("npcEntity");
            inventoryNpcField.setAccessible(true);

            Class<?> questMenu = Class.forName("com.magmaguy.elitemobs.quests.menus.QuestMenu");
            Class<?> npcClass = Class.forName("com.magmaguy.elitemobs.npcs.NPCEntity");
            generateDialogMenuMethod = questMenu.getMethod(
                    "generateDialogMenu", java.util.List.class, Player.class, npcClass);
        } catch (Throwable t) {
            questDirectoriesField = null;
            questInventoriesField = null;
            generateDialogMenuMethod = null;
            log.warning("[BRIDGE] Phase 7.3b quest reflection unavailable — quest reroute inert. Cause: " + t);
        }
    }

    /**
     * If {@code inventory} is an EM NPC quest chest (single quest or multi-quest directory),
     * recover the quests + originating NPC and <b>remove</b> the inventory from EM's internal
     * tracking map, returning the context. Otherwise returns empty.
     *
     * <p>The map entry is removed because the caller cancels the chest's open — EM only cleans
     * these maps on {@code InventoryCloseEvent}, which never fires for a cancelled open, so
     * without removal the entry would leak.
     */
    public static java.util.Optional<QuestMenuContext> tryRecoverQuestMenu(
            org.bukkit.inventory.Inventory inventory) {
        if (!isAvailable() || inventory == null) return java.util.Optional.empty();
        initQuestReflection();
        if (questDirectoriesField == null || questInventoriesField == null) {
            return java.util.Optional.empty();
        }
        try {
            // Multi-quest directory
            java.util.Map<?, ?> directories = (java.util.Map<?, ?>) questDirectoriesField.get(null);
            Object directory = directories == null ? null : directories.get(inventory);
            if (directory != null) {
                java.util.Map<?, ?> questMap = (java.util.Map<?, ?>) directoryQuestMapField.get(directory);
                java.util.List<Object> quests = new java.util.ArrayList<>(
                        questMap == null ? java.util.List.of() : questMap.values());
                Object npc = directoryNpcField.get(directory);
                directories.remove(inventory);
                return java.util.Optional.of(new QuestMenuContext(quests, npc));
            }
            // Single-quest entry
            java.util.Map<?, ?> inventories = (java.util.Map<?, ?>) questInventoriesField.get(null);
            Object questInv = inventories == null ? null : inventories.get(inventory);
            if (questInv != null) {
                Object quest = inventoryQuestField.get(questInv);
                java.util.List<Object> quests = new java.util.ArrayList<>();
                if (quest != null) quests.add(quest);
                Object npc = inventoryNpcField.get(questInv);
                inventories.remove(inventory);
                return java.util.Optional.of(new QuestMenuContext(quests, npc));
            }
            return java.util.Optional.empty();
        } catch (Throwable t) {
            log.warning("[BRIDGE] Phase 7.3b tryRecoverQuestMenu failed (quest reroute inert this call): " + t);
            return java.util.Optional.empty();
        }
    }

    /**
     * Trigger EM's native NPC quest MC dialog for the player (Geyser renders it as a Bedrock
     * form). {@code quests} and {@code npcEntity} must be the objects from
     * {@link #tryRecoverQuestMenu}. Returns true on success, false if EM is unavailable / the
     * call failed.
     */
    public static boolean openNativeQuestDialog(Player player, java.util.List<?> quests, Object npcEntity) {
        if (!isAvailable() || player == null) return false;
        initQuestReflection();
        if (generateDialogMenuMethod == null) return false;
        try {
            generateDialogMenuMethod.invoke(null, quests, player, npcEntity);
            return true;
        } catch (Throwable t) {
            log.warning("[BRIDGE] Phase 7.3b openNativeQuestDialog failed for "
                    + player.getName() + " (Bedrock player may see no menu): " + t);
            return false;
        }
    }
```

- [ ] **Step 2: Verify it compiles (and existing tests stay green)**

Run: `mvn clean package`
Expected: BUILD SUCCESS, `Tests run: 30` (24 existing + 5 RerouteDecision + 1 QuestMenuContext), 0 failures.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/elite/EliteMobsHook.java
git commit -m "Phase 7.3b: EliteMobsHook quest reflection (recover context + invoke dialog)

tryRecoverQuestMenu looks the opened inventory up in EM's QuestInventoryMenu
static maps, extracts quests+npc, and removes the entry (cancelled open => no
close-event cleanup). openNativeQuestDialog calls QuestMenu.generateDialogMenu.
Failures stay isolated from markApiBroken (do not disable Phase 7.1/7.2 hooks).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Wire the quest path into the listener + onEnable + config

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuRerouteListener.java`
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java:141-156`
- Modify: `src/main/resources/config.yml:70-77`

- [ ] **Step 1: Replace the listener's event handler with the two-path version**

In `BedrockMenuRerouteListener.java`, add these imports near the existing imports:

```java
import de.crazypandas.fmmbedrockbridge.elite.QuestMenuContext;
```

Replace the entire `onInventoryOpen(...)` method (current lines 40-65) with:

```java
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) return;

        String title = safeTitle(event);

        if (plugin.getConfig().getBoolean("debug", false)) {
            log.info("[Phase7.3] Bedrock " + player.getName() + " opened inventory title='" + title + "'");
        }

        boolean statusEnabled = plugin.getConfig().getBoolean("phase73.bedrock-dialog-reroute", true);
        boolean questEnabled = plugin.getConfig().getBoolean("phase73.bedrock-quest-reroute", true);

        // Status menu (Phase 7.3): title-registry match.
        Optional<Consumer<Player>> statusInvoker =
                (statusEnabled && title != null) ? registry.findInvoker(title) : Optional.empty();

        // Quest menu (Phase 7.3b): holder-map recovery. Only attempted when status did NOT match,
        // so we never disturb EM's quest maps for a status-menu open. tryRecoverQuestMenu has a
        // side effect (removes the entry), hence the guard.
        Optional<QuestMenuContext> questCtx;
        if (statusInvoker.isPresent() || !questEnabled) {
            questCtx = Optional.empty();
        } else {
            questCtx = EliteMobsHook.tryRecoverQuestMenu(event.getInventory());
        }

        RerouteDecision.Action action = RerouteDecision.resolve(
                statusEnabled, statusInvoker.isPresent(), questEnabled, questCtx.isPresent());

        switch (action) {
            case STATUS -> {
                event.setCancelled(true);
                scheduleReroute(player, () -> statusInvoker.get().accept(player), "status");
            }
            case QUEST -> {
                event.setCancelled(true);
                QuestMenuContext ctx = questCtx.get();
                scheduleReroute(player,
                        () -> EliteMobsHook.openNativeQuestDialog(player, ctx.quests(), ctx.npcEntity()),
                        "quest");
            }
            case NONE -> {
                // Not an EM menu we reroute; leave the inventory untouched.
            }
        }
    }

    private void scheduleReroute(Player player, Runnable invoke, String kind) {
        // Fire after the open-event call stack unwinds and the backend inventory state has settled.
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                invoke.run();
            } catch (Throwable t) {
                log.warning("[Phase7.3] " + kind + " reroute failed for " + player.getName() + ": " + t);
            }
        });
    }
```

(Keep the existing `safeTitle(...)` method and the constructor unchanged.)

- [ ] **Step 2: Update the class Javadoc**

Replace the class-level Javadoc block (current lines 17-25, `/** Phase 7.3 — reroutes ... */`) with:

```java
/**
 * Phase 7.3 / 7.3b — reroutes EliteMobs chest menus to EM's native MC dialog for Bedrock
 * players. When a Bedrock player opens an EM menu the bridge recognises, the chest is
 * cancelled and EM's dialog is fired next tick; Geyser renders it as a native Bedrock form
 * and the dialog's sub-pages cascade natively.
 *
 * <p>Two recognition mechanisms:
 * <ul>
 *   <li><b>Status menu (7.3):</b> {@link MenuRerouteRegistry} title match → {@code openNativeStatusDialog}.</li>
 *   <li><b>NPC quest menu (7.3b):</b> {@link EliteMobsHook#tryRecoverQuestMenu} holder-map lookup
 *       (titles are dynamic) → {@code openNativeQuestDialog}.</li>
 * </ul>
 * Status takes precedence; per-flag gating lives in {@link RerouteDecision}.
 *
 * <p>Java players are never intercepted. Registered only on MC >= 1.21.6 (see
 * {@link FMMBedrockBridge#onEnable()}), so the dialog API is always present here.
 */
```

- [ ] **Step 3: Update onEnable registration gating**

In `FMMBedrockBridge.java`, replace lines 141-156 (the Phase 7.3 block) with:

```java
        // Phase 7.3 / 7.3b — reroute Bedrock EM chest menus to EM's native MC dialogs so
        // Geyser renders them as native Bedrock forms. Requires MC >= 1.21.6.
        // getBukkitVersion() ("1.21.10-R0.1-SNAPSHOT") is always present; McVersions
        // strips the "-R0.1-SNAPSHOT" suffix before comparing.
        boolean mc1216 = de.crazypandas.fmmbedrockbridge.bridge.McVersions
                .isAtLeast(getServer().getBukkitVersion(), 1, 21, 6);
        boolean statusReroute = getConfig().getBoolean("phase73.bedrock-dialog-reroute", true);
        boolean questReroute = getConfig().getBoolean("phase73.bedrock-quest-reroute", true);
        boolean anyReroute = statusReroute || questReroute;
        if (floodgateAvailable && elitemobsAvailable && mc1216 && anyReroute) {
            getServer().getPluginManager().registerEvents(
                    new de.crazypandas.fmmbedrockbridge.bridge.BedrockMenuRerouteListener(this), this);
            log.info("Phase 7.3: Bedrock menu dialog-reroute registered (status=" + statusReroute
                    + ", quest=" + questReroute + ")");
        } else {
            log.info("Phase 7.3: reroute NOT registered (floodgate=" + floodgateAvailable
                    + ", em=" + elitemobsAvailable + ", mc>=1.21.6=" + mc1216
                    + ", status=" + statusReroute + ", quest=" + questReroute + ")");
        }
```

- [ ] **Step 4: Add the config flag**

In `src/main/resources/config.yml`, replace the `phase73:` block (lines 70-77) with:

```yaml
# Phase 7.3 / 7.3b — Bedrock menu dialog-reroute
phase73:
  # Reroute the Bedrock /em status menu to EliteMobs' native MC dialog so Geyser
  # renders it as a native Bedrock form (sub-pages cascade natively). Requires
  # MC >= 1.21.6. Java players are unaffected. Use the top-level `debug: true`
  # to log the title of every inventory a Bedrock player opens.
  bedrock-dialog-reroute: true
  # Reroute Bedrock EliteMobs NPC quest menus (single quest + multi-quest list) to
  # EM's native quest dialog. Same requirements/behaviour as above; toggle separately.
  bedrock-quest-reroute: true
```

- [ ] **Step 5: Build and verify all tests pass**

Run: `mvn clean package`
Expected: BUILD SUCCESS, `Tests run: 30`, 0 failures, 0 errors. (The listener and onEnable changes are Bukkit-coupled and not unit-tested; the pre-existing 24 tests plus the 6 new ones must all stay green.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuRerouteListener.java \
        src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java \
        src/main/resources/config.yml
git commit -m "Phase 7.3b: wire NPC quest-menu reroute (listener + onEnable + config)

Listener now resolves status-vs-quest via RerouteDecision, recovering quest
context from EM only when the status title did not match. onEnable registers
the listener when either reroute flag is on; new phase73.bedrock-quest-reroute
flag (default true).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Docs + final verification

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE_SESSION.md`

- [ ] **Step 1: Document Phase 7.3b in README.md**

Find the Phase 7.3 section/status entry in `README.md` and add an adjacent Phase 7.3b note. Use this content (adapt heading depth to match the surrounding file):

```markdown
### Phase 7.3b — Bedrock NPC quest-menu dialog-reroute

Extends Phase 7.3 to EliteMobs' NPC quest menu — the only other Bedrock-forced-to-chest
EM menu with a native dialog path (`QuestMenu.generateDialogMenu`). Detection is
holder-based: the bridge looks the opened chest up in EM's internal `QuestInventoryMenu`
maps (reflection in `EliteMobsHook`) because quest chest titles are dynamic (single-quest
title = quest name, multi-quest = literal `"Quests"`). On a hit the chest is cancelled and
EM's quest dialog fires next tick → Geyser renders a native Bedrock form.

Config: `phase73.bedrock-quest-reroute: true` (toggles independently of
`bedrock-dialog-reroute`). Requires MC >= 1.21.6. Java players unaffected.
```

- [ ] **Step 2: Add a Phase 7.3b progress entry to CLAUDE_SESSION.md**

Append a dated entry (2026-06-05) summarising: pom paper-api bump to 1.21.10; Phase 7.3b quest-menu reroute (new `RerouteDecision`, `QuestMenuContext`, `EliteMobsHook` quest reflection, listener two-path dispatch, `bedrock-quest-reroute` flag); 30 unit tests green; pending manual Bedrock verification + deploy. Match the formatting of the existing newest entry in the file.

- [ ] **Step 3: Final full build**

Run: `mvn clean package`
Expected: BUILD SUCCESS, `Tests run: 30`, 0 failures. Confirm the built jar exists:
`ls target/FMMBedrockBridge-0.1.0-SNAPSHOT-*.jar`

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE_SESSION.md
git commit -m "docs(phase7.3b): record NPC quest-menu reroute in README + session log

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Rollout (manual, after the plan is implemented)

These steps need the live server and a Bedrock client — run with Fabi (do not act on the server unprompted; per CLAUDE.md SCP of the jar is pre-authorised, console/restart is Fabi's).

1. SCP the jar:
   ```bash
   scp target/FMMBedrockBridge-0.1.0-SNAPSHOT-*.jar \
       amp@mc.crazypandas.de:/home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge.jar
   ```
2. Fabi does a full **stop→start** of TestServer01 via AMP (NOT `/reload` — it stalls on EM/magmacore init).
3. Verify boot log: `Done (Xs)!` and `Phase 7.3: Bedrock menu dialog-reroute registered (status=true, quest=true)`.
4. Bedrock client manual test:
   - Talk to a quest NPC offering **multiple** quests → native quest-list form (not a chest); pick a quest → detail form; Accept / Track / Back work.
   - Talk to a quest NPC offering a **single** quest → native quest-detail form directly.
   - `/em` status menu still renders as a native form (Phase 7.3 regression check).
   - A Java client sees the unchanged chest/dialog behaviour.
5. Optionally extend the upstream draft `docs/upstream-bugs/em-route-bedrock-to-dialog.md` to mention `QuestMenu.generateQuestMenu` has the same Bedrock-forced-to-chest branch.

## Self-Review Notes

- **Spec coverage:** holder-based detection (Task 3), map-leak removal (Task 3 `tryRecoverQuestMenu`), reflection isolated from `markApiBroken` (Task 3 — uses `log.warning`, never `markApiBroken`), own config flag + OR registration (Task 4), single + multi-quest both handled (Task 3 two branches), testing of `QuestMenuContext` (Task 2) and dispatch precedence/gating (Task 1 `RerouteDecision`), upstream draft extension (Rollout step 5). All spec sections map to a task.
- **Type consistency:** `RerouteDecision.resolve(boolean,boolean,boolean,boolean) → Action`, `RerouteDecision.Action{NONE,STATUS,QUEST}`, `QuestMenuContext(List<?> quests, Object npcEntity)` with accessors `quests()`/`npcEntity()`, `EliteMobsHook.tryRecoverQuestMenu(Inventory) → Optional<QuestMenuContext>`, `EliteMobsHook.openNativeQuestDialog(Player, List<?>, Object) → boolean` — names/signatures used identically across Tasks 1–4.
- **No placeholders:** every code step contains full code; commands include expected output.
```
