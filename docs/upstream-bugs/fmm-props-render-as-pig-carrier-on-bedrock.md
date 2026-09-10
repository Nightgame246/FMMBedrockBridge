# [FMM] Props/StaticEntities render as the raw PIG carrier on Bedrock — custom entity association never reaches the proxy

> ⛔ **NICHT MEHR EINREICHEN (Stand 10.09.2026).** Derselbe Bug ist bereits von dritter Seite
> upstream gemeldet worden. Dieses Dokument bleibt als **Analyse** liegen: der Trace unten ist
> weiter der belastbare Beleg dafür, warum Props auf Bedrock als Schwein rendern und warum die
> Ursache im Fake-Entity-Pfad (`carrierEntityType(EntityType.PIG)`) liegt, nicht bei RPM oder
> im Pack.

**Repo:** MagmaGuy/FreeMinecraftModels (touches Magmacore NMS + ResourcePackManager Geyser bridge)
**Version:** FreeMinecraftModels 2.10.2, ResourcePackManager 2.3.0, Geyser 2.11.1-b1210
**Observed:** 2026-08-08, production network (Velocity proxy + Paper backend)

## Summary

On Bedrock, **PropEntity/StaticEntity models render as a plain pig**, while **DynamicEntity
models (mobs, EliteMobs bosses) render correctly**. Java clients see all of them correctly.

The pig is FMM's own Bedrock carrier entity (`BedrockModeledEntity` uses
`carrierEntityType(EntityType.PIG)` for the fake-entity path), so the symptom means the
custom-entity swap never happened for that spawn — Geyser rendered the bare carrier.

We traced this as far as possible from the outside. **The Java side does everything right and
the Bedrock pack is complete; the per-entity association simply never arrives on the proxy for
the fake-entity path.**

## Steps to reproduce

1. Paper backend with FMM 2.10.2 + RPM 2.3.0, `sendCustomModelsToBedrockClientsV2: true`.
2. Velocity proxy with Geyser 2.11.1 + RPM 2.3.0 + the RSPM Geyser bridge extension.
3. Place any prop (e.g. from the BetterStructures prop pack) and spawn any FMM-modelled mob
   nearby.
4. Join as a Bedrock client and look at both.

**Result:** the mob renders with its custom model, the prop renders as a pig.

## Evidence

### 1. Both paths take the correct Bedrock branch

With `/fmm debug bedrock on`, every prop display logs only:

```
[FMM-BedrockDebug] SkeletonWatchers.displayTo entry — player=.Nightgame2272 v2=true
    allowResync=true entityClass=PropEntity boneCount=8 underlyingInvisible=false
```

Critically, **none** of the later diagnostics appear — no `wasAlreadyViewing=`, no
`V2=false fallback`, no `FALLBACK to UUID-broadcast`. All three sit *after* the Bedrock
custom-entity branch in `SkeletonWatchers.displayTo` (the branch that ends in `return`).
Their absence proves the branch was taken, i.e. `bedrockModeledEntity.displayTo(player)` ran
and `isAvailable()` was `true`. Same for DynamicEntity.

So FMM's Java side is behaving identically and correctly for both entity classes.

### 2. The Bedrock pack is complete

Diffed all 315 local `.bbmodel` files on the backend against the 316 client-entity
definitions in the merged `Bedrock.zip`: **no prop model is missing**. The only entries
without an entity definition are 10 item models (bows/crossbow/fishing rod), which correctly
do not need one. Prop definitions are well-formed, e.g.:

```json
{ "format_version": "1.10.0",
  "minecraft:client_entity": { "description": {
      "identifier": "freeminecraftmodels:armorcrushertowergold",
      "geometry": { "default": "geometry.freeminecraftmodels.armorcrushertowergold" }, … } } }
```

Proxy confirms registration:

```
[resourcepackmanagergeyserbridge] Preloaded 316 custom Bedrock entity identifiers and 281 property definition(s)
[resourcepackmanagergeyserbridge] Registered 316 RSPM custom Bedrock entity definitions with Geyser.
[resourcepackmanagergeyserbridge] ResourcePackManager Geyser bridge ready with 316 custom entity definitions.
```

### 3. The association is *absent*, not *late*

`RspmGeyserBridgeCore` keeps `ConcurrentMap<GeyserConnection, ConcurrentMap<Integer, String>> CUSTOM_ENTITIES`
and carries dedicated one-shot warnings `warnedUnregisteredSpawnDefinition` and
`loggedLateEntityReplacement`.

**Neither ever fired.** Over a full session with Bedrock players walking past dozens of props,
the extension logged nothing beyond the four boot lines above. A late-arriving association
would have tripped `loggedLateEntityReplacement`; an unknown identifier would have tripped
`warnedUnregisteredSpawnDefinition`. Silence on both means `onServerSpawnEntity` found **no
entry at all** for the prop's entity id, so it correctly did nothing — and the carrier pig
rendered.

## Where we think it is

The two implementations diverge exactly where props differ from mobs:

| Path | Used by | Call site |
|---|---|---|
| `BukkitCustomEntityImpl.prepareSpawnFor` | DynamicEntity (works) | bridge call, then the normal entity tracker spawns it a tick later |
| `FakeCustomEntityImpl.displayTo` | PropEntity / StaticEntity (broken) | bridge call and spawn packet back-to-back inside one `runTaskLater(…, 1L)` task |

Three candidate mechanisms, in our order of suspicion:

1. **`runBridgeSafely(...)` swallows the failure silently.** `prepareBedrockSpawn` wraps
   `registerDefinition` + `prepareEntitySpawn` in it. If the RPM-side implementation throws
   for the fake-entity path, the association is lost with no log anywhere — which matches the
   total absence of diagnostics on both ends.
2. **The `pluginProvider` early-return branch skips the bridge entirely.** In
   `FakeCustomEntityImpl.displayTo`, when `NMSManager.pluginProvider` is null or disabled the
   code calls `packetEntity.displayTo(uuid)` and returns **without** calling
   `prepareBedrockSpawn` at all.
3. **Ordering.** Association and spawn packet leave in the same tick; the Bukkit path has a
   natural tick of slack. This is our weakest candidate, since a lost race should have
   tripped `loggedLateEntityReplacement` at least once.

Note there is no `BedrockChecker.isBedrock(player)` guard on the fake path the way there is in
`BukkitCustomEntityImpl.prepareSpawnFor` — probably harmless, but worth a glance while you're
in there.

## Caveat on line references

Code references are read from the **public** sources: FMM 2.10.1 and Magmacore HEAD
(2026-06-28). The deployed builds are FMM 2.10.2 / RPM 2.3.0, whose sources are not on GitHub,
so exact lines may have shifted. The behavioural evidence above is from the deployed builds.

## What would confirm it in one step

A log line in `FakeCustomEntityImpl.prepareBedrockSpawn` — or simply not swallowing the
exception in `runBridgeSafely` — would immediately distinguish candidates 1 and 2 from 3.

## Environment

- Velocity 4.1.0-SNAPSHOT (git-2676520c-b14)
- Geyser-Velocity 2.11.1-b1210 (git-master-6e70ba2), Floodgate 2.2.5-SNAPSHOT (b138)
- ResourcePackManager 2.3.0 (backend **and** proxy), RSPM Geyser bridge built 2026-07-14
- FreeMinecraftModels 2.10.2, EliteMobs 10.7.3, BetterStructures 2.6.3
- Paper 1.21.x backend, Java 21, Debian Bookworm
- `sendCustomModelsToBedrockClientsV2: true`
