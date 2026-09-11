# [EliteMobs] Advanced Combat System: Bedrock players cannot trigger any active ability

**Status:** DRAFT — not yet posted upstream
**Repo:** MagmaGuy/EliteMobs
**Version:** EliteMobs 10.9.0 (Modrinth, released 2026-09-11, `md5 ffd90956ece71c5e88d3a183de80ec2d`)
**Environment:** Paper 26.2, Velocity proxy, Geyser-Velocity 2.11.1, Floodgate 2.2.5
**Related classes:** `advancedcombat.input.ClassAbilityInputRouter`,
`advancedcombat.input.ClassAbilityGestureState`, `advancedcombat.AdvancedCombatModule`,
`advancedcombat.presentation.ClassHudPresentation`, `presentation.actionbar.CombatHud`

> All findings below were verified with `javap` against the released 10.9.0 artifact, not against
> the GitHub master branch (which is still at the 2026-08-13 state and does not contain this
> feature).

---

## Issue 1 — Bedrock players are locked out of all three ability slots

### Summary

The Advanced Combat System binds its three ability slots to a keyboard chord:

```
F, F        → Mobility
F + LMB     → Signature
F + RMB     → Utility
```

`F` is the offhand-swap key. **Bedrock Edition has no offhand-swap control** — not on
controller, not on touch, not on console. Geyser therefore never produces a
`PlayerSwapHandItemsEvent` for a Bedrock client.

EliteMobs already detects this and disables the F layer:

```java
// AdvancedCombatModule
public boolean fLayerSupported(Player p) {
    return !GeyserDetector.bedrockPlayer(p);   // Bedrock → false
}
```

**However, no alternative input path exists.** The result is that Bedrock players can select a
class, gain levels and receive passives, but cannot trigger a single active ability.

### Root cause

`ClassAbilityInputRouter` has five input handlers. Only one of them can *open* a chord; the other
four require one to be open already:

| Handler | Event | Behaviour when no chord is open |
|---|---|---|
| `onSwapHands` | `PlayerSwapHandItemsEvent` | **opens the chord** — the only entry point |
| `onAbilityHotbarSelection` | `PlayerItemHeldEvent` | looks up `pendingGestures.get(uuid)`; if null, **`return`s at offset 28** |
| `onChordInteract` | `PlayerInteractEvent` | branches to a `recentDispatches` check that only cancels a follow-up right-click in the same tick, then **`return`s at offset 94**. No chord is opened. |
| `onChordInteractEntity` | `PlayerInteractEntityEvent` | same shape as `onChordInteract` |
| `onAttack` | `EntityDamageByEntityEvent` | requires an open `PendingGesture` |

And `onSwapHands` computes:

```java
boolean fSupported = input.hasActiveClass(player) && input.fLayerSupported(player);
FAction action = controlMode.pressF(uuid, tick, player.isSneaking(),
                                    input.controlsAlwaysAvailable(player),
                                    input.outsideControlsAllowed(),
                                    fSupported);
```

For a Bedrock player `fSupported` is always `false` — and since the event never fires for them
anyway, the handler is unreachable regardless.

**No F → no open chord → no ability. There is no second entry point.**

### Secondary lockout

Outside dungeons, controls must first be armed with `F,F` (`ClassControlMode.outsideEnabled`,
a per-player set). This is equally unreachable from Bedrock. Inside dungeons and matches controls
are auto-armed via `controlsAlwaysAvailable` → `DungeonCombatRuntime.isEligiblePlayer`, but that
does not help while the trigger itself is missing.

### Reproduction

1. Paper 26.2 + EliteMobs 10.9.0, Geyser + Floodgate, `AdvancedCombatSystemConfig.isEnabled: true`
2. Join as a Bedrock client (controller or touch) and unlock a class
3. Enter a dungeon, so controls are auto-armed
4. Attempt any ability — `F,F`, `F+LMB`, `F+RMB` have no Bedrock equivalent
5. Observe: passives apply, no active ability can ever be triggered

A Java client on the same server behaves correctly.

### Suggested fix

The infrastructure for this already exists in the codebase, which is why this looks like an
oversight rather than a decision:

1. **`InputProfile` already models alternative schemes** — the enum currently holds
   `JAVA_HOTBAR_LAYER` and `DEFAULT`, and `AdvancedCombatModule.activeInputProfile(Player)`
   resolves it per player. A third value (e.g. `BEDROCK_SNEAK_LAYER`) would fit naturally.
2. **Sneak is already threaded through the input layer** — `ClassControlMode.pressF` receives
   `player.isSneaking()` as a parameter. Using sneak as the chord opener when
   `!fLayerSupported(player)` would reuse the existing state machine almost unchanged:
   `PlayerToggleSneakEvent` (sneak *start*) → `ClassAbilityGestureState.pressF(...)`.
3. **`selectHotbar` already exists** on `ClassAbilityGestureState` — a chordless hotbar layer for
   Bedrock (three reserved slots) would be an alternative that needs no timing at all.

Any one of the three would close the gap. Option 2 preserves the muscle memory of the Java scheme
most closely.

**Note on sneak timing:** `CHORD_WINDOW_TICKS = 12` (0.6 s) is tuned for a key *press*. Sneak on
Bedrock is a *state*, and 0.6 s is tight on a controller — a sneak-based path would likely want a
longer or state-based window.

---

## Issue 2 — The graphical combat HUD is sent to Bedrock clients that cannot render it

### Summary

The new combat HUD renders through Java resource-pack font providers
(`em_rsp_defaults/assets/elitemobs/font/combat_hud_*.json`, plus ~1.9 MB of font metrics shipped
in the jar). **Bedrock cannot resolve Java font providers.**

The release notes mention a text fallback, and one exists — but it is a **global** config switch
(`AdvancedCombatSystemConfig.isEnableCombatHud()`), not a per-player Bedrock branch.

### Evidence

EliteMobs 10.9.0 checks `GeyserDetector` in nine classes:

```
wormhole.BedrockWormholeMarker          quests.menus.QuestMenu
skills.CombatLevelDisplay               quests.dialogue.QuestDialogueBossBarManager
playerdata.statusscreen.PlayerStatusScreen   pathfinding.patrol.PatrolEditor$Session
parties.PartyInventoryMenu              menus.MenuPresentation
advancedcombat.AdvancedCombatModule
```

`presentation.actionbar.CombatHud` and `advancedcombat.presentation.ClassHudPresentation` are
**not** among them — neither class contains a single Geyser/Floodgate/Bedrock reference.

### Consequence

On a mixed server the operator must choose between a graphical HUD for Java players and a
readable HUD for Bedrock players; there is no setting that serves both.

### Suggested fix

Resolve the graphical-vs-text decision per player rather than globally — the existing
`GeyserDetector.bedrockPlayer(player)` call is all that is needed, mirroring what
`MenuPresentation` and `PlayerStatusScreen` already do.

---

## Context

crazypandas.de runs a mixed Java/Bedrock network; a substantial part of the player base is on
console. We are bridging Issue 1 locally for the time being (sneak-based input in our own plugin,
calling `AdvancedCombatModule.useAbility` directly) and will drop that path as soon as upstream
covers it.

Happy to test any patch against a real Bedrock console client.
