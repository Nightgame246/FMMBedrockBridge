# Claude Session State

## Session: 2026-03-29

### Abgeschlossen
- **Phase 1:** FMMEntityTracker — Polling via ModeledEntityManager.getAllEntities() (20 ticks), erkennt Spawn/Despawn von DynamicEntity/StaticEntity
- **Phase 2 PoC:** BedrockEntityBridge — sendet `minecraft:wolf` Placeholder via GeyserUtils.setCustomEntity() an Bedrock-Spieler; auf TestServer bestätigt (Bedrock-Client sieht Wolf mit korrektem Namen)

### Erkenntnisse
- FMM hat keine Spawn/Despawn Events → Polling nötig
- ModeledEntity.underlyingEntity ist protected → Reflection-Zugriff
- GeyserUtils muss lokal gebaut werden (kein Maven-Repo)
- EliteMobs: EliteMobSpawnEvent / EliteMobRemoveEvent vorhanden (für Phase 2 Optimierung)

---

## Session: 2026-03-30

### Abgeschlossen
- **Phase 3:** Konverter vollständig implementiert
  - `BedrockModelConverter` — liest .bbmodel, generiert geometry.json + texture.png
  - `BedrockGeometryGenerator` — Java Model JSON → Bedrock .geo.json
  - `BedrockResourcePackGenerator` — Entity-Definitionen, Render Controller, manifest.json, ZIP
  - `FMMBridgeExtension` (Geyser Extension) — scannt input/, registriert Entities, serviert Resource Pack
  - `/fmmbridge convert all` Kommando — konvertiert alle 188 FMM-Modelle

---

## Session: 2026-03-31

### Abgeschlossen
- **Classloader-Problem gelöst:** Reflection mit `GeyserExtensionClassLoader` findet GeyserUtils korrekt
- **Geyser Extension funktioniert:** 188 Entities registriert, Resource Pack generiert
- **Downstream-Monitor:** Re-registriert GeyserUtils Packet-Listener bei Server-Switches
- **ProtocolLib-Versuch:** SPAWN_ENTITY feuert nicht in MC 1.21.x (BUNDLE-Wrapping) → verworfen

---

## Session: 2026-04-02

### Abgeschlossen
- **Komplett-Refactor auf PacketEvents:** ProtocolLib entfernt, packetevents 2.11.2 für alle Packet-Interception
- **Fake-Entity-Bridge:** PacketEntity (fake PIG, ID 300-400M) mit GeyserUtils setCustomEntity
- **Packet-Suppressor:** SPAWN_ENTITY + ENTITY_METADATA für versteckte Real-Entities blockiert
- **Multi-Textur Atlas:** Texturen vertikal gestapelt, UV V-Offset pro Textur-Slot
- **Hitbox-Fix:** Nutzt Real-Entity-Dimensionen statt minimal 0.01f
- **Material-Fix:** `entity_alphatest_change_color_one_sided` (wie GeyserModelEngine Referenz)
- **Interact-Redirect:** Angriffe auf Fake-Entity werden per PacketEvents zum Real-Entity umgeleitet
- **visible_bounds:** Dynamisch aus Cube-Koordinaten berechnet statt hardcoded 4x4
- **Faces ohne Textur:** texture=null Faces werden übersprungen
- **Duplicate Pack entfernt:** FMMBridgePack.zip aus packs/ gelöscht

### Noch zu testen (nächste Session)
- Material-Fix: Behebt das die "Redstone-Block"-Artefakte?
- Interact-Redirect: Können Bedrock-Spieler Mobs angreifen?
- Hitbox-Größe: Passt die Real-Entity-Dimension?
- Textur-Qualität: Sieht das Model jetzt korrekt aus?

### Offene Themen
- Nametags / EliteMobs UI (eigene Phase)
- Animationen (Phase 5: idle, walk, attack, death)

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-04-03

### Abgeschlossen
- **Model Scale Fix:** `scripts.scale: "1.6"` in Bedrock Entity-Definitionen (config: `converter.model-scale`)
- **Proxy Lag Fix:** `logCacheState()` (Reflection jede Sekunde) entfernt, Downstream-Monitor von 1s auf 5s
- **Texture Atlas Quality:** Atlas wird jetzt in nativer Textur-Auflösung gebaut statt UV-Auflösung
- **UV Seam Fix:** UV-Koordinaten nach Skalierung auf Integer gerundet (`Math.round()`) — verhindert Linien-Artefakte
- **Nametags:** Custom Names werden als Entity Metadata über PacketEvents gesendet
  - Liest `realEntity.customName()` (EliteMobs) mit Fallback auf `modeledEntity.getDisplayName()` (FMM)
  - Retry nach 20 Ticks falls Name beim Spawn noch nicht gesetzt
  - Kein kontinuierlicher Sync (verursachte Lag durch Component.equals()-Problem)
- **model-config.json:** Pro Model gespeichert, Geyser Extension liest Scale daraus

### Getestet & Bestätigt
- Nametags zeigen korrekt über Mobs (z.B. "[13] Eis-Elementar", "[3] Wilder Alphawolf")
- Models rendern korrekt auf Bedrock (Wolf, Ice Elemental)
- Kein Lag nach Entfernung des kontinuierlichen Name-Sync
- UV-Linien-Artefakte behoben

### Bekannte Einschränkungen
- **BossBar** zeigt bei manchen Mobs den Vanilla-Typ (z.B. "Evoker | 2") — kommt von EliteMobs, nicht unser Plugin
- **Partikel/Projektile** werden von Geyser anders übersetzt (z.B. Schneebälle statt Rauch)
- **Animationen** fehlen noch (statische Pose auf Bedrock)

### Offene Themen
- Animationen (Phase 5)
- BossBar-Interception (Polish-Phase)
- Partikel-Interception (Polish-Phase)
- Statische Entities (Props/Möbel)

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-04-03 (Fortsetzung)

### Abgeschlossen
- **Phase 5: Animation Support implementiert**
  - `BedrockAnimationConverter` — liest .bbmodel animations, konvertiert Keyframes zu Bedrock .animation.json
    - Channels: rotation, position, scale
    - Loop-Modi: loop, once, hold_on_last_frame
    - Interpolation: linear, catmullrom (step/bezier als linear)
  - `BedrockAnimationControllerGenerator` — generiert State Machine Controller
    - Bitmask-Ansatz (wie GeyserModelEngine): query.property('fmmbridge:anim0') / 2^N
    - Ein Controller pro Animation mit play/stop States und blend_transition: 0.1
  - `AnimationStateTracker` — pollt FMM AnimationManager.current per Reflection
    - Chain: ModeledEntity → animationComponent → animationManager → current → getType()
    - Erkennt: IDLE, WALK, ATTACK, DEATH, SPAWN, CUSTOM
  - **Runtime Sync**: FMMEntityData.syncAnimation() sendet GeyserUtils IntProperty bei State-Änderung
  - **Converter**: generiert animations.json + animation_controllers.json pro Model
  - **Resource Pack**: Entity-Definitionen enthalten animation/controller-Referenzen
  - **Geyser Extension**: kopiert Animation-Dateien ins generierte Pack

### Deployment
- Beide JARs deployed (Spigot + Geyser Extension)
- `/fmmbridge convert all` erfolgreich — Animationen erkannt (z.B. 4 für Wolf: idle, walk, attack, death)
- Skins inkl. Animationen zum Proxy kopiert
- **Noch zu testen**: Proxy neustarten und auf Bedrock verifizieren

### Bekannter Bug
- **StackOverflowError** beim Bedrock-Client Spawn nach registerProperty/sendIntProperty
  - Proxy-Log: `java.lang.StackOverflowError` bei Nightgame2272 Verbindung
  - Wahrscheinlich: GeyserUtils registerProperty/sendIntProperty löst Rekursion aus, oder zu viele Plugin Messages gleichzeitig
  - Muss nächste Session untersucht werden

### Offene Themen
- **StackOverflowError fixen** (Animation Property Registration)
- BossBar-Interception (Polish-Phase)
- Partikel-Interception (Polish-Phase)
- Statische Entities (Props/Möbel)

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-04-08

### Analyse & Planung

**StackOverflowError Root Cause gefunden:**
- `GeyserUtils.registerProperties()` (Zeile 140) ruft sich selbst auf statt `registerPropertiesForGeyser()`
- Das ist ein Bug in GeyserUtils, nicht in unserem Code
- Stacktrace aus Proxy-Log `2026-04-03-7.log.gz` bestätigt: reine Selbst-Rekursion

**Neuer Phasenplan (Ansatz B: Bug-Fix → Modularisierung → Features → Polish):**

| Phase | Beschreibung | Status |
|-------|-------------|--------|
| 5.1 | StackOverflow fix + Animation verification | Planned |
| 5.5 | Code-Modularisierung (Bridge + Extension aufsplitten) | Planned |
| 6 | Static Entities (Props/Möbel ohne underlying mob) | Planned |
| 7 | EliteMobs UI/UX (BossBar, Nametag-Verbesserung, GUIs) | Planned |
| 8 | Polish: Partikel, Config, Performance, Produktionsreife | Planned |

**Multi-AI Strategie:**
- Claude (Hauptrolle): Planung, Architektur, kritische Implementierung
- Gemini (großer Context): GeyserUtils Quellcode-Analyse
- Codex: Parallelisierbare Refactoring-Tasks (Phase 5.5)

### Implementiert (Phase 4.6 — Bedrock Compatibility Fixes)

Aus Review des Plans gegen Minecraft Superpowers Skills (`geyser-bridge-development`, `resourcepack-conversion`):

