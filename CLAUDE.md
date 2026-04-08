# CLAUDE.md — FMM Bedrock Bridge Plugin

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

## Server-Setup des Betreibers (Fabi)

### Infrastruktur:
- **Proxy:** Velocity 3.5.0-SNAPSHOT auf Hetzner Dedicated Server
- **Backend-Server:** Mehrere Paper-Server verwaltet über AMP
- **Geyser:** Auf dem Velocity Proxy (nicht auf Backend-Servern)
- **Floodgate:** Auf Proxy UND Backend-Servern
- **GeyserUtils:** Auf Backend-Servern als Spigot Plugin, auf Proxy als Geyser Extension

### Relevante Pfade (AMP):
```
/home/amp/.ampdata/instances/
├── Proxy01/Minecraft/
│   ├── plugins/Geyser-Velocity/
│   │   ├── extensions/          # Geyser Extensions (GeyserModelEngineExtension, GeyserUtils)
│   │   ├── packs/               # Bedrock Resource Packs die an Clients geschickt werden
│   │   └── config.yml           # force-resource-packs: true, enable-integrated-pack: true
│   └── logs/latest.log
├── Survival01/Minecraft/
│   ├── plugins/
│   │   ├── FreeMinecraftModels/
│   │   │   ├── imports/         # .bbmodel Input
│   │   │   ├── models/          # .fmmodel Output
│   │   │   └── output/          # Java Resource Pack
│   │   ├── EliteMobs/           # Nutzt FMM für Custom Boss Models
│   │   ├── GeyserModelEngine-1.0.6.jar  # Nur für ModelEngine, NICHT FMM
│   │   └── geyserutils-spigot-1.0-SNAPSHOT.jar
│   └── logs/latest.log
└── TestServer01/Minecraft/
    ├── plugins/
    │   ├── FreeMinecraftModels/  # Version 2.3.17
    │   ├── ModelEngine/          # R4.1.0 (separat von FMM)
    │   ├── EliteMobs/           # Version 9.6.3
    │   ├── MythicMobs/          # 5.10.1-SNAPSHOT
    │   ├── GeyserModelEngine-1.0.3.jar
    │   └── geyserutils-spigot-1.0-SNAPSHOT.jar
    └── logs/latest.log
```

### Wichtige Erkenntnisse:
- **PacketEvents wurde vom Proxy entfernt** — verursachte Disconnects mit 1.21.11
- **Snap Plugin wurde entfernt** — inkompatibel mit Velocity
- **GeyserModelEngine funktioniert NUR mit ModelEngine (Ticxo)**, nicht mit FMM
- EliteMobs-Bosse nutzen FMM, nicht ModelEngine, für Custom Models
- Bedrock-Spieler sehen das Basis-Mob (z.B. Wolf) statt des Custom Models

## Entwicklungsschritte

### Phase 1: Analyse & Proof of Concept
1. FMM Quellcode klonen und API-Events identifizieren
2. Verstehen wie FMM Display Entities spawnt und tracked
3. Minimales Plugin bauen das FMM Entity Spawns loggt
4. GeyserUtils API verstehen (wie Custom Entities für Bedrock registriert werden)

### Phase 2: Entity Bridging
1. FMM Entity Spawn Events abfangen
2. Für jeden Bedrock-Spieler (via Floodgate) die entsprechende Bedrock Custom Entity spawnen
3. Position/Rotation synchronisieren
4. Entity Lifecycle managen (Spawn/Despawn/Move)

### Phase 3: Resource Pack Konvertierung
1. Tool/Code schreiben der FMM's Java Models ins Bedrock .geo.json Format konvertiert
2. Entity Definitionen, Render Controller, Animation Controller generieren
3. Bedrock Resource Pack (.mcpack/.zip) automatisch generieren
4. Pack in Geyser packs/ Ordner integrieren

### Phase 4: Animation Support
1. FMM Animationen nach Bedrock Animation Format übersetzen
2. Animation States synchronisieren (idle, walk, attack, death)
3. Animation Controller für State Machines generieren

### Phase 5: Integration & Polish
1. Config-Datei für Admins (enable/disable, pack-output-path, etc.)
2. Auto-Reload bei FMM Model Changes
3. Performance-Optimierung (nur Bedrock-Spieler in Reichweite)
4. Kompatibilität mit EliteMobs verifizieren

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

## Coding-Konventionen

- **Sprache:** Java 21
- **Build:** Maven
- **Naming:** camelCase für Methoden/Variablen, PascalCase für Klassen
- **Package:** `de.crazypandas.fmmbedrockbridge`
- **Keine Shade von FMM** — als provided dependency
- **Async wo möglich** — FMM läuft selbst größtenteils async
- **Logging:** Java Logger, kein System.out
- **Config:** YAML via Bukkit Config API

## Dependencies (pom.xml Vorlage)

