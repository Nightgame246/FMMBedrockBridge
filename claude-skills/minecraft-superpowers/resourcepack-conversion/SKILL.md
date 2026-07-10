---
name: resourcepack-conversion
description: "Use when converting resource packs between Java and Bedrock formats — covers .geo.json geometry, UV conversion, animation format, Blockbench .bbmodel parsing, manifest.json, entity definitions, coordinate transforms"
---

# Skill: Resource Pack Conversion (Java ↔ Bedrock)

## Scope
Converting Minecraft resource packs between Java and Bedrock Edition formats, with focus on model/geometry conversion.

## Format Comparison

### Pack Structure
```
Java Pack:                          Bedrock Pack:
├── pack.mcmeta                     ├── manifest.json
├── pack.png                        ├── pack_icon.png
├── assets/                         ├── textures/
│   └── namespace/                  │   ├── entity/
│       ├── models/                 │   ├── blocks/
│       │   ├── block/              │   └── items/
│       │   └── item/               ├── models/
│       ├── textures/               │   └── entity/
│       │   ├── block/              ├── entity/
│       │   └── item/               ├── animations/
│       └── blockstates/            ├── animation_controllers/
└── (no entity defs needed)         └── render_controllers/
```

## Java Model → Bedrock Geometry Conversion

### Java Model Format (items/blocks)
```json
{
    "parent": "minecraft:item/generated",
    "textures": { "layer0": "namespace:item/my_item" },
    "overrides": [
        { "predicate": { "custom_model_data": 1 }, "model": "namespace:item/custom1" }
    ]
}
```

### Java Custom Model (3D)
```json
{
    "textures": { "0": "namespace:item/texture" },
    "elements": [
        {
            "from": [4, 0, 4],
            "to": [12, 16, 12],
            "rotation": { "angle": 22.5, "axis": "y", "origin": [8, 8, 8] },
            "faces": {
                "north": { "uv": [4, 0, 12, 16], "texture": "#0" },
                "south": { "uv": [4, 0, 12, 16], "texture": "#0" },
                "east":  { "uv": [4, 0, 12, 16], "texture": "#0" },
                "west":  { "uv": [4, 0, 12, 16], "texture": "#0" },
                "up":    { "uv": [4, 4, 12, 12], "texture": "#0" },
                "down":  { "uv": [4, 4, 12, 12], "texture": "#0" }
            }
        }
    ]
}
```

### Bedrock Geometry Format
```json
{
    "format_version": "1.21.0",
    "minecraft:geometry": [{
        "description": {
            "identifier": "geometry.my_model",
            "texture_width": 64,
            "texture_height": 64
        },
        "bones": [{
            "name": "root",
            "pivot": [0, 0, 0],
            "cubes": [{
                "origin": [-4, 0, -4],
                "size": [8, 16, 8],
                "uv": { "north": {"uv": [4, 0], "uv_size": [8, 16]} }
            }]
        }]
    }]
}
```

## Conversion Algorithm

### Step 1: Coordinate Transform
```
Java "from"/"to" → Bedrock "origin"/"size"

Java: from=[4, 0, 4], to=[12, 16, 12]
  origin = [from[0] - 8, from[1], from[2] - 8]  // Center at 0,0
  size = [to[0] - from[0], to[1] - from[1], to[2] - from[2]]

Bedrock: origin=[-4, 0, -4], size=[8, 16, 8]
```

### Step 2: UV Conversion
```
Java: per-face UV as [u1, v1, u2, v2] in pixels (0-16 scale for items)
Bedrock: per-face UV as {"uv": [u, v], "uv_size": [w, h]}

Java:   "uv": [4, 0, 12, 16]
Bedrock: "uv": [4, 0], "uv_size": [8, 16]
```

### Step 3: Rotation
```
Java rotations: limited to -45, -22.5, 0, 22.5, 45 on ONE axis
Bedrock rotations: any angle on any axis (more flexible)

Java:   "rotation": {"angle": 22.5, "axis": "y", "origin": [8, 8, 8]}
Bedrock: bone-level "rotation": [0, 22.5, 0] (applied to whole bone)
```

### Step 4: Group Elements into Bones
Java models are flat (just elements), Bedrock needs bones:
- Group elements that share rotation into the same bone
- Elements without rotation go into "root" bone
- Each unique rotation axis/origin combination = new bone

## Blockbench Model (.bbmodel) Conversion

### Reading .bbmodel
```json
{
    "meta": { "format_version": "4.10", "model_format": "free" },
    "resolution": { "width": 64, "height": 64 },
    "elements": [...],
    "outliner": [...],     // Bone hierarchy
    "textures": [...],
    "animations": [...]
}
```

### .bbmodel → Bedrock .geo.json
1. Parse `outliner` for bone hierarchy (groups = bones)
2. Parse `elements` for cubes (map to bones via outliner)
3. Convert UV coordinates based on `resolution`
4. Export textures from embedded base64 or reference paths
5. Generate entity definition
6. Convert animations to Bedrock format

## Animation Conversion

### Bedrock Animation Format
```json
{
    "format_version": "1.8.0",
    "animations": {
        "animation.model.idle": {
            "loop": true,
            "animation_length": 2.0,
            "bones": {
                "body": {
                    "rotation": {
                        "0.0": [0, 0, 0],
                        "1.0": [5, 0, 0],
                        "2.0": [0, 0, 0]
                    }
                }
            }
        }
    }
}
```

### Animation Controller (State Machine)
```json
{
    "format_version": "1.10.0",
    "animation_controllers": {
        "controller.animation.model": {
            "initial_state": "idle",
            "states": {
                "idle": {
                    "animations": ["idle"],
                    "transitions": [
                        { "walking": "query.is_moving" }
                    ]
                },
                "walking": {
                    "animations": ["walk"],
                    "transitions": [
                        { "idle": "!query.is_moving" }
                    ]
                }
            }
        }
    }
}
```

## Critical Rules

### MUST DO
- **per_texture_uv_size values MUST be integers** — Floats crash Geyser
- **manifest.json UUIDs must be unique** per pack
- **Texture dimensions must be power of 2** (16, 32, 64, 128, 256, 512)
- **Geometry identifier must match** entity definition reference
- **Test on actual Bedrock client** — rendering differs from Java

### AVOID
- Don't use transparency on non-alpha materials
- Don't exceed 256x256 texture resolution for mobile performance
- Don't create more than ~50 bones per model (Bedrock performance)
- Don't use per_texture_uv_size with non-integer values
- Don't forget render_controllers — without them models are invisible

## Tools

- **Blockbench:** https://blockbench.net — edit both Java and Bedrock models
- **bridge.:** https://bridge-core.app — Bedrock add-on editor
- **Geyser Custom Mappings:** Define item mappings in Geyser config
- **Rainbow (Geyser):** Auto-generate Bedrock item mappings from Java resource pack
