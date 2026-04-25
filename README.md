# FMMBedrockBridge

A Spigot/Paper plugin that bridges [FreeMinecraftModels (FMM)](https://github.com/MagmaGuy/FreeMinecraftModels) custom 3D models to Bedrock clients connected via [Geyser](https://geysermc.org/).

## Problem

FreeMinecraftModels displays custom 3D models on Java clients using Display Entities (1.19.4+). Bedrock clients connected through Geyser cannot see these models — they only see the base vanilla mob (e.g. a wolf instead of a custom boss model).

The existing [GeyserModelEngine](https://github.com/zimzaza4/GeyserModelEngine) plugin only hooks into ModelEngine (Ticxo), not FMM.

## Solution

FMMBedrockBridge creates fake packet-only entities for Bedrock players, replacing the vanilla mob with the custom 3D model via [GeyserUtils](https://github.com/zimzaza4/GeyserUtils). A companion Geyser Extension registers the custom entity IDs and serves the generated Bedrock resource pack.

```
FMM spawns DynamicEntity (wraps LivingEntity)
  → FMMBedrockBridge detects spawn via polling
  → For each Bedrock player (via Floodgate API):
    1. Hide real entity (suppress spawn/metadata packets via PacketEvents)
    2. Register custom entity in GeyserUtils cache
    3. Send fake PIG spawn packet (Geyser replaces with custom model)
    4. Redirect interact packets from fake → real entity
  → Bedrock client sees custom model, can interact with real entity
```

## Requirements

**Backend Server (Paper/Spigot 1.21.x):**
- [FreeMinecraftModels](https://github.com/MagmaGuy/FreeMinecraftModels) (required)
- [Floodgate](https://github.com/GeyserMC/Floodgate) (required for Bedrock detection)
- [GeyserUtils Spigot Plugin](https://github.com/zimzaza4/GeyserUtils) (required for Bedrock entity spawning)
- [PacketEvents](https://github.com/retrooper/packetevents) (required for packet interception)

**Proxy (Velocity/BungeeCord):**
- [Geyser](https://geysermc.org/)
- [GeyserUtils Geyser Extension](https://github.com/zimzaza4/GeyserUtils)
- FMMBridgeExtension (this repo, `geyser-extension/`)

## Current Status

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | FMM entity spawn/despawn detection (polling) | Done |
| 2 | Bedrock entity bridging via GeyserUtils | Done |
| 3 | Java to Bedrock resource pack conversion + Geyser Extension | Done |
| 4 | Hitbox, material, interact redirect, visible_bounds, multi-texture atlas | Done |
| 4.5 | Model scale, texture atlas quality, nametags, performance fixes | Done |
| 4.6 | Bedrock format fixes: UV integer values, texture POW2, bone count warning | Done |
| 5 | Animation conversion + runtime sync (idle, walk, attack, death) | Done |
| 5.1 | StackOverflow fix: move property registration to startup | Done |
| 5.5 | Code modularization (PacketInterceptor, ViewerManager, ResourcePackBuilder, EntityRegistrar, DownstreamMonitor) | Done |
| 5.6 | Animation format fixes: short names in controllers, new UUID per pack build | Done |
| 6 | Static Entities (Props/Furniture — no underlying mob) | Done |
| 7 | EliteMobs UI/UX (BossBar, nametag improvements, GUIs) | Planned |
| 8 | Polish: hitbox scale, hurt flash, particles, config, performance, production-readiness | Planned |

## Deployment

### 1. Build

Requires Java 21 and Maven.

```bash
# Builds both Spigot plugin and Geyser Extension
mvn clean package
# Output: target/FMMBedrockBridge-<version>.jar
# Output: geyser-extension/target/FMMBridgeExtension-0.1.0-SNAPSHOT.jar
```

### 2. Install

- Copy `FMMBedrockBridge-*.jar` to backend server `plugins/`
- Copy `FMMBridgeExtension-*.jar` to proxy `plugins/Geyser-Velocity/extensions/`

### 3. Convert models

In-game or via console on the backend server:

```
/fmmbridge convert all
```

This reads all loaded FMM models and writes to:
```
plugins/FMMBedrockBridge/
  bedrock-skins/<modelId>/   <- geometry.json + texture.png (per model)
  bedrock-pack/              <- full Bedrock resource pack (unpacked)
  bedrock-pack.zip           <- ready to serve
```

### 4. Copy to proxy

Copy the per-model folders to the Geyser Extension input directory:

```bash
cp -r plugins/FMMBedrockBridge/bedrock-skins/* \
  plugins/Geyser-Velocity/extensions/fmmbridgeextension/input/
```

### 5. Restart proxy

The Geyser Extension will:
- Register each `fmmbridge:<modelId>` custom entity via GeyserUtils
- Generate and serve the Bedrock resource pack to connecting clients

## How it works

### Spigot Plugin

| Class | Role |
|-------|------|
| `FMMEntityTracker` | Polls `ModeledEntityManager.getAllEntities()` every second, diffs for spawns/despawns |
| `BedrockEntityBridge` | Manages fake PacketEntities for Bedrock players, delegates to PacketInterceptor and ViewerManager |
| `PacketInterceptor` | Suppresses spawn/metadata packets for hidden real entities, redirects interact packets from fake to real |
| `ViewerManager` | Tracks Bedrock players (via Floodgate), handles join/quit events, range checks |
| `IBridgeEntityData` | Common interface for DynamicEntity and StaticEntity bridge data |
| `FMMEntityData` | Per-DynamicEntity state: wraps ModeledEntity + PacketEntity + viewer set, handles addViewer/removeViewer lifecycle, sends nametag metadata |
| `StaticEntityData` | Per-StaticEntity state: spawns fake entity at fixed location, no animation or combat redirect |
| `PacketEntity` | Fake packet-only PIG entity (ID 300-400M) via PacketEvents, handles spawn/teleport/destroy/name-metadata packets |
| `BedrockModelConverter` | Reads `.bbmodel` source files, generates `geometry.json` + texture atlas via `BedrockGeometryGenerator` |
| `BedrockGeometryGenerator` | Converts .bbmodel to Bedrock .geo.json with multi-texture atlas UV mapping and dynamic visible_bounds |
| `BedrockAnimationConverter` | Converts .bbmodel animations to Bedrock .animation.json format |
| `BedrockAnimationControllerGenerator` | Generates Bedrock animation controller state machines with bitmask-based property queries |
| `AnimationStateTracker` | Polls FMM animation state via reflection (idle/walk/attack/death/custom) |
| `BedrockResourcePackGenerator` | Generates entity definitions, render controllers, `manifest.json`; zips the full pack |

### Geyser Extension (`geyser-extension/`)

| Class | Role |
|-------|------|
| `FMMBridgeExtension` | Lifecycle event handling (PreInitialize, DefineResourcePacks, PostInitialize) |
| `EntityRegistrar` | GeyserUtils reflection for entity + animation property registration |
| `ResourcePackBuilder` | Generates entity defs, render controllers, manifest.json, zips pack |
| `DownstreamMonitor` | Re-registers GeyserUtils packet listener on server switches |

### Known Issues

- **Hitbox zu klein:** Bedrock-Hitbox nutzt unveränderte Java-Entity-Dimensionen; das visuelle Model wird mit `scale: 1.6` gerendert → Hitbox wirkt kleiner (Phase 8).
- **Kein Hurt-Flash:** Damage-Metadata der Real-Entity wird vollständig supprimiert; Bedrock zeigt keinen roten Blitz bei Treffer (Phase 8).

## Dependencies

```xml
<!-- FreeMinecraftModels -->
<repository>
    <id>magmaguy-repo</id>
    <url>https://repo.magmaguy.com/releases</url>
</repository>

<!-- Floodgate + Geyser API -->
<repository>
    <id>opencollab</id>
    <url>https://repo.opencollab.dev/main/</url>
</repository>
```

GeyserUtils must be built and installed locally from source:
```bash
git clone https://github.com/zimzaza4/GeyserUtils
cd GeyserUtils
mvn install -pl common,spigot -am
```

## License

GPL-3.0 — compatible with FreeMinecraftModels.
