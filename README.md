# FMMBedrockBridge

A Spigot/Paper plugin that adds the **EliteMobs UX layer** (combat-styled BossBar, one name above modelled bosses, class abilities and menus) for Bedrock clients connected via [Geyser](https://geysermc.org/) — features that [FreeMinecraftModels (FMM) 2.6.0](https://github.com/MagmaGuy/FreeMinecraftModels) and [ResourcePackManager 2.0.2](https://github.com/MagmaGuy/ResourcePackManager) don't cover natively.

## Background

As of **FMM 2.6.0** (May 2026), MagmaGuy added native Geyser/Floodgate support — Bedrock players see FMM custom mob models, animations, and static props directly. **ResourcePackManager 2.0.0** converts any plugin's resource pack to Bedrock format, emits Geyser custom-item mappings (3D weapons/armor, flipbook textures, attachable display offsets), and runs in **Network-Mode**: a Velocity/Bungee sub-plugin polls each backend over HTTP, merges per-network packs, and delivers them to Geyser sessions — no manual scp between backend and proxy any more.

Previous versions of this plugin (Phases 1-6, 7.2c/d) built a parallel mob/item pipeline using fake PIG entities + GeyserUtils + a custom Geyser Extension. That work is **archived under git tag `archive/2026-05-24-pre-rpm18-pivot`** — refer to it if you need the old Bridge approach for FMM <2.6.0 servers.

The current plugin is a focused **EM↔Bedrock UX-Bridge**.

## What this plugin does

| Feature | Why it exists |
|---------|---------------|
| **Phase 7.1a/c — Styled Combat BossBar** | EM-managed Bukkit BossBar with the YAML-styled name (e.g. "Tier 13 Eis-Elementar") instead of the Vanilla "Evoker | 2" Geyser would otherwise show on Bedrock. EM's own BOSS_EVENT packets are suppressed for Bedrock players; our bar is identified by its wire UUID, read reflectively, because EliteMobs 10.8.0 pools and re-titles up to four bars per player and an ordering heuristic can no longer tell them apart. |
| ~~**Phase 7.1b — Combat Nametag**~~ | **Removed on 2026-10-01.** A Bukkit TextDisplay showing HP above bridged mobs for Bedrock only. EliteMobs' own overhead health display (`displayVisualHealthBars` / `displayNumericHealth`) reaches Bedrock clients now — an A/B test on TestServer01 showed health twice with both on, once with ours off. History: git before the removal commit. |
| **Phase 7.7 — EliteMobs menu icons on Bedrock** | EliteMobs puts its menu icons (`elitemobs:ui/*`, `nightbreak:ui/*`) on emerald, redstone, barrier or banners and draws them via `item_model`. Geyser maps custom items per base item and RPM only registers these icons under paper (redstone and banners Geyser cannot override at all), so Bedrock showed the vanilla items. For Bedrock viewers the bridge rewrites the base item to paper in container `WINDOW_ITEMS`/`SET_SLOT` packets, keeping all components — RPM's existing mapping applies. Player inventory and cursor are never touched. Config: `phase77.bedrock-menu-icons` (+ prefix/exclude lists). Upstream draft: `docs/upstream-bugs/em-menu-icons-paper-base-bedrock.md`. |
| **Phase 7.6 — EliteMobs menu backgrounds on Bedrock** | EliteMobs draws its menu backgrounds as font glyphs in the chest title (`U+F0E00`–`U+F0F0B`, outside the BMP), which Bedrock shows as boxes over a bare grey chest. For Bedrock viewers the bridge rewrites the title in `OPEN_WINDOW` to invisible BMP markers (`U+E900 + n`, plus `U+E8FF` = hide grey slot cells); the generated pack `FMMBridge-EliteMobsMenus.mcpack` (in Geyser's `packs/`) shows the matching background aligned to the slot grid. Pack built by `tools/bedrock-menus/generate.py` from RPM's merged Java pack — textures never enter this repo. Config: `phase76.bedrock-menu-backgrounds`, `phase76.hide-slot-backgrounds`. Classic UI (PC/console). |
| ~~**Phase 7.3 / 7.3b / 7.3c — Menu Dialog-Reroute**~~ | **Removed on 2026-10-25.** The bridge rerouted EliteMobs' `/em` status, NPC quest and class menus from the Bedrock chest to EM's native dialog (Geyser renders it as a Bedrock form). EliteMobs 10.9.10 does this itself: set `allowDialogMenusForBedrockPlayers: true` in `plugins/EliteMobs/config.yml`. An A/B test on TestServer01 showed EM's own forms complete for all three menus, without the chest flashing up first. **Without that option Bedrock players get chests again.** History: git before the removal commit. |
| ~~**Phase 7.4 — Bedrock ability input**~~ | **Removed on 2026-10-30.** Sneak-based input for EliteMobs' Advanced Combat abilities, whose F-chord Bedrock cannot produce. EliteMobs 10.9.12 ships its own Bedrock controls (sneak opens the ability window; attack/use/jump; `/em class controls` outside EM content), built after our upstream report and console test. History: git before the removal commit. |

That's it. No mob rendering, no animation conversion, no 3D item conversion — those are FMM + RPM's job now.

## Requirements

**Backend Server (Paper/Spigot 1.21.x **or** 26.x):**
- [FreeMinecraftModels](https://github.com/MagmaGuy/FreeMinecraftModels) **2.6.0+** with `sendCustomModelsToBedrockClients: true` in its `config.yml`
- [ResourcePackManager](https://github.com/MagmaGuy/ResourcePackManager) **2.0.0+** (generates the Bedrock pack, serves it to the proxy via embedded HTTP server)
- [EliteMobs](https://github.com/MagmaGuy/EliteMobs) (optional — required for BossBar replacement and the class abilities; for Bedrock menus as forms set EM's own `allowDialogMenusForBedrockPlayers: true`, 10.9.10+). **10.8.0+ recommended:** it pools and re-titles up to four boss bars per player, which the BossBar suppression is built around.
- [Floodgate](https://github.com/GeyserMC/Floodgate) (required for Bedrock player detection — its `key.pem` also derives the RPM Network-Mode key)
- [PacketEvents](https://github.com/retrooper/packetevents) **2.12.1+** on 1.21.x, **2.13.0+** on 26.x (required for packet manipulation)

> **Minecraft 26.x:** Mojang replaced the `1.x` scheme with year-based versions in 2026 — there
> is no 1.22, the line runs 1.21.11 → **26.1** → **26.2**. The plugin supports both generations
> from a single jar (see Build). Server-side, MC 26.2 additionally requires **Java 25** and, for
> Bedrock, **Geyser 2.11+** together with **RPM 2.3.0+**.
>
> Verified in production on **Paper 26.2 (build 112)** with FMM 2.11.1, EliteMobs 10.8.0,
> ResourcePackManager 2.3.1, Geyser 2.11.1 and PacketEvents 2.13.0.
>
> Note that on 26.x `Bukkit.getBukkitVersion()` returns a build/channel string such as
> `26.2.build.112-stable`. Version gates must tolerate the non-numeric trailing segments — a
> gate that does not will silently disable features rather than fail loudly. (The bridge's own
> gate, `McVersions`, left with Phase 7.3 on 2026-10-25.)

**Proxy (Velocity/BungeeCord):**
- [Geyser](https://geysermc.org/)
- [Floodgate](https://github.com/GeyserMC/Floodgate)
- `ResourcePackManager-Velocity.jar` (or `-BungeeCord.jar`) — RPM 2.0.0 polls each backend, merges packs network-wide, and ships them to Bedrock sessions. On a co-located proxy the backend auto-extracts the right jar into the proxy's `plugins/`; on a separate proxy host you extract it manually (see Deploy below). **From RPM 2.3.0 on this is obsolete:** the backend jar is a universal jar carrying both `plugin.yml` and `velocity-plugin.json` — copy the same file to the proxy. From 2.3.1 it also installs its own Geyser extension via Geyser's `extensions/update/` queue, so **do not** hand-copy `ResourcePackManager-GeyserBridge.jar` any more; restart the proxy twice instead (first boot stages, second loads).

## Build

Requires **JDK 25** and Maven. The jar itself targets Java 21 bytecode and runs on both Java 21
and 25 — but *compiling* needs 25, because Paper's 26.2 API ships class file version 69, which
javac 21 cannot read at all.

```bash
mvn clean package
# Output: target/FMMBedrockBridge-<version>.jar
```

One jar serves both Minecraft generations. That is not something a single compile can prove, so
the same sources are compiled twice — against 26.2 and against 1.21.10:

```bash
bash verify-both-apis.sh
```

Both runs must pass, and the script also asserts the emitted bytecode is version 65 (Java 21).
Only API present in *both* generations may be used; anything 26.x-only needs a reflective guard.

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
  resolve-own-bossbar-uuid: true  # read our bar's wire UUID reflectively (see below).
                                  # false = legacy "first title match is ours" heuristic.
                                  # Symptom of a mis-resolved UUID: Bedrock sees NO bar at all.


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
| `bridge/BedrockEntityBridge` | Holds the BossBar controller map, `entityDataMap`, per-tick sync |
| `bridge/FMMEntityData` | Per-mob holder for the BossBar controller and the 7.1d name fix (no rendering — FMM does that) |
| `bridge/ViewerManager` | Bedrock player tracking, range checks; detection delegated to `BedrockDetection` |
| `bridge/BedrockDetection` | Pure "is this a Bedrock player" decision, mirroring EliteMobs' own `BedrockChecker` order (Floodgate UUID → name pattern → Floodgate → Geyser). Kept in step with EM on purpose: EM's `fLayerSupported()` asks the same question, and a disagreement leaves the player with no input at all |
| `bridge/PacketInterceptor` | PacketEvents listener: BossBar suppress, Java-TextDisplay suppress |
| `bridge/BedrockBossBarController` | Bukkit BossBar lifecycle per boss × Bedrock viewer |
| `bridge/BedrockCombatTrigger` | Bukkit listener: forwards `EliteMobEnterCombatEvent` / `ExitCombatEvent` to controllers |
| `bridge/BossBarRegistry` | EliteMobs BossBar UUIDs currently suppressed. Membership is temporary — since EM 10.8.0 these are pooled bars that get re-titled, so entries are evicted on REMOVE or on reuse for a title we don't own |
| `bridge/BossBarUuidResolver` | Reads our own bar's wire UUID off the Bukkit BossBar reflectively, so EM's pooled bars can't be mistaken for ours. Returns null on any failure → legacy heuristic |
| `bridge/BedrockNameFix` | Phase 7.1d: hides the custom name of the mob underneath an FMM-modelled boss for Bedrock viewers when the model carries a real name — FMM draws its own nameplate for Bedrock too, so Bedrock otherwise shows two names (and the mob's is EliteMobs' generic "Evoker"). `phase71d.bedrock-name-fix` |
| `bridge/BedrockMenuIcons` | Phase 7.7: pure rule — which items (by `item_model` prefix, minus excludes, not already paper) are rebased onto paper for Bedrock |
| `bridge/MenuIconRebaser` | Phase 7.7: applies the rule to a PacketEvents `ItemStack` — base item paper, same amount and component patches |
| `bridge/BedrockMenuTitle` | Phase 7.6: pure title rewrite — EliteMobs background glyph → Bedrock marker (`U+E900 + (cp − U+F0E00)`), spacing glyphs and other plane-15/16 characters removed; shares its test cases with the Python generator (`src/test/resources/bedrock-menus/cases.json`) |
| `elite/EliteMobsHook` | Soft-dep wrapper around EliteMobs' stable API (`com.magmaguy.elitemobs.api.*`); since Phase 7.1c not the only EM importer — `BedrockCombatTrigger` imports EM too |
| `commands/FMMBridgeCommand` | `/fmmbridge debug` — shows active controllers, ready Bedrock players, suppressed UUIDs |

17 classes under `src/main/java`. The pre-refactor bridge was 27 classes + a Geyser Extension; both archived under the git tag mentioned above.

> **Phase 7.2b removed (2026-06-14):** EM 2D UI items (legacy `custom_model_data` overrides on `minecraft:emerald` and similar base items) are now handled natively by ResourcePackManager 2.0.2 via `GenericJavaScanner.scanLegacyCustomModelOverrides` (legacy `→` Bedrock conversion, 10 of 12 EM UI icons). The bridge no longer injects `item_model` or generates/ships an `em_bridge_pack.mcpack`. Known Bedrock/Geyser limitation: the 2 banner-based icons (`green_banner`+CMD31173→`boxinput`, `red_banner`+CMD31173→`boxoutput`, used in EM's enchantment/"Verzauberer" and elite-scroll menus) do **not** render on Bedrock — Geyser cannot apply custom-item-v2 to banner base items (block-entity/pattern-rendered). This is a known upstream-pending gap; see `docs/upstream-bugs/em-banner-ui-items-bedrock.md`.

## ~~Phase 7.4 — Bedrock-Eingabe für EliteMobs' Klassen-Fähigkeiten~~ (ausgebaut am 30.10.2026)

EliteMobs 10.9.0 band die Klassen-Fähigkeiten an einen F-Chord, den Bedrock nicht erzeugen kann; die Bridge
übersetzte ihn von September bis Oktober auf Schleichen (+ Angriff/Benutzen/Hotbar-Taste). Nach unserem
Upstream-Report (`docs/upstream-bugs/em-advanced-combat-bedrock-input-lockout.md`) und einem Konsolentest macht
**EliteMobs 10.9.12** das selbst: Ducken öffnet für 1 s das Fähigkeitsfenster, Angriff = Signature,
Benutzen = Utility, **Springen = Mobility** (unser Vorschlag), Hotbar 1/2/3 bzw. 7/8/9; außerhalb von EM-Inhalten
schaltet `/em class controls` die Steuerung ein. Ein alter `phase74:`-Block in der Config wird ignoriert.
Design der alten Phase: `docs/specs/2026-09-11-bedrock-ability-input-design.md`. Historie: git vor dem Ausbau-Commit.

## Server tooling

Moved out of this repo on 2026-09-09. The admin helpers for the AMP host now live one level up,
in the workspace, because they serve every plugin and not just this one:

- **`../server-tools/`** — `plugin-update-check.sh`, `backup-testserver.sh`, `check-invsee.sh`,
  `server-CLAUDE.md` (→ `~/.claude/CLAUDE.md` on the host), and **`SERVER-STATE.md`**, the shared
  working file with the Claude instance running on the server.
- **`../CLAUDE.md`** — the operational rules that used to be duplicated here: SSH access, the
  coupled Geyser/RPM/FMM/EliteMobs set, the twice-restart rule after an RPM update, download
  source precedence, and why Bedrock rendering can only be verified in-game.

## License

GPL-3.0 — compatible with FreeMinecraftModels.
