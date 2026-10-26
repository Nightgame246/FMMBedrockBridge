# [EliteMobs] Translation keys collide when a boss, item and quest share a file name

> **Status (2026-10-27):** Entwurf, nicht eingereicht. Gefunden beim Einspielen unserer deutschen Übersetzung
> für den Content vom 26.10. (EM 10.9.11). Betrifft jede Sprache, nicht nur Deutsch, und hat mit Bedrock nichts
> zu tun — liegt hier nur, weil alle Upstream-Entwürfe hier gesammelt werden. Unsere Übersetzung selbst:
> Umbrella `server-config/elitemobs/`.

**Repo:** MagmaGuy/EliteMobs
**Version:** EliteMobs 10.9.11, Primis content as downloaded 2026-10-26, `translations/german.csv`

## Summary

Translation keys are built from the config **file name** plus the field (`<filename>.name`). Several Primis
configs exist with the same file name in different folders, so two different objects write to — and read
from — one translation row:

| Key | Object 1 | Object 2 |
|---|---|---|
| `primis_ice_village_salmon.name` | boss `custombosses/primis/…` → `$normalLevel &bChilly Salmon` | item `customitems/primis/…` → `&2Fresh Salmon` |
| `primis_farmstead_lighthouse_keeper.name` | quest `customquests/primis/…` → `&2The Lighthouse Keeper` | boss `custombosses/primis/…` → `$bossLevel &6The Lighthouse Keeper` |
| `primis_ice_village_bell_banger.name` | quest `customquests/primis/…` → `&2Bell banger` | miniboss `custombosses/primis/…` → `$minibossLevel &6Undead` |

## What happens

On every start EliteMobs logs:

```
[EliteMobs] Translation for primis_ice_village_salmon.name uses $normalLevel, which the current English text no longer has, so the English text is shown instead. …
[EliteMobs] german.csv: 0 new keys, 6 English defaults corrected, 0 left alone because the English column was edited by hand (e.g. primis_ice_village_salmon.name, primis_farmstead_lighthouse_keeper.name, primis_ice_village_bell_banger.name, …)
[EliteMobs] 6 shipped translations in german.csv now describe English text that has been corrected (…). Nothing was overwritten. To pull the current translation, delete translations/german.csv and translations/german_data.csv, then run '/em language german'.
```

The English column flips between the two objects depending on load order, so "6 English defaults
corrected" comes back on every start, and the suggested fix (re-downloading the translation) cannot help.
Since both objects read the same row, one of them necessarily gets the other one's translated name (e.g. the
Undead miniboss can end up as "Glockenschläger" = "Bell banger"), or the English fallback when the
placeholders don't match (the Fresh Salmon item vs. the boss's `$normalLevel`). Not yet checked in game.

The same rows are in the current upstream `german.csv` (`magmaguy.com/api/elitemobs_translations/german.csv`),
so every language is affected.

## Suggested fix

Include the config type (or the relative path) in the key, e.g. `customitems.primis_ice_village_salmon.name`,
with a one-time migration of the existing rows — or rename the colliding Primis files.

## Environment

- EliteMobs 10.9.11, Paper 26.2, Java 25
- `language: german`, translation file freshly loaded via `/em language german` plus our additions
