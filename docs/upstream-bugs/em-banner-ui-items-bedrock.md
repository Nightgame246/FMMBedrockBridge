# [EM / RPM] Banner-based UI icons invisible on Bedrock — Geyser cannot apply custom-item-v2 to banner base items

**Status:** DRAFT — not yet posted upstream
**Repos:** MagmaGuy/EliteMobs, MagmaGuy/ResourcePackManager (either can fix)
**Versions:** EliteMobs 10.5.0, ResourcePackManager 2.0.2, Geyser-Velocity 2.10.x
**Related:** `CustomModelsConfig`, `ItemEnchantmentMenuConfig`, `EliteScrollMenuConfig`

## Summary

Two EliteMobs UI icons use `green_banner` and `red_banner` as their base item:

| EM model identifier | Base item | CMD | Used in |
|---------------------|-----------|-----|---------|
| `boxinput` | `green_banner` | 31173 | Enchantment/"Verzauberer" menu, Elite-Scroll menu (input frame) |
| `boxoutput` | `red_banner` | 31173 | Enchantment/"Verzauberer" menu, Elite-Scroll menu (output frame) |

These are defined in EM's `CustomModelsConfig` (class `CustomModelsConfig`; referenced in
`ItemEnchantmentMenuConfig` and `EliteScrollMenuConfig`).

Under RPM 2.0.2's native Bedrock conversion (`GenericJavaScanner.scanLegacyCustomModelOverrides`),
all other EM UI icons — backed by flat-icon base items such as `emerald`, `green_stained_glass_pane`,
`redstone`, `paper`, etc. — are correctly mapped via `custom-item-v2` entries and render on Bedrock
clients.

**The two banner-based icons do not render on Bedrock clients.** They appear completely invisible
(no item, no icon) in the affected inventory menus.

## Root Cause

Bedrock / Geyser cannot apply `custom-item-v2` mappings to banner items. Banners are implemented
as block-entities with pattern-based rendering on Bedrock, not as flat item icons. Geyser's
`CustomItemRegistryPopulator` cannot register a per-CMD override for a banner base item that
Bedrock will actually honour. The icons end up invisible rather than falling back to a vanilla
banner.

The other 10 EM UI icons (on flat-icon base items) work correctly — this issue is limited
specifically to the banner base items.

## Reproduction

1. Run Minecraft 1.21.x (Paper) with EliteMobs 10.5.0 + ResourcePackManager 2.0.2 + Geyser (Network-Mode).
2. Connect as a Bedrock client (via Floodgate).
3. Open an enchantment table / "Verzauberer" NPC menu in EliteMobs, or an Elite-Scroll menu.
4. Observe: the input and output frame slots (normally shown as `boxinput`/`boxoutput` icon) are empty/invisible on Bedrock. Java clients see them correctly.

## Expected behaviour

The `boxinput` and `boxoutput` frame icons should be visible to Bedrock clients as custom
flat icons (or at minimum as a visible placeholder item).

## Suggested fixes

Either repository can resolve this:

**Option A — EliteMobs: move `boxinput`/`boxoutput` models onto a flat-icon base item**

Change the base item for both models from `green_banner`/`red_banner` to a flat-icon item
(e.g. `paper`, `emerald`, or a stained glass pane) — the same pattern used by the other
10 EM UI icons. RPM's `scanLegacyCustomModelOverrides` would then pick them up automatically.

**Option B — ResourcePackManager: re-base banner-backed legacy overrides onto a flat-icon carrier during Bedrock conversion**

When `scanLegacyCustomModelOverrides` encounters a `custom_model_data` override on a banner
base item, emit the Bedrock custom-item mapping against a synthetic flat-icon item (e.g. the
Bedrock identifier could include a `_banner_rebased` suffix). This requires Geyser-side
support as well (emitting a fake ItemStack with the flat-icon material to Bedrock).

**Option C — Geyser: support custom-item-v2 on banner base items**

Teach Geyser to render a custom texture/model for a banner-based ItemStack when a matching
`custom-item-v2` mapping exists, bypassing the block-entity pattern renderer for that specific
CMD value.

## Workaround (removed)

The FMMBedrockBridge plugin previously injected `item_model = bridge_em:<key>` for these
items and shipped its own Bedrock mini-pack (`em_bridge_pack.mcpack`). This workaround was
removed on 2026-06-14 when RPM 2.0.2 began natively handling the other 10 EM UI icons.
The banner limitation was not solvable by the Bridge workaround either — it is a
Bedrock/Geyser renderer constraint. The 2 affected icons were visible under the old approach
only because GeyserUtils injected a synthetic entity-based render, which is not feasible in
the current RPM-native pipeline.

## Notes

- All other EM UI icons in `CustomModelsConfig` use flat-icon base items and render correctly
  on Bedrock under RPM 2.0.2 — the scope of this bug is exactly these two models.
- The affected menus remain fully functional on Bedrock; only the frame decoration icons are
  missing.
