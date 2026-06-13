# FMMBedrockBridge

A Spigot/Paper plugin that adds the **EliteMobs UX layer** (combat-styled BossBar, HP/Bar combat-nametag) for Bedrock clients connected via [Geyser](https://geysermc.org/) — features that [FreeMinecraftModels (FMM) 2.6.0](https://github.com/MagmaGuy/FreeMinecraftModels) and [ResourcePackManager 2.0.2](https://github.com/MagmaGuy/ResourcePackManager) don't cover natively.

## Background

As of **FMM 2.6.0** (May 2026), MagmaGuy added native Geyser/Floodgate support — Bedrock players see FMM custom mob models, animations, and static props directly. **ResourcePackManager 2.0.0** converts any plugin's resource pack to Bedrock format, emits Geyser custom-item mappings (3D weapons/armor, flipbook textures, attachable display offsets), and runs in **Network-Mode**: a Velocity/Bungee sub-plugin polls each backend over HTTP, merges per-network packs, and delivers them to Geyser sessions — no manual scp between backend and proxy any more.

Previous versions of this plugin (Phases 1-6, 7.2c/d) built a parallel mob/item pipeline using fake PIG entities + GeyserUtils + a custom Geyser Extension. That work is **archived under git tag `archive/2026-05-24-pre-rpm18-pivot`** — refer to it if you need the old Bridge approach for FMM <2.6.0 servers.

The current plugin is a focused **EM↔Bedrock UX-Bridge**.

## What this plugin does

| Feature | Why it exists |
|---------|---------------|
| **Phase 7.1a/c — Styled Combat BossBar** | EM-managed Bukkit BossBar with the YAML-styled name (e.g. "Tier 13 Eis-Elementar") instead of the Vanilla "Evoker | 2" Geyser would otherwise show on Bedrock. First-match heuristic suppresses EM's BOSS_EVENT for Bedrock players. |
| **Phase 7.1b/c — Combat Nametag** | Bukkit TextDisplay above bridged mobs showing HP-number / health-bar (combat-only, 2 lines above FMM's native name). Java players see only FMM's native nametag (packet-suppress for our TextDisplay). |
| **Phase 7.3 — Bedrock Menu Dialog-Reroute** | EM forces Bedrock players to the `/em` chest menu (a bare container grid on Bedrock) even though it already builds the same menu as a native MC dialog for Java 1.21.6+. Geyser now renders MC dialogs as native Bedrock forms, so the bridge cancels the Bedrock chest and triggers EM's `showPlayerStatusDialog` — Bedrock gets a real form, sub-pages cascade natively. Reroute-only (no form-building); registry-extensible to other EM menus. Requires MC ≥ 1.21.6. |
| **Phase 7.3b — Bedrock NPC Quest-Menu Dialog-Reroute** | Extends Phase 7.3 to EliteMobs' NPC quest menu — the only other Bedrock-forced-to-chest EM menu with a native dialog path (`QuestMenu.generateDialogMenu`). Detection is holder-based (looks the opened chest up in EM's internal `QuestInventoryMenu` maps via reflection) because quest chest titles are dynamic (single-quest title = quest name, multi-quest = literal `"Quests"`). On a hit the chest is cancelled and EM's quest dialog fires next tick → Geyser renders a native Bedrock form. Status-vs-quest precedence + per-flag gating live in `RerouteDecision`; recovered quest context is carried opaquely in `QuestMenuContext`. Config: `phase73.bedrock-quest-reroute: true`. Requires MC ≥ 1.21.6. |

That's it. No mob rendering, no animation conversion, no 3D item conversion — those are FMM + RPM's job now.

## Requirements

**Backend Server (Paper/Spigot 1.21.x):**
- [FreeMinecraftModels](https://github.com/MagmaGuy/FreeMinecraftModels) **2.6.0+** with `sendCustomModelsToBedrockClients: true` in its `config.yml`
- [ResourcePackManager](https://github.com/MagmaGuy/ResourcePackManager) **2.0.0+** (generates the Bedrock pack, serves it to the proxy via embedded HTTP server)
- [EliteMobs](https://github.com/MagmaGuy/EliteMobs) (optional — required for BossBar replacement and dialog-reroute)
- [Floodgate](https://github.com/GeyserMC/Floodgate) (required for Bedrock player detection — its `key.pem` also derives the RPM Network-Mode key)
- [PacketEvents](https://github.com/retrooper/packetevents) **2.12.1+** (required for packet manipulation)

**Proxy (Velocity/BungeeCord):**
- [Geyser](https://geysermc.org/)
- [Floodgate](https://github.com/GeyserMC/Floodgate)
- `ResourcePackManager-Velocity.jar` (or `-BungeeCord.jar`) — RPM 2.0.0 polls each backend, merges packs network-wide, and ships them to Bedrock sessions. On a co-located proxy the backend auto-extracts the right jar into the proxy's `plugins/`; on a separate proxy host you extract it manually (see Deploy below).

## Build

Requires Java 21 and Maven.

```bash
mvn clean package
# Output: target/FMMBedrockBridge-<version>.jar
```

There is no longer a separate Geyser Extension submodule — RPM does that work.

## Deploy

```bash
# Drop the JAR into the backend server's plugins/ folder
cp target/FMMBedrockBridge-*.jar /path/to/server/plugins/FMMBedrockBridge.jar
```

**RPM 2.0.0 Network-Mode setup (one-time):**

```bash
# Backend: just drop the Bukkit jar — RPM extracts the proxy jars on first boot
cp ResourcePackManager.jar /path/to/backend/plugins/

# Proxy on the same host: RPM auto-copies ResourcePackManager-Velocity.jar
# into the proxy's plugins/. Nothing to do.

# Proxy on a separate host (multi-host setup): manually extract the velocity sub-jar
# from the backend bukkit jar and place it on the proxy. Don't drop the bukkit jar
# on velocity — it will be rejected with "appears to be a Paper, Bukkit ... plugin".
unzip -j ResourcePackManager.jar proxy-extension/ResourcePackManager-Velocity.jar
mv ResourcePackManager-Velocity.jar /path/to/proxy/plugins/

# Network key is auto-derived from plugins/floodgate/key.pem (no manual paste).
# Verify with /rspm status on both sides after restart.
```

## Config

`plugins/FMMBedrockBridge/config.yml`:

```yaml
enabled: true
debug: false                 # /fmmbridge debug shows live state
entity-view-distance: 50

phase71a:
  suppress-em-bossbar: true  # false = both bars side-by-side (diagnostic)

phase71c:
  combat-enabled: true        # false = BossBar always-visible
  damage-refresh-enabled: true # EliteMobDamagedByPlayerEvent also refreshes the overlay
  hide-on-exit-event: false   # let the display-timeout decide when to hide (Java-feel)
  damage-timeout-ticks: 0     # 0 = use EliteMobs combatDisplayTimeoutSeconds (~30s)
```

## Architecture

### Spigot plugin (`src/main/java/de/crazypandas/fmmbedrockbridge/`)

| Class | Role |
|-------|------|
| `FMMBedrockBridge` | Plugin lifecycle, dependency checks, controller wire-up |
| `tracker/FMMEntityTracker` | Polls `ModeledEntityManager.getAllEntities()` every second; calls `bridge.onEntitySpawn/Despawn` |
| `bridge/BedrockEntityBridge` | Holds the controller maps (BossBar + Nametag), `entityDataMap`, per-tick sync |
| `bridge/FMMEntityData` | Per-mob holder for the BossBar + Nametag controllers (no rendering — FMM does that) |
| `bridge/ViewerManager` | Bedrock player tracking via Floodgate, range checks |
| `bridge/PacketInterceptor` | PacketEvents listener: BossBar suppress, Java-TextDisplay suppress |
| `bridge/BedrockBossBarController` | Bukkit BossBar lifecycle per boss × Bedrock viewer |
| `bridge/BedrockNametagController` | TextDisplay lifecycle, combat-state, position/text sync |
| `bridge/BedrockCombatTrigger` | Bukkit listener: forwards `EliteMobEnterCombatEvent` / `ExitCombatEvent` to controllers |
| `bridge/BossBarRegistry` | Captured EM BossBar UUIDs for ongoing suppression |
| `bridge/NametagTextBuilder` | Pure utility composing the Nametag Component (empty out-of-combat, HP+Bar in-combat) |
| `bridge/BedrockMenuRerouteListener` | Phase 7.3/7.3b: cancels the Bedrock `/em` chest or NPC quest-chest open and fires EM's native dialog next tick (Geyser → Bedrock form); dispatches status vs quest via `RerouteDecision` |
| `bridge/MenuRerouteRegistry` | Phase 7.3: title-normalize (strip color codes) + title→dialog-invoker lookup; extensible to more EM menus |
| `bridge/McVersions` | Pure dotted-version threshold check (gates the reroute on MC ≥ 1.21.6) |
| `bridge/RerouteDecision` | Phase 7.3b: pure resolver — status-vs-quest precedence + per-flag gating; no side effects |
| `elite/QuestMenuContext` | Phase 7.3b: opaque carrier record for the recovered quest menu context |
| `elite/EliteMobsHook` | Soft-dep wrapper around EliteMobs API (only file with `com.magmaguy.elitemobs.*` imports); incl. Phase 7.3 reflection wrappers for EM's native status dialog, and Phase 7.3b `tryRecoverQuestMenu` + `openNativeQuestDialog` reflection into EM's `QuestInventoryMenu` static maps |
| `commands/FMMBridgeCommand` | `/fmmbridge debug` — shows active controllers, ready Bedrock players, suppressed UUIDs |

Roughly 18 classes. The pre-refactor bridge was 27 classes + a Geyser Extension; both archived under the git tag mentioned above.

> **Phase 7.2b removed (2026-06-14):** EM 2D UI items (legacy `custom_model_data` overrides on `minecraft:emerald` and similar base items) are now handled natively by ResourcePackManager 2.0.2 via `GenericJavaScanner.scanLegacyCustomModelOverrides` (legacy `→` Bedrock conversion, 10 of 12 EM UI icons). The bridge no longer injects `item_model` or generates/ships an `em_bridge_pack.mcpack`. Known Bedrock/Geyser limitation: the 2 banner-based icons (`green_banner`+CMD31173→`boxinput`, `red_banner`+CMD31173→`boxoutput`, used in EM's enchantment/"Verzauberer" and elite-scroll menus) do **not** render on Bedrock — Geyser cannot apply custom-item-v2 to banner base items (block-entity/pattern-rendered). This is a known upstream-pending gap; see `docs/upstream-bugs/em-banner-ui-items-bedrock.md`.

## Phase 7.3b — Bedrock NPC quest-menu dialog-reroute

Extends Phase 7.3 to EliteMobs' NPC quest menu — the only other Bedrock-forced-to-chest EM menu with a native dialog path (`QuestMenu.generateDialogMenu`). Detection is holder-based: the bridge looks the opened chest up in EM's internal `QuestInventoryMenu` maps (reflection in `EliteMobsHook`) because quest chest titles are dynamic (single-quest title = quest name, multi-quest = literal `"Quests"`). On a hit the chest is cancelled and EM's quest dialog fires next tick → Geyser renders a native Bedrock form. Status-vs-quest precedence + per-flag gating live in `RerouteDecision`; recovered quest context is carried opaquely in `QuestMenuContext`.

Config: `phase73.bedrock-quest-reroute: true` (toggles independently of `bedrock-dialog-reroute`). Requires MC >= 1.21.6. Java players unaffected.

## License

GPL-3.0 — compatible with FreeMinecraftModels.
