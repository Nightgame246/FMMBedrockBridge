# AGENTS.md — Codex-Anleitung

## Pflichtlektüre beim Start

Lies immer zuerst:
1. `CLAUDE.md` — Projektübersicht, Server-Setup, Konventionen
2. `CLAUDE_SESSION.md` — letzte Session-Logs (chronologisch); insbesondere "Session: 2026-05-24" (großer Architektur-Pivot)
3. `README.md` — aktuelle Architektur (post-Refactor)

---

## Großer Pivot 2026-05-24

MagmaGuy hat in **FMM 2.6.0** nativen Bedrock/Geyser-Support eingebaut, und **ResourcePackManager 1.8.0** konvertiert jeden Java-Pack zu Bedrock (3D-Items, Armor, attachable geometry 1.21.0, `bedrock_display_offsets.yml`). Dadurch wurden Phasen 1-6 + 7.2c/d unserer Bridge **redundant**.

Die alte Bridge (mit fake-PIG-Pipeline + Geyser-Extension) ist als git tag **`archive/2026-05-24-pre-rpm18-pivot`** gesichert. Aktuelle Bridge ist eine reine **EM↔Bedrock UX-Bridge**.

---

## Deine Rolle

Du bist **Implementierer & Diagnose-Partner** für das Backend-Plugin. Geyser-Extension existiert nicht mehr.

Default-Scope: `src/main/java/de/crazypandas/fmmbedrockbridge/` (Backend).

---

## Scope

**Dein Bereich:**
- `src/main/java/de/crazypandas/fmmbedrockbridge/` — alle Klassen
- `src/main/resources/` — `plugin.yml`, `config.yml`
- `pom.xml` — bei Dependency- oder Build-Änderungen
- `src/test/java/...` — JUnit-Tests

**Nicht dein Bereich:**
- `CLAUDE.md`, `CLAUDE_SESSION.md`, `README.md`, `AGENTS.md` — pflegt Claude
- Server-Deployment, SSH, Remote-Aktionen — Fabi/Claude
- `references/` — andere MagmaGuy-Repos zum Querlesen, nicht editieren

---

## Aktueller Phasenplan (post-Pivot)

| Phase | Beschreibung | Status |
|-------|-------------|--------|
| 7.1a | Combat-styled BossBar (replaces EM Vanilla "Evoker | 2") | Done & live |
| 7.1b | Combat-Nametag mit HP/Bar (FMM rendert Name separat) | Done — visueller Verify nach `modeledEntity.getDisplayName()`-Source-Fix ausstehend |
| 7.1c | Combat-Event-Trigger (`EliteMobEnterCombatEvent` → setCombatState) | Done — Issue: bei manchen Mobs feuert kein zweiter EnterCombat, Combat-Nametag verschwindet (zu untersuchen) |
| 7.2b | 2D EM UI-Items via Map+Inject (`item_model` Component-Set auf SET_SLOT/WINDOW_ITEMS/ENTITY_METADATA) | Done & verified |
| **7.3** | EM-Adventurer-Guild / Shop-GUIs als native Bedrock-Forms (Cumulus API) | **Geplant — NEUE PRIO 1** |

Mob-Rendering, Animationen, 3D-Items, Static-Props → **FMM 2.6.0 + RPM 1.8.0 nativ**, nicht mehr Bridge-Zuständigkeit.

---

## Constraints

- **Java 21, Maven** — keine neuen Dependencies ohne klare Notwendigkeit
- **Kein `System.out`** — nur Java Logger (`FMMBedrockBridge.getInstance().getLogger()`) oder `FMMBedrockBridge.debugLog()`
- **PacketEvents 2.12.1+** ersetzt ProtocolLib (BUNDLE-Problem auf MC 1.21.x)
- **Package:** `de.crazypandas.fmmbedrockbridge`
- **Naming:** camelCase Methoden/Variablen, PascalCase Klassen
- **Server-Config gating:** `sendCustomModelsToBedrockClients: true` in FMM-Config ist die NEUE Erwartung (ab 2026-05-24). FMM kümmert sich um Mob-Render.
- **Keine fake-PIG-Pipeline mehr** — das war Phase 1-6, ist obsolet

---

## Build

```bash
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
# Output: target/FMMBedrockBridge-<version>.jar (~54 KB)
```

Kein Submodul mehr — `geyser-extension/` ist gelöscht.

---

## Aktuelle Klassen (16 total)

### Plugin-Kern
| Klasse | Verantwortung |
|--------|---------------|
| `FMMBedrockBridge` | Main: lifecycle, dep checks, controller wire-up |
| `commands/FMMBridgeCommand` | `/fmmbridge debug` (zeigt active Controllers + ready Bedrock-Spieler) |
| `tracker/FMMEntityTracker` | Pollt `ModeledEntityManager.getAllEntities()` jede Sekunde |