1. **UV Integer Fix (KRITISCH):** `BedrockGeometryGenerator.toIntJsonArray()` — `uv` und `uv_size` werden jetzt als Integer serialisiert. Float-Werte crashen Geyser's JSON Parser.
2. **Bone Count Warning:** Log-Warning wenn Model >50 Bones hat (Bedrock Performance-Limit).
3. **Texture POW2:** `BedrockModelConverter.nextPowerOfTwo()` — Textur-Dimensionen automatisch auf nächste Zweierpotenz aufgerundet.

### Plan aktualisiert
- Task 0 (Phase 4.6) als erledigt eingetragen
- Verifikations-Checkliste erweitert (9 Punkte aus geyser-bridge-development Skill)
- Risiko-Hinweis: FMM Display Entity Transformationen ≠ Bedrock Bone Animationen

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-04-11

### Abgeschlossen
- **Phase 5.1: StackOverflow Fix**
  - `FMMBridgeExtension.registerAnimationProperties()` — registriert Animation Properties via `GeyserUtils.addProperty()` + `registerProperties()` per Reflection beim Startup (`GeyserPreInitializeEvent`, `GEYSER_LOADED=false`)
  - Aufruf in `processModelDirectory()` nach `addCustomEntity()`, vor `generatePackFiles()`
  - `FMMEntityData`: `registerAndSendInitialAnimation()` → `sendInitialAnimation()` — `EntityUtils.registerProperty()` Aufrufe entfernt, nur noch `sendIntProperty()` für initialen State
  - **Deployed & verifiziert:** Proxy-Logs zeigen `Registered N animation property slots` für alle Models, kein StackOverflowError

- **Phase 5.5: Code-Modularisierung**
  - Spigot Plugin:
    - `PacketInterceptor` — Packet suppression + interact redirect (aus BedrockEntityBridge)
    - `ViewerManager` — Bedrock player tracking, join/quit, range checks (aus BedrockEntityBridge)
    - `BedrockEntityBridge` implementiert nicht mehr Listener, delegiert an beide
  - Geyser Extension:
    - `ResourcePackBuilder` — Entity defs, render controllers, manifest, zip (aus FMMBridgeExtension)
    - `EntityRegistrar` — GeyserUtils reflection für entity + property registration (aus FMMBridgeExtension)
    - `DownstreamMonitor` — Session tracking + listener re-registration (aus FMMBridgeExtension)
    - `FMMBridgeExtension` nur noch Lifecycle-Event-Handling

### Noch zu testen
- Bedrock-Client Animation-Test (kein Client verfügbar)

### Offene Themen
- Phase 6: Static Entities (Props/Möbel)
- Phase 7: EliteMobs UI/UX
- Phase 8: Polish

---

## Session: 2026-04-25

### Abgeschlossen

- **Referenz-Plugins aktualisiert (lokal):**
  - `FreeMinecraftModels` auf Branch `build-bone-fix` gebaut — Cherry-pick nur Bone.java-Fix (post-2.4.0)
    - Bug: `if (isBedrock && sendCustomModelsToBedrockClients)` war invertiert → mit `false` wurden trotzdem Display Entities an Bedrock gesendet
    - Deployed als `FreeMinecraftModels-2.4.0-local-boneFix.jar` (originales `.jar` → `.jar1`)
  - `EliteMobs` lokal auf 10.1.1 gebaut (System-Gradle 8.9, kein Linux-gradlew)
    - Deployed als `EliteMobs-10.1.1-local.jar`

- **Phase 5.6: Animation-Format-Fixes**
  - **Root Cause #1 — Falscher Animations-Referenz-Typ im Controller:**
    - `BedrockAnimationControllerGenerator`: States-`animations[]` nutzte volle ID (`"animation.fmmbridge.wolf.idle"`)
    - Bedrock erwartet Short-Name aus der Entity-Definition `animations`-Map (`"idle"`)
    - Fix: `createController(animName, query)` statt `createController(animId, query)`
  - **Root Cause #2 — Stale Pack-Cache beim Bedrock-Client:**
    - `manifest.json` wurde einmalig erstellt (April 2) und nie erneuert
    - Bedrock cached Packs nach UUID → Client lud nie die neuen Dateien
    - Fix: `writeManifest()` Guard (`if exists return`) entfernt in `ResourcePackBuilder` + `BedrockResourcePackGenerator` → jeder Proxy-Start generiert neue UUID
  - **Animationen verifiziert:** Wolf idle/walk/attack laufen korrekt auf Bedrock-Client

- **Debugging-Erkenntnisse (dokumentiert für zukünftige Sessions):**
  - `query.property(...)` in Animation-Controllern funktioniert nur mit Short-Names aus dem Entity-Definition `animations`-Map
  - GeyserUtils `entity.getPropertyManager()` arbeitet korrekt — Problem war nicht das Property-System
  - Diagnostic: Controller temporär auf `initial_state: play` gesetzt um Pack-Cache-Problem zu isolieren

### Bekannte Einschränkungen (Phase 8)
- **Hitbox zu klein:** Bedrock-Hitbox = Java-Entity-Größe, aber Model rendert mit `scale: 1.6` → Hitbox wirkt zu klein
- **Kein Hurt-Flash (rotes Flackern):** Damage-Metadata der Real-Entity wird supprimiert

### Offene Themen
- Phase 7: EliteMobs UI/UX
- Phase 8: Polish (Hitbox-Scale, Hurt-Flash, Partikel, Config, Performance)

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-04-25 (Fortsetzung)

### Abgeschlossen

- **Phase 6: Static Entity Support**
  - `IBridgeEntityData` Interface — gemeinsame API für DynamicEntity und StaticEntity Bridge-Daten
    - Methoden: `addViewer`, `removeViewer`, `tick`, `destroy`, `isDestroyed`, `isAlive`, `getViewers`, `getPacketEntity`, `getLocation`
  - `StaticEntityData` — neue Klasse für FMM StaticEntity (Props/Möbel)
    - Kein underlyingEntity (StaticEntity hat keine LivingEntity)
    - Kein Animation-Tracking (Props sind statisch)
    - Kein Combat-Redirect
    - Position fest aus `modeledEntity.getLocation()` beim Spawn
    - `addViewer()`: setzt GeyserUtils Custom Entity + sendet Fake-PIG-Spawn-Paket (gleicher Mechanismus wie DynamicEntity)
  - `FMMEntityData` implementiert jetzt `IBridgeEntityData`
    - `tick()` → `syncPosition()`
    - `isAlive()` → `realEntity != null && !realEntity.isDead()`
    - `getLocation()` → `realEntity.getLocation()`
  - `ViewerManager` — `isInRange(Player, Location)` Overload hinzugefügt
  - `BedrockEntityBridge` — generalisiert für beide Entity-Typen
    - `entityDataMap` ist jetzt `Map<ModeledEntity, IBridgeEntityData>`
    - `onEntitySpawn()`: DynamicEntity → `FMMEntityData`, StaticEntity → `StaticEntityData`
    - `tick()` nutzt Interface-Methoden, keine DynamicEntity-spezifischen Casts mehr
  - **Hinweis:** Ob FMM Armor Stands für StaticEntity an Bedrock schickt (mit `sendCustomModelsToBedrockClients: false`) muss getestet werden. Falls Armor-Stand-Artefakte sichtbar sind → Suppression in Phase 8 ergänzen

### Noch zu testen
- StaticEntity auf Bedrock-Client sichtbar? (TestServer01 Neustart erforderlich)
- Armor Stand Artefakte? Falls ja: Suppression für StaticEntity-Bones nachrüsten

### Offene Themen
- Phase 7: EliteMobs UI/UX
- Phase 8: Polish (Hitbox-Scale, Hurt-Flash, Partikel, Config, Performance, Armor-Stand-Suppression für Static)

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-04-25 (Abend)

### Abgeschlossen

- **Console-Spam gefixt:**
  - Alle Routine-Events (addViewer, removeViewer, spawn, despawn, nametag, animation-sync) von `log.info()` auf `log.fine()` gesetzt
  - Betroffen: `FMMEntityData`, `BedrockEntityBridge`, `StaticEntityData`, `FMMEntityTracker`, `ViewerManager`
  - Ergebnis: Bridge erzeugt keine INFO-Log-Einträge mehr bei normalem Betrieb

- **`/fmmbridge debug` Command hinzugefügt** (permanent behalten laut Fabi):
  - Zeigt alle getrackten Entities: Typ (DYNAMIC/STATIC), entityID, alive-Status, viewers-Anzahl, fakeId, Location
  - Zeigt alle ready Bedrock-Spieler mit Koordinaten
  - Tab-Completion: `convert`, `debug`

- **Blockbench v5 Geometrie-Fix (Bone-Namen):**
  - FMM NPC-Models (em_ag_xxx) nutzen Blockbench v5 Format
  - v5: `outliner` enthält nur UUIDs + children, kein `name`-Feld in Gruppen
  - Bone-Namen stehen in `animations.animators` keyed by UUID
  - Fix: `BedrockGeometryGenerator` baut `Map<UUID, String> uuidToName` aus animations → `traverseOutliner` nutzt diesen als Fallback
  - Vorher: 0 Bones, 410 Bytes .geo.json. Nachher: korrekte Bones, 39.899 Bytes

- **Blockbench v5 Bone-Pivot-Fix (in Arbeit):**
  - Pivots (`origin`) und Rotationen stehen im `groups`-Array, nicht im `outliner`
  - Fix: `BedrockGeometryGenerator` baut `Map<UUID, Map> uuidToGroup` aus `groups`-Array
  - `traverseOutliner` schaut Pivot und Rotation aus `uuidToGroup` nach
  - Deployed + `/fmmbridge convert all` + bedrock-skins zu Proxy kopiert
  - **Ergebnis: NPCs sichtbar, aber visuelle Verzerrung bleibt** → Nächste Session weiter debuggen

