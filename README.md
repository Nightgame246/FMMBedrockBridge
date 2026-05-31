# FMMBedrockBridge

A Spigot/Paper plugin that adds the **EliteMobs UX layer** (combat-styled BossBar, HP/Bar combat-nametag, 2D legacy UI items) for Bedrock clients connected via [Geyser](https://geysermc.org/) — features that [FreeMinecraftModels (FMM) 2.6.0](https://github.com/MagmaGuy/FreeMinecraftModels) and [ResourcePackManager 2.0.0](https://github.com/MagmaGuy/ResourcePackManager) don't cover natively.

## Background

As of **FMM 2.6.0** (May 2026), MagmaGuy added native Geyser/Floodgate support — Bedrock players see FMM custom mob models, animations, and static props directly. **ResourcePackManager 2.0.0** converts any plugin's resource pack to Bedrock format, emits Geyser custom-item mappings (3D weapons/armor, flipbook textures, attachable display offsets), and runs in **Network-Mode**: a Velocity/Bungee sub-plugin polls each backend over HTTP, merges per-network packs, and delivers them to Geyser sessions — no manual scp between backend and proxy any more.

Previous versions of this plugin (Phases 1-6, 7.2c/d) built a parallel mob/item pipeline using fake PIG entities + GeyserUtils + a custom Geyser Extension. That work is **archived under git tag `archive/2026-05-24-pre-rpm18-pivot`** — refer to it if you need the old Bridge approach for FMM <2.6.0 servers.

The current plugin is a focused **EM↔Bedrock UX-Bridge**.

## What this plugin does

| Feature | Why it exists |
|---------|---------------|
| **Phase 7.1a/c — Styled Combat BossBar** | EM-managed Bukkit BossBar with the YAML-styled name (e.g. "Tier 13 Eis-Elementar") instead of the Vanilla "Evoker | 2" Geyser would otherwise show on Bedrock. First-match heuristic suppresses EM's BOSS_EVENT for Bedrock players. |
| **Phase 7.1b/c — Combat Nametag** | Bukkit TextDisplay above bridged mobs showing HP-number / health-bar (combat-only, 2 lines above FMM's native name). Java players see only FMM's native nametag (packet-suppress for our TextDisplay). |
| **Phase 7.2b — 2D Legacy UI Items** | EM still uses `custom_model_data` overrides on Emerald (e.g. CMD 31173 → BagOfCoin). RPM 1.8.0 only scans the 1.21.4+ `items/` namespace, so these icons would render as Vanilla on Bedrock. Bridge scans EM's pack and injects `item_model=geyser_custom:<key>` on the relevant inventory packets. |

That's it. No mob rendering, no animation conversion, no 3D item conversion — those are FMM + RPM's job now.

## Requirements

**Backend Server (Paper/Spigot 1.21.x):**
- [FreeMinecraftModels](https://github.com/MagmaGuy/FreeMinecraftModels) **2.6.0+** with `sendCustomModelsToBedrockClients: true` in its `config.yml`
- [ResourcePackManager](https://github.com/MagmaGuy/ResourcePackManager) **2.0.0+** (generates the Bedrock pack, serves it to the proxy via embedded HTTP server)
- [EliteMobs](https://github.com/MagmaGuy/EliteMobs) (optional — required for BossBar replacement and 2D-item scan)
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

elite-items:
  enabled: true              # 2D UI icons (BagOfCoin, AnvilHammer, ...)
  resource-pack-path: "plugins/EliteMobs/resource_pack"
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
| `bridge/PacketInterceptor` | PacketEvents listener: BossBar suppress, Java-TextDisplay suppress, 2D item_model inject |
| `bridge/BedrockBossBarController` | Bukkit BossBar lifecycle per boss × Bedrock viewer |
| `bridge/BedrockNametagController` | TextDisplay lifecycle, combat-state, position/text sync |
| `bridge/BedrockCombatTrigger` | Bukkit listener: forwards `EliteMobEnterCombatEvent` / `ExitCombatEvent` to controllers |
| `bridge/BossBarRegistry` | Captured EM BossBar UUIDs for ongoing suppression |
| `bridge/NametagTextBuilder` | Pure utility composing the Nametag Component (empty out-of-combat, HP+Bar in-combat) |
| `bridge/EliteMobsItemScanner` | Reads EM resource pack, finds legacy `custom_model_data` overrides on Emerald & co. |
| `bridge/EMCustomItem` | Record: javaMaterial, customModelData, texture path, bedrockKey |
| `bridge/BedrockInventoryRefresher` | Bukkit listener: schedules `Player.updateInventory()` 1 tick after Bedrock player slot changes (forces WINDOW_ITEMS resend that the interceptor can re-inject) |
| `elite/EliteMobsHook` | Soft-dep wrapper around EliteMobs API (only file with `com.magmaguy.elitemobs.*` imports) |
| `commands/FMMBridgeCommand` | `/fmmbridge debug` — shows active controllers, ready Bedrock players, suppressed UUIDs |

Roughly 16 classes / 54 KB JAR. The pre-refactor bridge was 27 classes + a Geyser Extension; both archived under the git tag mentioned above.

## License

GPL-3.0 — compatible with FreeMinecraftModels.
