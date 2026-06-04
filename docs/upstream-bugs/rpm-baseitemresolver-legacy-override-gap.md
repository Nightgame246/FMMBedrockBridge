# [RPM] `BaseItemResolver` misses legacy `models/item/<base>.json` overrides → EM UI items don't render on Bedrock

**Repo:** MagmaGuy/ResourcePackManager
**Version:** 2.0.1 (Network-Mode)
**Related:** EliteMobs 10.4.0, Geyser-Velocity 2.10.0

## Summary

`BaseItemResolver` only scans the modern 1.21.4+ `assets/<ns>/items/` namespace when
guessing which vanilla base item a custom model attaches to. EliteMobs ships its UI
icons (BagOfCoins, AnvilHammer, GoldenQuestionMark, …) the **legacy** way:

- ItemStack at runtime: `minecraft:emerald` + `custom_model_data=31173`
- Pack override: `assets/minecraft/models/item/emerald.json` with a `overrides[]` block
  keyed on `custom_model_data`

Because RPM never reads those legacy `models/item/<base>.json` overrides, the resulting
Geyser mappings contain **10 guessed base items for `elitemobs:ui/bagofcoins`
(compass, paper, stick, sword variants, …) but never `minecraft:emerald`**. Geyser then
has no mapping to route the real `minecraft:emerald + CMD=31173` stack to a Bedrock custom
item, so Bedrock clients see a plain emerald instead of the UI icon.

## Steps to reproduce

1. Server with EliteMobs 10.4.0 + RPM 2.0.1 in Network-Mode behind Velocity/Geyser.
2. Let RPM generate the Bedrock pack + `rspm_geyser_mappings.json`.
3. Open any EM GUI that uses a UI icon (e.g. `/ag` Adventurer's Guild) as a Bedrock client.
4. Inspect the generated mappings for `elitemobs:ui/bagofcoins`.

## Expected

The generated mappings include `minecraft:emerald` (the actual runtime base material from
the legacy `models/item/emerald.json` override), so Geyser can route the stack.

## Actual

`minecraft:emerald` is absent; only the ~10 guessed base items are present. Bedrock clients
render the vanilla base item.

## Suggested fix

When resolving base items for a custom model, also scan
`assets/<ns>/models/item/<base>.json` for an `overrides[]` array and treat each entry's
`predicate.custom_model_data` → that file's `<base>` as a valid base-material mapping. This
covers all pre-1.21.4 packs (EliteMobs included) that still use the legacy override system.

## Workaround in use

We bridge these items ourselves with a dedicated `bridge_em:<key>` namespace + a separate
Geyser custom-item-v2 mappings file. If RPM picked up the legacy overrides, this bridge
layer (Phase 7.2b) could be retired entirely.

## Environment

- ResourcePackManager 2.0.1 (Backend + Velocity sub-jar, Network-Mode)
- EliteMobs 10.4.0
- FreeMinecraftModels 2.7.0
- Geyser-Velocity 2.10.0-b1141, Floodgate b132
- Paper 1.21.x backend, Velocity 3.5.0 proxy
