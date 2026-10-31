# [FMM/RPM] Adventurer's Guild model NPCs: body faces backwards on Bedrock, head is correct

> **Status (2026-10-31):** ✅ von Fabi gepostet (31.10.), Fehler vorher in-game mit FMM 2.14.0 / RPM 2.4.9 bestätigt —
> Antwort von MagmaGuy offen. (MagmaGuys Discord, gleicher Kanal wie
> `em-skill-menu-bedrock-reopen-closes.md`). Erstmals gesehen 2026-10-25 (FMM 2.12.9 / RPM 2.4.7), unverändert mit
> FMM 2.13.0 / RPM 2.4.8; seit der Welle 30.10. laufen FMM 2.14.0 / RPM 2.4.9 / EM 10.9.12 — Fabi hat es dort
> am 31.10. unverändert gesehen. Nicht von der Bridge verursacht — die fasst die Ausrichtung von
> FMM-Modellen seit Mai 2026 nicht mehr an (Bridge-Variante A/B ohne Unterschied); Gegenprobe mit FMM
> `weightedTurning: false` ohne Wirkung. Screenshots liegen im (privaten) Umbrella unter
> `references/screenshots/alt/2026-10-25_gilden-npcs-verdreht/{bedrock,java}.png` — beim Posten anhängen.

**Repos:** MagmaGuy/FreeMinecraftModels, MagmaGuy/ResourcePackManager
**Versions:** FreeMinecraftModels 2.13.0, ResourcePackManager 2.4.8 (proxy + backend), EliteMobs 10.9.11,
Geyser-Velocity 2.11.1, Paper 26.2, Velocity proxy (RPM network mode, Geyser on the proxy)

## Summary

The new FMM model NPCs of the Adventurer's Guild hub (e.g. **Sewer Rat** — `npcs/sewers_teleporter.yml`,
`customModel: em_ag_sewerrat` — and the **Steam Technician**) render correctly on Java. On Bedrock the
**head points the right way, but the body is turned around** and faces away from where the NPC is
looking. All seated guild NPCs in the hub show it; bosses and other FMM models look fine.

## Steps to reproduce

1. EliteMobs 10.9.11 with the current Adventurer's Guild hub content, FMM 2.13.0, RPM 2.4.8 converting the
   pack for Bedrock (Geyser custom entities).
2. Join as a Bedrock client and go to the NPC table in the guild hub (Sewer Rat, Castle Steward,
   Pyrotechnician Pete, Steam Technician).
3. Compare with a Java client at the same spot.

## Expected

Bedrock matches Java: the NPC sits at the table, body and head facing the same direction.

## Actual

Bedrock: head correct, torso/arms/legs rotated (body facing the other way). Java: correct.

## What we checked

- The generated Bedrock geometry (`em_ag_sewerrat.geo.json`) has no rotation on `head`, `upper_body` or `body`;
  the only static rotations are on the legs (seated pose, ~-90° X). The idle animation (`a_69646c65`) only
  adds small X tilts (body 10°, arms) — **no Y rotation anywhere**, and no animation drives the head separately.
- The entity file animates two controllers: the idle animation and `lean`, which rotates/offsets the
  root bone `freeminecraftmodels_lean` from the entity properties `freeminecraftmodels:lean_x/_y/_z` and
  `body_offset` (each `/ 100`). The guild NPCs are seated, so this is the path that differs from regular
  standing models — a `lean_y` or yaw value that Java applies to the whole display-entity stack but that
  ends up as an extra 180° on the Bedrock root while the head follows the entity head yaw would produce
  exactly this picture. We could not read the live property values from the client side.
- NPC config: `spawnLocation: …,297.5,92,259.5,0,0` (yaw 0), `syncMovement: false`.
- FMM `weightedTurning: false` (instead of `true`) — no change.
- Our own Bedrock helper plugin does not touch model orientation (it only handles boss bars, nametags and
  menu icons); FMM bosses and other models render with correct orientation on the same server.

## Environment

- FreeMinecraftModels 2.13.0 (also 2.12.9)
- ResourcePackManager 2.4.8 (also 2.4.7), network mode, Geyser bridge on Velocity
- EliteMobs 10.9.11 (also 10.9.10)
- Geyser-Velocity 2.11.1, Floodgate on proxy and backend
- Paper 26.2, Java 25

---

# Discord-Fassung — zum Kopieren

<!-- BEGIN -->
**[FreeMinecraftModels 2.14.0 / RPM 2.4.9] Guild NPCs sit with their body turned around on Bedrock**

One more Bedrock-only one, this time in the new Adventurers' Guild hub: the seated model NPCs at the table (Sewer Rat, Castle Steward, Pyrotechnician Pete, Steam Technician) look right on Java. On Bedrock the **head faces the correct way, but the body is turned around** and faces away from where the NPC is looking. Bosses and other FMM models look fine. Screenshots Bedrock vs. Java attached.

Setup: FMM 2.14.0, RPM 2.4.9 (network mode, Geyser bridge on Velocity), EliteMobs 10.9.12, Geyser 2.12.1, Paper 26.3. First seen with FMM 2.12.9, unchanged since.

What I checked:
- The generated Bedrock geometry (e.g. `em_ag_sewerrat.geo.json`) has no Y rotation anywhere, only the seated leg rotation, and the idle animation only tilts on X.
- The entity file also runs the `lean` controller on the root bone `freeminecraftmodels_lean` (`lean_x/_y/_z`, `body_offset`). Only the seated guild NPCs show it, so my guess is that path: a yaw that ends up as an extra 180° on the Bedrock root while the head follows the head yaw.
- `weightedTurning: false` makes no difference. NPC config is plain: yaw 0, `syncMovement: false`.

Happy to test a build on Bedrock again.
<!-- END -->