```xml
<repositories>
    <repository>
        <id>magmaguy-repo</id>
        <url>https://repo.magmaguy.com/releases</url>
    </repository>
    <repository>
        <id>opencollab</id>
        <url>https://repo.opencollab.dev/main/</url>
    </repository>
    <repository>
        <id>papermc</id>
        <url>https://repo.papermc.io/repository/maven-public/</url>
    </repository>
</repositories>

<dependencies>
    <!-- Paper API -->
    <dependency>
        <groupId>io.papermc.paper</groupId>
        <artifactId>paper-api</artifactId>
        <version>1.21.4-R0.1-SNAPSHOT</version>
        <scope>provided</scope>
    </dependency>
    <!-- FreeMinecraftModels -->
    <dependency>
        <groupId>com.magmaguy</groupId>
        <artifactId>FreeMinecraftModels</artifactId>
        <version>2.3.17</version>
        <scope>provided</scope>
    </dependency>
    <!-- Floodgate API -->
    <dependency>
        <groupId>org.geysermc.floodgate</groupId>
        <artifactId>api</artifactId>
        <version>2.2.3-SNAPSHOT</version>
        <scope>provided</scope>
    </dependency>
    <!-- GeyserUtils (muss ggf. lokal installiert werden) -->
</dependencies>
```

## Nützliche Links

- **FMM Source:** https://github.com/MagmaGuy/FreeMinecraftModels
- **FMM Wiki:** https://nightbreak.io/plugin/freeminecraftmodels/
- **GeyserUtils:** https://github.com/zimzaza4/GeyserUtils
- **GeyserModelEngine (Referenz):** https://github.com/zimzaza4/GeyserModelEngine
- **Geyser Custom Entity API:** https://geysermc.org/wiki/geyser/custom-entities/
- **Bedrock Entity Docs:** https://learn.microsoft.com/en-us/minecraft/creator/reference/content/addonsreference/
- **Blockbench Bedrock Format:** https://www.blockbench.net/wiki/guides/bedrock-modeling

## Hinweise für Claude Code

- Der Betreiber (Fabi) kommuniziert auf Deutsch
- Server läuft unter dem `amp` User auf Debian Bookworm (Hetzner Dedicated)
- SSH-Zugang: `amp@mc.crazypandas.de` mit `~/.ssh/id_ed25519` (bereits eingerichtet)
- Vor jeder Remote-Aktion den User fragen — nicht selbstständig auf dem Server handeln
- Teste nie mit root, immer als `amp` User
- Der Proxy (Velocity) und Backend-Server (Paper) sind separate Prozesse
- GeyserUtils braucht sowohl ein Spigot-Plugin (Backend) als auch eine Geyser Extension (Proxy)
- FMM ist GPL-3.0 — alle abgeleiteten Werke müssen ebenfalls GPL-3.0 sein
- Existierende GeyserModelEngine-Dateien auf dem Server können als Referenz für das Bedrock-Format dienen (Pfad: `/home/amp/.ampdata/instances/Proxy01/Minecraft/plugins/Geyser-Velocity/extensions/geysermodelengineextension/input_backup/`)
- **Vor jedem git push:** `README.md` und `CLAUDE_SESSION.md` aktualisieren (Status-Tabelle, neue Klassen, Deployment-Schritte, Session-Fortschritt)

## Bekannte Probleme & Erkenntnisse (aus Implementierung)

### FMM Config
- `sendCustomModelsToBedrockClients: false` in `plugins/FreeMinecraftModels/config.yml` **MUSS so bleiben**
- Mit `true`: FMM aktiviert eigene (kaputte) Bedrock-Implementierung → Vanilla Wolf verschwindet komplett
- Mit `false`: Bedrock-Spieler sehen das Basis-Mob (Wolf) — das ist der korrekte Ausgangszustand für unsere Bridge

### GeyserUtils — Downstream Listener Problem (gelöst)
- `GeyserUtils.registerPacketListener()` wird in `onSessionJoin` mit 80ms Delay aufgerufen
- In Multi-Server-Setup (Hub → TestServer01): Listener landet auf Hub-Session-Objekt
- **Fix:** Downstream-Monitor in FMMBridgeExtension re-registriert den Listener periodisch

### GeyserUtils — setCustomEntity Timing (gelöst)
- Spawn-Paket kommt sofort, `setCustomEntity` muss VORHER im Cache sein
- **Fix:** Fake-Entity-Ansatz — eigenes PIG-Entity spawnen mit 2-Tick Delay nach setCustomEntity
- Cache hat 30s Expiry, wird pro Bedrock-Spieler befüllt

### GeyserUtils — Classloader (gelöst)
- Reflection mit `GeyserExtensionClassLoader` findet GeyserUtils korrekt
- `addCustomEntity` via Reflection erfolgreich → 188 Entities in `LOADED_ENTITY_DEFINITIONS`

### Geyser API
- `GeyserPreInitializeEvent` → für Entity-Registrierung und Pack-Generierung (korrekt)
- `GeyserDefineResourcePacksEvent` → `event.register(ResourcePack.create(PackCodec.path(zipPath)))` (korrekt)
- `GeyserDefineEntityPropertiesEvent` ist NICHT in der öffentlichen API (2.7.0-SNAPSHOT) — nur intern in GeyserUtils

### PacketEvents (aktiv genutzt)
- packetevents 2.11.2 auf TestServer01 installiert
- Ersetzt ProtocolLib komplett (ProtocolLib hat BUNDLE-Problem auf MC 1.21.x)
- Packet-Suppressor: blockiert SPAWN_ENTITY + ENTITY_METADATA für versteckte Real-Entities
- Interact-Redirect: INTERACT_ENTITY Pakete von Fake→Real Entity ID umschreiben
- PacketEntity: Fake PIG-Entity Spawn/Teleport/Destroy via PacketEvents
