# [EliteMobs] Menu icons on non-paper base items render as vanilla items on Bedrock

**Status:** DRAFT — not yet posted upstream
**Channel:** MagmaGuy's **Discord, Suggestions forum** (not GitHub). Copy-ready text at the end.
**Repos:** MagmaGuy/EliteMobs (primary), MagmaGuy/ResourcePackManager (alternative fix)
**Versions:** EliteMobs 10.9.7/10.9.8, ResourcePackManager 2.4.5/2.4.6, Geyser-Velocity 2.11.x, Paper 26.2
**Supersedes:** `em-banner-ui-items-bedrock.md`, `rpm-baseitemresolver-legacy-override-gap.md`
(both written for the old custom-model-data icons)
**Our workaround:** FMMBedrockBridge Phase 7.7 (`BedrockMenuIcons`, `MenuIconRebaser`)

## Summary

On Bedrock (via Geyser), the buttons in EliteMobs menus show **vanilla emerald, redstone, barrier,
green banner and red banner** instead of the `elitemobs:ui/*` icons — e.g. in the repair menu the
cancel button is a barrier, input/output are banners, confirm is an emerald. **The golden question
mark renders correctly.**

## Root cause

1. `CustomModelAdder.addCustomModel` only sets the `item_model` component. The base material stays
   whatever the menu config says: `EMERALD`, `REDSTONE`, `BARRIER`, `GREEN_BANNER`, `RED_BANNER`
   (defaults in `RepairMenuConfig`, `SellMenuConfig`, `ScrapperMenuConfig`, `BuyOrSellMenuConfig`,
   `ItemEnchantmentMenuConfig`, `EliteScrollMenuConfig`, `UnbinderMenuConfig`). On Java that is
   invisible.
2. Geyser's v2 custom-item mappings are keyed **per Java base item**. A mapping under
   `minecraft:paper` does not apply to an emerald carrying the same `item_model`.
3. RPM cannot know which base item a plugin uses for an `item_model` and guesses
   (`BaseItemResolver`). For `elitemobs:ui/*` it lands on the generic fallback
   (`paper, stick, name_tag, compass, <6 swords>`) — emerald and barrier are not in that list.
4. Redstone and banners are explicitly excluded by RPM (`GeyserBaseItemCompatibility`): Geyser
   derives an invalid `block_placer` for them. **So no RPM heuristic can ever fix those icons** —
   only a different base item can.

**Evidence that paper works:** `BuyOrSellMenu` sets `Material.PAPER` for the info item before
`addCustomModel` — that is exactly the icon that renders on Bedrock. The live
`rspm_geyser_mappings.json` lists all `elitemobs:ui/*` models (except the two crowns, which are
under helmets) and all `nightbreak:ui/*` models under `minecraft:paper`.

## Suggested fix

**EliteMobs:** when `useResourcePackModels()` is on, use `PAPER` as base for menu icons — either in
the menu config defaults or by forcing it in `CustomModelAdder.addCustomModel` for UI models. Java
looks identical (the `item_model` decides), Bedrock picks up RPM's existing paper mappings.

**Alternative (since RPM 2.4.6):** EliteMobs could write `assets/elitemobs/rspm_item_bases/ui/<icon>.json`
(`{"base_items": ["minecraft:emerald"]}`) like FreeMinecraftModels 2.12.6 does for its staves. That fixes
emerald and barrier, but **not redstone and banners** — RPM still filters those out
(`GeyserBaseItemCompatibility`). So `PAPER` as base stays the complete fix.

## Our workaround

For Bedrock players only, in container windows only, the bridge rewrites the base item of items
whose `item_model` starts with `elitemobs:ui/` or `nightbreak:ui/` to `minecraft:paper` in
`WINDOW_ITEMS` / `SET_SLOT`, keeping all component patches. Switch: `phase77.bedrock-menu-icons`.
Once EliteMobs ships the fix, we turn it off.

---

## Discord-Fassung

> **Bedrock: EM menu icons show as vanilla emerald/redstone/barrier/banners (EM 10.9.7, RPM 2.4.5, Geyser 2.11)**
>
> On Bedrock, menu buttons render as the vanilla base item instead of the `elitemobs:ui/*` icon — only
> the golden question mark works. Reason: `CustomModelAdder` only sets `item_model`, the base stays
> EMERALD/REDSTONE/BARRIER/*_BANNER. Geyser maps custom items per base item, and RPM registers
> `elitemobs:ui/*` under paper (and a few others) but never under those — redstone and banners it
> can't map at all (`GeyserBaseItemCompatibility`).
>
> The question mark works because `BuyOrSellMenu` sets `Material.PAPER` first. Suggestion: use PAPER
> as base for all menu icons when resource-pack models are on. Java looks the same, Bedrock gets the
> icons through RPM's existing mappings. Happy to share details/screenshots.
