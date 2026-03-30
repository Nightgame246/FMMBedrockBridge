# FMMBedrockBridge

A Spigot/Paper plugin that bridges [FreeMinecraftModels (FMM)](https://github.com/MagmaGuy/FreeMinecraftModels) custom 3D models to Bedrock clients connected via [Geyser](https://geysermc.org/).

## Problem

FreeMinecraftModels displays custom 3D models on Java clients using Display Entities (1.19.4+). Bedrock clients connected through Geyser cannot see these models — they only see the base vanilla mob (e.g. a wolf instead of a custom boss model).

The existing [GeyserModelEngine](https://github.com/zimzaza4/GeyserModelEngine) plugin only hooks into ModelEngine (Ticxo), not FMM.

## Solution

FMMBedrockBridge detects FMM model spawns/despawns and uses [GeyserUtils](https://github.com/zimzaza4/GeyserUtils) to send the corresponding Bedrock custom entity to each connected Bedrock player. A companion Geyser Extension registers the custom entity IDs and serves the generated Bedrock resource pack.

```
FMM spawns DynamicEntity (wraps LivingEntity)
  → FMMBedrockBridge detects spawn via polling
  → Checks each player via Floodgate API (is Bedrock?)
  → Sends custom entity via GeyserUtils to Bedrock client
  → Bedrock client sees custom model instead of vanilla mob
```

## Requirements

**Backend Server (Paper/Spigot 1.21.x):**
- [FreeMinecraftModels](https://github.com/MagmaGuy/FreeMinecraftModels) (required)
- [Floodgate](https://github.com/GeyserMC/Floodgate) (required for Bedrock detection)
- [GeyserUtils Spigot Plugin](https://github.com/zimzaza4/GeyserUtils) (required for Bedrock entity spawning)

**Proxy (Velocity/BungeeCord):**
- [Geyser](https://geysermc.org/)
- [GeyserUtils Geyser Extension](https://github.com/zimzaza4/GeyserUtils)
- FMMBridgeExtension (this repo, `geyser-extension/`)

## Current Status

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | FMM entity spawn/despawn detection (polling) | ✅ Done |
| 2 | Bedrock entity bridging via GeyserUtils | ✅ Done |
| 3 | Java → Bedrock resource pack conversion + Geyser Extension | ✅ Done |
| 4 | Animation sync (idle, walk, attack, death) | ⏳ Planned |
| 5 | Config, auto-reload, performance optimization | ⏳ Planned |

## Deployment

### 1. Build

Requires Java 21 and Maven.

```bash
# Spigot plugin
mvn clean package
# Output: target/FMMBedrockBridge-<version>-<timestamp>.jar

# Geyser Extension
cd geyser-extension
mvn clean package
# Output: geyser-extension/target/FMMBridgeExtension-0.1.0-SNAPSHOT.jar
```

### 2. Install

- Copy `FMMBedrockBridge-*.jar` → backend server `plugins/`
- Copy `FMMBridgeExtension-*.jar` → proxy `plugins/Geyser-Velocity/extensions/`

### 3. Convert models

In-game or via console on the backend server:

```
/fmmbridge convert all
```

This reads all loaded FMM models and writes to:
```
plugins/FMMBedrockBridge/
  bedrock-skins/<modelId>/   ← geometry.json + texture.png (per model)
  bedrock-pack/              ← full Bedrock resource pack (unpacked)
  bedrock-pack.zip           ← ready to serve
```

### 4. Copy to proxy

Copy the per-model folders to the Geyser Extension input directory:

```
plugins/FMMBedrockBridge/bedrock-skins/<modelId>/
  → extensions/FMMBridgeExtension/input/<modelId>/
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
| `BedrockEntityBridge` | Calls `EntityUtils.setCustomEntity(player, javaEntityId, "fmmbridge:<modelId>")` for each Bedrock player; also handles `PlayerJoinEvent` to sync already-spawned entities |
| `BedrockModelConverter` | Reads `.bbmodel` source files, generates `geometry.json` + `texture.png` via `BedrockGeometryGenerator` |
| `BedrockResourcePackGenerator` | Generates entity definitions, render controllers, `manifest.json`; zips the full pack |

### Geyser Extension (`geyser-extension/`)

| Event | Action |
|-------|--------|
| `GeyserPreInitializeEvent` | Scans `input/` for model folders, registers each via `GeyserUtils.addCustomEntity()` (reflection), generates and zips resource pack |
| `GeyserDefineResourcePacksEvent` | Registers the generated ZIP as a Bedrock resource pack served to all connecting clients |

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
