# Phase 7.3 — EM Bedrock Menu Dialog-Reroute (Design Spec)

**Date:** 2026-06-04
**Branch:** `feat/em-cumulus-forms`
**Status:** Approved design, ready for implementation plan
**Supersedes framing of:** "EM-GUIs als Cumulus Bedrock-Forms" (original Phase 7.3 idea — building our own Cumulus forms; replaced by the much smaller reroute approach after the spike below).

## Problem

EliteMobs GUIs render on Bedrock as plain Bukkit chest containers (Geyser's automatic
container translation) with none of EM's intended menu design. The flagship case is the
`/em` (`/elitemobs`) **player status menu** (`PlayerStatusScreen`), which on a Bedrock
client appears as a bare chest grid instead of the clean vertical button list Java sees.

## Key discovery (spike-validated 2026-06-04)

`PlayerStatusScreen(Player)` already has three render paths:

```
if  Bedrock(player)  ||  !useBookMenus  ||  onlyUseBedrockMenus
      → chest menu (CoverPage)          ← what Bedrock gets today
elif serverOlderThan(1.21.6)
      → book menu
else  (Java ≥ 1.21.6)
      → PlayerStatusScreenDialog.showPlayerStatusDialog(player)   ← native MC Dialog API
```

EM forces Bedrock players to the chest path (`GeyserDetector.bedrockPlayer(player)` → first
branch). This is a historical decision from before Bedrock could render dialogs.

