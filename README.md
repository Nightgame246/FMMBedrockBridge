# FMMBedrockBridge

A Spigot/Paper plugin that adds the **EliteMobs UX layer** (combat-styled BossBar, HP/Bar combat-nametag) for Bedrock clients connected via [Geyser](https://geysermc.org/) — features that [FreeMinecraftModels (FMM) 2.6.0](https://github.com/MagmaGuy/FreeMinecraftModels) and [ResourcePackManager 2.0.2](https://github.com/MagmaGuy/ResourcePackManager) don't cover natively.

## Background

As of **FMM 2.6.0** (May 2026), MagmaGuy added native Geyser/Floodgate support — Bedrock players see FMM custom mob models, animations, and static props directly. **ResourcePackManager 2.0.0** converts any plugin's resource pack to Bedrock format, emits Geyser custom-item mappings (3D weapons/armor, flipbook textures, attachable display offsets), and runs in **Network-Mode**: a Velocity/Bungee sub-plugin polls each backend over HTTP, merges per-network packs, and delivers them to Geyser sessions — no manual scp between backend and proxy any more.

Previous versions of this plugin (Phases 1-6, 7.2c/d) built a parallel mob/item pipeline using fake PIG entities + GeyserUtils + a custom Geyser Extension. That work is **archived under git tag `archive/2026-05-24-pre-rpm18-pivot`** — refer to it if you need the old Bridge approach for FMM <2.6.0 servers.

The current plugin is a focused **EM↔Bedrock UX-Bridge**.

## What this plugin does

| Feature | Why it exists |
|---------|---------------|
| **Phase 7.1a/c — Styled Combat BossBar** | EM-managed Bukkit BossBar with the YAML-styled name (e.g. "Tier 13 Eis-Elementar") instead of the Vanilla "Evoker | 2" Geyser would otherwise show on Bedrock. EM's own BOSS_EVENT packets are suppressed for Bedrock players; our bar is identified by its wire UUID, read reflectively, because EliteMobs 10.8.0 pools and re-titles up to four bars per player and an ordering heuristic can no longer tell them apart. |
| **Phase 7.1b/c — Combat Nametag** | Bukkit TextDisplay above bridged mobs showing HP-number / health-bar (combat-only, 2 lines above FMM's native name). Java players see only FMM's native nametag (packet-suppress for our TextDisplay). **This does not duplicate EliteMobs' own overhead health display** — verified in-game on 2026-08-16: EM's `EliteOverheadHealthDisplay` (`displayVisualHealthBars` / `displayNumericHealth`) reaches Java only and never arrives on Bedrock, while this overlay is suppressed for Java. The two serve disjoint client groups; do not "deduplicate" them by disabling either side. |
| **Phase 7.3 — Bedrock Menu Dialog-Reroute** | EM forces Bedrock players to the `/em` chest menu (a bare container grid on Bedrock) even though it already builds the same menu as a native MC dialog for Java 1.21.6+. Geyser now renders MC dialogs as native Bedrock forms, so the bridge cancels the Bedrock chest and triggers EM's `showPlayerStatusDialog` — Bedrock gets a real form, sub-pages cascade natively. Reroute-only (no form-building); registry-extensible to other EM menus. Requires MC ≥ 1.21.6. |
| **Phase 7.3b — Bedrock NPC Quest-Menu Dialog-Reroute** | Extends Phase 7.3 to EliteMobs' NPC quest menu — the only other Bedrock-forced-to-chest EM menu with a native dialog path (`QuestMenu.generateDialogMenu`). Detection is holder-based (looks the opened chest up in EM's internal `QuestInventoryMenu` maps via reflection) because quest chest titles are dynamic (single-quest title = quest name, multi-quest = literal `"Quests"`). On a hit the chest is cancelled and EM's quest dialog fires next tick → Geyser renders a native Bedrock form. Status-vs-quest precedence + per-flag gating live in `RerouteDecision`; recovered quest context is carried opaquely in `QuestMenuContext`. Config: `phase73.bedrock-quest-reroute: true`. Requires MC ≥ 1.21.6. |

That's it. No mob rendering, no animation conversion, no 3D item conversion — those are FMM + RPM's job now.

## Requirements

**Backend Server (Paper/Spigot 1.21.x **or** 26.x):**
- [FreeMinecraftModels](https://github.com/MagmaGuy/FreeMinecraftModels) **2.6.0+** with `sendCustomModelsToBedrockClients: true` in its `config.yml`
- [ResourcePackManager](https://github.com/MagmaGuy/ResourcePackManager) **2.0.0+** (generates the Bedrock pack, serves it to the proxy via embedded HTTP server)
- [EliteMobs](https://github.com/MagmaGuy/EliteMobs) (optional — required for BossBar replacement and dialog-reroute). **10.8.0+ recommended:** it pools and re-titles up to four boss bars per player, which the BossBar suppression is built around.
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
> `26.2.build.112-stable`. Version gates must tolerate the non-numeric trailing segments —
> `McVersions` does, and there are tests for it. A gate that does not will silently disable
> features rather than fail loudly.

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

phase71b:
  nametag-enabled: true      # false = drop our combat HP overlay. EliteMobs renders an
                             # equivalent one itself (MobCombatSettings.yml:
                             # displayVisualHealthBars / displayNumericHealth), so turn one
                             # of the two off to avoid showing health twice.
                             # Does NOT affect the BossBar.

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
| `bridge/BossBarRegistry` | EliteMobs BossBar UUIDs currently suppressed. Membership is temporary — since EM 10.8.0 these are pooled bars that get re-titled, so entries are evicted on REMOVE or on reuse for a title we don't own |
| `bridge/BossBarUuidResolver` | Reads our own bar's wire UUID off the Bukkit BossBar reflectively, so EM's pooled bars can't be mistaken for ours. Returns null on any failure → legacy heuristic |
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

## Phase 7.4 — Bedrock-Eingabe für EliteMobs' Klassen-Fähigkeiten

EliteMobs 10.9.0 bindet die drei Fähigkeits-Slots seines Advanced Combat System an einen
F-Chord (`F,F` / `F+LMB` / `F+RMB`). `F` ist der Offhand-Tausch — **den gibt es auf Bedrock
nicht**, weder auf Controller noch auf Touch. Bedrock- und Konsolenspieler können dort ohne
diese Phase keine einzige aktive Fähigkeit auslösen.

Die Bridge übersetzt den Chord auf Schleichen:

| Geste | Fähigkeit |
|---|---|
| Schleichen, Schleichen | Mobility |
| Schleichen + Angriff | Signature |
| Schleichen + Benutzen | Utility |

Scharf ist die Steuerung **nur im Kampf bzw. in Dungeons**, damit normales Schleichen beim
Bauen nichts auslöst. Java-Spieler sind nicht betroffen und behalten EMs Originalsteuerung.

**Voraussetzungen:** EliteMobs **10.9.0+** mit eingeschaltetem Advanced Combat System und
Floodgate. Fehlt eines davon, schaltet sich die Phase beim Start selbst ab und schreibt den
Grund ins Log — die übrige Bridge läuft normal weiter.

Konfiguration: Block `phase74` in der `config.yml`. Der Startwert für `chord-max-ticks` (40 =
2 s) ist bewusst großzügiger als EMs 12 Ticks und im Spieltest zu justieren.

- Design: `docs/specs/2026-09-11-bedrock-ability-input-design.md`
- Upstream gemeldet: `docs/upstream-bugs/em-advanced-combat-bedrock-input-lockout.md`

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
