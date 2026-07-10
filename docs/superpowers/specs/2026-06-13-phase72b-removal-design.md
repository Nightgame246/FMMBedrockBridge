# Phase 7.2b Removal — Design Spec

**Date:** 2026-06-13
**Branch:** `refactor/remove-phase72b`
**Status:** approved, pre-implementation

## Background

Phase 7.2b made EliteMobs' 2D UI icons (BagOfCoin, golden question mark, hand-with-coins,
box-input/output, …) visible to Bedrock clients. EM ships these as legacy
`custom_model_data` overrides inside `assets/minecraft/models/item/emerald.json` (and a few
other vanilla item models). ResourcePackManager up to 2.0.1 did **not** resolve
`minecraft:emerald` as a base item for the `elitemobs:ui/*` models (its `BaseItemResolver`
guessed ~10 candidate base items by filename heuristic, none of them emerald), so Geyser
never received a mapping and Bedrock players saw the raw vanilla item.

7.2b worked around this by generating its own Bedrock pack (`em_bridge_pack.mcpack`) + a
Geyser `custom-item-v2` mappings file (`em_bridge_mappings.json`, `bridge_em:<key>`
namespace), and live-injecting an `item_model` component into inventory/entity packets for
Bedrock players via `PacketInterceptor`. A SHA-256 drift-detection + maintenance subsystem
warned operators when the EM pack changed and the proxy artifacts went stale.

## What changed upstream (verified 2026-06-13)

**RPM 2.0.2** added `GenericJavaScanner.scanLegacyCustomModelOverrides`, which scans
`assets/minecraft/models/item/<base>.json`, derives the base item from the **filename**
(`emerald.json` → `minecraft:emerald`), reads each override's `custom_model_data` predicate +
model ref, and synthesizes a modern `range_dispatch` definition. This is exactly the
`BaseItemResolver` gap 7.2b worked around.

Empirically confirmed in the live merged mappings on Proxy01
(`rspm_geyser_mappings.json`, RPM 2.0.2): `bagofcoins` is now registered under
`minecraft:emerald` with the correct CMD predicate and an icon:

```json
"minecraft:emerald": [{
  "type": "definition",
  "bedrock_identifier": "r:m3d3c2e5",
  "predicate": [{ "type": "range_dispatch", "property": "custom_model_data", "threshold": 31173.0 }],
  "bedrock_options": { "icon": "ib89ae7c" },
  "model": "elitemobs:ui/bagofcoins"
}]
```

⇒ Phase 7.2b is redundant at the mappings level. RPM 2.0.2 handles EM UI items natively.

**Caveat:** the live `PacketInterceptor` 7.2b inject overwrites the `item_model` component for
Bedrock players, which **masks** RPM's native CMD path. So RPM's native render has not yet
been *visually* confirmed on a Bedrock client — that confirmation requires disabling the
inject first (the de-risk gate below). The leftover `em_bridge_mappings.json` also still sits
in the proxy's `custom_mappings/` and likewise defines `minecraft:emerald`, duplicating RPM's
entry.

## Goal

Remove Phase 7.2b in full — all dedicated classes, the maintenance subsystem, the config
section, the `maintenance` command subtree, the `PacketInterceptor` inject, and the stale
proxy/backend artifacts. RPM 2.0.2 takes over natively. Phases 7.1 (BossBar / nametag /
combat) and 7.3/7.3b (dialog reroute) are unaffected.

## Approach: full deletion, de-risk first

Two decisions taken during brainstorming:
1. **Full deletion** (not config-disable, not keep-as-fallback) — the maintenance subsystem
   is substantial dead weight once 7.2b goes, and RPM 2.0.2 is the supported path.
2. **De-risk before deleting** — confirm RPM's native render on a real Bedrock client *before*
   removing the code, so we never delete a working fallback for a broken replacement.

### Phase A — De-Risk Gate (no code changes)

Hard stop. Do not touch code until this passes.

1. TestServer01: set `elite-items.enabled: false` in
   `plugins/FMMBedrockBridge/config.yml` (disables the inject + pack/mappings generation).
2. Proxy01: remove the stale bridge artifacts:
   - `plugins/Geyser-Velocity/custom_mappings/em_bridge_mappings.json`
   - `plugins/Geyser-Velocity/packs/em_bridge_pack.mcpack`
3. Restart backend + proxy (operator/Fabi).
4. As a Bedrock client, open an EM menu containing UI icons (e.g. BagOfCoin) and confirm the
   custom icons render via RPM's native mapping (not the vanilla emerald/raw item).
5. **Gate:** only if step 4 is ✅ proceed to Phase B. If ❌, stop — keep the inject, file an
   RPM render bug with MagmaGuy, and re-evaluate.

### Phase B — Code removal (only after a green gate)

