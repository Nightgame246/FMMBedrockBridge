# Phase 7.3b — EM Bedrock NPC Quest-Menu Dialog-Reroute (Design Spec)

**Date:** 2026-06-05
**Branch:** `feat/em-quest-reroute`
**Status:** Approved design, ready for implementation plan
**Builds on:** [Phase 7.3 status-menu reroute](2026-06-04-em-bedrock-dialog-reroute-design.md) — same
"reroute the Bedrock chest to EM's native dialog" pattern, applied to the second (and currently last)
EM menu that has a native-dialog equivalent.

## Problem

EliteMobs NPC **quest menus** render on Bedrock as plain Bukkit chest containers (Geyser's automatic
container translation) instead of EM's intended menu design. When a Bedrock player talks to a
quest-giver NPC, `QuestMenu.generateQuestMenu(...)` forces them to the chest path while Java clients
on MC ≥ 1.21.6 get a clean native MC dialog.

## Key discovery

`QuestMenu.generateQuestMenu(quests, player, npcEntity)` has the exact same three-path shape as the
Phase 7.3 status menu:

```
if  !useBookMenus(player) || Bedrock(player) || onlyUseBedrockMenus
      → generateInventoryQuestEntries(...)     ← chest, what Bedrock gets today
elif serverOlderThan(1.21.6)
      → generateBookQuestEntries(...)          ← book
else  (Java ≥ 1.21.6)
      → generateDialogMenu(...) → DialogMaker.sendQuestMessage(quests, player, npcEntity)
                                                 ← native MC Dialog API
```

