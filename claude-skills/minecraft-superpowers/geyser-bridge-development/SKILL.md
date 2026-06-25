---
name: geyser-bridge-development
description: "Use when building Java→Bedrock bridges via Geyser/Floodgate/GeyserUtils — covers entity replacement, Bedrock resource packs, custom entities, packet interception, Geyser extensions, Display Entity to Bedrock translation"
---

# Skill: Geyser Bridge Development

## Scope
Building plugins/extensions that make Java Edition features work for Bedrock Edition clients connected via Geyser/Floodgate.

## Architecture Overview

### The Geyser Stack
```
Bedrock Client
    ↓ (Bedrock Protocol)
Geyser (on Proxy or Standalone)
    ↓ (Java Protocol)
Velocity/BungeeCord Proxy
    ↓ (Java Protocol)
Paper/Spigot Backend Server
```

### Where Code Runs
- **Geyser Extensions:** Run inside Geyser on the proxy — access Geyser internals, can modify Bedrock packets
- **Backend Plugins:** Run on Paper/Spigot — detect Bedrock players via Floodgate, send data via Plugin Messaging
- **Both needed:** Most bridges need a backend plugin AND a Geyser extension working together

## Detecting Bedrock Players

### Via Floodgate API (Backend Server)
```java
import org.geysermc.floodgate.api.FloodgateApi;

public boolean isBedrockPlayer(Player player) {
    if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }
    return false;
}

// Floodgate player UUIDs always start with 00000000-0000-0000-0009-
public boolean isBedrockUUID(UUID uuid) {
    return uuid.toString().startsWith("00000000-0000-0000-0009-");
}
```

### Via Floodgate Prefix
```java
// Floodgate prefixes Bedrock usernames (default: ".")
boolean isBedrock = player.getName().startsWith(".");
```

## GeyserUtils API

GeyserUtils enables spawning custom Bedrock entities from backend servers.

### Backend Plugin (Spigot/Paper Side)
```java
// GeyserUtils communicates via Plugin Messaging
// The backend plugin registers entity definitions that the Geyser extension picks up

// Check if GeyserUtils is available
if (Bukkit.getPluginManager().isPluginEnabled("GeyserUtils")) {
    // GeyserUtils API calls here
}
```

### Geyser Extension Side
```java
// Extensions are loaded by Geyser, not by the server
// They have access to Geyser's session and packet APIs
// Place .jar in plugins/Geyser-Velocity/extensions/
```

## Bedrock Resource Packs

### Structure (Bedrock Format)
```
pack/
├── manifest.json              # Pack metadata (UUID, version, name)
├── entity/                    # Entity definitions (.json)
│   └── my_entity.entity.json
├── models/
│   └── entity/               # Geometry files (.geo.json)
│       └── my_model.geo.json
├── textures/
│   └── entity/               # PNG textures
│       └── my_texture.png
├── animations/               # Bedrock animation files
│   └── my_anim.animation.json
├── animation_controllers/    # State machines for animations
│   └── my_controller.json
├── render_controllers/       # How to render entities
│   └── my_render.json
└── materials/                # Shader definitions (usually not needed)
```

### manifest.json
```json
{
    "format_version": 2,
    "header": {
        "name": "My Bridge Pack",
        "description": "Custom models for Bedrock",
        "uuid": "GENERATE-UNIQUE-UUID",
        "version": [1, 0, 0],
        "min_engine_version": [1, 21, 0]
    },
    "modules": [
        {
            "type": "resources",
            "uuid": "GENERATE-ANOTHER-UUID",
            "version": [1, 0, 0]
        }
    ]
}
```

### Entity Definition
```json
{
    "format_version": "1.10.0",
    "minecraft:client_entity": {
        "description": {
            "identifier": "mypack:custom_mob",
            "materials": { "default": "entity_alphatest" },
            "textures": { "default": "textures/entity/my_texture" },
            "geometry": { "default": "geometry.my_model" },
            "render_controllers": ["controller.render.default"],
            "animations": {
                "idle": "animation.my_model.idle",
                "walk": "animation.my_model.walk"
            },
            "animation_controllers": [
                { "controller": "controller.animation.my_model" }
            ]
        }
    }
}
```

