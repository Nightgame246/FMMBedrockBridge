# EM Bedrock Menu Dialog-Reroute Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For Bedrock players, intercept EliteMobs' `/em` status chest menu and trigger EM's native MC dialog instead, so Geyser renders it as a native Bedrock form (sub-pages cascade natively).

**Architecture:** A single Bukkit `InventoryOpenEvent` listener checks Floodgate + a title-match registry; on match it cancels the chest and invokes EM's `PlayerStatusScreenDialog.showPlayerStatusDialog` via reflection (isolated in `EliteMobsHook`). Pure helpers (`McVersions`, `MenuRerouteRegistry`) carry the testable logic. Registration is gated on Floodgate + EliteMobs + MC ≥ 1.21.6 + config.

**Tech Stack:** Java 21, Paper API 1.21.4 (provided), Floodgate API, JUnit 5, Maven. Build: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn`.

**Spec:** `docs/superpowers/specs/2026-06-04-em-bedrock-dialog-reroute-design.md`

---

## File Structure

- Create `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/McVersions.java` — pure dotted-version comparison.
- Create `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/MenuRerouteRegistry.java` — pure title-normalization + title→invoker registry.
- Modify `src/main/java/de/crazypandas/fmmbedrockbridge/elite/EliteMobsHook.java` — add reflection wrappers `statusIndexMenuTitle()` + `openNativeStatusDialog(Player)`.
- Create `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuRerouteListener.java` — the production listener.
- Modify `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java` — replace spike registration with gated production registration.
- Modify `src/main/resources/config.yml` — remove `spike:` block, add `phase73:` block.
- Delete `src/main/java/de/crazypandas/fmmbedrockbridge/spike/BedrockStatusDialogSpike.java` (and the now-empty `spike` package).
- Create `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/McVersionsTest.java`.
- Create `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/MenuRerouteRegistryTest.java`.
- Create `docs/upstream-bugs/em-route-bedrock-to-dialog.md` — upstream feature request (Approach ③).

---

## Task 1: McVersions pure version helper

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/McVersions.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/McVersionsTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/McVersionsTest.java`:

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McVersionsTest {

    @Test
    void atOrAboveThreshold() {
        assertTrue(McVersions.isAtLeast("1.21.10", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("1.21.6", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("1.22.0", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("2.0.0", 1, 21, 6));
        assertTrue(McVersions.isAtLeast("1.21.10-R0.1-SNAPSHOT", 1, 21, 6));
    }

    @Test
    void belowThreshold() {
        assertFalse(McVersions.isAtLeast("1.21.4", 1, 21, 6));
        assertFalse(McVersions.isAtLeast("1.21", 1, 21, 6));   // patch 0 < 6
        assertFalse(McVersions.isAtLeast("1.20.10", 1, 21, 6));
    }

    @Test
    void malformedIsFalse() {
        assertFalse(McVersions.isAtLeast(null, 1, 21, 6));
        assertFalse(McVersions.isAtLeast("garbage", 1, 21, 6));
        assertFalse(McVersions.isAtLeast("", 1, 21, 6));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q test -Dtest=McVersionsTest`
Expected: FAIL — compilation error, `McVersions` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/McVersions.java`:

```java
package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Pure helper for comparing a dotted Minecraft version string (e.g. "1.21.10")
 * against a major.minor.patch threshold. Anything after the first '-', ' ' or '_'
 * (e.g. "-R0.1-SNAPSHOT") is ignored. Malformed input returns false (fail-safe).
 */
public final class McVersions {

    private McVersions() {}

    public static boolean isAtLeast(String version, int major, int minor, int patch) {
        if (version == null) return false;
        int cut = version.length();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '-' || c == ' ' || c == '_') { cut = i; break; }
        }
        String[] parts = version.substring(0, cut).split("\\.");
        int[] v = new int[]{0, 0, 0};
        try {
            for (int i = 0; i < 3 && i < parts.length; i++) {
                v[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return false;
        }
        if (v[0] != major) return v[0] > major;
        if (v[1] != minor) return v[1] > minor;
        return v[2] >= patch;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q test -Dtest=McVersionsTest`
Expected: PASS — `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/McVersions.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bridge/McVersionsTest.java
git commit -m "Phase 7.3: McVersions pure version-threshold helper"
```

---

## Task 2: MenuRerouteRegistry (title normalization + lookup)

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/MenuRerouteRegistry.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/MenuRerouteRegistryTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/MenuRerouteRegistryTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q test -Dtest=MenuRerouteRegistryTest`
Expected: FAIL — compilation error, `MenuRerouteRegistry` does not exist.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/MenuRerouteRegistry.java`:

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Maps an opened inventory title to the dialog-invoker that should replace it for
 * Bedrock players. Titles are matched after {@link #normalize(String)} so config
 * color formatting (e.g. {@code §2EliteMobs Index}) does not break the match.
 *
 * <p>Each entry's title is supplied lazily (read from EM config at match time, so
 * it reflects the live configured/localized menu name). Phase 7.3 registers a
 * single entry (EM status index); the structure makes additional EM menus with a
 * dialog equivalent a one-line addition.
 */
public final class MenuRerouteRegistry {

    private record Entry(Supplier<String> titleSupplier, Consumer<Player> invoker) {}

    private final List<Entry> entries = new ArrayList<>();

    public void register(Supplier<String> titleSupplier, Consumer<Player> invoker) {
        entries.add(new Entry(titleSupplier, invoker));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The invoker whose configured title matches {@code openedTitle}, if any. */
    public Optional<Consumer<Player>> findInvoker(String openedTitle) {
        if (openedTitle == null) return Optional.empty();
        String norm = normalize(openedTitle);
        for (Entry e : entries) {
            String expected = e.titleSupplier().get();
            if (expected == null) continue;
            if (normalize(expected).equals(norm)) return Optional.of(e.invoker());
        }
        return Optional.empty();
    }

    /** Strip legacy color codes (&x / §x), trim, lowercase (Locale.ROOT). */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                i++; // skip the code char too
                continue;
            }
            b.append(c);
        }
        return b.toString().trim().toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q test -Dtest=MenuRerouteRegistryTest`
Expected: PASS — `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/MenuRerouteRegistry.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bridge/MenuRerouteRegistryTest.java
git commit -m "Phase 7.3: MenuRerouteRegistry title-normalize + lookup"
```

---

## Task 3: EliteMobsHook reflection wrappers

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/elite/EliteMobsHook.java`

No unit test — these call EM internals via reflection and are verified by compile + the manual Bedrock test in Task 6 (matches the existing hook's integration-level style).

- [ ] **Step 1: Add the `Player` import**

In `EliteMobsHook.java`, add to the imports (after `import org.bukkit.entity.LivingEntity;`):

```java
import org.bukkit.entity.Player;
```

- [ ] **Step 2: Add the reflection fields + methods**

In `EliteMobsHook.java`, insert before the closing `private static void markApiBroken(Throwable t)` method:

```java
    // --- Phase 7.3: native player-status dialog reroute (EM internals via reflection) ---

    private static volatile boolean dialogReflectionInit = false;
    private static java.lang.reflect.Method getIndexChestMenuNameMethod;
    private static java.lang.reflect.Method showPlayerStatusDialogMethod;

    private static synchronized void initDialogReflection() {
        if (dialogReflectionInit) return;
        dialogReflectionInit = true;
        try {
            Class<?> cfg = Class.forName(
                    "com.magmaguy.elitemobs.config.menus.premade.PlayerStatusMenuConfig");
            getIndexChestMenuNameMethod = cfg.getMethod("getIndexChestMenuName");
            Class<?> dlg = Class.forName(
                    "com.magmaguy.elitemobs.playerdata.statusscreen.PlayerStatusScreenDialog");
            showPlayerStatusDialogMethod = dlg.getMethod("showPlayerStatusDialog", Player.class);
        } catch (Throwable t) {
            getIndexChestMenuNameMethod = null;
            showPlayerStatusDialogMethod = null;
            log.warning("[BRIDGE] Phase 7.3 dialog reflection unavailable — reroute inert. Cause: " + t);
        }
    }

    /**
     * The configured title of EM's {@code /em} status index chest menu
     * (e.g. {@code §2EliteMobs Index}), or null if EM is unavailable / API changed.
     */
    public static String statusIndexMenuTitle() {
        if (!isAvailable()) return null;
        initDialogReflection();
        if (getIndexChestMenuNameMethod == null) return null;
        try {
            Object v = getIndexChestMenuNameMethod.invoke(null);
            return v instanceof String s ? s : null;
        } catch (Throwable t) {
            markApiBroken(t);
            return null;
        }
    }

    /**
     * Trigger EM's native player-status MC dialog for the player (Geyser renders it
     * as a Bedrock form). Returns true on success, false if EM is unavailable / the
     * call failed.
     */
    public static boolean openNativeStatusDialog(Player player) {
        if (!isAvailable() || player == null) return false;
        initDialogReflection();
        if (showPlayerStatusDialogMethod == null) return false;
        try {
            showPlayerStatusDialogMethod.invoke(null, player);
            return true;
        } catch (Throwable t) {
            markApiBroken(t);
            return false;
        }
    }
```

- [ ] **Step 3: Verify it compiles (full build, existing tests stay green)**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q package`
Expected: BUILD SUCCESS, 0 failures (24 tests total: 17 existing + 3 McVersions + 4 MenuRerouteRegistry).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/elite/EliteMobsHook.java
git commit -m "Phase 7.3: EliteMobsHook reflection wrappers for native status dialog"
```

---

## Task 4: BedrockMenuRerouteListener

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuRerouteListener.java`

No unit test — needs a live server/event; verified by compile + Task 6 manual test.

- [ ] **Step 1: Create the listener**

Create `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuRerouteListener.java`:

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import de.crazypandas.fmmbedrockbridge.elite.EliteMobsHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Phase 7.3 — reroutes EliteMobs chest menus to EM's native MC dialog for Bedrock
 * players. When a Bedrock player opens a chest whose title matches a registered EM
 * menu, the chest is cancelled and EM's dialog is fired next tick; Geyser renders it
 * as a native Bedrock form and the dialog's sub-pages cascade natively.
 *
 * <p>Java players are never intercepted. Registered only on MC >= 1.21.6 (see
 * {@link FMMBedrockBridge#onEnable()}), so the dialog API is always present here.
 */
public final class BedrockMenuRerouteListener implements Listener {

    private final FMMBedrockBridge plugin;
    private final Logger log;
    private final MenuRerouteRegistry registry = new MenuRerouteRegistry();

    public BedrockMenuRerouteListener(FMMBedrockBridge plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        // Phase 7.3 ships one entry: EM's /em status index menu.
        registry.register(EliteMobsHook::statusIndexMenuTitle,
                p -> EliteMobsHook.openNativeStatusDialog(p));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) return;

        String title = safeTitle(event);
        if (title == null) return;

        if (plugin.getConfig().getBoolean("debug", false)) {
            log.info("[Phase7.3] Bedrock " + player.getName() + " opened inventory title='" + title + "'");
        }

        Optional<Consumer<Player>> invoker = registry.findInvoker(title);
        if (invoker.isEmpty()) return;

        // Suppress the Bedrock chest; fire EM's native dialog next tick (the backend
        // inventory state has settled and we are out of the open-event call stack).
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                invoker.get().accept(player);
            } catch (Throwable t) {
                log.warning("[Phase7.3] dialog reroute failed for " + player.getName() + ": " + t);
            }
        });
    }

    private static String safeTitle(InventoryOpenEvent event) {
        try {
            return event.getView().getTitle();
        } catch (Throwable t) {
            return null;
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q package`
Expected: BUILD SUCCESS, all tests pass, 0 failures.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuRerouteListener.java
git commit -m "Phase 7.3: BedrockMenuRerouteListener (Bedrock /em chest -> native dialog)"
```

---

## Task 5: Wire registration, config, and remove the spike

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java`
- Modify: `src/main/resources/config.yml`
- Delete: `src/main/java/de/crazypandas/fmmbedrockbridge/spike/BedrockStatusDialogSpike.java`

All three changes go in one commit so the build stays green (removing the spike class and its registration together).

- [ ] **Step 1: Replace the spike registration block in `onEnable`**

In `FMMBedrockBridge.java`, find this block:

```java
        // SPIKE (Phase 7.3) — reroute Bedrock /em status menu to EM's native MC-Dialog
        // to learn whether Geyser renders it as a Bedrock form. Throwaway, opt-in.
        if (floodgateAvailable && elitemobsAvailable
                && getConfig().getBoolean("spike.bedrock-status-dialog", false)) {
            getServer().getPluginManager().registerEvents(
                    new de.crazypandas.fmmbedrockbridge.spike.BedrockStatusDialogSpike(this), this);
            log.info("[SPIKE] BedrockStatusDialogSpike registered (Phase 7.3 dialog-reroute test)");
        }
```

Replace it with:

```java
        // Phase 7.3 — reroute Bedrock /em status menu to EM's native MC dialog so
        // Geyser renders it as a native Bedrock form. Requires MC >= 1.21.6.
        // getBukkitVersion() ("1.21.10-R0.1-SNAPSHOT") is always present; McVersions
        // strips the "-R0.1-SNAPSHOT" suffix before comparing.
        boolean mc1216 = de.crazypandas.fmmbedrockbridge.bridge.McVersions
                .isAtLeast(getServer().getBukkitVersion(), 1, 21, 6);
        boolean rerouteCfg = getConfig().getBoolean("phase73.bedrock-dialog-reroute", true);
        if (floodgateAvailable && elitemobsAvailable && mc1216 && rerouteCfg) {
            getServer().getPluginManager().registerEvents(
                    new de.crazypandas.fmmbedrockbridge.bridge.BedrockMenuRerouteListener(this), this);
            log.info("Phase 7.3: Bedrock menu dialog-reroute registered");
        } else {
            log.info("Phase 7.3: reroute NOT registered (floodgate=" + floodgateAvailable
                    + ", em=" + elitemobsAvailable + ", mc>=1.21.6=" + mc1216
                    + ", config=" + rerouteCfg + ")");
        }
```

- [ ] **Step 2: Replace the spike block in `config.yml`**

In `src/main/resources/config.yml`, find this block:

```yaml
# SPIKE (Phase 7.3, throwaway) — reroute the /em status menu for Bedrock players
# from EM's chest menu to EM's native MC-Dialog path, to learn whether Geyser
# renders the dialog as a real Bedrock form. Opt-in; remove once Phase 7.3 is designed.
spike:
  bedrock-status-dialog: false
  # When true, logs the title of every inventory a Bedrock player opens (to confirm
  # the exact match string in-game). Leave false in normal use.
  debug-titles: false
```

Replace it with:

```yaml
# Phase 7.3 — Bedrock menu dialog-reroute
phase73:
  # Reroute the Bedrock /em status menu to EliteMobs' native MC dialog so Geyser
  # renders it as a native Bedrock form (sub-pages cascade natively). Requires
  # MC >= 1.21.6. Java players are unaffected. Use the top-level `debug: true`
  # to log the title of every inventory a Bedrock player opens.
  bedrock-dialog-reroute: true
```

- [ ] **Step 3: Delete the spike class**

```bash
git rm src/main/java/de/crazypandas/fmmbedrockbridge/spike/BedrockStatusDialogSpike.java
```

- [ ] **Step 4: Verify full build is green with no remaining spike references**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q package`
Expected: BUILD SUCCESS, all tests pass, 0 failures.

Run: `grep -rn "spike" src/main pom.xml ; echo "exit=$?"`
Expected: no matches in `src/main` (exit non-zero from grep is fine; the point is zero `spike` references remain in source/config).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java src/main/resources/config.yml
git commit -m "Phase 7.3: wire dialog-reroute (gated on MC>=1.21.6) + config, remove spike"
```

---

## Task 6: Build, deploy, and manual Bedrock verification

**Files:** none (deployment + verification).

- [ ] **Step 1: Build the deployable JAR**

Run: `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn -q clean package`
Expected: BUILD SUCCESS. Note the produced jar path: `target/FMMBedrockBridge-0.1.0-SNAPSHOT-<timestamp>.jar`.

- [ ] **Step 2: SCP the JAR to TestServer01**

```bash
scp -i ~/.ssh/id_ed25519 \
  target/FMMBedrockBridge-0.1.0-SNAPSHOT-*.jar \
  amp@mc.crazypandas.de:/home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge.jar
```

(If multiple timestamped jars exist in `target/`, pass the newest explicitly.)

- [ ] **Step 3: Remove the now-defunct spike block from the live server config (tidy-up)**

```bash
ssh -i ~/.ssh/id_ed25519 amp@mc.crazypandas.de \
  'CFG=/home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/FMMBedrockBridge/config.yml; \
   sed -i "/^# SPIKE (Phase 7.3/,/debug-titles: false/d; /^spike:/,/debug-titles: false/d" "$CFG"; \
   echo "--- spike refs remaining: ---"; grep -n spike "$CFG" || echo none'
```

Expected: `none`. (The new `phase73.bedrock-dialog-reroute` defaults to `true` in code, so the live config needs no addition.)

- [ ] **Step 4: Ask Fabi to do a FULL stop→start of TestServer01 via AMP**

Not `/reload` — it stalls on EliteMobs/magmacore init. This step is performed by Fabi.

- [ ] **Step 5: Verify boot + registration in the fresh log**

```bash
ssh -i ~/.ssh/id_ed25519 amp@mc.crazypandas.de \
  'LOG=/home/amp/.ampdata/instances/TestServer01/Minecraft/logs/latest.log; \
   grep -nE "Enabling FMMBedrockBridge|Phase 7.3:|Done \(" "$LOG" | tail -10'
```

Expected: `Enabling FMMBedrockBridge`, `Phase 7.3: Bedrock menu dialog-reroute registered`, and `Done (Xs)!` — all from the fresh boot (timestamp after the SCP). If instead you see `Phase 7.3: reroute NOT registered (...)`, read the printed gate values to see which precondition failed.

- [ ] **Step 6: Manual Bedrock test (performed by Fabi)**

As a Bedrock client: run `/em`. Expected: a native Bedrock form with the status buttons (not a chest grid). Click sub-pages (Statistiken, Teleports, Ausrüstung, …) — each renders as a native form. As a Java client: `/em` is unchanged.

- [ ] **Step 7: Update docs and commit**

Add a `## Session: 2026-06-04 (Phase 7.3)` entry to `CLAUDE_SESSION.md` summarizing the reroute feature + the deploy, and a Phase 7.3 row/section to `README.md`'s feature table. Then:

```bash
git add CLAUDE_SESSION.md README.md
git commit -m "docs(phase7.3): record Bedrock dialog-reroute feature + deploy"
```

---

## Task 7: Upstream feature-request draft (Approach ③)

**Files:**
- Create: `docs/upstream-bugs/em-route-bedrock-to-dialog.md`

- [ ] **Step 1: Write the draft**

Create `docs/upstream-bugs/em-route-bedrock-to-dialog.md`:

```markdown
# [EliteMobs] Route Bedrock players to the native Dialog menu (Geyser now renders dialogs)

**Repo:** MagmaGuy/EliteMobs
**Version:** 10.4.0
**Related:** Geyser dialog→Bedrock-form translation (PR #5603, MC 1.21.6)

## Summary

`PlayerStatusScreen(Player)` forces Bedrock players to the chest menu path:

    if (!useBookMenus || GeyserDetector.bedrockPlayer(player) || onlyUseBedrockMenus)
        generateChestMenu(player);            // chest
    else if (serverOlderThan(1.21.6))
        generateBook(player);                 // book
    else
        PlayerStatusScreenDialog.showPlayerStatusDialog(player);  // native dialog

On Bedrock this renders as a bare chest container with none of the menu's intended
design. EM already builds the full status menu as native MC dialogs
(`PlayerStatusScreenDialog`), and **Geyser now translates the MC Dialog API into
native Bedrock forms** (since 1.21.6). We verified empirically that cancelling the
Bedrock chest and calling `showPlayerStatusDialog` makes the Bedrock client render a
clean native form, with sub-pages cascading correctly.

## Request

On MC >= 1.21.6, route Bedrock players to the dialog path as well (or add a config
flag, e.g. `useBedrockDialogs: true`), instead of forcing the chest. This gives
Bedrock players the same polished menu Java 1.21.6+ players already get.

## Notes

- Keep the chest path as the fallback for MC < 1.21.6 or if a server opts out.
- We currently work around this in a bridge plugin by intercepting the chest open and
  invoking `showPlayerStatusDialog` ourselves; native routing would let us drop that.

## Environment

- EliteMobs 10.4.0, FreeMinecraftModels 2.7.0
- Geyser-Velocity 2.10.0, Floodgate, Paper 1.21.10 backend, Velocity 3.5.0 proxy
```

- [ ] **Step 2: Commit**

```bash
git add docs/upstream-bugs/em-route-bedrock-to-dialog.md
git commit -m "docs: upstream draft — ask EM to route Bedrock to native dialog"
```

---

## Self-Review notes

- **Spec coverage:** reroute listener (Task 4), EliteMobsHook reflection (Task 3), registry + normalize (Task 2), version guard at registration (Task 1 + Task 5), config `phase73.bedrock-dialog-reroute` + spike removal (Task 5), Bedrock-only/fail-safe/coexistence (Task 4 behavior), manual verify + deploy gotcha (Task 6), upstream ③ (Task 7). All spec sections mapped.
- **Type consistency:** `MenuRerouteRegistry.normalize`, `register(Supplier<String>, Consumer<Player>)`, `findInvoker(String) → Optional<Consumer<Player>>`; `McVersions.isAtLeast(String,int,int,int)`; `EliteMobsHook.statusIndexMenuTitle() → String`, `openNativeStatusDialog(Player) → boolean` — used identically in Tasks 4 and 5.
- **Not unit-tested by design:** EliteMobsHook reflection + the listener (need a live server) — verified by build + the Task 6 manual Bedrock test, consistent with the codebase's integration-level EM code.
```
