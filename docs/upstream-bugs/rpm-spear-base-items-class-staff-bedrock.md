# [RPM / EliteMobs] Advanced Staff of Wonders renders as a vanilla wooden spear on Bedrock — no Geyser mapping under `*_spear`

> ✅ **Upstream gelöst am 02.10.2026, bevor wir posten konnten** — FMM 2.12.6 schreibt `assets/<ns>/rspm_item_bases/<pfad>.json` mit dem echten Grund-Item, RPM 2.4.6 liest das (`BaseItemResolver.declaredBaseItems`, Commit `3a2de40`). Changelog: *„wands and staves show their models on Bedrock"*. **Nicht posten.** Nach dem Upgrade auf Bedrock gegenprüfen.

**Status:** OBSOLETE — fixed upstream (FMM 2.12.6 + RPM 2.4.6), never posted
**Channel:** MagmaGuy's **Discord, Suggestions forum** (not GitHub). Copy-ready text at the end.
**Repos:** MagmaGuy/ResourcePackManager (primary), MagmaGuy/EliteMobs, MagmaGuy/FreeMinecraftModels
**Versions:** EliteMobs 10.9.7, FreeMinecraftModels 2.12.5, ResourcePackManager 2.4.5, Geyser-Velocity 2.11.x, Paper 26.2
**Related:** `em-menu-icons-paper-base-bedrock.md` (same mechanism, menu icons)
**Screenshots** (local, not in repo): `references/screenshots/alt/2026-10-02_waffen-ruestung-bedrock/`

## Summary

The Advanced Combat class weapon **"Advanced Staff of Wonders"** shows on Bedrock (via Geyser) as a
plain **vanilla wooden spear** — in the inventory and in hand. Java shows the FMM staff model.
Everything else checked renders correctly on Bedrock: tiered gear icons (bronze … ultimatium),
worn tiered armor (own body and seen by Java players), weapons in hand.

## Root cause

1. EliteMobs builds the staff on **`Material.WOODEN_SPEAR`**
   (`AdvancedMagicWeaponItems.staffMaterial()`, "Staves use a spear item (1.21.11+)").
   FMM sets `item_model = freeminecraftmodels:display/fmm_default_arcane_staff`
   (`MagicWeaponIdentity.apply`).
2. Geyser's v2 custom-item mappings are keyed **per Java base item**.
3. RPM guesses the base item from the filename (`BaseItemResolver` → `FilenameHeuristic`):
   `staff` → `minecraft:stick`, plus `leather_horse_armor` for the FMM namespace. So the model is
   registered under `stick` and `leather_horse_armor` only.
4. **The live `rspm_geyser_mappings.json` has zero entries under `minecraft:wooden_spear`** (or any
   `*_spear`). The heuristic predates the 1.21.11 spear items — its `pike|spear|lance` rule still maps
   to `minecraft:trident`.

Same for `freeminecraftmodels:display/em_goblin_events_free_chicken_staff_of_wonders`.
(The default arcane **wand** is unaffected: FMM ships no `.bbmodel` for it, so it carries no
`item_model` and is a blaze rod on both editions.)

## Suggested fix

**RPM** (`FilenameHeuristic`):
- `staff|wand|scepter` → also register under the `*_spear` items (`minecraft:wooden_spear` and its
  material variants), or at least `wooden_spear`.
- `pike|spear|lance` → `*_spear` items in addition to (or instead of) `trident`.

**Or EliteMobs/FMM:** declare the base item of magic weapons so RPM does not have to guess
(the explicit-base path in `ItemsDefinition` exists, but only for legacy custom-model-data).

## Notes

- Spears have client-side behaviour in 26.x (charge/lunge), so rebasing the item to `stick` for
  Bedrock on the server side is not a safe workaround — which is why we don't do it in the bridge.
- Verified read-only against the live mappings on our proxy (02.10.2026) and in-game (Bedrock PC +
  Java side by side).

---

## Discord-Fassung

> **Bedrock: "Advanced Staff of Wonders" shows as vanilla wooden spear (EM 10.9.7, FMM 2.12.5, RPM 2.4.5, Geyser 2.11)**
>
> The new class staff is built on `WOODEN_SPEAR` with `item_model freeminecraftmodels:display/fmm_default_arcane_staff`.
> Geyser maps custom items per base item, and RPM's filename heuristic sends "staff" to `stick` only (+ leather_horse_armor
> for FMM) — there isn't a single mapping under `minecraft:wooden_spear` in `rspm_geyser_mappings.json`. The `spear` rule
> still points to trident, from before the 1.21.11 spear items.
>
> Fix idea: let `FilenameHeuristic` register staff/spear models under the `*_spear` items too (or let EM/FMM declare the
> base item). Tiered gear and worn armor all render fine on Bedrock — it's only the new spear-based staff. Screenshots on request.