### Offenes Problem: NPC-Visuelle Verzerrung
Bone-Pivots sollten jetzt aus dem `groups`-Array kommen — unklar ob sie tatsächlich korrekt im .geo.json ankommen. Nächste Session: Pivot-Werte im generierten .geo.json auf dem Proxy direkt prüfen und mit .bbmodel-Originalwerten vergleichen. Proxy-Neustart war noch ausstehend.

### Offene Themen
- NPC-Verzerrung debuggen (Pivot-Werte im .geo.json verifizieren, Proxy neustarten)
- EliteMobs GUI (Shops etc.) — noch nicht untersucht
- Phase 7: EliteMobs UI/UX
- Phase 8: Polish

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-04-30

### Abgeschlossen

- **FMM 4x-Skalierungs-Fix (Root Cause der NPC-Verzerrung):**
  - Ursache gefunden: FMMs `BoneBlueprint.class` enthält `MODEL_SCALE = 4.0f` (bestätigt per Decompile aus FreeMinecraftModels-2.4.0.jar)
  - FMM authoriert alle .bbmodel-Modelle mit 4x vergrößerten Koordinaten; Display Entities werden mit Scale 0.25 gerendert
  - Unser Converter hat die rohen 4x-Koordinaten direkt ans Bedrock-Format übergeben → Bones/Cubes erschienen 4x zu groß ("aufgeblasen")
  - Mit `modelScale=1.6` war es sogar **6.4x** die richtige Größe

- **`BedrockGeometryGenerator` Fix:**
  - `MODEL_SCALE = 4.0` Konstante hinzugefügt
  - Alle Positionskoordinaten werden durch 4 geteilt: Bone-Pivot (origin), Cube from/to, Cube-Rotationspivot, inflate
  - Rotationswinkel (Grad) und UV-Koordinaten bleiben unverändert

- **`BedrockAnimationConverter` Fix:**
  - `MODEL_SCALE = 4.0` ebenfalls hinzugefügt
  - `position`-Keyframes werden durch 4 geteilt (gleicher Pixelraum wie Geometrie)
  - `rotation`- und `scale`-Channels bleiben unverändert

- **Default `modelScale` korrigiert:** `1.6` → `1.0`
  - Nach dem Koordinaten-Fix entspricht Scale 1.0 exakt FMMs eigenem Rendering (Display Entity scale=0.25)
  - Admins können über `converter.model-scale` in config.yml anpassen

- **Deployment-Memory korrigiert:**
  - `fmmbridgeextension/input/` als korrekten Proxy-Pfad in Memory gespeichert
  - `packs/generated_pack.zip` (alte Datei vom 29. März) gelöscht — Extension registriert Pack selbst via GeyserDefineResourcePacksEvent
  - Beide rsync-Pfade (geyserutils/skins + fmmbridgeextension/input) dokumentiert

- **Proxy-Start verifiziert:**
  - Alle 188 Modelle mit `scale=1.0` registriert (vorher `scale=1.6`)
  - `Generated Bedrock resource pack at fmmbridgeextension/generated-pack.zip` ✅
  - Keine Fehler beim Start

### Noch zu testen
- NPC-Modelle (em_ag_xxx) auf Bedrock-Client: erscheinen sie jetzt korrekt skaliert statt aufgeblasen?
- Boss-Modelle (01_em_wolf etc.): erscheinen sie noch korrekt, oder zu klein durch Scale-Änderung?
- Falls Boss-Modelle zu klein: `converter.model-scale` in config.yml anpassen und neu converten

### Offene Themen
- Visuelles Testing mit Bedrock-Client (beim nächsten Login)
- Phase 7: EliteMobs UI/UX
- Phase 8: Polish (Hitbox-Scale, Hurt-Flash, Partikel, Config, Performance)

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-05-01

### Problem
NPC-Modelle (z.B. EliteMobs Arena Master) hatten auf Bedrock-Clients eine Orientierungs-Inkonsistenz: Body und Head zeigten in unterschiedliche Richtungen — meistens war Body korrekt ausgerichtet, aber der Kopf war 180° verdreht (Hinterkopf zum Spieler, Gesicht weg). Tritt sowohl bei dynamischen als auch statischen Entities auf.

### Root Cause
Bedrock-Client-Rendering: **Animationen auf einzelnen Bones überschreiben die Parent-Rotation** des Root-Bones (oder verhalten sich anders als bei nicht-animierten Bones). Mit unserem alten Setup (virtueller `fmmbridge_root` Bone mit `[0,180,0]` Rotation) bekamen statische Bones (`waist`, `body`) die 180°-Drehung über Vererbung, animierte Bones (`head`, `arms`, `legs`) aber nicht. Body sah richtig aus (zufällig + symmetrische Texturen), Head fiel sofort als 180° verkehrt auf.

Mehrere Workaround-Hypothesen wurden getestet und als falsch verworfen:
- Bone-Rename `head`→`h_head`/`noggin` (kein Bedrock-Substring-Match auf "head")
- Pig-Entity-Body-Tracking (war ARMOR_STAND, kein Tracking)
- Cube-Rotation [0, 180, 0] (komponiert mit Animationen schwierig)

### Lösung
Komplett anderer Ansatz: **UV-Face-Swap in der Geometrie + `+180°` Yaw-Korrektur am Entity-Spawn**.

- **`BedrockGeometryGenerator.buildCube`:** UV-Faces werden bei der Generierung getauscht — `north↔south`, `east↔west`. Front-Texturen aus Blockbench (NORTH face) landen auf der SOUTH face. Up/down bleiben gleich (180° um Y bewegt sie nicht).
- **`BedrockGeometryGenerator.generate`:** Virtueller `fmmbridge_root` Bone entfernt. Top-level Bones haben kein parent.
- **`PacketEntity`:** `+180°` Yaw-Korrektur in `sendSpawnPacket` und `sendLocationPacket` zurück. Body- und Head-Yaw bleiben identisch (kein Body-Tracking).
- Bone-Rename `head`→`h_head`/`noggin` wieder entfernt — wird nicht mehr gebraucht.

### Warum der Ansatz robust ist
- Keine Abhängigkeit von Bedrocks Bone-Hierarchie oder Animation-Override-Verhalten
- Das gleiche Vorgehen wie GeyserModelEngine (deren Modelle haben Front-Texturen schon auf SOUTH face designed)
- Funktioniert für alle Entity-Typen (Static + Dynamic), unabhängig von Animations-Komplexität

### Bekannte Folge-Issue (Phase 8)
Animation-Keyframes mit Position oder Rotation auf X/Z-Achse sind weiterhin im ursprünglichen Koordinatensystem. Folge: Boss-Animationen mit Bewegung (z.B. Wolf-Attack) sehen "rückwärts" aus. Fix für Phase 8: in `BedrockAnimationConverter` Position- und Rotation-X/Z-Werte negieren (Y-Werte unverändert, da 180°-Drehung um Y-Achse).

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-05-02 (Dependency Bump)

### Abgeschlossen
- **FMM 2.4.0 → 2.5.0:** Lokal in `~/.m2` installiert (kein Public-Maven-Release noch), pom.xml updated. Kein Code-Change in der Bridge nötig — `ModeledEntityManager` API unverändert. Exclusions für geshade'te transitive Deps (`MagmaCore`, `ResourcePackManager`, `EliteMobs`, VaultAPI, Lombok, spigot-api, Floodgate, Geyser, bstats, commons-io, gson) ergänzt — sonst will Maven nicht-existente `MagmaCore:2.2.0-SNAPSHOT` auflösen.
- **EliteMobs 10.1.1 → 10.2.0:** Server-Plugin-Update (keine Maven-Dep). Native FMM↔EliteMobs-Integration ist neu in 2.5.0 aktiv: `Bridged EliteMobs dungeon locator into FreeMinecraftModels`.
- **PacketEvents 2.11.2 → 2.12.1:** pom.xml + Server. API abwärtskompatibel — Bridge kompiliert ohne Anpassung. Wichtig: Maven-Repo enthält nur API-only JAR (148 KB), für Server muss der shaded JAR (5.1 MB) von Github Releases geholt werden.
- **Geyser-Velocity b1107 → b1129:** Proxy. Kein Breaking-Change (Entity refactor pt2 war b1107, davor schon drauf). Relevante Bugfixes: b1109 (Text display offsets), b1113 (vehicle nametag) — beide für Phase 7.1b wichtig.
- **floodgate b123 → b132 (Spigot)** + **b131 → b132 (Velocity).**
- **Cleanup:** Doppelte `fmmbridgeextension.jar` (lowercase) auf Proxy entfernt — nur `FMMBridgeExtension.jar` bleibt aktiv.

### Erkenntnisse
- **boneFix-Patch ist in FMM 2.5.0 mainline:** Bytecode-Diff `Bone.class` zeigt 2.4.0 hatte `ifeq 39` (Bug — `if (bedrock && sendCustom) return;` invertiert), 2.5.0 hat `ifne 39` (korrekt — `if (bedrock && !sendCustom) return;`). Lokales `FreeMinecraftModels-local-2.4.0-boneFix.jar` ist obsolet.
- **FMM 2.5.0 Packet-Refactor (`easyminecraftgoals/v26/packets/`) bricht unseren PacketSuppressor NICHT** — Bedrock-Test bestätigt: Custom Models werden weiterhin korrekt angezeigt, Animationen laufen, Hitbox passt.
- **GeyserUtils NPE in `loadSkin`/`loadSkins`** ist pre-existing seit mindestens 1. Mai (564 NPE-Matches in alten Logs vor dem Update). Nicht durch das Update verursacht. Geyser läuft nach dem Crash weiter, Custom Items + `fmmbridge:*` Entity-Definitionen werden registriert. Skin-Cache von GeyserUtils ist tot, aber unsere Bridge-Pipeline ist davon nicht betroffen. Sollte später separat adressiert werden.
- **Verifiziert per Bedrock-Test (User):** Custom Models ✓, Animationen ✓, keine Nametags (erwartet — Phase 7.1b), Bewegungsrichtung falsch (bekannt — Phase 8).