**Files deleted entirely (10 source + 5 tests):**

| File | Why |
|------|-----|
| `bedrock/BedrockItemPackBuilder.java` (+`…Test`) | builds `em_bridge_pack.mcpack` |
| `bedrock/GeyserMappingsWriter.java` (+`…Test`) | writes `em_bridge_mappings.json` |
| `bridge/EliteMobsItemScanner.java` | scans EM pack for 2D items |
| `bridge/EMCustomItem.java` | 7.2b item record |
| `bridge/BedrockInventoryRefresher.java` | re-sends inventories so inject re-fires |
| `maintenance/MaintenanceState.java` | drift state record |
| `maintenance/MaintenanceStateStore.java` (+`…Test`) | persists drift state |
| `maintenance/MaintenanceTracker.java` (+`…Test`) | drift evaluation |
| `maintenance/OpDriftNotifier.java` | op chat drift warning |
| `maintenance/PackHashCalculator.java` (+`…Test`) | SHA-256 pack hash |

After deletion the `bedrock/` and `maintenance/` packages are empty (BedrockEntityBridge
lives in `bridge/`, not `bedrock/`).

**Files edited surgically (kept, with 7.2b parts removed):**

- **`bridge/PacketInterceptor.java`** — remove the inject block (`onPacketSend` lines
  118–157, the `if (!emItemModelMap.isEmpty() …)` branch covering SET_SLOT / WINDOW_ITEMS /
  ENTITY_METADATA), the `emItemModelMap` field, `setEmItemModelMap(...)`,
  `injectItemModelIfNeeded(...)`, and now-unused wrapper imports (SetSlot, WindowItems).
  **Keep** 7.1b entity-hiding (lines 159–180, separate ENTITY_METADATA branch at 166, uses
  `javaHiddenEntityIds`) and 7.1a BOSS_EVENT suppression (182–185). The
  `WrapperPlayServerEntityMetadata` import stays (used by 7.1b).
- **`FMMBedrockBridge.java`** — remove 7.2b imports (BedrockItemPackBuilder,
  GeyserMappingsWriter, EMCustomItem, EliteMobsItemScanner, all `maintenance.*`); fields
  `driftActive`, `currentPackHash`, `currentEmVersion`, `maintenanceTracker`, `mcpackPath`,
  `mappingsJsonPath`; onEnable lines 106–139 (scan / build / inject wiring / refresher
  registration); methods `writeEmItemsJson`, `buildPackAndMappings`, `evaluateMaintenance`,
  `pluginVersionOrUnknown`, `shortHash`, `getMaintenanceStatus`,
  `getMaintenanceArtifactPaths`, `markMaintenanceDeployed`; the `MaintenanceStatusSnapshot` +
  `MaintenanceArtifactPaths` records; the class-Javadoc 7.2b bullet; and the 7.2b mention in
  the PacketEvents-missing warning (line 88, reword to 7.1a only).
- **`commands/FMMBridgeCommand.java`** — remove the entire `maintenance` subcommand subtree
  (`runMaintenance`, `showMaintenanceStatus`, `showRedeployInstructions`, `markDeployed`) and
  its tab-completions; usage string and dispatch reduce to `/fmmbridge debug`. Keep `debug`.
- **`src/main/resources/config.yml`** — remove the `elite-items:` section. Keep `enabled`,
  `debug`, `phase71c`, `phase73`.

**Server cleanup (operator/SCP):**

- TestServer01: delete the orphaned `plugins/FMMBedrockBridge/bedrock-pack/` directory.
- Proxy01: the two stale artifacts were already removed in Phase A.

## Testing

- `mvn clean package` is green. Five test files are deleted; the remaining suite (7.3 tests:
  `McVersionsTest`, `MenuRerouteRegistryTest`, `RerouteDecisionTest`, `QuestMenuContextTest`)
  stays green.
- Boot log: no `[ItemScanner]`, `[Phase 7.2b]`, or maintenance lines; Phases 7.1c and 7.3
  still register exactly as before.
- Post-deploy Bedrock smoke: EM UI icons still render (now via RPM); 7.1 BossBar/nametag and
  7.3 dialog reroute unchanged.

## Risks & mitigations

- **RPM native render actually broken** → caught by the Phase A gate before any code is
  deleted.
- **Collateral damage in `PacketInterceptor`** → line-precise removal (118–157) plus build +
  a 7.1 BossBar/nametag smoke test on a live boss.
- **Reversibility** → the whole removal is one branch (`refactor/remove-phase72b`);
  `git revert` / branch-drop restores 7.2b if needed.

## Out of scope

- No changes to Phases 7.1 or 7.3/7.3b.
- No new functionality; this is pure removal.
- Posting upstream bug drafts to GitHub (separate, operator task).
