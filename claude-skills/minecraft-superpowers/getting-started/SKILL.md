---
name: getting-started
description: "Overview of all Minecraft Superpowers skills — scan triggers here to find which Minecraft skills to load for any task involving Spigot, Paper, Geyser, Bedrock, resource packs, mod porting, or server admin"
---

# Minecraft Superpowers — Getting Started

You have Minecraft Superpowers. These are specialized skills for Minecraft development that extend the base Superpowers framework.

## How Skills Work

Before starting ANY Minecraft-related task, scan the skill descriptions below and load the relevant SKILL.md file(s). If a skill applies to your task, you MUST use it. Multiple skills can apply simultaneously.

## Available Minecraft Skills

### plugin-development
**Triggers:** Spigot, Paper, Velocity, Bukkit, BungeeCord, plugin.yml, pom.xml with Paper/Spigot dependencies, any mention of Minecraft plugin development, NMS, CraftBukkit, event listeners, commands, permissions
**Use for:** Creating new plugins, modifying existing plugins, understanding Bukkit/Paper/Velocity APIs, Maven/Gradle setup, plugin.yml configuration, database integration, PlaceholderAPI, Vault, LuckPerms hooks

### mod-porting  
**Triggers:** Forge, Fabric, NeoForge, Quilt, mod porting, version migration, mappings (Mojang, Yarn, SRG, Intermediary), Mixin, registry changes, breaking changes between MC versions, updating mods
**Use for:** Porting mods between Minecraft versions, migrating between mod loaders (Forge→Fabric, Forge→NeoForge), updating registries, fixing mapping changes, understanding version-specific API differences

### geyser-bridge-development
**Triggers:** Geyser, Floodgate, Bedrock, crossplay, GeyserUtils, Bedrock resource pack, bedrock models, Java→Bedrock, custom entities on Bedrock, Display Entities to Bedrock, FreeMinecraftModels + Bedrock, ModelEngine + Bedrock
**Use for:** Building bridge plugins that make Java features work on Bedrock via Geyser, GeyserUtils API, Floodgate player detection, Bedrock entity/model translation, custom item definitions for Bedrock

### resourcepack-conversion
**Triggers:** Resource pack, texture pack, .mcpack, Java resource pack to Bedrock, model conversion, .geo.json, Blockbench, bbmodel, custom model data, item models, Bedrock geometry format
**Use for:** Converting Java resource packs to Bedrock format, generating Bedrock geometry from Java models, atlas/texture management, pack.mcmeta vs manifest.json, animation format differences

### minecraft-debugging
**Triggers:** Server crash, plugin error, stack trace, ClassNotFoundException, NoSuchMethodError, player disconnect, TPS lag, chunk loading, packet errors, version incompatibility, NMS mismatch, dependency conflict
**Use for:** Diagnosing server crashes, plugin conflicts, version mismatches, performance issues, packet-level debugging, log analysis, identifying incompatible plugin combinations

### minecraft-server-admin
**Triggers:** Velocity config, server.properties, spigot.yml, paper.yml, Pterodactyl, AMP, server setup, proxy configuration, MySQL/MariaDB for plugins, backup, DriveBackupV2, Cloudflare tunnel, reverse proxy
**Use for:** Server configuration, proxy setup, database configuration, backup strategies, performance tuning, security hardening, multi-server networks

## Activation Rules

1. **Always check skills before acting** — read the triggers above and load matching skills
2. **Multiple skills can apply** — a Geyser bridge plugin needs both `plugin-development` AND `geyser-bridge-development`
3. **Debugging always adds `minecraft-debugging`** — any error/crash investigation loads this skill alongside the relevant domain skill
4. **When uncertain, load the skill** — it's better to read a skill and find it partially relevant than to miss critical guidance
5. **Skills complement Superpowers** — use brainstorming, TDD, and planning from base Superpowers alongside these Minecraft skills