### Geometry (.geo.json)
```json
{
    "format_version": "1.21.0",
    "minecraft:geometry": [{
        "description": {
            "identifier": "geometry.my_model",
            "texture_width": 64,
            "texture_height": 64,
            "visible_bounds_width": 2,
            "visible_bounds_height": 3,
            "visible_bounds_offset": [0, 1.5, 0]
        },
        "bones": [
            {
                "name": "body",
                "pivot": [0, 24, 0],
                "cubes": [
                    {
                        "origin": [-4, 12, -2],
                        "size": [8, 12, 4],
                        "uv": [16, 16]
                    }
                ]
            }
        ]
    }]
}
```

## Java → Bedrock Model Conversion

### Key Differences
| Aspect | Java Resource Pack | Bedrock Resource Pack |
|--------|-------------------|----------------------|
| Model format | JSON (custom) | .geo.json (Bedrock Geometry) |
| Animations | Not in resource pack (server-side) | .animation.json files |
| Entity defs | Not needed | Required (.entity.json) |
| Textures | Per-model references | Per-entity in entity def |
| UV mapping | Per-face UV | Per-face or per-bone UV |
| Max model size | Limited by display entity | 128x128x128 per bone |
| Rotations | Multiples of 22.5° | Any angle |

### Conversion Gotchas
- **per_texture_uv_size MUST be integers** — Float values crash Geyser's JSON parser
- Bedrock uses Y-up coordinate system (same as Java Blockbench)
- Bedrock bone pivots are in model space, not relative to parent
- Animations must be in Bedrock format — Java display entity animations don't translate
- Each bone becomes a locator in Bedrock, not a separate entity

## Delivering Packs to Bedrock Clients

### Via Geyser packs/ Folder
```bash
# Place .zip or .mcpack in:
plugins/Geyser-Velocity/packs/my_bridge_pack.zip
```

### Geyser Config
```yaml
# In Geyser config.yml:
force-resource-packs: true      # Force Bedrock clients to accept
enable-integrated-pack: true    # Enable Geyser's built-in pack
```

### Via Geyser API (Dynamic)
```java
// For extensions that need to modify packs at runtime
// Use ResourcePackManifest and PackCodec APIs
```

## Common Bridge Patterns

### Pattern 1: Entity Replacement
Java spawns entity with custom model → Bridge detects spawn → Sends Bedrock custom entity instead
```
Java: Display Entity with custom model data
  ↓ (Bridge intercepts)
Bedrock: Custom entity defined in resource pack
```

### Pattern 2: Packet Interception
Listen for specific Java packets → Translate to equivalent Bedrock behavior
```
Java: Custom particle/effect packet
  ↓ (Geyser Extension intercepts)
Bedrock: Equivalent particle or custom entity
```

### Pattern 3: Scoreboard/UI Bridge
Java scoreboard/bossbar/actionbar → Translate to Bedrock Forms/UI
```
Java: Custom inventory GUI
  ↓ (Bridge translates)
Bedrock: Forms API or chest-based GUI
```

## Debugging Bridges

### Check List
1. Is the resource pack being sent? Check Geyser log for pack loading
2. Is the pack being accepted? Bedrock shows download progress
3. Is the entity registered? Check `[geyserutils] Defined entity: ...` in log
4. Is GeyserUtils running on BOTH backend AND proxy?
5. Are Floodgate versions matched between proxy and backend?
6. Is the entity identifier in the pack matching the registered identifier?

### Common Errors
- **"Not registering custom item definition: conflicts"** — Duplicate Bedrock identifiers
- **No model visible** — Pack not loaded or entity identifier mismatch
- **Player sees base mob** — GeyserUtils not communicating, entity not swapped
- **per_texture_uv_size crash** — Float values where integers expected

### Log Locations
```bash
# Proxy (Geyser) logs:
/path/to/proxy/logs/latest.log

# Backend server logs:
/path/to/server/logs/latest.log

# Grep for relevant entries:
grep -i "geyser\|bedrock\|floodgate\|pack\|entity" logs/latest.log
```

## Performance Considerations

- Only send Bedrock entities to players who are actually Bedrock (check Floodgate)
- Use view distance to limit entity spawning for remote players
- Batch entity updates — don't send per-tick updates for every entity
- Resource packs should be as small as possible (compress textures)
- Consider lazy loading: only register entities that are actually in use on the server