EM forces Bedrock to the chest (`GeyserDetector.bedrockPlayer(player)` → first branch), a historical
decision from before Bedrock could render dialogs. **Geyser now translates the MC Dialog API into
native Bedrock forms** (PR #5603 / MC 1.21.6), already validated end-to-end in Phase 7.3.

It is the **only** remaining Bedrock-forced-to-chest EM menu with a native dialog path. The shops
(`CustomShopMenu`, `ProceduralShopMenu`, `ArrowShopMenu`, …), `ArenaMenu`, `RepairMenu`,
`ScrapperMenu`, enchant/unbind/scroll menus, and gambling have **no** `DialogManager` equivalent and
cannot be rerouted until EM adds one (tracked as upstream work, not this phase).

### Why the Phase 7.3 title-registry does NOT work here

The status menu had a single stable, config-derived chest title (`PlayerStatusMenuConfig
.getIndexChestMenuName()`), so a title-match registry entry sufficed. The quest chest titles are
**dynamic and not registrable**:

- Multi-quest directory (`generateInventoryQuestDirectory`): title is the hardcoded literal `"Quests"`.
- Single-quest entry (`generateInventoryQuestEntry`): title is the **quest's own header**
  (`QuestMenu.QuestText.getHeader().toPlainText()`) — varies per quest.

So detection must be **holder-based**, not title-based.

## Goals

- Bedrock players who open an EM NPC quest menu (single quest or multi-quest directory) get EM's
  native quest dialog (→ Bedrock form) instead of the chest container.
- Java players are completely unaffected.
- Reuse EM's existing dialog implementation (`QuestMenu.generateDialogMenu`) — the bridge adds **no**
  form-building code.
- EM coupling is reflection-based and isolated in `EliteMobsHook`, tolerant of EM API drift.

## Non-Goals (this phase)

- Shops, arena, repair, scrapper, enchant/unbind/scroll, gambling menus (no EM dialog equivalent).
- Building any Cumulus form ourselves.
- Changing EM's Java-side behavior.
- Rerouting the in-dialog quest-detail command (`/elitemobs quest check <id>`) — it is a command,
  not an `InventoryOpenEvent`, and already cascades natively inside the dialog tree (same as the
  status menu's sub-pages in Phase 7.3).

## Architecture

### Data flow

```
Bedrock player talks to quest-giver NPC
  → EM: QuestMenu.generateQuestMenu(quests, player, npc)
        → generateInventoryQuestEntries → new QuestDirectory|QuestInventory(...)   // registers holder in static map
        → player.openInventory(chest)
  → Bukkit InventoryOpenEvent fires
  → BedrockMenuRerouteListener (priority HIGH, ignoreCancelled=true):
        is Floodgate(player)?                     no  → return (Java untouched)
        title-registry match (status menu)?       yes → existing Phase 7.3 path
        EliteMobsHook.tryRecoverQuestMenu(inv)?   empty → return (not a quest menu, untouched)
        present →
          event.setCancelled(true)                // suppress the Bedrock chest
          next tick: EliteMobsHook.openNativeQuestDialog(player, quests, npc)
  → EM sends native MC dialog packets (DialogMaker)
  → Geyser translates dialog → native Bedrock form
  → quest-list sub-navigation + "quest check" buttons cascade natively (no further bridge involvement)
```

### Components

1. **`elite/EliteMobsHook` additions** (the only class touching `com.magmaguy.*`)
   - `static Optional<QuestMenuContext> tryRecoverQuestMenu(Inventory inv)` —
     reflectively reads the two private static maps
     `QuestInventoryMenu.questDirectories` (`HashMap<Inventory, QuestDirectory>`) and
     `QuestInventoryMenu.questInventories` (`HashMap<Inventory, QuestInventory>`):
     - `questDirectories.get(inv)` present → quests = `directory.questMap.values()`, npc = `directory.npcEntity`.
     - else `questInventories.get(inv)` present → quests = `[inventory.quest]`, npc = `inventory.npcEntity`.
     - else → `Optional.empty()`.
     - On a hit, **remove the entry from the map** before returning. EM only cleans these maps on
       `InventoryCloseEvent`; since we cancel the open, no close fires, so without this removal the
       entry would leak. (Removing it is also correct: the chest never becomes interactive.)
   - `static boolean openNativeQuestDialog(Player player, List<?> quests, Object npcEntity)` —
     reflection on `QuestMenu.generateDialogMenu(List, Player, NPCEntity)`; returns false on failure.
   - `QuestMenuContext` — tiny carrier record `{ List<?> quests, Object npcEntity }` (raw types kept
     opaque; the bridge never inspects the EM objects, only passes them back to EM).
   - Reflection `Field`/`Method` handles cached once. Consistent with Phase 7.3: **quest-reroute
     reflection failures do NOT call `markApiBroken`** — they are isolated from the Phase 7.1/7.2
     hook health so a quest-API drift can't disable bossbar/nametag/item features. Failure → empty/false.

2. **`bridge/BedrockMenuRerouteListener`** (extended, not duplicated)
   - Same single `@EventHandler(priority = HIGH, ignoreCancelled = true)` on `InventoryOpenEvent`.
   - After the existing title-registry lookup misses, attempt `EliteMobsHook.tryRecoverQuestMenu(
     event.getInventory())`. On a present context: `event.setCancelled(true)` and schedule
     `openNativeQuestDialog(player, ctx.quests, ctx.npc)` next tick (same cancel/next-tick boilerplate
     and try/catch WARN as the status path).
   - Gated by the new `phase73.bedrock-quest-reroute` flag (checked at lookup time, cheap) in addition
     to the boot-time gates the listener is already registered behind.

### Reflection targets

- `com.magmaguy.elitemobs.quests.menus.QuestInventoryMenu` — private static fields
  `questDirectories`, `questInventories` (both `HashMap<Inventory, …>`).
- Inner `QuestInventoryMenu$QuestDirectory` — fields `questMap` (`HashMap<Integer, Quest>`), `npcEntity`.
- Inner `QuestInventoryMenu$QuestInventory` — fields `quest` (`Quest`), `npcEntity`.
- `com.magmaguy.elitemobs.quests.menus.QuestMenu#generateDialogMenu(java.util.List, org.bukkit.entity.Player, com.magmaguy.elitemobs.npcs.NPCEntity)` → void.

(Reflection rather than direct import because these are EM **internal** classes/fields, not stable API;
reflection keeps compile decoupled and degrades gracefully on EM drift.)

## Safety / edge cases

- **Version guard:** unchanged from Phase 7.3 — the listener is only **registered** on MC ≥ 1.21.6.
- **Fail-safe ordering:** cancel the event **only** after a context is successfully recovered. The
  dialog invoke is next-tick; on throw, log WARN and accept "no menu shown" as a rare failure path.
  (Optional later hardening: on invoke failure reopen EM's chest — deferred unless needed.)
- **Map-leak avoidance:** entry removed on recovery (see component 1). If recovery succeeds but the
  next-tick invoke later throws, the map entry is already gone — no leak, and EM's own
  click/close listeners simply have nothing registered for that (now-cancelled) inventory.
- **Bedrock only:** Floodgate `isFloodgatePlayer` gate; Java players never intercepted.
- **Ordering vs. status path:** title-registry (status) is checked first; quest recovery second. The
  two are mutually exclusive in practice (different inventories), so order is just a cheap short-circuit.
- **EM absent / API broken:** reflection handles resolve to inert (empty/false) → chest stays, never crashes.
- **Reflection drift containment:** isolated from `markApiBroken` (see component 1).

## Config

```yaml
phase73:
  # Reroute the Bedrock /em status menu to EliteMobs' native MC dialog. Requires MC >= 1.21.6.
  bedrock-dialog-reroute: true
  # Reroute Bedrock EM NPC quest menus to EliteMobs' native quest dialog. Requires MC >= 1.21.6.
  bedrock-quest-reroute: true
```

- The listener is registered when `bedrock-dialog-reroute || bedrock-quest-reroute` is true (plus the
  existing `floodgate && elitemobs && MC ≥ 1.21.6` boot gates). Each path then self-checks its own flag
  at lookup time, so either reroute can be toggled independently without code changes.
- Each failed gate is logged at enable so the inert state is diagnosable.

## Testing

- **Unit:**
  - `QuestMenuContext` carrier behavior (holds quests + npc, no inspection).
  - Listener dispatch logic with a faked recovery hook: given (a) a status-title match, (b) a quest
    recovery hit, (c) neither — asserts the right branch is taken / event cancelled only on a hit /
    quest path skipped when `bedrock-quest-reroute=false`. (EM reflection itself is integration-level,
    covered by manual test, not unit-mocked — same boundary as Phase 7.3's `EliteMobsHook`.)
- **Manual (Bedrock client):**
  - Talk to a quest NPC offering **multiple** quests → native quest-list form (not a chest); pick a
    quest → quest detail form; Accept / Track / Back buttons work.
  - Talk to a quest NPC offering a **single** quest → native quest-detail form directly.
  - Confirm a Java client is unchanged.
  - Confirm the status `/em` reroute (Phase 7.3) still works (no regression).

## Parallel work (Approach ③, separate from this phase)

Extend the existing upstream draft to MagmaGuy (`docs/upstream-bugs/em-route-bedrock-to-dialog.md`):
the same Bedrock-forced-to-chest pattern exists in `QuestMenu.generateQuestMenu` — route Bedrock
(MC ≥ 1.21.6) to the dialog path there too. If adopted, both bridge reroutes become removable.

## Rollout

1. Implement `EliteMobsHook` quest methods + `QuestMenuContext` + listener extension + config flag.
2. Build (unit tests green), SCP `FMMBedrockBridge.jar` to TestServer01.
3. Full **stop→start** of TestServer01 (not `/reload` — it stalls on EM/magmacore init).
4. Verify boot log: `Done (Xs)!` + listener registered; Bedrock NPC quest menu → native form
   (single + directory); status `/em` still native; Java client unchanged.

### Deployment gotcha (carried from Phase 7.3)

`/reload` reliably stalls on EliteMobs/magmacore init (watchdog dump). JAR changes require a full
JVM stop→start via AMP. Verify by JVM start time (`ps -o lstart`) after the SCP, plus a fresh boot
banner and `Done (Xs)!` in a new `latest.log`.
