# [EliteMobs] NPC configs keep a broken `profession:` value from older versions — NPCs fall back to nitwit

> **Status (2026-10-27):** Entwurf, nicht eingereicht. Niedrige Priorität: sichtbar nur als falsche
> Villager-Kleidung bei NPCs ohne eigenes Modell. Gefunden beim Prüfen der Gilden-NPCs. Hat mit Bedrock nichts
> zu tun — liegt hier nur, weil alle Upstream-Entwürfe hier gesammelt werden.

**Repo:** MagmaGuy/EliteMobs
**Version:** EliteMobs 10.9.11 (files written by an earlier 10.x build), Paper 26.2

## Summary

46 of our 65 files in `plugins/EliteMobs/npcs/` contain the Java `toString()` of the profession object
instead of its key — written by an older EliteMobs build, where `NPCsConfigFields.professionConfigName()`
still returned `profession.toString()`:

```yaml
profession: CraftProfession{holder=Reference{ResourceKey[minecraft:villager_profession
  / minecraft:leatherworker]=VillagerProfession[name=translation{key='entity.minecraft.villager.leatherworker',
  args=[]}, heldJobSite=net.minecraft.world.entity.npc.villager.VillagerProfession$$Lambda/0x…@…, …
```

The serializer was fixed upstream (commit `0023beb1`, "serialize NPC professions using their registry
keys": `((Keyed) profession).getKey().getKey()`), but **existing files are never repaired**:

- On load, the string matches no `Villager.Profession` key, so `profession` stays `null` and
  `getProfession()` returns `NITWIT`. No warning is logged (the loop simply finds nothing).
- `processString("profession", …, false)` doesn't write back, so the broken value stays forever —
  `/em forcereinstallcontent all` didn't fix it either.

Affected here are 23 NPCs **without** a custom model that are plain villagers (e.g. `blacksmith.yml`,
`barkeep.yml`, `fletcher.yml`, `quest_giver.yml`, `combat_instructor.yml`, `unbinder.yml`), which therefore
should all show up in nitwit clothing instead of their profession (derived from the code, not yet checked
in game). The other 23 use an FMM model, so it's invisible there.
The files are identical on two servers, so this isn't a one-off.

## Suggested fix

While loading, recognise the old format (`ResourceKey[minecraft:villager_profession / minecraft:<key>]`),
take `<key>` from it and write the clean key back — or at least log a warning and reset the value to the
default from the premade config.

## Environment

- EliteMobs 10.9.11, Paper 26.2, Java 25
