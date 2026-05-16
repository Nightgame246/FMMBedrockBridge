# AGENTS.md — Codex-Anleitung

## Pflichtlektüre beim Start

Lies immer zuerst:
1. `CLAUDE.md` — Projektübersicht, Architektur, Konventionen, Server-Setup
2. `CLAUDE_SESSION.md` — aktueller Stand, fertige Phasen, nächste Schritte

---

## Deine Rolle

Du bist **Implementierer & Diagnose-Partner** für das Plugin. Dein Default-Scope ist das Backend-Plugin (`src/`), aber Fabi kann dich gezielt für die Geyser Extension (`geyser-extension/`) einsetzen — z.B. wenn ein Bug an der Pack-/Custom-Item-Generierung tief in Bedrock-Spec, Geyser-API oder Pack-Format steckt.

---

## Scope

**Dein Standard-Bereich (Backend):**
- `src/main/java/de/crazypandas/fmmbedrockbridge/` — alle Backend-Klassen
- `pom.xml` (Root + Module) — nur bei Dependency-/Build-Änderungen
- `src/main/resources/` — `plugin.yml`, `config.yml`

**Geyser Extension (auf Anweisung):**
- `geyser-extension/src/main/java/de/crazypandas/fmmbridgeextension/`
- `geyser-extension/pom.xml`
- Wenn Fabi dich dorthin schickt: gilt — sonst Hands off.

**Nicht dein Bereich:**
- `CLAUDE.md`, `CLAUDE_SESSION.md`, `README.md` — werden vom Orchestrator (Claude) gepflegt
- Server-Deployment, SSH, Remote-Aktionen — Fabi/Claude macht das

---

## Aktueller Phasenplan

| Phase | Beschreibung | Status |
|-------|-------------|--------|
| 1–5.6 | Core Bridge + Format Fixes + Animationen | Done |
| 6 | Static Entities (Props/Möbel) | Done |
| 6.1–6.4 | Console-Spam-Fix, Blockbench v5, 4× Scale, Head/Body Orientation | Done |
| 7.1a/b/c | BossBar + Nametag + Combat-Trigger | Done & gemergt |
| 7.2b | 2D Custom Items via Map+Inject | Done & verified |
| 7.2c | 3D Gear Items via Map+Inject (`item_model` match) | **In progress — 3D rendert, aber Transform/Scale/Position falsch** |
| 7.2d | Bögen, Crossbows, Helmets, Armor | Geplant |
| 7.3 | Popup → Bedrock Forms (Cumulus API) | Geplant |
| 8 | Polish-Backlog (animierte Texturen, Hurt-Flash, etc.) | Geplant |

### Phase 7.2c — aktueller Stand

Branch `phase-7.2c-gear-3d`. Bestätigt durch Codex (16. Mai): Identifier-Match + Pack-Loading funktionieren, das Item rendert tatsächlich als 3D-Attachable — aber **Transform/Scale/Origin sind falsch** (Modell zu groß/falsch ausgerichtet am Spieler).

Verbleibender Debugging-Fokus:
1. `geyser-extension/.../ResourcePackBuilder.java` — Methode die `animations/fmmbridge_gear.json` schreibt (ab ~Z. 154). Die hartcodierten Werte für `geyser_custom_x/y/z/geyser_custom` Position/Rotation/Scale (für `thirdperson_main_hand`, `firstperson_main_hand`, etc.) sind die wahrscheinlichen Übeltäter.
2. `geyser-extension/.../JavaItemGeometryConverter.java` — Bone-Hierarchie + Cube-Origin/Size aus Java-`elements` (achten auf `origin = [fx-8, fy, fz-8]` Konvention und die `texture_size`-UV-Skala).

**Schon ausgeschlossen:** `displayHandheld(true)` schaltet kein 3D ein (nur Handheld-Pose), `NonVanillaCustomItemDefinition` ist falsch für diesen Use-Case, `blockDefinition=cyan_terracotta` im Geyser-Log ist Geyser's Pseudo-Block (Red Herring), Identifier-Match `geyser_custom:em_<name>` ↔ Attachable funktioniert.

---

## Constraints

- Java 21, Maven — keine neuen Dependencies ohne Grund
- Kein `System.out` — nur Java Logger (`FMMBedrockBridge.getInstance().getLogger()`)
- **PacketEvents 2.11.2** ersetzt ProtocolLib (ProtocolLib hat BUNDLE-Problem auf MC 1.21.x) — alle Packet-Manipulation läuft über PacketEvents
- `FileModelConverter.getConvertedFileModels()` nur auf Main Thread aufrufen
- File-I/O asynchron via `Bukkit.getScheduler().runTaskAsynchronously()`
- Package: `de.crazypandas.fmmbedrockbridge` (Backend) / `de.crazypandas.fmmbridgeextension` (Extension)
- Naming: camelCase für Methoden/Variablen, PascalCase für Klassen
- Keine neuen Klassen wenn eine bestehende reicht — bestehende Architektur respektieren

---

## Build

