# CLAUDE.md — FMMBedrockBridge

> ⚠️ **Diese Datei enthält nur noch, was NUR dieses Plugin angeht.**
> Rolle & Arbeitsweise, Server-Infrastruktur, SSH- und Deploy-Regeln, der geprüfte
> Versions-Satz, Coding-Konventionen und der Multi-PC-Workflow stehen eine Ebene höher in
> **`../CLAUDE.md`** (Workspace-weit) — beide werden geladen.
> **Claude Code aus `codeing/minecraft/` starten, nicht aus diesem Ordner.**

**Lizenz:** GPL-3.0 — FMM ist GPL-3.0, alle abgeleiteten Werke müssen es ebenfalls sein.
**Package:** `de.crazypandas.fmmbedrockbridge`

## Projektübersicht

Dieses Projekt ist ein Spigot/Paper-Plugin (Java 21, Minecraft 1.21.x) das als Bridge zwischen **FreeMinecraftModels (FMM)** und **Geyser/Bedrock** fungiert. Ziel: Custom 3D Models die FMM auf Java-Clients über Display Entities anzeigt, sollen auch für Bedrock-Clients sichtbar werden.

**Projektname:** `FMMBedrockBridge`
**Lizenz:** GPL-3.0 (kompatibel mit FMM)
**Sprache:** Java 21
**Build:** Maven
**Ziel-MC-Version:** 1.21.x (Paper/Spigot)

## Problemstellung

FreeMinecraftModels (FMM) zeigt Custom Models in Minecraft Java über Display Entities (1.19.4+) und Armor Stands (ältere Clients). Bedrock-Clients die über Geyser verbunden sind, sehen diese Models **nicht** — sie sehen nur das Basis-Mob (z.B. einen Wolf statt eines Custom Boss-Models).

Das existierende Plugin "GeyserModelEngine" hooked nur in **ModelEngine (Ticxo)**, nicht in FMM. FMM hat Bedrock-Support als "planned feature" gelistet, aber noch nicht implementiert.

## Architektur & Datenfluss

### Aktueller Datenfluss (nur Java):
```
FMM (Backend-Server)
  → Liest .bbmodel/.fmmodel aus imports/
  → Generiert Java Resource Pack (output/)
  → Spawnt Display Entities (Java 1.19.4+) oder Armor Stands (ältere Clients)
  → Java-Client sieht Custom Model via Resource Pack
```

### Gewünschter Datenfluss (mit Bridge):
```
FMM (Backend-Server)
  → Spawnt Display Entities/Armor Stands (Java)
  → FMMBedrockBridge erkennt FMM-Entity-Spawn
  → Prüft via Floodgate API ob Spieler Bedrock ist
  → Wenn Bedrock: Sendet Bedrock Custom Entity via GeyserUtils API
  → Bedrock-Client sieht Custom Entity mit Bedrock Resource Pack

Separat (Einmalig/Beim Build):
  → .bbmodel/.fmmodel → Konvertierung → Bedrock Resource Pack (.mcpack)
  → Pack wird in Geyser packs/ Ordner gelegt
  → Bedrock-Clients laden das Pack automatisch beim Joinen
```

## Technische Komponenten

### 1. FMM Event Listener
FMM feuert Events wenn Models gespawnt/entfernt werden. Relevante Events:
- `ModeledEntitySpawnEvent` oder ähnlich (FMM API prüfen)
- Alternativ: FMM's `DynamicEntity`, `StaticEntity`, `PropEntity` APIs überwachen

**FMM Source Code:** https://github.com/MagmaGuy/FreeMinecraftModels (GPL-3.0)
**FMM Maven:**
```xml
<repository>
    <id>magmaguy-repo-releases</id>
    <url>https://repo.magmaguy.com/releases</url>
</repository>
<dependency>
    <groupId>com.magmaguy</groupId>
    <artifactId>FreeMinecraftModels</artifactId>
    <version>2.3.17</version>
    <scope>provided</scope>
</dependency>
```

### 2. Floodgate Integration
Prüfen ob ein Spieler ein Bedrock-Client ist:
```java
import org.geysermc.floodgate.api.FloodgateApi;
boolean isBedrock = FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
```

