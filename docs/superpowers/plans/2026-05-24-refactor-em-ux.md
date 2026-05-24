# Refactor: Bridge zu EM-UX-Layer (2026-05-24)

## Kontext

FMM 2.6.0 + RPM 1.8.0 übernehmen Mob-Rendering, 3D-Items und Pack-Conversion nativ.
Coexistence-Test (2026-05-24) zeigt Doppel-Spawn-Konflikte:
- Mob-Models doppelt (Bridge fake-PIG + FMM nativ)
- Static-Möbel doppelt
- Nametag doppelt (Bridge + FMM)
- BossBar inkonsistent (Suppression-Race-Condition)

→ Bridge muss zur "EM-UX-Bridge" werden: nur BossBar, Combat-Nametag (HP/Bar/Name) und 2D-Items (legacy CMD-format) — alles was MagmaGuy nicht abdeckt.

## Klassen-Klassifikation

### Backend (`src/main/java/de/crazypandas/fmmbedrockbridge/`)

| Klasse | Aktion | Begründung |
|--------|--------|------------|
| `FMMBedrockBridge.java` | **strippen** | Main-Plugin: alle Mob-Pipeline-Inits raus, nur Phase 7.1 + 7.2b initialisieren |
| `tracker/FMMEntityTracker.java` | **behalten** | Findet FMM-Mobs — wird für Combat-Trigger weiter gebraucht. `onSpawnHandler` muss aber jetzt nur Controllers erstellen, kein Render. |
| `bridge/BedrockEntityBridge.java` | **strippen** | Render-Code (fake-PIG, GeyserUtils setCustomEntity) komplett raus. Übrig: Controllers verwalten (`activeControllers`, `activeNametags`), PacketInterceptor-Bind, ViewerManager-Bind, Shutdown-Cleanup. Wahrscheinlich umbenennen in `BedrockUXBridge` oder `FMMBossWatcher`. |
| `bridge/FMMEntityData.java` | **strippen** | Render-Code raus (`fakeEntity`, `addViewer`/`removeViewer` Render-Calls, `tick()`-Position-Sync, `destroyFakeEntity`). Bleibt: BossBar/Nametag-Controller-Holding. `realEntity` bleibt für Range-Check + Name-Source. |
| `bridge/IBridgeEntityData.java` | **löschen** | War nur für Polymorphismus zwischen Static + Dynamic im Render-Pfad. Nur DynamicEntity (= Mobs mit underlying entity) hat BossBar/Nametag. |
| `bridge/StaticEntityData.java` | **löschen** | Static-Props haben keine BossBar/Nametag/Combat — komplett FMM nativ. |
| `bridge/PacketEntity.java` | **löschen** | fake-PIG-Logik — Render-Pipeline weg. |
| `bridge/PacketInterceptor.java` | **strippen** | Mob-Suppression (SPAWN_ENTITY, ENTITY_METADATA für Real-Entities) raus. Java-Suppression für TextDisplay (Phase 7.1b) raus — Bedrock-Spieler kriegt jetzt FMM-native Nametag. Bleibt: 2D-Item-Inject (Phase 7.2b), BossBar-Suppression-Logik (Phase 7.1a). |
| `bridge/ViewerManager.java` | **behalten** | Bedrock-Player-Tracking + Range-Check — gebraucht für Controller-AddViewer-Logic. |
| `bridge/BedrockBossBarController.java` | **behalten** | Phase 7.1a/c |
| `bridge/BedrockNametagController.java` | **strippen oder löschen?** | Phase 7.1b/c. **Problem:** Doppel-Nametag mit FMM-nativ. **Lösung:** Combat-Layout (3 Zeilen HP/Bar/Name) bleibt, aber out-of-combat (1 Zeile) muss aus (sonst doppelt mit FMM-nativ-Name). Brauche evtl. Hide-FMM-Nametag-Logik IM Combat — komplex. Vorerst: **behalten** und im Test schauen. |
| `bridge/BedrockCombatTrigger.java` | **behalten** | Phase 7.1c |
| `bridge/BossBarRegistry.java` | **behalten** | Phase 7.1a Suppression |
| `bridge/NametagTextBuilder.java` | **behalten** | Phase 7.1b/c |
| `bridge/BedrockInventoryRefresher.java` | **behalten** | Phase 7.2c (`updateInventory()` Trigger) — gilt auch für 2D-Items |
| `bridge/EliteMobsItemScanner.java` | **strippen** | `scan3DGear()` raus, 2D-`scan()` bleibt |
| `bridge/EMCustomItem.java` | **behalten** | Record für 2D |
| `bridge/EMGearItem.java` | **löschen** | 3D-Gear-Record |
| `bridge/AnimationStateTracker.java` | **löschen** | Animation für Mob-Pipeline |
| `commands/FMMBridgeCommand.java` | **strippen** | `convert all`-Subcommand raus (war Phase 3). `debug`-Subcommand bleibt (zeigt Controllers + Bedrock-Viewer). |
| `elite/EliteMobsHook.java` | **behalten** | EM-Soft-Dep für styled name |
| `converter/BedrockAnimationControllerGenerator.java` | **löschen** | Phase 3+5 |
| `converter/BedrockAnimationConverter.java` | **löschen** | Phase 3+5 |
| `converter/BedrockGeometryGenerator.java` | **löschen** | Phase 3+5 |
| `converter/BedrockModelConverter.java` | **löschen** | Phase 3+5 |
| `converter/BedrockResourcePackGenerator.java` | **löschen** | Phase 3+5 |
| `converter/` Package | **löschen** | komplett |