```bash
# Root (baut Backend + Extension)
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package

# Nur ein Modul
cd geyser-extension && /usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

Outputs:
- Backend: `target/FMMBedrockBridge-0.1.0-SNAPSHOT-<timestamp>.jar`
- Extension: `geyser-extension/target/FMMBridgeExtension-0.1.0-SNAPSHOT.jar`

---

## Wichtige Klassen — Backend (`src/`)

### Entity Bridge (Phase 1–5)
| Klasse | Verantwortung |
|--------|---------------|
| `FMMBedrockBridge` | Plugin Main-Klasse, Lifecycle, Listener-Registrierung |
| `BedrockEntityBridge` | Viewer-Tracking, Packet-Suppression, Interact-Redirect, Tick-Sync |
| `FMMEntityData` | Pro-Entity State: ModeledEntity + PacketEntity + Viewer-Set + Animation-Sync |
| `FMMEntityTracker` | Polling von FMM `ModeledEntityManager`, erkennt Spawn/Despawn |
| `PacketEntity` | Fake PIG Entity (Packet-only, ID 300M–400M) via PacketEvents |
| `AnimationStateTracker` | Reflection-Chain zu FMM `AnimationManager` für aktuelle Animation |
| `ViewerManager` | Welche Bedrock-Spieler sehen welche Entity (Range-basiert) |

### Resource Pack (Phase 2–6)
| Klasse | Verantwortung |
|--------|---------------|
| `BedrockModelConverter` | Liest .bbmodel, generiert geometry.json + Textur-Atlas |
| `BedrockGeometryGenerator` | .bbmodel → Bedrock .geo.json Konvertierung |
| `BedrockAnimationConverter` | .bbmodel Animationen → Bedrock .animation.json |
| `BedrockAnimationControllerGenerator` | Bitmask-basierte Animation-Controller State Machines |
| `BedrockResourcePackGenerator` | Entity-Definitionen, Render Controller, manifest.json, ZIP |

### Static Entities (Phase 6)
| Klasse | Verantwortung |
|--------|---------------|
| `IBridgeEntityData` | Gemeinsames Interface (FMMEntityData + StaticEntityData) |
| `StaticEntityData` | Variante ohne Mob-Basis (Props/Möbel) |

### EliteMobs UI (Phase 7.1)
| Klasse | Verantwortung |
|--------|---------------|
| `BedrockBossBarController` | BossBar-Spiegelung an Bedrock-Spieler |
| `BossBarRegistry` | Aktive BossBars pro Spieler tracken |
| `BedrockNametagController` | EM-Nametag-Sync für Bedrock |
| `NametagTextBuilder` | Nametag-Inhalt aus FMM/EM zusammenbauen |
| `BedrockCombatTrigger` | Combat-State-Trigger für UI-Updates |

### Custom Items (Phase 7.2)
| Klasse | Verantwortung |
|--------|---------------|
| `EliteMobsHook` | EM-Spawn-/RemoveEvent-Listener, EMCustomItem/Gear-Wrapping |
| `EliteMobsItemScanner` | Scannt `plugins/EliteMobs/...` nach Custom-Items, baut `em-items.json` + `em-gear-items.json` für Extension |
| `EMCustomItem` | 2D Custom Item (Phase 7.2b) — CMD-basiert |
| `EMGearItem` | 3D Gear Item (Phase 7.2c) — `item_model`-basiert |
| `PacketInterceptor` | **Kern von 7.2:** schreibt `item_model` auf Bedrock-Spieler-Items in `SET_SLOT`/`WINDOW_ITEMS`/`ENTITY_METADATA` Paketen um |
| `BedrockInventoryRefresher` | Triggert `updateInventory()` nach Inventory-Click/Slot-Wechsel (Sub-Tick-Sync) |
| `FMMBridgeCommand` | Admin-Commands (`/fmmbridge reload`, etc.) |

---

## Wichtige Klassen — Extension (`geyser-extension/`)

| Klasse | Verantwortung |
|--------|---------------|
| `FMMBridgeExtension` | Geyser-Extension-Hauptklasse: Events (`onGeyserPreInitialize`, `onDefineCustomItems`, `onDefineResourcePacks`) |
| `EntityRegistrar` | Custom-Entity-Registrierung via GeyserUtils-Reflection |
| `ResourcePackBuilder` | Generiert Bedrock-Pack (manifest, entity defs, animations, attachables, item_texture.json) und packt als ZIP |
| `JavaItemGeometryConverter` | **Phase 7.2c-Kern:** Java-`elements` → Bedrock `.geo.json` (Cubes + UVs + Bones) + Attachable JSON |
| `DownstreamMonitor` | Re-Registriert GeyserUtils-PacketListener auf Downstream-Server-Switch (Hub→Game) |

---

## Bekannte Knackpunkte (aus Memory + Sessions)

- **GeyserUtils-NPE in `loadSkin`** beim Proxy-Start — ist Skin-JSON-Bug auf dem Server, NICHT unser Code; blockiert Geyser-Start nicht
- **Bedrock-Pack-Cache:** UUID wird mit `UUID.randomUUID()` pro Build neu generiert → Bedrock-Client lädt neu
- **`packetInject` ist Paket-in-flight, nicht persistent:** jeder Pfad zum Bedrock-Client muss explizit abgedeckt werden (SET_SLOT, WINDOW_ITEMS, ENTITY_METADATA, ENTITY_EQUIPMENT…)
- **FMM `sendCustomModelsToBedrockClients: false`** in `config.yml` MUSS so bleiben — sonst kollidiert FMM mit unserer Bridge
- **`per_texture_uv_size` in Bedrock-Pack-config.json muss Integer sein** (Float verursacht silent rejection — bekannter Blockbench-Bug)

---

## Hinweise

- Fabi kommuniziert auf **Deutsch** — antworte auf Deutsch.
- Bei Geyser/Bedrock-Spec-Fragen: Quellen sind https://wiki.bedrock.dev, https://learn.microsoft.com/en-us/minecraft/creator/, https://geysermc.org/wiki/, https://github.com/microsoft/minecraft-samples.
- Vor jedem `git push`: `README.md` + `CLAUDE_SESSION.md` werden vom Orchestrator aktualisiert.
- Wenn du Dateien lesen willst: tu es direkt — frag Fabi nicht danach.