### Bridge / Controllers
| Klasse | Verantwortung |
|--------|---------------|
| `bridge/BedrockEntityBridge` | Hält `entityDataMap`, BossBar-Map, Nametag-Map, ViewerManager, per-tick sync |
| `bridge/FMMEntityData` | Pro-Mob-Container: BossBar+Nametag-Controller, viewers-Set, kein Render |
| `bridge/ViewerManager` | Bedrock-Player-Tracking via Floodgate, Range-Check |
| `bridge/PacketInterceptor` | PacketEvents-Listener: BossBar suppress, Java-TextDisplay suppress, 2D-item_model inject |

### Phase 7.1 (BossBar + Nametag)
| Klasse | Verantwortung |
|--------|---------------|
| `bridge/BedrockBossBarController` | Per-Boss Bukkit-BossBar, addViewer/cleanup, combat-state |
| `bridge/BedrockNametagController` | Per-Mob TextDisplay, combat-state, position/text-tick |
| `bridge/BedrockCombatTrigger` | Bukkit-Listener: `EliteMob{Enter,Exit}CombatEvent` → setCombatState |
| `bridge/BossBarRegistry` | UUID-Set: erfasste EM-BossBars für Suppression |
| `bridge/NametagTextBuilder` | Pure-Util: out-of-combat empty, in-combat HP+Bar (kein Name — FMM rendert Name) |
| `elite/EliteMobsHook` | Soft-dep wrapper, einziger Import von `com.magmaguy.elitemobs.*` |

### Phase 7.2b (2D UI-Items)
| Klasse | Verantwortung |
|--------|---------------|
| `bridge/EliteMobsItemScanner` | Scannt EM-Resource-Pack nach legacy `custom_model_data` overrides |
| `bridge/EMCustomItem` | Record: javaMaterial, customModelData, texturePath, bedrockKey |
| `bridge/BedrockInventoryRefresher` | Bukkit-Listener: `updateInventory()` 1 Tick nach Bedrock-Spieler Click/Drag/Hotbar-Switch (forciert WINDOW_ITEMS-Resend) |

---

## Bekannte Knackpunkte

### EliteMobs API
- **EM 10.3.1 EVOKER-basierte CustomBosses** (z.B. Ice Elemental) liefern aus **beiden** API-Pfaden ("Evoker | 2"):
  - `livingEntity.getCustomName()` ← Vanilla-Format
  - `eliteEntity.getName()` ← Vanilla-Format
  - **Lösung:** `modeledEntity.getDisplayName()` (FMM-API) liefert den korrekten YAML-Namen — gleiche Source-Priorität wie für den Nametag. Wird in `FMMEntityData.createBossBarControllerIfElite()` als primary verwendet, `EliteMobsHook.getStyledName()` als Fallback.
- **`EliteMobEnterCombatEvent` feuert nicht zuverlässig wieder** nach Combat-Pause — Untersuchung steht aus (eventuell eigene Damage-Heuristik statt EM-Event)

### PacketEvents
- BOSS_EVENT-Suppression läuft auf Netty-IO-Thread, nicht Bukkit-Main-Thread → ThreadLocal-Bypass funktioniert nicht
- **Lösung:** First-Match-Heuristik in `PacketInterceptor.handleBossEvent` — erste title-matchende ADD per Controller ist unsere, spätere sind EM-Duplikate

### FMM-Config
- `sendCustomModelsToBedrockClients: true` ist Pflicht ab FMM 2.6.0 (vorher war `false` Pflicht — alter Stand)

### Multi-Host Pack-Transfer
- RPM generiert Bedrock-Pack auf Backend-Server (`plugins/ResourcePackManager/output/`)
- Geyser läuft auf Proxy → Pack muss manuell rüber (`Geyser-Velocity/packs/` + `custom_mappings/`)
- RPM warnt "Geyser installation not detected" — normal in Multi-Host, ignorieren

---

## Hinweise

- **Fabi kommuniziert auf Deutsch** — antworte auf Deutsch
- **Server-Memory:** [memory] Dateien im Claude-Memory-Folder enthalten kuratierten Kontext zu Project-State, Workflows, bekannten Issues
- **Tests laufen lassen** vor Deploy: `mvn package` führt JUnit-Tests aus
- **Vor `git push`:** `README.md` + `CLAUDE_SESSION.md` werden vom Orchestrator (Claude) aktualisiert
- **Quellen für Bedrock-Spec-Fragen:** https://wiki.bedrock.dev, https://learn.microsoft.com/en-us/minecraft/creator/, https://geysermc.org/wiki/
- **Alte Architektur einsehen:** `git checkout archive/2026-05-24-pre-rpm18-pivot` (read-only, danach zurück auf aktuelle Branch)