### Geyser-Extension (`geyser-extension/`)

**Komplett löschen** — alle 5 Klassen + Tests + pom.xml + Submodul-Reference.

| Klasse | Begründung |
|--------|------------|
| `FMMBridgeExtension.java` | Extension-Entry-Point (war für Render + 2D/3D-Item-Registration) |
| `EntityRegistrar.java` | Custom-Entity-Registration via Reflection (Phase 1-6) |
| `DownstreamMonitor.java` | GeyserUtils Listener Re-Register (Phase 1-6) |
| `ResourcePackBuilder.java` | Pack-Generation (Phase 3+5+7.2) |
| `JavaItemGeometryConverter.java` | 3D Gear Geometry (Phase 7.2c, Codex) |

**Hinweis:** Phase 7.2b 2D-Item-Inject läuft komplett Backend-side (PacketInterceptor injectet `item_model` in WindowItems-Packets). Geyser-Extension wurde dafür NICHT gebraucht — nur für die ursprüngliche Custom-Item-Registration via `GeyserDefineCustomItemsEvent`. Mit RPM 1.8.0 + manuellen Mappings auf Proxy01 ist auch das überflüssig.

### Tests

| Test | Aktion |
|------|--------|
| `EliteMobsItemScannerTest.java` | **behalten** — 2D Scan-Tests bleiben relevant |
| `JavaItemGeometryConverterTest.java` | **löschen** mit Extension |

### Config

`src/main/resources/config.yml`:
- `entity-view-distance` → bleibt (für Range-Check Controllers)
- `converter.*` → **löschen** (war Phase 3+5)
- `phase71a.*` → bleibt
- `phase71c.*` → bleibt
- `elite-items.enabled` → bleibt
- `elite-items.resource-pack-path` → bleibt
- `elite-items.gear-3d.enabled` → **löschen** (Phase 7.2c raus)
- Neue Hinweise in Kommentaren: "Requires FMM 2.6.0+ with sendCustomModelsToBedrockClients: true and ResourcePackManager 1.8.0+"

### Maven

`pom.xml` (root):
- Submodul `<module>geyser-extension</module>` raus
- Dependencies die nur Phase 3+5 brauchten (Gson?) → behalten falls noch genutzt für 2D-Items
- Geyser API Dep raus (war für Extension)

`geyser-extension/pom.xml`:
- **löschen** (komplettes Verzeichnis raus)

### Memory Updates nach Refactor

- `project_state.md` → "Refactor durch, Bridge ist EM-UX-Layer"
- `next_implementation.md` → "Phase 7.3 (Cumulus Forms) starten"
- `MEMORY.md` → obsolete Phase-7.2c/Bedrock-Orientation-Memories raus
- `deployment_paths.md` → Extension-SCP-Pfad raus
- `bedrock_orientation_fix.md` → obsolet markieren
- `phase72c_progress.md` → obsolet markieren

### CLAUDE.md / AGENTS.md

- Architektur-Sektion neu schreiben
- "Was MagmaGuy macht vs was Bridge macht" Sektion
- Workflow nur noch Backend-Plugin

## Implementations-Reihenfolge (TaskCreate-Subtasks)

1. **Geyser-Extension komplett löschen** (isoliert, niedrig-risikant)
2. **`converter/` Package löschen** (isoliert)
3. **Backend Render-Klassen löschen**: AnimationStateTracker, IBridgeEntityData, StaticEntityData, PacketEntity, EMGearItem
4. **FMMEntityData strippen** (Render raus, Controller bleibt)
5. **BedrockEntityBridge strippen** (Render-Loop raus, Controller-Map bleibt)
6. **PacketInterceptor strippen** (Mob-Suppression raus, 2D-Inject bleibt)
7. **EliteMobsItemScanner strippen** (3D-Scan raus)
8. **FMMBedrockBridge.java strippen** (Init-Code für rausgeworfene Klassen raus, gear-3d-Logik raus)
9. **FMMBridgeCommand strippen** (convert all raus)
10. **config.yml aufräumen**
11. **pom.xml aufräumen** (Submodul raus)
12. **Build + Compile-Fix Iteration**
13. **Tests laufen**
14. **Memory + Docs Updates**
15. **Deploy + Bedrock-Test**

## Erfolgs-Kriterien

- Build grün
- Keine Compile-Errors
- Backend-Plugin started ohne Errors auf TestServer
- Bedrock-Test zeigt:
  - **Kein** Doppel-Spawn (Mobs/Static nur von FMM nativ)
  - **Kein** Doppel-Nametag (FMM zeigt 1-Zeilen Name, Bridge zeigt 3-Zeilen NUR im Combat)
  - **BossBar konsistent** styled über alle Bosse (Phase 7.1a Suppression läuft, eigene BossBar erscheint)
  - **2D Items** (BagOfCoin etc.) sichtbar in Hotbar + Inventar + Shop-GUIs
  - **3D Gear** weiterhin via RPM (Bridge mischt sich nicht ein)