### Deployment
- TestServer01: `FMM 2.5.0`, `EliteMobs 10.2.0`, `floodgate-spigot b132`, `packetevents-spigot 2.12.1`, neuer `FMMBedrockBridge.jar`
- Proxy01: `Geyser-Velocity b1129`, `floodgate-velocity b132`
- Rollback-Backups als `*.bak` auf beiden Hosts erhalten

### Noch zu tun
- Phase 7.1a brainstormen (BossBar mit korrekter EM-Style-Name-Quelle)
- Phase 7.1b brainstormen (Nametag-Architektur — TextDisplay? Bedrock-Entity-Definition? Andere Floating-Entity?)
- Phase 8 (später): Bewegungsrichtung-Fix (X/Z Animation-Keyframes negieren), GeyserUtils-NPE-Cleanup

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-05-02 (Phase 7.1a Brainstorm + Plan, Session pausiert vor Implementation)

### Abgeschlossen
- **Phase 7.1a Brainstorm** durchlaufen (superpowers:brainstorming Skill): Problem-Analyse, EM-Source-Recherche, Architektur-Entscheidung
  - Geklärt: Bedrock zeigt "Evoker | 2" oben am Screen statt "Tier X Boss Name" — Java zeigt's korrekt
  - EM-Source-Recherche: `Bukkit.createBossBar(eliteEntity.getName(), ...)` ist die Quelle (BossHealthDisplay.java:1118), `EliteEntity.getName()` Lombok-`@Getter` (kein Reflection-Hack nötig)
  - Approach 2 gewählt: eigene Bukkit-BossBar pro EM-Boss × Bedrock-Viewer mit suppress von EM's Original via heuristische UUID-Capture (Title-Match)
- **Spec geschrieben + committed** (`ecfdebf`): `docs/superpowers/specs/2026-05-02-phase7.1a-bossbar-design.md` (304 Zeilen)
- **Implementation-Plan geschrieben + committed** (`13ffb7a`): `docs/superpowers/plans/2026-05-02-phase7.1a-bossbar-implementation.md` (1088 Zeilen, 8 bite-sized Tasks)

### Plan-Übersicht (für Re-Start der Session)

8 Tasks, jeder mit Compile-Check + Commit am Ende:
1. **Foundation:** `plugin.yml` (EliteMobs zu softdepend), `config.yml` (`phase71a.suppress-em-bossbar: true`)
2. **EliteMobsHook:** Soft-dep Wrapper, einzige Stelle mit `com.magmaguy.elitemobs.*` Imports
3. **BossBarRegistry:** Concurrent `Set<UUID>` für Suppress-Logic
4. **BedrockBossBarController:** Per-Boss Bukkit-BossBar-Lifecycle (addViewer, tickUpdate Progress+Color, cleanup)
5. **BedrockEntityBridge:** `activeControllers`-Map + Cleanup auf shutdown
6. **FMMEntityData:** Wire BossBar-Lifecycle in spawn/addViewer/tick/destroy
7. **PacketInterceptor:** BOSS_EVENT-Suppress mit Title-Match-Heuristik + UUID-Capture
8. **Build, Deploy, Manual Test:** Spec-Test-Matrix auf TestServer01 + diagnostic toggle

### Erkenntnisse
- **`realEntity.customName()` für EM-Bosse liefert nicht den styled Name** — bestätigt durch EM-Source: EM setzt zwar `livingEntity.setCustomName(this.name)` (`EliteEntity.java:613`), aber irgendwo überschreibt EM den customName mit dem Vanilla-Format "Evoker | 2". `eliteEntity.getName()` ist die einzige verlässliche Quelle für den styled Boss-Name.
- **MythicMobs-Bosse sind out-of-scope** (User-Klärung): MythicMobs nutzt typischerweise ModelEngine (Ticxo) → GeyserModelEngine, nicht FMM/unsere Bridge.
- **EM-Quest/Dungeon-Pfade können nicht prophylaktisch getestet werden** (User-Klärung): "Real-World Observation"-Strategie statt geplanter Soak-Tests — Probleme fixen wenn sie im Live-Betrieb auftauchen.

### Deployment
Keine Code-Änderungen committed — nur Spec + Plan. Implementation startet beim Wiederaufnehmen der Session mit Task 1.

### Noch zu tun (nächste Session)
- **Task 1-8 des Implementation-Plans abarbeiten** (siehe Plan-Datei). Subagent-Driven Execution wurde empfohlen; User hat Session davor pausiert.
- Phase 7.1b brainstormen (Nametag-Architektur) — erst NACH Phase 7.1a fertig.
- Phase 8 Backlog: Bewegungsrichtung-Animation-Fix (X/Z-Achse negieren), GeyserUtils-NPE-Cleanup (pre-existing, nicht blocking).

### Hinweis für Re-Start
- **Bei Debug-Tasks:** Vor dem Diagnose-Vorschlag immer `superpowers:minecraft-debugging` Skill laden (User-Wunsch 2026-05-02).
- **Bei Bridge-Code-Änderungen:** `superpowers:geyser-bridge-development` Skill prüfen.
- Plan-Execution-Empfehlung: Subagent-Driven (`superpowers:subagent-driven-development`).

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-05-03 (Phase 7.1a Implementation + Debug-Mode-Integration)

### Abgeschlossen — Phase 7.1a komplett funktional auf TestServer01
- **Tasks 1-7 implementiert via Subagent-Driven Development** (commits 7463fe0 → 0db5ff6): plugin.yml softdepend, EliteMobsHook (soft-dep), BossBarRegistry, BedrockBossBarController, BedrockEntityBridge wiring, FMMEntityData lifecycle, PacketInterceptor BOSS_EVENT suppress
- **Task 8 (deploy + manual test):** drei Diagnostic-Iterationen waren nötig um zwei latente Bugs zu finden, beide jetzt gelöst
- **Test-Matrix bestanden:** Wolf "『3』 Wilder Alphawolf" + Ice Elemental "Evoker | 2" (EM-Bug, nicht unsers) erscheinen auf Bedrock, HP-Sync grün→gelb→rot, Multi-Boss stacked, Boss-Death cleanup OK

### Bugs während Phase 8 gefunden und gelöst

**1. Wrong styled-name source (Bug A).**
Spec sagte `eliteEntity.getName()` als kanonische Quelle. Live-Test zeigte: für `EVOKER`-basierte CustomBosses liefert das den Vanilla-`<Mob> | <Tier>`-Namen, nicht den YAML-`name`. `livingEntity.getCustomName()` ist verlässlich (was Java auch als Mob-Nametag zeigt).
**Fix:** `EliteMobsHook.getStyledName()` nutzt customName als primary, eliteEntity.getName() nur als fallback wenn customName nicht gesetzt.

**2. Self-suppression (Bug B, kritisch).**
Unser PacketInterceptor.handleBossEvent suppressed UNSERE EIGENE Bukkit-BossBar! Title-Match findet ja den eigenen Controller. ThreadLocal-basierter Bypass-Versuch funktionierte nicht weil Bukkit das BOSS_EVENT-Paket auf einem Netty-IO-Thread sendet, nicht auf dem Bukkit-Main-Thread wo `bossBar.addPlayer()` aufgerufen wird → ThreadLocal nicht sichtbar.
**Fix:** First-Match-Heuristik ohne Threading. Pro Controller ist das **erste** title-matchende ADD-Paket unsere BossBar (wir adden immer beim Spawn, EM erst beim Combat-Enter — Reihenfolge garantiert), spätere matches sind EM's Duplikate. Implementiert via `BedrockBossBarController.hasOwnUuid()` / `registerOwnUuid()`.

**3. `setVisible(true)` fehlt (Bug C).**
Vergleich mit EM's `SkillXPBar.java` (das auf Bedrock funktioniert) zeigte: explizites `bossBar.setVisible(true)` nach `addPlayer()` ist nötig. Bukkit defaultet zwar auf visible=true aber der explizite Call generiert ein UPDATE_FLAGS-Paket das Geyser/Bedrock zur Anzeige der BossBar zu brauchen scheinen.
**Fix:** `setVisible(true)` im Konstruktor und in `addViewer` ergänzt.

### Debug-Mode-Integration (User-Wunsch)
Statt die diagnostischen Logs zu entfernen, ins Plugin als nutzbares Debug-System integriert:
- **`FMMBedrockBridge.debugLog(String)`** Helper: log.info wenn `debug: true` in config.yml, sonst log.fine (unsichtbar im Default-Logger)
- **Phase 7.1a Logs** nutzen den Helper — toggle via config = sofortige sichtbare Diagnose ohne Build
- **`/fmmbridge debug` Command** zeigt jetzt zusätzlich aktive BossBar-Controllers (Title, own-UUID-claimed-status, Entity-UUID) + Anzahl der suppressten EM-UUIDs + aktuellen debug-mode-Status

### Crossplay-Behaviour-Klärung
Aktuelles Verhalten: Bedrock-Spieler sieht BossBar **proximity-based** (in Range), Java-Spieler sieht EM's BossBar **combat-based** (erst bei Damage). Das ist ein kleiner Crossplay-Unterschied, aber:
- Vanilla MC (Wither/Dragon) ist auch proximity-based — unser Bedrock-Verhalten ist Vanilla-konsistent
- Java-Spieler hat den Mob-Custom-Name als Nametag — Bedrock fehlt der noch (Phase 7.1b)
- Phase 7.1c (Combat-only Trigger) ist als Future Enhancement im Spec dokumentiert — sinnvoll erst NACH Phase 7.1b weil sonst Bedrock keine proximity-Awareness hätte

### Phase-Erweiterung
- Phase 7.1c neu hinzugefügt: Combat-only BossBar-Trigger für Crossplay-Fairness (geplant, nach 7.1b)
- README + Spec aktualisiert mit Phase 7.1c-Beschreibung

