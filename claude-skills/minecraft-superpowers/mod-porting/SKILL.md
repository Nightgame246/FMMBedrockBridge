---
name: mod-porting
description: "Use when porting mods between Minecraft versions or mod loaders (Forge, Fabric, NeoForge, Quilt) — covers mapping systems, registry changes, Mixin porting, event system translation, build system migration"
---

# Skill: Minecraft Mod Porting

## Scope
Porting mods between Minecraft versions and between mod loaders (Forge, Fabric, NeoForge, Quilt).

## Before You Start

### Understand the Source
1. Identify current mod loader and MC version
2. Identify target mod loader and MC version
3. Read the mod's source — understand what APIs it uses
4. Check if the mod uses Mixins, Access Wideners, or NMS
5. List all dependencies and check if they exist for the target version

### Version Change Impact Assessment
Not all version bumps are equal:
- **Patch versions** (1.21.3 → 1.21.4): Usually minor, few breaking changes
- **Minor versions** (1.20 → 1.21): Moderate changes, new features, some API breaks
- **Major refactors** (1.12 → 1.13, 1.16 → 1.17): Massive changes, near-rewrite required

## Mapping Systems

### Types of Mappings
- **Mojang (Official):** Mojang's official mappings, used by NeoForge and modern Fabric
- **Yarn:** Fabric community mappings, more descriptive names
- **SRG/MCP:** Legacy Forge mappings (Forge ≤1.20.1)
- **Intermediary:** Fabric's stable intermediary mappings
- **Hashed (Quilt):** Quilt's hashed mappings

### Mapping Migration
When porting, class/method/field names change between mapping sets:
```
# Example: A block class
Mojang:    net.minecraft.world.level.block.Block
SRG:       net.minecraft.world.level.block.Block (same in modern)
Yarn:      net.minecraft.block.Block
```

### Tools
- **Linkie:** https://linkie.shedaniel.dev/ — search mappings across versions
- **Fabric Matcher:** Matches classes between versions
- **ForgeAutoRenamingTool:** Converts between SRG and Mojang

## Forge → Fabric Migration

### Key Differences
| Concept | Forge | Fabric |
|---------|-------|--------|
| Entry point | @Mod annotation | ModInitializer interface |
| Events | MinecraftForge.EVENT_BUS | Callbacks (specific interfaces) |
| Registry | DeferredRegister | Registry.register() |
| Config | ForgeConfigSpec | Custom or Cloth Config |
| Mixins | Supported but less common | Primary extension mechanism |
| Networking | SimpleChannel | Fabric Networking API |

### Event System Translation
```java
// Forge
@SubscribeEvent
public void onBlockBreak(BlockEvent.BreakEvent event) { }

// Fabric
PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> { });
```

### Registry Translation
```java
// Forge (DeferredRegister)
public static final DeferredRegister<Block> BLOCKS = 
    DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
public static final RegistryObject<Block> MY_BLOCK = 
    BLOCKS.register("my_block", () -> new Block(Properties.of()));

// Fabric
public static final Block MY_BLOCK = Registry.register(
    Registries.BLOCK, Identifier.of(MOD_ID, "my_block"),
    new Block(Settings.create()));
```

## Forge → NeoForge Migration (1.20.2+)

### Package Changes
```
net.minecraftforge.* → net.neoforged.*
net.minecraftforge.event.* → net.neoforged.neoforge.event.*
net.minecraftforge.registries.* → net.neoforged.neoforge.registries.*
```

### Key API Changes
- `ForgeRegistries` → `NeoForgeRegistries` or vanilla `BuiltInRegistries`
- `@Mod.EventBusSubscriber` still works but package changed
- Capability system completely reworked in NeoForge
- Data generation API modernized

## Version-Specific Breaking Changes

### 1.19.4 → 1.20
- Display Entities introduced
- New network protocol for resource packs
- Smithing table recipe changes

### 1.20.4 → 1.21
- Component system replaces NBT for items (huge change!)
- `ItemStack.getTag()` → `ItemStack.get(DataComponents.CUSTOM_DATA)`
- Registry system changes
- New Experiment/Feature flags system

### 1.21 → 1.21.2+
- Further component system refinements
- Recipe system overhaul
- New equippable component

### 1.21.10 → 1.21.11
- New registry entries (environment attributes, timeline)
- Packet changes in CONFIGURATION state
- New dimension type fields

## Mixin Porting

### Common Issues
- Target class renamed between versions → update `@Mixin(targets = ...)`
- Target method signature changed → update `@Inject(method = ...)`
- Method removed → find alternative hook point
- Access Widener entries may need updating

### Debugging Mixins
```
# Add to JVM args for verbose mixin output
-Dmixin.debug.verbose=true
-Dmixin.debug.export=true
```

## Porting Workflow

### Step-by-Step
1. **Fork/clone** the source mod repository
2. **Create a branch** for the port: `port/1.21.4-fabric`
3. **Update build scripts** (build.gradle/pom.xml) for target version
4. **Update mappings** — use Linkie to find renamed classes/methods
5. **Fix compilation errors** one by one, starting with:
   - Import statements
   - Registry changes
   - Event system changes
   - Removed/renamed methods
6. **Fix Mixins** — update targets and method signatures
7. **Test in-game** — not just compilation, actually run it
8. **Test edge cases** — multiplayer, different game modes, mod interactions

### Common Pitfalls
- Don't assume methods just got renamed — they may have been completely refactored
- Check if vanilla behavior changed (e.g., item components in 1.21)
- Verify data fixers if the mod stores persistent data (worlds/chunks)
- Test with the latest version of dependencies, not just compatible ones
- Watch for thread safety changes between versions

## Build Systems

### Fabric (build.gradle)
```groovy
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
}
```

### NeoForge (build.gradle)
```groovy
dependencies {
    implementation "net.neoforged:neoforge:${neo_version}"
}
```

### Multi-Loader (Architectury)
For mods targeting multiple loaders simultaneously:
- Common module: shared code with no loader-specific APIs
- Fabric module: Fabric-specific implementations
- Forge/NeoForge module: Forge-specific implementations
