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