### Erkenntnisse (für Phase 7.1b und Folgephasen)
- **PacketEvents Send-Listener läuft auf Netty-IO-Thread, nicht Bukkit-Main-Thread.** ThreadLocal-Tricks funktionieren nicht für Self-Identifikation von Outgoing-Pakete. Falls 7.1b ähnliche Distinction braucht, Timing-Heuristik (first-match) oder UUID-Whitelist nutzen.
- **EM SkillXPBar als Working-Reference:** wenn ein Bedrock-BossBar-Mechanism gebaut wird, EM's `SkillXPBar.java` als Pattern nehmen — das funktioniert empirisch auf Bedrock (Bukkit-API + setVisible-Call).
- **EM customName ist verlässliche Source für Boss-Nametag** auf der LivingEntity, auch wenn `eliteEntity.getName()` für manche Boss-Types inkonsistent ist.

### Noch zu tun (nächste Session)
- Phase 7.1b brainstormen (Bedrock-Nametag-Architektur — TextDisplay-Spawn? Bedrock-Entity-Definition mit Nametag-Component?)
- Phase 7.1c brainstormen (Combat-only BossBar) — erst nach 7.1b fertig
- Phase 8 Backlog: Bewegungsrichtung-Animation-Fix, GeyserUtils-NPE-Cleanup

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-05-03 (Phase 7.1b — Bedrock Nametag)

### Abgeschlossen
- **Phase 7.1b Brainstorm + Spec + Plan + Implementation** auf eigener Branch `phase-7.1b`
- **Architektur:** echte Bukkit `TextDisplay`-Entity über jedem FMM-Mob, position+text-sync via Tick-Loop, Java-Spieler sehen sie nicht (PacketInterceptor-Suppress). Bedrock-Spieler bekommen die Pakete durch — Geyser übersetzt zu Bedrock-Nametag-Entity.
- **Plugin-agnostic Trigger:** Nametag erscheint wenn `modeledEntity.getDisplayName()` oder `realEntity.customName()` einen Wert hat — funktioniert für EM, BetterStructures, Tower Defense und alle anderen Magma-Plugins
- **6 Tasks via Subagent-Driven Development** + 2 Review-Fixes
- **Erfolgreich verifiziert auf TestServer01** mit Bedrock-Account (Wolf, Ice Elemental, Vindicator)

### Schlüssel-Erkenntnis (Source-Bug-Fix)

**`realEntity.customName()` ist NICHT die richtige Quelle für den Nametag-Text** bei FMM-Custom-Bosses (z.B. Ice Elemental zeigt "Evoker | 2" statt "Tier 13 Ice Elemental"). Nach dem ersten Build sahen wir das selbe Symptom wie bei Phase 7.1a BossBar.

