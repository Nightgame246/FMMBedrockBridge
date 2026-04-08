# AGENTS.md — Codex-Anleitung

## Pflichtlektüre beim Start

Lies immer zuerst:
1. `CLAUDE.md` — Projektübersicht, Architektur, Konventionen, Server-Setup
2. `CLAUDE_SESSION.md` — aktueller Stand, was bereits fertig ist, nächste Schritte

---

## Deine Rolle

Du bist **Implementierer** für den Spigot/Paper-Teil des Plugins. Du arbeitest **nur** an Dateien unter `src/` — die Geyser Extension unter `geyser-extension/` ist nicht dein Bereich.

---

## Scope

**Dein Bereich:**
- `src/main/java/de/crazypandas/fmmbedrockbridge/` — alle Java-Dateien
- `pom.xml` — nur wenn Dependencies sich ändern müssen
- `src/main/resources/` — plugin.yml, config.yml

**Nicht dein Bereich:**
- `geyser-extension/` — wird separat bearbeitet
- `CLAUDE.md`, `README.md`, `CLAUDE_SESSION.md` — werden vom Orchestrator gepflegt
- Server-Deployment, SSH, Remote-Aktionen

---

## Aktueller Phasenplan

| Phase | Beschreibung | Status |
|-------|-------------|--------|
| 1–4.5 | Entity detection, bridging, resource packs, hitbox, nametags | Done |
| 5 | Animation conversion + runtime sync | Bug (StackOverflow) |
| 5.1 | StackOverflow fix + Animation verification | Planned |
| 5.5 | Code-Modularisierung (Bridge + Extension aufsplitten) | Planned |
| 6 | Static Entities (Props/Möbel ohne underlying mob) | Planned |
| 7 | EliteMobs UI/UX (BossBar, Nametag-Verbesserung, GUIs) | Planned |
| 8 | Polish: Partikel, Config, Performance, Produktionsreife | Planned |

**Bekannter Bug Phase 5:** `GeyserUtils.registerProperties()` (Zeile 140) ruft sich selbst rekursiv auf statt `registerPropertiesForGeyser()`. Ist ein Bug in GeyserUtils, nicht in unserem Code.

---

## Constraints

- Java 21, Maven — keine neuen Dependencies ohne Grund
- Kein `System.out` — nur Java Logger (`FMMBedrockBridge.getInstance().getLogger()`)
- `FileModelConverter.getConvertedFileModels()` nur auf Main Thread aufrufen
- File-I/O asynchron via `Bukkit.getScheduler().runTaskAsynchronously()`
- Keine neuen Klassen anlegen wenn eine bestehende reicht (außer bei Modularisierung)
- Package: `de.crazypandas.fmmbedrockbridge`
- Naming: camelCase für Methoden/Variablen, PascalCase für Klassen

---

## Build

```bash
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

Output: `target/FMMBedrockBridge-<version>.jar`

---

## Wichtige Klassen

| Klasse | Verantwortung |
|--------|---------------|
| `BedrockEntityBridge` | Hauptklasse: Viewer-Tracking, Packet-Suppression, Interact-Redirect, Tick-Sync |
| `FMMEntityData` | Pro-Entity State: ModeledEntity + PacketEntity + Viewer-Set, Animation-Sync |
| `PacketEntity` | Fake PIG Entity (Packet-only, ID 300-400M) via PacketEvents |
| `AnimationStateTracker` | Reflection-Chain zu FMM AnimationManager für aktuelle Animation |
| `FMMEntityTracker` | Polling von FMM ModeledEntityManager, erkennt Spawn/Despawn |
| `BedrockModelConverter` | Liest .bbmodel, generiert geometry.json + Textur-Atlas |
| `BedrockGeometryGenerator` | .bbmodel → Bedrock .geo.json Konvertierung |
| `BedrockAnimationConverter` | .bbmodel Animationen → Bedrock .animation.json |
| `BedrockAnimationControllerGenerator` | Bitmask-basierte Animation Controller State Machines |
| `BedrockResourcePackGenerator` | Entity-Definitionen, Render Controller, manifest.json, ZIP |
