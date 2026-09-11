# [EliteMobs] Advanced Combat System: Bedrock players cannot trigger any active ability

**Status:** ✅ **Gepostet am 11.09.2026 von Fabi** im Suggestions-Forum von MagmaGuys Discord.
**Thread:** https://discord.com/channels/320602228669022208/1547941122335187025
(Stand 11.09.2026: noch keine Reaktion im Thread. Nachtrag 4 ist von Fabi zum Nachposten
vorgesehen.)
**Kanal:** MagmaGuys **Discord, Suggestions-Forum** (nicht GitHub — so will MagmaGuy es).
Fertige Fassung zum Kopieren steht am Ende unter „Discord-Fassung".
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


---

# Discord-Fassung — zum Kopieren

> **Ziel:** MagmaGuys Discord, **Suggestions-Forum** (so will MagmaGuy es, nicht GitHub).
> **Ton:** Vorschlag, nicht Fehlermeldung.
> **Format:** Discord kann **keine Markdown-Tabellen** — deshalb Listen und Code-Blöcke.
> **Grenze:** 2000 Zeichen je Nachricht → ein Thread-Start plus zwei Antworten, in dieser
> Reihenfolge in **denselben** Thread.
>
> Die Nachrichten stehen bewusst **nicht** in Code-Blöcken: sie enthalten selbst welche, und ein
> innerer ``` beendet den äußeren. Kopiert wird jeweils zwischen den BEGIN/END-Markern.

## Thread-Titel

Advanced Combat: add a Bedrock-compatible input path (F has no Bedrock equivalent)

## Nachricht 1 — Thread-Start

<!-- BEGIN-1 -->
**Advanced Combat looks great — but Bedrock players currently can't use any of it.**

I run a mixed Java/Bedrock network with a lot of console players, so I went through 10.9.0 with javap to see how the new class abilities are triggered.

The three slots are bound to an F-chord (F,F / F+LMB / F+RMB). F is the offhand-swap key, and **Bedrock has no offhand-swap control** — not on controller, not on touch, not on console. Geyser never produces a `PlayerSwapHandItemsEvent` for those clients.

EliteMobs already knows about this:
```java
public boolean fLayerSupported(Player p) {
    return !GeyserDetector.bedrockPlayer(p);   // Bedrock -> false
}
```
The catch is that `ClassAbilityInputRouter` has five input handlers, and only `onSwapHands` can *open* a chord. The other four — hotbar select, interact, interact-entity, attack — all bail out when no chord is open. (`onChordInteract` only checks `recentDispatches` to swallow a follow-up right-click, then returns.)

So: no F → no chord → no ability, with no second entry point.

The net effect is that Bedrock players can pick a class, level it and get passives, but can't trigger a single active ability — roughly a third of the system is unreachable for them.

Suggestions in the next message, all three reuse things that are already in the code.
<!-- END-1 -->

## Nachricht 2 — die Vorschläge

<!-- BEGIN-2 -->
**Three ways to close it, all building on what's already there:**

**1. A third InputProfile.** The enum already models alternative schemes (`JAVA_HOTBAR_LAYER`, `DEFAULT`) and `AdvancedCombatModule.activeInputProfile(Player)` resolves it per player. A `BEDROCK_*` value would slot right in.

**2. Sneak as the chord opener when `!fLayerSupported(player)`.** This looks like the smallest change: `ClassControlMode.pressF` already receives `player.isSneaking()` as a parameter, so sneak is literally already wired into the input layer. Feeding sneak-start (`PlayerToggleSneakEvent`) into `ClassAbilityGestureState.pressF` would reuse the existing state machine almost unchanged, and it keeps the muscle memory of the Java scheme:
```
sneak, sneak     -> Mobility
sneak + attack   -> Signature
sneak + use      -> Utility
```
**3. A chordless hotbar layer.** `selectHotbar()` already exists on `ClassAbilityGestureState` — three reserved slots need no timing at all, which is the friendliest option on a controller.

One timing note if you go with sneak: `CHORD_WINDOW_TICKS = 12` (0.6s) is tuned for a key *press*. Sneak is a *state*, and 0.6s is tight on a controller — a sneak path would probably want a longer or state-based window.

I'm happy to test any of this against real console clients and report back.
<!-- END-2 -->

## Nachricht 3 — der HUD-Nebenbefund

<!-- BEGIN-3 -->
**Related, smaller thing in the same feature: the graphical combat HUD.**

It renders through Java resource-pack font providers (`assets/elitemobs/font/combat_hud_*.json`). Bedrock can't resolve those. The text fallback exists, but it's a global switch (`isEnableCombatHud`), not a per-player Bedrock branch.

10.9.0 checks `GeyserDetector` in nine classes — MenuPresentation, PlayerStatusScreen, QuestMenu, QuestDialogueBossBarManager, PartyInventoryMenu, CombatLevelDisplay, BedrockWormholeMarker, PatrolEditor$Session and AdvancedCombatModule — but `CombatHud` and `ClassHudPresentation` contain no Geyser/Floodgate reference at all.

On a mixed server that forces a choice: graphical HUD for Java players, or a readable HUD for Bedrock players. Deciding graphical-vs-text per player, with the same `GeyserDetector` call the menus already use, would serve both.
<!-- END-3 -->

## Vor dem Posten prüfen

- [ ] „a lot of console players" in Nachricht 1 gegen die echte Zahl ersetzen, falls verfügbar —
      konkrete Zahlen wirken bei Upstream deutlich stärker
- [ ] Alle drei Nachrichten in **denselben** Thread, in dieser Reihenfolge
- [ ] Nach dem Posten hier oben den Status von DRAFT auf „gepostet am <Datum>" setzen

---

## Nachtrag 4 — optional, erst nach dem Posten entstanden

> **Warum es ihn gibt:** Die drei geposteten Nachrichten beschreiben nur die **erste** Sperre (der
> F-Chord). Die **zweite** — EMs Opt-in per F-Doppeltipp ausserhalb von Dungeons — stand zwar im
> Analyse-Teil oben, aber nicht in der Discord-Fassung. Beim Bau unserer eigenen Ueberbrueckung
> am 11.09. hat sich gezeigt, dass genau diese zweite Sperre das Feature fuer Bedrock auf
> Dungeons und Matches zusammenschrumpfen laesst. Das ist ein belastbares Praxisargument, das
> MagmaGuy in den geposteten Nachrichten noch nicht hat.
>
> Nur posten, wenn der Thread noch offen ist und sich ein Nachtrag natuerlich einfuegt.
> Zeichenzahl siehe unten.

<!-- BEGIN-4 -->
**One more lock I ran into while bridging this locally — it may matter more than the first.**

Even with a working Bedrock input path, abilities only fire inside dungeons and matches. `useAbility` → `mechanicsActive` → `ClassAbilityInputRouter.controlsEnabled` → `ClassControlMode.enabled(uuid, controlsAlwaysAvailable, outsideControlsAllowed)`. Outside instanced content, `controlsAlwaysAvailable` is false, so the player must be in the `outsideEnabled` set — and the only way in is the F double-tap while sneaking. Same key Bedrock does not have.

So there are two independent locks, not one:
1. no F → no chord → no ability (the original report)
2. no F → never in `outsideEnabled` → abilities stay off in the open world even if 1 is solved

I hit this concretely: my sneak-based bridge consumed the player's attack and then `useAbility` returned a failure, because our arming gate was wider than EliteMobs' own. I have since narrowed our gate to `isInitialized() && mechanicsActive(player)` so we never swallow input EliteMobs would not act on — which is correct, but leaves Bedrock players with abilities in dungeons only.

If a `BEDROCK_*` InputProfile lands, it would need to cover the arming path too, not just the chord — otherwise the open world stays dark for those players.

(Also worth knowing: `AdvancedCombatModule.get()` throws `IllegalStateException` rather than returning null when the system is disabled, while `DungeonCombatRuntime.start()` runs unconditionally. Anything integrating with this should gate on `isInitialized()` first.)
<!-- END-4 -->