**Source-Trace via EM + FMM-Source ergab:**
- EM's `CustomBossEntity.setName(name, true)` → `customModel.setName(name, true)` (mit `customModel: CustomModelFMM`)
- `CustomModelFMM.setName` → `dynamicEntity.setDisplayName(name)` (FMM API)
- FMM `ModeledEntity.setDisplayName(displayName)` → setzt Text auf `Skeleton.nametags` (FMM's interne TextDisplay-Bones)
- **Java rendert seinen Mob-Nametag aus diesen FMM-Bones**, nicht aus `livingEntity.customName()`!

**Fix:** `BedrockNametagController` nimmt einen `Supplier<Component> textSource` statt fixem `initialText`. `FMMEntityData` baut den Supplier mit `modeledEntity.getDisplayName()` (Legacy-§-codes via `LegacyComponentSerializer` parsen) als primary, `realEntity.customName()` als fallback. Live-Updates (z.B. EM-Phase-Wechsel) werden via Tick-Loop automatisch reflected.

### Implementation-Details

| Komponente | Status |
|---|---|
| `BedrockNametagController` (NEU) | Per-mob lifecycle, Supplier<Component>-getrieben |
| `BedrockEntityBridge.activeNametags` Map + Accessor + Shutdown-Cleanup | additive |
| `PacketInterceptor.javaHiddenEntityIds` + `hideFromJava`/`unhideFromJava` + Suppress-Branch | mirror der hide-from-Bedrock Logik, decked SPAWN_ENTITY/ENTITY_METADATA/ENTITY_TELEPORT/ENTITY_RELATIVE_MOVE/ENTITY_RELATIVE_MOVE_AND_ROTATION/ENTITY_POSITION_SYNC ab |
| `FMMEntityData` Wiring | createNametagControllerIfNamed + tick + destroy + Floodgate-Guard |
| `/fmmbridge debug` | zeigt Nametag-Controllers Sektion |

### Bekannte Limitations (nach Phase 8 verschoben)

1. **Wolf-Nametag mitten im Mob** — Y-Offset = `realEntity.getHeight() + 0.3` ist relativ zur Vanilla-Hitbox. FMM-Custom-Modelle sind oft größer (Scale `1.6×`), Nametag erscheint zu niedrig. Fix: model-aware Y-Offset (FMM-Skeleton-Bounds oder per-Modell-Konfig).
2. **Multi-BossBar bei Rejoin** — Phase 7.1a First-Match-Heuristik verliert State bei Disconnect. EM's BossBar erscheint kurz parallel zu unserer. Fix in Phase 7.1c (Combat-only) oder Phase 8 Polish.

### Phase-Reorganisation

Phase 7.1c Scope erweitert auf "alles Combat-triggered zusammen" (BossBar-Toggle + HP-Zahl + Health-Bar) — weil das alles auf den gleichen `EliteMobEnterCombatEvent`-Hook geht. Phase 7.1b war ursprünglich als 3-zeiliger Nametag (HP/Bar/Name) gedacht, wurde aber während des Brainstorms reduziert auf "nur Name (always-visible)" — weil Java HP/Bar auch erst bei Combat zeigt.

### Branch-Stand

Alle Phase 7.1b Commits auf `phase-7.1b` Branch, nicht auf main. 8 Commits inkl. Brainstorm-Spec-Plan + Implementation + 2 Review-Fixes + finaler displayName-Source-Fix:

```
0d73c71 Phase 7.1b: nametag uses FMM displayName as primary source
8ce747f Phase 7.1b: /fmmbridge debug shows active Nametag controllers
eda7c58 Phase 7.1b: nametag fixes — Floodgate guard, Y-offset constant, ...
46b3af7 Phase 7.1b: wire Nametag lifecycle into FMMEntityData
7185163 Phase 7.1b: include position-sync packets in Java-suppress (review fix)
925c382 Phase 7.1b: PacketInterceptor javaHiddenEntityIds + suppress for Java
9a8ba52 Phase 7.1b: BedrockEntityBridge activeNametags map + accessor
d617245 Phase 7.1b: BedrockNametagController — per-mob TextDisplay lifecycle
7b29ffa Phase 7.1b implementation plan: Bedrock nametag via TextDisplay
ad8236b Phase 7.1b design: Bedrock nametag via TextDisplay + Java suppress
```

Branch ready for merge to main wenn akzeptiert.

### Noch zu tun (nächste Session)
- Phase 7.1b → main mergen (oder als PR review)
- Phase 7.1c brainstormen (Combat-only HP+Bar+BossBar-Toggle, includes Multi-BossBar-Rejoin-Fix)
- Phase 8 Backlog erweitert: Wolf-Y-Offset, Bewegungsrichtung-Animation, GeyserUtils-NPE

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

## Session: 2026-05-03/05/10 (Phase 7.1c — Combat-triggered Visuals)

### Abgeschlossen
- **Phase 7.1c Brainstorm + Spec + Plan + Implementation + Code-Reviews + Manual-Test** auf eigener Branch `phase-7.1c`
- Combat-aware BossBar (Enter/Exit via EliteMobs Events) statt always-visible
- Combat-aware Nametag (1 Zeile außerhalb Combat → 3 Zeilen HP / Bar / Name im Combat)
- HP-Bar 10×█/░ farbcodiert (green ≥66% / yellow ≥33% / red <33%), HP-Number weiß
- Config-Toggle `phase71c.combat-enabled: true` (Default), `false` = Phase-7.1a-Fallback
- Multi-BossBar-Rejoin-Fix organisch via Lazy-Add in `BedrockBossBarController.addViewer`
- `/fmmbridge debug` zeigt `inCombat: yes/no` pro Controller

### Test-Ergebnis
Fabi: "war die erste Phase die direkt funktioniert hat" — die 13-Test-Matrix lief ohne Bug-Fixes durch nach Deploy. Sauberes Spec/Plan/Review-Vorgehen hat sich ausgezahlt.

### Code-Review-Fixes (während Implementierung gefangen)
- `String.format` → `String.format(Locale.ROOT, ...)` — sonst rendert deutsche Locale "15,50 / 100,00" Komma-Decimal
- `tickUpdate` `compose()` Call mit try/catch wrapped — Defense-in-depth gegen Scheduler-Spam

### Branch-Stand
Alle Phase-7.1c-Commits auf `phase-7.1c` Branch. 11 Commits inkl. Spec/Plan/Foundation + 6 Implementation-Commits + 2 Review-Fix-Commits + Final-Polish.

```
b78dc2c Phase 7.1c: /fmmbridge debug shows inCombat per controller
6d23a67 Phase 7.1c: BedrockCombatTrigger listener + registration
e79fc0b Phase 7.1c: restore defensive try/catch in BedrockNametagController.tickUpdate
799049e Phase 7.1c: BedrockNametagController refactor — drop Supplier, use NametagTextBuilder
07280d7 Phase 7.1c: reset isInCombat in BossBar cleanup() — defensive
cb741b6 Phase 7.1c: BedrockBossBarController combat-aware (lazy-add, enter/exitCombat)
9dd34de Phase 7.1c: NametagTextBuilder — use Locale.ROOT for HP formatting
41bd24b Phase 7.1c: NametagTextBuilder — combat-aware 1/3-line component
88d2bcd Phase 7.1c foundation: combat-enabled config toggle + helper
7762815 Phase 7.1c implementation plan: combat-triggered visuals
1575039 Phase 7.1c design: Combat-triggered visuals + multi-BossBar rejoin fix
```

### Server-Update am 2026-05-10
Vor finalem Test: Geyser-Velocity `2.9.5-b1129` → `2.10.0-b1141` auf Proxy01 deployed (Bedrock-Protocol-Update). Floodgate-Spigot `b121` → `b132` auf Survival01 (kosmetisch). Alte JARs als `.bak` gesichert.

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-05-10 (Phase 7.2a Research + 7.2b Custom Items)

### Abgeschlossen

- **Phase 7.2 Aufteilung** in 7.2a (Research), 7.2b (2D Items), 7.2c (3D Items conditional), 7.3 (Bedrock Forms)
- **Phase 7.2a Research** (SSH auf TestServer01 + Proxy01):
  - EM Resource Pack liegt unter `plugins/EliteMobs/resource_pack/` mit Sub-Packs (`em_rsp_defaults/`, `em_ag_rsp/` etc.)
  - 13 × 2D-UI-Icons gefunden (bag of coins, anvil hammer, white anvil, locks, crowns, …) — parent `item/generated`, kein `elements`-Array
  - 3D-Gear (Waffen/Rüstung) existiert in EM — aber nur 2D-Items in Phase 7.2b in Scope
  - Nitrosetups `.mcpack` auf Proxy01 als Referenz: `item_texture.json` braucht `texture_name: "atlas.items"`, Textur-Keys ohne `.png`-Extension
  - Geyser `GeyserDefineCustomItemsEvent`: `event.register(javaMaterial, CustomItemData)` — pro CMD-Wert ein Aufruf

- **Phase 7.2b implementiert auf Branch `phase-7.2b-custom-items`** (7 Commits):
  - `EMCustomItem` Record (Spigot): javaMaterial, customModelData, sourceTexturePath, bedrockTextureKey
  - `EliteMobsItemScanner` (Spigot): scannt alle Sub-Packs via `Files.list().sorted()`, filtert 3D-Modelle via `elements`-Check, bedrockTextureKey = `"em_" + pngBasename`
  - `config.yml` erweitert: `elite-items.enabled` + `elite-items.resource-pack-path`
  - `FMMBedrockBridge.onEnable()`: Phase-7.2b-Block ruft Scanner auf, schreibt `bedrock-pack/em-items.json` + kopiert PNGs nach `bedrock-pack/em-item-textures/`
  - `ResourcePackBuilder.embedEliteItems()` (Geyser-Extension): liest em-items.json, kopiert nach `textures/em/<key>.png`, schreibt `textures/item_texture.json` mit `texture_name: "atlas.items"`
  - `FMMBridgeExtension.onDefineCustomItems()`: `@Subscribe GeyserDefineCustomItemsEvent` → registriert alle Einträge per `event.register(javaMaterial, CustomItemData)`
  - `FMMBridgeExtension.onPreInitialize()`: ruft `embedEliteItems()` vor `zip()` auf

- **Deployment auf TestServer01 + Proxy01 verifiziert:**
  - 13/13 EM Custom Items registriert (`[Phase 7.2b] Registered 13 / 13 EM custom items with Geyser.`)
  - Geyser registriert 497 Custom Items gesamt (484 Nitrosetups + 13 FMMBridge)
  - Bedrock-Client sieht Custom Icons in EliteMobs-Shops: Geldsack ✓, Ambosshammer ✓
  - Weißer Amboss (CMD 31175) technisch korrekt registriert — erscheint nur im Unbind-Menü-Confirm-Button (slot 35); soulbound item + unbind scroll zum Testen nötig
  - Vanilla-Smaragde in buy/sell/repair/enchant-Menüs (kein CMD) bleiben vanilla — expected, EM setzt dort kein `custom_model_data`

### Erkenntnisse

- **EM-Menüs mit CMD 31173 nutzen verschiedene Materialien** (EMERALD, GREEN_BANNER, REDSTONE) — wir registrieren nur für Materialien die im Resource Pack Override definiert sind; GREEN_BANNER-Slots bleiben vanilla
- **`texture_name: "atlas.items"` ist Pflichtfeld** in item_texture.json — fehlt es, rendert Bedrock keine Custom-Texturen (aus Nitrosetups-Research)
- **Weißer Amboss erscheint NUR im Unbind-Menü** — andere Menus zeigen echte Vanilla-Smaragde (kein CMD)

### Offene Themen

- Phase 7.2c: 3D Custom Items (Blockbench-Waffen/Rüstung als Bedrock Attachables)
- Phase 7.3: Bedrock Forms (NPC-Dialoge via Cumulus API)
- Phase 8: Polish-Backlog (Animation X/Z-Fix, Nametag Y-Offset, Hitbox-Scale, Hurt-Flash)

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-05-14 (Phase 7.2c In-Game-Test + Scanner-Fix + Paket-Abdeckung)

### Abgeschlossen

- **7.2c In-Game getestet** (Bedrock-Spieler): Map+Inject + Pack-Pipeline funktionieren — Backend injectet `item_model`, Geyser registriert, Attachables/Geometrien/Texturen im Pack strukturell korrekt. **Aber:** Gear rendert **flach in der Hand**.
  - Root-Cause: `JavaItemGeometryConverter.convertToGeo()` ist ein Flat-Sprite-Stub. Commit `032bee3` hatte echte `elements`→`geo`-Konvertierung, `59e9f17` ("Phase 7.2 WIP") ersetzte sie durch `texture_meshes`-Sprite. 3D-in-der-Hand war nie fertig.
- **Scanner-Bug gefixt** (TDD, commit `c225770`): `EliteMobsItemScanner.resolveGear3D()` hatte Textur-Key `"0"` hartkodiert; Blockbench vergibt beliebige Keys (`"29"`, `"1"`, …) → `ultimatium_sword`, `corrupted_trident`, `magmaguys_toothpick` wurden übersprungen.
  - `pickGearTextureRef()` extrahiert (erster Key ≠ `"particle"`), 5 JUnit-Tests, JUnit 5 + surefire neu in `pom.xml`.
  - Logger-Init in `EliteMobsItemScanner` null-sicher gemacht (`getInstance().getLogger()` warf NPE im Test-Kontext).
  - **End-to-End verifiziert:** Backend scannt 21 → **24**, Proxy registriert **24/24**, Live-Inject aller 3 freigeschalteten Items im Log bestätigt.
- **Paket-Abdeckung gefixt** (systematic-debugging, Diagnostic-Build → Reproduktion → Fix): Custom-Items fielen beim Droppen / Slot-Wechsel auf Vanilla zurück.
  - Diagnostic-Befund: Droppen → `ENTITY_METADATA` einer Item-Entity (nicht injiziert). Slot-/Hotbar-Wechsel → **gar kein** Server-Paket (client-seitig vorhergesagt; `SET_CURSOR_ITEM`/`SET_PLAYER_INVENTORY` feuerten 0×).
  - **Teil A:** `ENTITY_METADATA`-Branch im `PacketInterceptor` — ItemStack aus Entity-Metadata extrahieren + injizieren.
  - **Teil B:** neuer `BedrockInventoryRefresher` (Listener) — `updateInventory()` einen Tick nach `InventoryClick`/`InventoryDrag`/`ItemHeld` von Bedrock-Spielern → erzwingt `WINDOW_ITEMS`-Resend.
  - Verifiziert: Droppen + Aufheben halten sauber; Slot-/Hotbar-Wechsel haben sub-Tick-Flackern, enden aber custom.
- **Cleanup TestServer01:** doppelte/stale `FMMBedrockBridge-0.1.0-SNAPSHOT.jar` (plugins/ + .paper-remapped/) entfernt — Paper meldete "Ambiguous plugin name".
- **Echte 3D-`elements`→`geo`-Konvertierung** (TDD, 5 JUnit-Tests, JUnit5+surefire neu in `geyser-extension/pom.xml`): `JavaItemGeometryConverter.convertToGeo()` ersetzt den Flat-Sprite-Stub durch echte Cube-Geometrie.
  - Referenz: Kafal-java2bedrock-Pack auf Proxy01 (`packs/Kafal-Java2Bedrock-gui-offsets.zip1`) hat konvertierte EM-Equipment-Geometrien — daran verifiziert statt zu raten.
  - Transform: `origin = [from.x-8, from.y, from.z-8]` (Y NICHT verschoben — das war `032bee3`s Bug), `size = to-from`, UV direkt kopiert, `texture_width/height` aus `texture_size`.
  - Rotierte Elemente → eigener Child-Bone von `geyser_custom_z` (Bone-Rotation, nicht Cube), X/Y negiert / Z behalten.
  - Extension-JAR auf Proxy01 deployt — **Bedrock-In-Game-Verifikation noch ausstehend** (Proxy-Neustart + visueller Test).

### Erkenntnisse

- **`032bee3`s Bug war der Y-Shift:** der erste Konvertierungs-Versuch subtrahierte 8 auch von Y und nutzte unparented Root-Bones ohne `geyser_custom`-Binding → "near-invisible geometry". Korrekt (Kafal-Referenz): nur X/Z um -8 verschieben, Cubes unter die `geyser_custom`-Hierarchie hängen.
- **nitrosetups ist KEINE 3D-Referenz:** nitrosetups-"3D"-Items sind `texture_meshes`-Flat-Sprites (die Items sind in Java auch nur 2D-Sprites). Für echte Cube-Geometrie war der Kafal-java2bedrock-Pack die richtige Referenz.
- **EM-Gear-Texturen sind teils animiert:** `bronzesword.png` ist 64×768 (12 Frames). Kafal splittet das in 12 Texturen + Render-Controller; wir croppen auf Frame 0 (statisch) — voller Animations-Support = Phase 8.
- **Bedrock client-seitige Inventar-Moves senden kein Server-Paket:** Verschiebt ein Bedrock-Spieler ein Item zwischen Slots, sagt der Client den Move voraus und der Backend schickt nichts (Diagnostic bestätigt: 0× `SET_CURSOR_ITEM`/`SET_PLAYER_INVENTORY` über die ganze Session). Ein Paket-in-flight-Inject kann das nicht abfangen → braucht einen `updateInventory()`-Re-Send-Trigger über Bukkit-Events.
- **Inject ist Paket-in-flight, nicht persistent:** Alles was den Bedrock-Client über einen nicht abgefangenen Pfad erreicht, behält das Original-`item_model`. Daher müssen alle Pfade (Inventar, Item-Entity, künftig evtl. `ENTITY_EQUIPMENT`) explizit abgedeckt werden.

### Offene Themen

- **7.2c Bedrock-Verifikation:** Proxy01 neu starten, `bronze_sword`/`bronze_axe` (keine Rotation) + `ultimatium_sword`/Scythe (mit Rotation) in der Hand testen — Form/Textur/Rotation korrekt?
- **Phase 8 Polish:** animierte Gear-Texturen (statisch → Frame-Splitting); sub-Tick-Flackern beim Inventar-Umsortieren; ggf. `ENTITY_EQUIPMENT`-Pfad für von anderen gehaltene Items
- Phase 7.2d (Rüstung/Bögen/Armbrüste), 7.3 (Bedrock Forms), Phase 8 (Polish-Backlog)

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-05-16 (Phase 7.2c 3D-Render-Durchbruch + Codex-Co-Pilot eingeführt)

### Abgeschlossen

- **7.2c In-Game-Test (Fortsetzung von 14. Mai):** Bedrock-Spieler sieht Gear-Items weiterhin **flach in der Hand** (test8.png). Backend-Inject + Pack-Generierung + Geyser-Registrierung (24/24) alle bestätigt — der Bug liegt im Pack-Format / der Bedrock-Attachable-Verarbeitung.
- **Lange Debugging-Iteration über Webrecherche** (Bedrock-Wiki, Microsoft Learn `custom_items` Sample, Geyser-Source `CustomItemRegistryPopulator.java`): mehrere Theorien getestet und verworfen:
  - `displayHandheld(true)` in `CustomItemBedrockOptions` hinzugefügt → setzt nur `hand_equipped=true`, schaltet **kein 3D ein**. (Geyser-Source verifiziert)
  - `"item": { "<id>": "query.is_owner_identifier_any('minecraft:player')" }` Feld in description hinzugefügt (Microsoft-Sample) → kein Effekt.
  - `materials.enchanted` von `entity_alphatest_glint_item` auf Standard `entity_alphatest_glint` korrigiert → kein Effekt allein.
  - `format_version` von `1.10.0` auf `1.20.30` (Microsoft) angehoben → **machte es schlimmer** (siehe Codex-Befund unten).
  - Minimal-Attachable ohne Animations/Scripts probiert → wieder flach.
  - **Verdächtige Geyser-Logs** dokumentiert: `Missing mapping for bedrock item ... componentBased=true, blockDefinition=cyan_terracotta` — Codex hat das später als Geyser-Pseudo-Block für Runtime-ID 0 entlarvt (Red Herring).
- **Codex als zweiter KI-Assistent eingeführt** (OpenAI CLI) für tiefes Geyser-Source-Code-Debugging. Workflow etabliert: Codex analysiert Geyser-Source + schreibt Patches, Claude orchestriert (build + deploy + Pack-Pull + Memory/Docs).
- **Codex's Root-Cause-Befund** (Pose):
  - Per-Item Animations statt globaler `fmmbridge_gear.json` — aus Java-`display`-Transforms je Modell generiert.
  - **Composed Transforms:** Java-`display`-Werte werden AUF die bekannten Bedrock-Base-Transforms komponiert (translation addiert, rotation als Delta, scale multipliziert), nicht ersetzt. Java's display ist relative Anpassung, Bedrock braucht komplette Transform.
- **Codex's Root-Cause-Befund** (Render):
  - **`format_version: "1.10.0"` ist Pflicht im Attachable** — `1.20.30` (Microsoft-Sample) lässt `scripts.animate` silent durchfallen, Item hängt in roher Identity-Pose. Mit `1.10.0` greift die Animation.
  - Verifiziert: test10.png (1.20.30, rohe Pose) → test11.png (1.10.0, korrekte Waffenpose).
- **Commit `a5aa6b8`** "Phase 7.2c: 3D gear renders in hand — pose + format_version fix" (Composed Transforms + per-item animations + format_version 1.10.0).
- **Commit `e4e2efb`** "Docs: refresh AGENTS.md for Phase 7.x state" — AGENTS.md komplett auf Phase 7.x aktualisiert (Phasenplan, Scope erweitert auf Geyser-Extension auf Anweisung, vollständige Klassen-Inventur Backend + Extension, PacketEvents-Constraint, bekannte Spec-Stolperfallen).
- **First-Person Pose-Iteration mit Codex** (uncommitted, mehrere Build/Deploy-Zyklen):
  1. `firstperson_attack_adjust` als Gegenanimation für übertriebene Bedrock-Bewegung
  2. First-Person komplett auf normale item/handheld-Basis (wie diamond_sword)
  3. **Per-Weapon-Family Split:** Axe → handheld-First-Person-Basis, Sword/Trident/Scythe → Cube-First-Person-Basis. Auswahl über Waffen-Gruppe aus Java-Modell.
- **Verifiziert In-Game:**
  - `corrupted_trident` → 3D Pose korrekt (test11.png)
  - `living_axe` ("Mystisch Wandelaxt") → 3D mit handheld-Pfad korrekt
  - Schwert + Trident + Sense Kalibrierung steht für nächste Session aus.

### Erkenntnisse

- **`displayHandheld(true)` ≠ 3D-Schalter:** schaltet nur `item_properties.hand_equipped=true` (Tool/Waffen-Pose), aktiviert kein 3D-Rendering. 3D entsteht durch das im Pack mitgelieferte Attachable — Bedrock verknüpft via Identifier-Match (`bedrock_identifier == attachable.identifier`) automatisch.
- **`format_version` im Attachable MUSS `1.10.0` sein** (verifiziert 2026-05-16). `1.20.30` (Microsoft's offizielles `custom_items`-Sample!) führt zu silent `scripts.animate`-Skip. Geo-Files (`models/entity/*.geo.json`) sind davon unabhängig — die nutzen weiterhin `1.16.0`.
- **Java `display` ≠ Bedrock-Attachable-Animation 1:1:** Java's display ist relative Anpassung zur Default-Hand-Pose, Bedrock-Attachables brauchen die komplette Transform. Direkte 1:1-Übernahme (erster Codex-Versuch) ergab Items in roher Identity-Pose. Komposition mit Bedrock-Base-Transforms ist nötig.
- **`blockDefinition=cyan_terracotta` im Geyser-Log:** Pseudo-Block für Runtime-ID 0, kein Hinweis auf Item-Mis-Mapping (Red Herring).
- **Eine globale Attachable-Pose passt nicht für alle Waffen-Typen:** Axe vs Sword vs Trident vs Scythe haben in Java unterschiedliche Default-Renderings. Per-Family-Split nötig.
- **Microsoft's `custom_items`-Sample auf GitHub ist für Attachables irreführend** (`format_version: 1.20.30`). Praxis (verifiziert in 2026) braucht `1.10.0`.
- **Bedrock-Content-Log zeigt keine Errors für unser Pack** — Pack wird geladen, aber bei `1.20.30` werden Animation-Conditions silent nicht ausgewertet. Heißt: "kein Error" ≠ "funktioniert wie erwartet".
- **Co-Pilot-Workflow Codex + Claude funktioniert:** für tiefe Spec-/Source-Analyse ist Codex effektiver, für Orchestrierung + Deploy + Memory bleibt Claude im Lead. Festgehalten in [memory] `codex_collaboration.md` + AGENTS.md.

### Offene Themen

- **7.2c verbleibend:** Schwert (mit Cube-First-Person-Basis) sowie Trident + Sense einzeln verifizieren. Bei Bedarf Codex für Family-spezifische Kalibrierung. Uncommitted Codex-Changes (First-Person handheld + per-weapon-family split) committen.
- **Phase 8 Polish (in 7.2c bewusst nicht angegangen):**
  - Animierte Gear-Texturen: z.B. `livingaxe.png` ist 64×256 mit 4 Frames + `frametime: 9` — aktuell auf Frame 0 gecroppt → in Bedrock statisch (sieht "falsch" aus weil Frame 0 oft die ruhige Variante ohne Glow ist)
  - Sub-Tick-Flackern beim Inventar-Umsortieren
  - `ENTITY_EQUIPMENT`-Pfad für von anderen Spielern gehaltene Items
  - Reverse-Mapping für `Missing mapping for bedrock item` Geyser-Log (Inventory-Desync)
- Phase 7.2d (Rüstung/Bögen/Armbrüste), 7.3 (Bedrock Forms via Cumulus API), Phase 8 (komplettes Polish-Backlog).

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-05-24 (FMM 2.6.0 + RPM 1.8.0 — großer Pivot, Bridge-Refactor)

### Hintergrund

MagmaGuy hat in **FMM 2.6.0** nativen Bedrock/Geyser-Support eingebaut (`Bedrock players now see custom-modeled entities correctly on first display through Geyser`), und **ResourcePackManager 1.8.0** konvertiert jetzt JEDE Plugin-Resource-Pack automatisch zu Bedrock — inklusive 3D-Items, Armor, attachable geometry 1.21.0, flipbook texture icons, bedrock_display_offsets.yml für Pose-Tuning. Dadurch sind Phasen 1-6 + 7.2c/d unserer Bridge **redundant**.

### Updates & Deploys

- **References-Repos aktualisiert** (`git pull` auf master/main, FMM von `build-bone-fix` Branch zu master gewechselt, Stash für drift): EliteMobs (10.2.1+), GeyserModelEngine, FreeMinecraftModels (2.6.0). ResourcePackManager + BetterStructures als neue Refs hinzugefügt.
- **TestServer01 Deploys:** FMM 2.5.0 → 2.6.0, EM 10.2.0 → 10.3.1, RPM 1.7.6 → 1.8.0 (über Public-Maven), BetterStructures 2.3.0 → 2.3.1 (Nightbreak). Alte JARs als `.bak` gesichert.
- **Manueller Pack-Transfer:** RPM generiert `ResourcePackManager_Bedrock.zip` (64 MB, 1555 Bones aus 204 Models) + `rspm_geyser_mappings.json` (667 Einträge) auf TestServer01, aber Geyser läuft auf Proxy01 → manuell kopiert nach `Geyser-Velocity/packs/ResourcePackManager_Bedrock.mcpack` + `custom_mappings/rspm_geyser_mappings.json`. RPM warnt "Geyser installation not detected" — Multi-Host-Architektur erfordert den manuellen Schritt.

### Test 1: FMM native solo (Bridge + Extension deaktiviert)

Bridge-JAR + FMMBridgeExtension als `.bak` weggenommen, FMM-Config `sendCustomModelsToBedrockClients: true`. Geyser registrierte **2701 custom items** (vorher 497).

**Funktioniert nativ:** Mob-Models (besser als unsere Bridge!), Animationen, 3D Schwerter/Rüstung, Combat-BossBar bei manchen Bossen, LevelUp-BossBar, statische Möbel.

**Fehlt nativ:**
- 2D UI-Icons (BagOfCoin etc.) — EM nutzt legacy `custom_model_data` overrides auf Emerald + CMD 31173, RPM scannt nur 1.21.4+ `items/`-Format
- BossBar bei manchen Bossen (Ice Elemental: nein, Alpha Wolf: ja — EM-Inkonsistenz)
- Combat-Nametag mit HP/Bar (FMM zeigt nativ nur statischen Namen)
- EM-GUIs als Popup (nur Inventory, nicht "popup")
- 3D-Waffenpose teilweise falsch (RPM `bedrock_display_offsets.yml` muss eingestellt werden)

**RPM-Bugs (an MagmaGuy melden):** schwarze Schatten auf allen Custom Models, 80-Zeichen-Pfade in Pack.

### Test 2: Bridge + FMM native koexistierend

Bridge wieder aktiv, `gear-3d.enabled: false`, FMM-Config bleibt `true`. **Klare Konflikte:**
- Doppel-Spawn aller Mobs + Static-Möbel (Bridge fake-PIG + FMM nativ)
- Doppel-Nametag (Bridge 3-Zeilen + FMM-statisch)
- BossBar-Suppression-Race-Conditions

→ Coexistence ohne Refactor unmöglich.

### Refactor: Bridge → EM-UX-Layer

**Branch `refactor-em-ux`.** Vor Refactor: **Archive-Tag `archive/2026-05-24-pre-rpm18-pivot`** auf `phase-7.2c-gear-3d` HEAD gepushed (komplette Bridge-Historie gesichert).

**Gelöscht** (27 → 16 Backend-Klassen, JAR 104 KB → 54 KB):
- `geyser-extension/` komplett (5 Klassen + pom + test) — RPM macht jetzt Pack-Generation + Custom-Item-Registration
- `converter/` Package (5 Klassen) — Java→Bedrock-Konvertierung war RPM-redundant
- `bridge/AnimationStateTracker.java` — FMM nativ
- `bridge/EMGearItem.java` — RPM macht 3D Gear
- `bridge/IBridgeEntityData.java` — kein Polymorphismus nötig wenn nur DynamicEntity
- `bridge/StaticEntityData.java` — Statics sind FMM nativ
- `bridge/PacketEntity.java` — keine fake-PIGs mehr
- `EliteMobsItemScannerTest.java` — testete `pickGearTextureRef` (3D-Gear)

**Strippes** (Bridge-Pipeline raus, Controller-Logik bleibt):
- `FMMBedrockBridge` (283 → 200 LoC): keine entityTracker-bridge-Init für Mob-Pipeline-Render, kein Phase 7.2c gear-3d-Block, kein `BedrockModelConverter` Command-Init, `writeEmGearItemsJson` raus
- `BedrockEntityBridge` (300 → 200 LoC): kein bedrockId/GeyserUtils-Verweis, kein StaticEntity-Branch, kein `animationNamesCache`/`getAnimationNames`, `entityDataMap` ist jetzt `Map<ModeledEntity, FMMEntityData>`
- `FMMEntityData` (482 → 170 LoC): kein packetEntity/bedrockEntityId/sortedAnimationNames, kein hideEntity/setCustomEntity in addViewer, kein syncPosition/syncAnimation/sendInitialAnimation. Nur noch BossBar+Nametag-Controller-Holder.
- `PacketInterceptor` (461 → 250 LoC): kein Mob-Suppression (hiddenEntityIds/hideEntity/unhideEntity), kein fake-real Interact-Redirect, kein 3D-gear-Inject. Bleibt: 2D-Item-Inject + BossBar-Suppression + Java-TextDisplay-Suppress.
- `EliteMobsItemScanner` (296 → 155 LoC): `scan3DGear()` + helpers + `pickGearTextureRef` raus
- `FMMBridgeCommand` (156 → 110 LoC): `convert all`-Subcommand raus, nur `debug` bleibt

**Config aufgeräumt:** alte `converter.*` und `elite-items.gear-3d` Sektionen raus. Neue Header-Kommentare beschreiben "EM↔Bedrock UX-Bridge" Architektur.

### Test 3: Refactored Bridge + FMM native koexistierend

`elite-items.gear-3d` als Config nicht mehr nötig (Code weg), FMM `sendCustom: true`. Bridge sauber gestartet, **keine Errors**.

**Test-Ergebnisse:**
- ✅ Mobs single-spawn (kein Doppel mehr)
- ✅ Static-Möbel single-spawn
- ✅ 2D-Items (BagOfCoin etc.) sichtbar in EM-Shop
- ✅ Wolf Alpha BossBar: "Wilder Alphawolf" styled
- ✅ Wolf Alpha Combat-Nametag: HP/Bar/Name 3 Zeilen
- ⚠️ **Doppel-Nametag** (Bridge 3-Zeilen + FMM native Name) → **Fix:** `NametagTextBuilder.compose()` out-of-combat liefert jetzt `Component.empty()`, in-combat nur HP+Bar (kein Name) — FMM zeigt Name nativ
- ❌ **Ice Elemental BossBar: "Evoker | 2"** (statt styled "Tier 13 Eis-Elementar")
- ❌ **Combat-Nametag verschwindet nach Stoppen, kommt bei re-attack nicht wieder** (EM-Combat-Event-Issue — nur 1× enterCombat, keine zweite)

### Diagnose & Fix Ice Elemental BossBar

`EliteMobsHook.getStyledName()` Pfad geprüft:
- EM 10.3.1: für EVOKER-basierte CustomBosses (Ice Elemental: `entityType: EVOKER`, `disguise: POLAR_BEAR`) gibt SOWOHL `livingEntity.getCustomName()` ALS AUCH `eliteEntity.getName()` "Evoker | 2" zurück — kein API-Pfad liefert den YAML-`name: $bossLevel &9Ice Elemental`
- ABER: `modeledEntity.getDisplayName()` (FMM-API) liefert den korrekten styled Namen — Java rendert ja den Mob-Nametag aus genau dieser Quelle
- **Fix:** `FMMEntityData.createBossBarControllerIfElite()` nutzt jetzt `modeledEntity.getDisplayName()` als primary, `EliteMobsHook.getStyledName()` als Fallback (gleiche Source-Priorität wie Nametag)
- **Deployed, aber visueller Test ausstehend** — Session beendet vor finalem Restart

### Branch-Stand

`refactor-em-ux` (lokal, **noch nicht gepushed**). Uncommitted: alle Refactor-Files + neuer Plan-File `docs/superpowers/plans/2026-05-24-refactor-em-ux.md`.

### Memory + Doc Updates

- Neues Memory `magmaguy_native_bedrock_2026-05-24.md` — komplette Auswertung was nativ funktioniert + Lücken
- `project_state.md` umgeschrieben — "EM-UX-Bridge"
- `next_implementation.md` umgeschrieben — Refactor + Coexistence-Test + Phase 7.3
- `MEMORY.md` index aktualisiert (RPM + BetterStructures als refs, obsolete Memories markiert)
- `README.md` + `AGENTS.md` komplett überarbeitet für neue Architektur

### Offen für nächste Session

1. **Visueller Verify Ice Elemental BossBar-Fix** (TestServer-Restart + Bedrock-Test) — `modeledEntity.getDisplayName()` als BossBar-Source sollte styled "Eis-Elementar" zeigen
2. **Combat-Nametag-Issue:** Untersuchen warum `EliteMobExitCombatEvent` nicht feuert (oder `EliteMobEnterCombatEvent` nicht bei re-attack). Eventuell eigenes Damage-Tracking als Heuristik statt EM-Events
3. **Refactor-Branch committen + pushen** (zwei logische Commits: Refactor + BossBar-Source-Fix)
4. **Phase 7.3 starten:** EM-Adventurer's-Guild-Menu + Shop-GUIs als native Bedrock-Forms (Cumulus API)
5. **Schwarze-Schatten + 80-Zeichen-Pfade** an MagmaGuy melden (RPM-Bugs)

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```