**Floodgate Maven:**
```xml
<repository>
    <id>opencollab-releases</id>
    <url>https://repo.opencollab.dev/main/</url>
</repository>
<dependency>
    <groupId>org.geysermc.floodgate</groupId>
    <artifactId>api</artifactId>
    <version>2.2.3-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

### 3. GeyserUtils Integration
GeyserUtils ermöglicht es, Custom Entities auf Bedrock-Clients zu spawnen. Das Backend-Plugin (geyserutils-spigot) kommuniziert mit der Geyser Extension auf dem Proxy.

**GeyserUtils Source:** https://github.com/zimzaza4/GeyserUtils
**Funktionsweise:**
- Backend-Server registriert Custom Entity Definitionen
- Wenn ein Bedrock-Spieler in Reichweite ist, wird die Entity über Plugin Messaging an den Proxy/Geyser gesendet
- Geyser spawnt die Bedrock Custom Entity für den Client

### 4. Bedrock Resource Pack Generator
Das Java Resource Pack von FMM muss ins Bedrock-Format konvertiert werden:

**Java Format (FMM output):**
- `assets/freeminecraftmodels/models/` → JSON Model Dateien
- `assets/freeminecraftmodels/textures/` → PNG Texturen
- `pack.mcmeta` → Pack Metadata

**Bedrock Format (benötigt):**
- `models/entity/` → .geo.json (Bedrock Geometry Format)
- `textures/entity/` → PNG Texturen
- `entity/` → Entity Definition JSON
- `animation_controllers/` → Animation Controller JSON
- `animations/` → Animation JSON
- `render_controllers/` → Render Controller JSON
- `manifest.json` → Pack Metadata

Die Konvertierung muss folgendes leisten:
- Java Model JSON → Bedrock .geo.json (Geometry)
- Java Animationen → Bedrock Animation Format
- Entity Definitionen generieren
- Render Controller generieren
- manifest.json generieren

**Referenz für das Bedrock-Format:** Der bestehende GeyserModelEngineBlockbenchPacker (Blockbench Plugin) macht eine ähnliche Konvertierung. Die generierten Dateien im `geysermodelengineextension/input/` Ordner auf dem Proxy zeigen das erwartete Format.

**Wichtig:** `per_texture_uv_size` Werte in config.json müssen **Integer** sein, keine Floats! (Bekannter Bug im BlockbenchPacker)

## FMM Interna (aus README & Source)

### Model-Typen:
- **StaticEntity** — temporäre Dekorationen, bewegen sich nicht
- **DynamicEntity** — basiert auf Living Entity (für Bosse/Pets)
- **PropEntity** — persistent, überlebt Server-Restarts

### FMM Tricks:
- Models werden 4x skaliert, dann in Code zurückskaliert (erweitert max Model-Größe)
- Resource Pack Models gehen von -16 bis +32, werden im Hintergrund verschoben
- Leather Horse Armor auf Head-Slot des Armor Stands für Farbsteuerung
- Jeder Bone = separate Entity (Performance-relevant!)
- Bedrock-Clients bekommen Armor Stands, Java 1.19.4+ bekommt Display Entities

### Virtual Bones:
- `hitbox` — definiert Hitbox-Grenzen
- `tag_` Prefix — Nametag Position
- `h_` Prefix — Kopf-Rotation (folgt Entity Head Rotation)

## Nützliche Links

- **FMM Source:** https://github.com/MagmaGuy/FreeMinecraftModels
- **FMM Wiki:** https://nightbreak.io/plugin/freeminecraftmodels/
- **GeyserUtils:** https://github.com/zimzaza4/GeyserUtils
- **GeyserModelEngine (Referenz):** https://github.com/zimzaza4/GeyserModelEngine
- **Geyser Custom Entity API:** https://geysermc.org/wiki/geyser/custom-entities/
- **Bedrock Entity Docs:** https://learn.microsoft.com/en-us/minecraft/creator/reference/content/addonsreference/
- **Blockbench Bedrock Format:** https://www.blockbench.net/wiki/guides/bedrock-modeling

## Bridge-spezifische Merker

- Existierende GeyserModelEngine-Dateien auf dem Server dienen als Referenz für das
  Bedrock-Format: `Proxy01/Minecraft/plugins/Geyser-Velocity/extensions/geysermodelengineextension/input_backup/`
- **Vor jedem git push:** `README.md` und `CLAUDE_SESSION.md` aktualisieren (Status-Tabelle,
  neue Klassen, Deployment-Schritte, Session-Fortschritt).
- Die ursprünglichen „Entwicklungsschritte Phase 1–5" standen hier bis 09.09.2026 und sind
  durch den Architektur-Pivot vom 24.05.2026 gegenstandslos geworden — Phasen 1–6 wurden
  entfernt. Historie: git tag `archive/2026-05-24-pre-rpm18-pivot`.

## Architektur-Pivot 2026-05-24 + RPM 2.0.0 Upgrade 2026-05-28

**Wichtig:** FMM 2.6.0 + ResourcePackManager 2.0.0 übernehmen die Mob/Item-Render-Pipeline nativ. Phasen 1-6 + 7.2c/d wurden in einem Refactor entfernt (git tag `archive/2026-05-24-pre-rpm18-pivot` sichert den alten Stand).

Aktuelle Bridge-Verantwortung: **EM↔Bedrock UX-Layer** — Combat-styled BossBar, Combat-Nametag (HP/Bar), 2D legacy UI-Items. Mob-Rendering, Animationen, 3D-Items, Static-Props laufen nativ über FMM 2.6.0 + RPM 2.0.0 Network-Mode.

## Bekannte Probleme & Erkenntnisse

### FMM Config (post-pivot)
- `sendCustomModelsToBedrockClients: true` in `plugins/FreeMinecraftModels/config.yml` ist die NEUE Erwartung (ab FMM 2.6.0). FMM rendert Mobs nativ für Bedrock-Clients.
- (Historisch: vor FMM 2.6.0 war `false` Pflicht — die alte Bridge übernahm dann das Rendering. Siehe `archive/2026-05-24-pre-rpm18-pivot` tag.)

### ResourcePackManager 2.0.0 — Network-Mode (ab 2026-05-28)
- Multi-Module: Backend-JAR (`plugins/ResourcePackManager.jar`) auf Paper, **Velocity-Sub-JAR** (`ResourcePackManager-Velocity.jar`) auf Proxy
- **Multi-Host-Setup-Quirk (bis 2.2.2):** Backend extrahiert die Velocity-JAR beim ersten Boot nach `plugins/ResourcePackManager/proxy-extension/` — bei separatem Proxy-Host muss man `unzip -j ResourcePackManager.jar proxy-extension/ResourcePackManager-Velocity.jar` ausführen und auf Proxy/plugins/ legen (Bukkit-JAR auf Velocity wird mit "appears to be a Paper/Bukkit plugin" abgelehnt)
- **Ab 2.3.0 hinfällig — die Backend-JAR ist eine Universal-JAR:** sie enthält `plugin.yml` **und** `velocity-plugin.json` (beide 2.3.0). Man kopiert schlicht dieselbe JAR auf den Proxy. Ein `proxy-extension/`-Ordner existiert in der JAR nicht mehr, nur noch `geyser-extension/`. Verifiziert 2026-08-02 (Proxy bootet damit sauber).
- **Network-Mode aktiviert sich automatisch** wenn Backend Velocity detected (`paper-global.yml proxies.velocity.enabled`). Backend serviert pack/mappings auf `MC-Port + networkHttpOffset-v2` (default `+1`) via `PackHttpServer`, Proxy pollt alle 5s mit If-Modified-Since
- **Network-Key auto-derived** aus `plugins/floodgate/key.pem` (Floodgate-Hash) — kein Paste nötig
- Bedrock-Pack-Delivery: Proxy mergt alle Backends per `BedrockMappingsMerger` und sendet via `GeyserBinder` direkt an Geyser-Session — kein manueller scp mehr nötig
- Fixe gegenüber 1.8.0: 80-Zeichen-Pfad-Warnings weg (SHA-256 hex prefixes), `bedrockConverterDebug: false` default (weniger Spam), Multi-Host detection sauber
- Diagnose: `/rspm status` auf Backend UND Proxy zeigt deploy-mode + key + pack-state
- **RPM-Update auf dem Proxy — ab 2.3.1 NUR NOCH EINE DATEI.** ~~Früher mussten beide Seiten von Hand getauscht werden (Plugin-JAR **und** `Geyser-Velocity/extensions/ResourcePackManager-GeyserBridge.jar`).~~ **Ab 2.3.1 ist das Mitkopieren der Extension falsch und geht schief** — es gibt keine neue `…GeyserBridge.jar`; die einzige vorhandene ist die alte 2.3.0. Verifiziert am 14.08.2026 am Proxy-Log (Server-Claude). Richtiges Verfahren:
  1. **Nur** `plugins/ResourcePackManager.jar` tauschen (Universal-JAR, bit-identisch mit der Backend-JAR). Die alte `…GeyserBridge.jar` liegen lassen, nicht löschen — wird sie nicht überschrieben, bleibt wenigstens eine funktionierende 2.3.0.
  2. **Proxy ZWEIMAL neu starten.** RPM 2.3.1 installiert die Bridge selbst (`GeyserBridgeInstaller`/`GeyserDeployer`) und legt die Universal-JAR in Geysers `extensions/update/`-Queue. Der erste Boot schreibt, der zweite lädt.
  3. Kontrolle: `Erweiterung ResourcePackManagerGeyserBridge aktiviert` + `bridge ready with <n>` bzw. `RSPM Geyser bridge health: version=2.3.1 loadedDefinitions=<n>` mit **n > 0**. Danach liegt in `extensions/` die **`ResourcePackManager.jar` (~6 MB)** statt der alten 375-KB-Bridge.
  - **⚠️ Diagnose-Falle:** `loadedDefinitions=0` **nach dem ersten** der beiden Neustarts ist **normal** (Definitionen wurden fertig, nachdem Geysers Registrierungsfenster zu waren, und sind für den nächsten Start gesichert). Erst wenn es **nach dem zweiten** noch 0 ist, fehlen die Property-Schemas.
- ~~Offen bei MagmaGuy melden: schwarze Schatten auf Custom Models (RPM-Visual-Bug)~~ — **von MagmaGuy gefixt** (2026-08-08). Entwurf bleibt als Beleg unter `docs/upstream-bugs/rpm-black-shadows-custom-models.md`, ist aber als erledigt markiert.
- ~~**Case-Sensitivity-Bug lebt weiter (RPM 2.3.0):** Das Velocity-Plugin schreibt nach `plugins/resourcepackmanager/…` (klein), die Geyser-Extension liest aus `plugins/ResourcePackManager/…` (groß). Auf Linux → `bridge ready with 0`, Bedrock-Models ohne Animationen. Symlink `ln -s resourcepackmanager ResourcePackManager` bleibt Pflicht.~~ — **von MagmaGuy gefixt in RPM 2.3.1** (2026-08-13, verifiziert am Quellcode): `RspmGeyserBridgeCore.BEDROCK_PACK_PATHS` probiert jetzt beide Schreibweisen durch, mit Kommentar *„Velocity's default data directory is lowercase"*. **Der Symlink ist am 16.08.2026 entfernt und der Wegfall live verifiziert** — Proxy-Boot 17:51 ohne Symlink: `Preloaded 316 … from …/plugins/`**`resourcepackmanager`**`/work/merged/Bedrock.zip`, `loadedDefinitions=316`. Der Workaround ist damit endgültig Geschichte; **bei einem Downgrade auf ≤ 2.3.0 muss er zurück.** Belege am Artefakt statt am GitHub-Master (Lehre „Commit ≠ Artefakt"): `javap` auf die laufende `RspmGeyserBridgeCore.class` zeigt `BEDROCK_PACK_PATHS` als `List.of` dreier Kandidaten — Kleinschreibung **zuerst**, dann zweimal Großschreibung; und im gesamten `geyserbridge`-Package konstruiert **nur diese eine Klasse** Pfade. Entwurf bleibt als Beleg unter `docs/upstream-bugs/rpm-geyser-bridge-case-sensitive-pack-path.md`, ist aber als erledigt markiert.

### FMM 2.10.2 — Props erscheinen auf Bedrock als Schwein (offen, upstream)

- **Symptom:** PropEntity/StaticEntity rendern für Bedrock als **Schwein**, DynamicEntity (Mobs/EM-Bosse) korrekt. Java sieht alles richtig.
- Das Schwein ist FMMs **Träger-Entity**: `BedrockModeledEntity` nutzt für den Fake-Entity-Pfad `carrierEntityType(EntityType.PIG)`; DynamicEntity bindet stattdessen den echten Mob (`bindToUnderlyingEntity`). Schwein = „Custom-Entity-Zuordnung hat nicht gegriffen".
- **Ausgeschlossen** (2026-08-08 verifiziert): Java-Seite nimmt den richtigen Bedrock-Zweig (Debug-Log via `/fmm debug bedrock on` — die Fallback-Zeilen fehlen alle); Pack vollständig (alle 315 bbmodels haben Entity-Defs, 316 registriert); Zuordnung kommt nicht „zu spät", sondern **gar nicht** an (die Extension-Warnungen `loggedLateEntityReplacement` / `warnedUnregisteredSpawnDefinition` feuern nie).
- **Verdacht:** `prepareEntitySpawn` geht im Fake-Entity-Pfad verloren — entweder stumm geschluckt in `runBridgeSafely(...)` oder übersprungen im `pluginProvider`-Early-Return von `FakeCustomEntityImpl.displayTo`.
- Nicht in der Bridge fixbar. Report-Entwurf: `docs/upstream-bugs/fmm-props-render-as-pig-carrier-on-bedrock.md`
- Diagnose-Werkzeug: `/fmm debug bedrock on|off` (Log-Stream `[FMM-BedrockDebug]`, sehr gesprächig — wieder ausschalten!)

### GeyserUtils 1.0-SNAPSHOT (2026-01-11) — loadSkin NPE
- `loadSkin()` (`GeyserUtils.java:384-403`) iteriert über Skin-Ordner und **überschreibt** `geometryFile` für **jede** `.json` — wenn mehrere JSONs im Ordner liegen, gewinnt die filesystem-abhängig zuletzt zurückgegebene → wenn das keine valide Bedrock-geometry ist, NPE auf `.get("minecraft:geometry").getAsJsonArray()`
- **Fix:** In `Geyser-Velocity/extensions/geyserutils/skins/*/` darf nur EINE .json liegen (`geometry.json`). Alte Bridge-generierte Reste (`model-config.json`, `animations.json`, `animation_controllers.json`) löschen — siehe Aufräum-Befehl in `CLAUDE_SESSION.md` 2026-05-28
- Upstream (zimzaza4/GeyserUtils) hat seit 2026-01-11 keine Updates — Bug bleibt bestehen
- **⚠️ Die Proxy-Extension ist seit 2026-08-08 DEAKTIVIERT** (`geyserutils-geyser-1.0-SNAPSHOT.jar.disabled-20260808-geyser2111`). Der Feb-2026-Build ist gegen Geyser-API 2.4.1 gebaut und greift auf `Registries.ENTITY_DEFINITIONS` zu — ab **Geyser 2.11.1** entfernt. Da GeyserUtils Geysers AddEntity-Translator **ersetzt**, starb damit jedes Entity-Spawn: Bedrock sah netzwerkweit **gar keine Entities**. Die Bridge nutzt GeyserUtils post-Pivot nicht mehr, FMM/RPM rendern nativ. Falls je wieder gebraucht: Upstream-Commit `9dc686a` (12.07.2026, „Update to Geyser API 2.11.0") neu bauen.

### EliteMobs 10.3.1 — styled Name für EVOKER-Bosses
- Für EVOKER-basierte CustomBosses (Ice Elemental etc.) liefern BEIDE `livingEntity.getCustomName()` UND `eliteEntity.getName()` "Evoker | 2" statt des YAML-`name:`-Werts
- **Lösung:** `modeledEntity.getDisplayName()` (FMM-API) liefert den korrekten YAML-Namen — gleiche Source wie der Java-Mob-Nametag
- In `FMMEntityData.createBossBarControllerIfElite()` ist FMM-displayName primary, `EliteMobsHook.getStyledName()` Fallback

### PacketEvents
- packetevents 2.12.1 auf TestServer01 installiert
- Ersetzt ProtocolLib komplett (ProtocolLib hat BUNDLE-Problem auf MC 1.21.x)
- BOSS_EVENT-Suppression läuft auf Netty-IO-Thread, nicht Bukkit-Main-Thread → ThreadLocal-Bypass funktioniert nicht; Lösung ist First-Match-Heuristik (siehe `PacketInterceptor.handleBossEvent`)
### Phase 7.2b — bridge_em Namespace (removed 2026-06-14)

Historisch: Bridge injizierte `item_model = bridge_em:<key>` für EM-2D-UI-Items + generierte eigenes `em_bridge_pack.mcpack` + Geyser-Mappings. **Entfernt**, weil RPM 2.0.2 diese Items jetzt nativ konvertiert (`scanLegacyCustomModelOverrides`). Siehe CLAUDE_SESSION 2026-06-14 für Details.
