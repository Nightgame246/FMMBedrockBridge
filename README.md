# FMMBedrockBridge

A Spigot/Paper plugin that bridges [FreeMinecraftModels (FMM)](https://github.com/MagmaGuy/FreeMinecraftModels) custom 3D models to Bedrock clients connected via [Geyser](https://geysermc.org/).

## Problem

FreeMinecraftModels displays custom 3D models on Java clients using Display Entities (1.19.4+). Bedrock clients connected through Geyser cannot see these models — they only see the base vanilla mob (e.g. a wolf instead of a custom boss model).

The existing [GeyserModelEngine](https://github.com/zimzaza4/GeyserModelEngine) plugin only hooks into ModelEngine (Ticxo), not FMM.

## Solution

FMMBedrockBridge detects FMM model spawns/despawns and uses [GeyserUtils](https://github.com/zimzaza4/GeyserUtils) to send the corresponding Bedrock custom entity to each connected Bedrock player.

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

## Current Status

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | FMM entity spawn/despawn detection | ✅ Done |
| 2 | Bedrock entity bridging via GeyserUtils (vanilla placeholder) | ✅ Done |
| 3 | Java → Bedrock resource pack conversion (.geo.json, textures) | 🔄 In Progress |
| 4 | Animation sync (idle, walk, attack, death) | ⏳ Planned |
| 5 | Config, auto-reload, performance optimization | ⏳ Planned |

## Build

Requires Java 21 and Maven.

```bash
mvn clean package
```

Output: `target/FMMBedrockBridge-<version>-<timestamp>.jar`

## Dependencies

```xml
<!-- FreeMinecraftModels -->
<repository>
    <id>magmaguy-repo</id>
    <url>https://repo.magmaguy.com/releases</url>
</repository>

<!-- Floodgate -->
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