**Geyser now translates the Minecraft Java Dialog API into native Bedrock forms**
([Geyser PR #5603](https://github.com/GeyserMC/Geyser/pull/5603), MC 1.21.6;
[Issue #5690](https://github.com/GeyserMC/Geyser/issues/5690) tracks known roughness).

### Spike result (empirical, throwaway listener)

A reflection-based listener cancelled the Bedrock status chest and invoked EM's own
`PlayerStatusScreenDialog.showPlayerStatusDialog(player)` instead. Outcome:

- Server log `19:03:24`: chest cancelled + dialog invoked, **zero errors**.
- **Bedrock client rendered a native form with buttons.**
- Clicking sub-buttons (Statistiken, Teleports, Ausrüstung, …): **all render as native
  forms, cleanly** — EM builds these as nested dialogs (`DialogListDialog`), so sub-navigation
  stays inside the dialog tree (no new `InventoryOpenEvent`, our listener is not involved),
  and Geyser carries the nested dialogs natively.

**Conclusion:** Phase 7.3 collapses from "build our own Cumulus forms" to "reroute the Bedrock
status chest to EM's native dialog." The entire `/em` tree then renders natively via Geyser.

## Goals

- Bedrock players who open the `/em` status menu get EM's native dialog (→ Bedrock form)
  instead of the chest container.
- Java players are completely unaffected.
- Reuse EM's existing dialog implementation — the bridge adds **no** form-building code.
- EM coupling is reflection-based and isolated, tolerant of EM API drift.

## Non-Goals (this phase)

- Other EM menus (shops, quest browser, reforge/enchant, gambling, etc.). They are rerouted
  only if/when they gain a dialog equivalent, via a later registry entry — not in this phase.
- Building any Cumulus form ourselves.
- Changing EM's Java-side behavior.

## Architecture

### Data flow

```
Bedrock player runs /em
  → EM: PlayerStatusScreen(player) → CoverPage.coverPage(player) → player.openInventory(chest)
  → Bukkit InventoryOpenEvent fires
  → BedrockMenuRerouteListener (priority HIGH, ignoreCancelled=true):
        is Floodgate(player)?                          no  → return (Java untouched)
        is reflection ready?                           no  → return (chest stays, safe)
        does normalized title match EM status index?   no  → return (other menu, untouched)
        yes →
          event.setCancelled(true)                     // suppress the Bedrock chest
          next tick: EliteMobsHook.openNativeStatusDialog(player)
  → EM sends native MC dialog packets
  → Geyser translates dialog → native Bedrock form
  → sub-page buttons navigate the nested dialog tree natively (no further bridge involvement)
```

### Components

1. **`bridge/BedrockMenuRerouteListener`** (new; replaces `spike/BedrockStatusDialogSpike`)
   - Implements `Listener`. Single `@EventHandler(priority = HIGH, ignoreCancelled = true)`
     on `InventoryOpenEvent`.
   - Holds the menu **reroute registry** (see below) and performs the guard checks +
     cancel + next-tick dialog invoke.
   - Optional `debug-titles` log (behind the existing `debug` config) to surface the title
     of any inventory a Bedrock player opens, for diagnosing match failures in the field.

2. **Reroute registry** (inside the listener, small)
   - A list of entries, each: `{ titleSupplier: Supplier<String>, dialogInvoker: Consumer<Player> }`.
   - Phase 7.3 ships **one** entry: EM status index
     (`titleSupplier = EliteMobsHook::statusIndexMenuTitle`,
     `dialogInvoker = EliteMobsHook::openNativeStatusDialog`).
   - Match = `normalize(openedTitle).equals(normalize(titleSupplier.get()))`.
   - `normalize()` strips legacy color codes (`&x` / `§x`), trims, lowercases — so config
     color formatting (e.g. `§2EliteMobs Index`) doesn't break the match.
   - Designed so additional EM menus with dialog equivalents are a one-line registry addition.

3. **`elite/EliteMobsHook` additions** (the only class touching `com.magmaguy.*`)
   - `static String statusIndexMenuTitle()` — reflection on
     `PlayerStatusMenuConfig.getIndexChestMenuName()`; returns null on failure.
   - `static boolean openNativeStatusDialog(Player)` — reflection on
     `PlayerStatusScreenDialog.showPlayerStatusDialog(Player)`; returns false on failure.
   - Reflection `Method`/`Class` handles cached once; failures set the existing `apiBroken`
     pattern / return null/false so callers null-check (consistent with current hook style).

### Reflection targets

- `com.magmaguy.elitemobs.config.menus.premade.PlayerStatusMenuConfig#getIndexChestMenuName()` → String
- `com.magmaguy.elitemobs.playerdata.statusscreen.PlayerStatusScreenDialog#showPlayerStatusDialog(org.bukkit.entity.Player)` → void

(Reflection rather than direct import because these are EM **internal** classes, not the
stable API surface; reflection keeps compile decoupled and degrades gracefully on EM drift.)

## Safety / edge cases

- **Version guard:** the listener is only **registered** on MC ≥ 1.21.6 (the Dialog API);
  checked once at boot, not per event. Below that, the listener is absent → EM's chest opens
  as before, logged at enable. (Fabi runs 1.21.10.)
- **Fail-safe ordering:** cancel the event **only** when Floodgate + version + reflection are
  all confirmed ready. The dialog invoke is scheduled for the next tick; if it throws, log a
  WARN. v1 accepts "no menu shown" as a rare failure path. (Optional hardening: on invoke
  failure, reopen EM's chest as fallback — deferred unless it proves necessary.)
- **Bedrock only:** Floodgate `isFloodgatePlayer` gate; Java players never intercepted.
- **Coexistence with Phase 7.2b:** independent. 7.2b's `item_model` injection still applies to
  other EM chest GUIs we do not reroute (shops etc.). No interaction with the status menu once
  it is a dialog.
- **EM absent / API broken:** listener is only registered when EliteMobs is present; reflection
  failures make the reroute inert (chest stays), never crashing.
- **Registry miss:** any inventory whose title doesn't match an entry is left untouched.

## Config

```yaml
phase73:
  # Reroute the Bedrock /em status menu to EliteMobs' native MC dialog
  # (Geyser renders it as a native Bedrock form). Requires MC >= 1.21.6.
  bedrock-dialog-reroute: true
```

- The throwaway `spike:` keys (`spike.bedrock-status-dialog`, `spike.debug-titles`) are removed.
- Registration in `FMMBedrockBridge#onEnable` gated on:
  `floodgateAvailable && elitemobsAvailable && serverMc ≥ 1.21.6 && config phase73.bedrock-dialog-reroute`.
  Each failed gate is logged at enable so the inert state is diagnosable.

## Cleanup

- Delete `spike/BedrockStatusDialogSpike.java` and the `spike` package.
- Remove the `spike:` block from `config.yml`; add the `phase73:` block.
- Remove the spike registration in `onEnable`; add the production listener registration.
- On the test server, remove the `spike:` block from the live config (or it's simply ignored).

## Testing

- **Unit:**
  - `normalize()` color-code stripping / case / trim (table of inputs incl. `§2EliteMobs Index`,
    `&2EliteMobs Index`, `EliteMobs Index`).
  - Registry lookup: given a title and a one-entry registry, returns the right invoker / no match.
  - (EliteMobsHook reflection is integration-level; covered by manual test, not unit-mocked.)
- **Manual (Bedrock client):** `/em` → native form; click each sub-page → native form; confirm
  Java client unchanged. (Index + sub-pages already validated in the spike.)

## Parallel work (Approach ③, separate from this phase)

File an upstream feature request to MagmaGuy (EliteMobs): now that Geyser renders EM's dialogs
natively on Bedrock, route Bedrock players (MC ≥ 1.21.6) to the dialog path instead of the chest
(or add a config toggle). If adopted, this bridge reroute becomes removable. Draft goes in
`docs/upstream-bugs/`.

## Rollout

1. Implement production listener + EliteMobsHook methods + config; remove spike.
2. Build (unit tests green), SCP `FMMBedrockBridge.jar` to TestServer01.
3. Full **stop→start** of TestServer01 (not `/reload` — it stalls on EM/magmacore init).
4. Verify boot log: `Done (Xs)!` + listener registered; Bedrock `/em` → native form + sub-pages.

### Deployment gotcha (learned during the spike)

`/reload` reliably stalls on EliteMobs/magmacore init (watchdog dump). JAR changes require a full
JVM stop→start via AMP. Verify by JVM start time (`ps -o lstart`) being after the SCP, plus a
fresh boot banner and `Done (Xs)!` in a new `latest.log`.
