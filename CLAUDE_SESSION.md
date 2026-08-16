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

---

## Session: 2026-05-28 (RPM 2.0.0 Network-Mode + Codex Combat-UX Polish)

### Ausgangslage

Branch `refactor-em-ux` HEAD `3fa02a7` deployed seit 24.05. Server lief 4 Tage durch ohne Restart — der Ice-Elemental-BossBar-Fix war im File aber nie geladen. Außerdem: MagmaGuy released RPM 2.0.0 mit Multi-Module Network-Mode (Velocity/Bungee sub-plugins, Floodgate-derived network key, BedrockShortName hex prefixes gegen 80-Char-Path-Bug, `/rspm status` command).

### Codex Patch #1 (uncommitted, 1724-Build)

`BedrockCombatTrigger` bekommt `EntityDamageEvent`-Handler als zweiten Refresh-Path neben EM-EnterCombat — fängt den "EM feuert kein zweites EnterCombat nach Combat-Pause"-Issue ab. Nametag-Overlay zeigt out-of-combat `Component.empty()` (FMM rendert Name nativ → kein Doppel-Nametag mehr). Neue Config-Keys `damage-refresh-enabled` + `damage-timeout-ticks` (160 = 8s).

### Deploys & Cleanup

- **Bridge 1724-JAR** auf TestServer01 deployed
- **RPM 2.0.0 Bukkit-JAR** war bereits auf TestServer01 + Proxy01 (von Fabi)
- **Proxy-Pfad-Cleanup** (Punkt 3 aus Setup-Plan): manuelle RPM 1.8.0 Outputs gelöscht — `Geyser-Velocity/packs/ResourcePackManager_Bedrock.mcpack` + `custom_mappings/rspm_geyser_mappings.json`
- **Proxy-Restart #1 (20:58):** Velocity lehnt Bukkit-JAR ab ("appears to be a Paper/Bukkit plugin"). Multi-Host-Quirk: das Bukkit-JAR enthält embedded `proxy-extension/ResourcePackManager-Velocity.jar` + `-BungeeCord.jar`, kann sich aber nur auf einem Co-located Backend selbst kopieren
- **Manuell extrahiert:** `unzip -j ResourcePackManager.jar proxy-extension/ResourcePackManager-Velocity.jar` → Proxy/plugins/. Bukkit-JAR als `.bak-wrong-bukkit` gesichert
- **Proxy-Restart #2 (21:01):** RPM 2.0.0 lädt: "Network-key auto-derived from Floodgate key.pem ✓", `NetworkSync starting (poll interval 5000 ms, network-http-offset 1)`. Pollt 8 Backends auf `MC-Port + 1`, alle initial nicht antwortend (Backends noch alt)
- **FMMBridgeExtension Cleanup:** alte Geyser-Extension `Geyser-Velocity/extensions/FMMBridgeExtension-0.1.0-SNAPSHOT.jar` + `fmmbridgeextension/` Datenordner (38 MB inkl. 193 input-Modelle + generated pack) gelöscht — war Reste vom alten Pipeline-Refactor
- **GeyserUtils NPE Root-Cause:** `loadSkin()` (`GeyserUtils.java:384-403`) überschreibt `geometryFile` für jede `.json` im Skin-Ordner. Wenn `listFiles()` z.B. `model-config.json` zuletzt zurückgibt → `.get("minecraft:geometry")` ist null → NPE. **Fix:** 382 überflüssige JSONs in `Geyser-Velocity/extensions/geyserutils/skins/*/` gelöscht (nur `geometry.json` + `texture.png` bleiben). Upstream-Bug noch nicht gefixt (zimzaza4/GeyserUtils HEAD ist `d045474` vom 11.01.2026)

### Codex Patch #2 (1936-Build) — Combat-UX Polish

Nach Reference-Screenshot `references/screenshots/testneu6.png` (zeigt zwei BossBars übereinander):

- `EliteMobDamagedByPlayerEvent` statt rohem `EntityDamageEvent` — derselbe Event den EM für Java-HP-Displays nutzt
- "Evoker | 2" wird auch dann als **Suppression-Alias** gespeichert wenn unsere Bar sofort den korrekten FMM-Namen hat → EM-Doppel-Bar verschwindet
- `hide-on-exit-event: false` neu — HP/Bar bleibt nach ExitCombat sichtbar bis Display-Timeout (matched Java-Feel)
- `damage-timeout-ticks: 0` neu — nutzt EMs `MobCombatSettings.combatDisplayTimeoutSeconds` (default 30s)

### Login-Fail wegen gelöschter FMMBridgeExtension

Fabi versucht zu joinen → Login hängt bei "Lade Ressourcenpakete". Geyser hatte beim 21:01-Boot den FMMBridgeExtension-Pack-Pfad gecached, das File war aber dann gelöscht → `NoSuchFileException: ... fmmbridgeextension/generated-pack.zip` in `SessionLoadResourcePacksEventImpl.infoPacketEntries`. **Lesson:** Pack-Pfade müssen ENTWEDER vor Proxy-Restart gelöscht werden, ODER der Cleanup geht erst nach dem nächsten Restart sauber durch.

### Final State (Restart-Cycle 3)

- **Proxy-Restart #3 (21:40):** Bridge-Extension weg → kein NoSuchFileException. GeyserUtils-Skins clean → kein NPE-Spam. `Merged Bedrock pack published` mit sha1=a1e013a4… ✓
- **Backend-Restart (21:41):** `Network mode detected. Proxy plugin jars extracted to: proxy-extension/`. Backend HTTP-Server auf Port 25574 (`/rspm.zip`, `/bedrock.zip`, `/mappings.json`). `Bedrock conversion complete: 17717 mappings (1704 unique models)`. Backend pusht 2 files an "Bedrock relay for proxy fallback". Bridge 1936-Build geladen: `Phase 7.1c: combat trigger registered`, 13 EM 2D-Items injection map geladen ✓

### Branch-Stand

`refactor-em-ux` HEAD bleibt `3fa02a7` (gepushed seit 24.05.). 8 Files lokal uncommitted (Codex Patch #1 + #2 zusammengeführt im 1936-Build).

### Visueller Test ausstehend

Fabi stoppt heute hier. Nächste Session = Bedrock-Client-Test:

1. **Ice Elemental BossBar:** "Eis-Elementar" styled statt "Evoker | 2"
2. **Wolf Alpha BossBar bleibt unverändert** "Wilder Alphawolf"
3. **Kein Doppel-BossBar mehr** (durch Suppression-Alias-Fix)
4. **Combat-Nametag HP/Bar überlebt Combat-Pause** + kommt bei re-attack via EM-DamagedByPlayer-Event wieder
5. **RPM Network-Mode Pack-Delivery** — Bedrock-Client sollte beim Login Pack vom Velocity-Proxy bekommen, nicht mehr von magmaguy.com Self-Host-URL Richtung Bedrock (Backend hostet weiter aber für Java)

### Offen für nächste Session

1. **Visueller Bedrock-Verify** der vier obigen Punkte
2. **Codex Patches committen** wenn Visual passt — zwei logische Commits ("Phase 7.1c: EliteMobDamagedByPlayerEvent + display-timeout" und "BossBar: capture EM alias for late-suppression")
3. **Push** `refactor-em-ux` (oder direkt merge nach `main`)
4. **Phase 7.3 brainstorming** — EM Adventurer's Guild + Shop-GUIs als Cumulus-Forms
5. **MagmaGuy melden:** schwarze Schatten auf RPM-Custom-Models (in 2.0.0 noch nicht gefixt)
6. **GeyserUtils Bug-Report bei zimzaza4:** `loadSkin` überschreibt geometry-File-Pick

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

---

## Session: 2026-06-04

### Abgeschlossen — Aufräumen vor Phase 7.3
- **pom.xml Versions-Bump** auf den live deployten Stack: FMM `2.5.0 → 2.7.0`, EliteMobs `10.1.1 → 10.4.0`, Floodgate `2.2.3 → 2.2.5-SNAPSHOT`. Build verifiziert (alle Deps lösen auf), 17/17 Unit-Tests grün.
- **Upstream-Bug-Report-Entwürfe** in `docs/upstream-bugs/` (GitHub-fertig, noch nicht gepostet):
  - `rpm-baseitemresolver-legacy-override-gap.md` → RPM ignoriert legacy `models/item/<base>.json` overrides → EM-UI-Items (würde Phase 7.2b obsolet machen wenn gefixt)
  - `rpm-black-shadows-custom-models.md` → schwarze Schatten auf Bedrock-konvertierten Models (TODO: Fabi ergänzt Screenshots + Modellname)
  - `geyserutils-loadskin-json-pick-npe.md` → `loadSkins` last-json-wins NPE + Fix-Vorschlag

### Branch-Stand
- `chore/dep-bump-and-bug-reports` (Commit `032cd0b`) → nach `main` gemerged + gepusht
- `feat/em-cumulus-forms` von aktualisiertem `main` für Phase 7.3 angelegt

### Offen für nächste Session
- **Phase 7.3 brainstorming** — EM Adventurer's Guild + Shop-GUIs als Cumulus Bedrock-Forms
- Bug-Reports auf GitHub posten (macht Fabi)

---

## Session: 2026-06-04 (Phase 7.3 — Bedrock Menu Dialog-Reroute)

### Erkenntnis statt Aufwand
Brainstorming + Spike ergaben: das `/em`-Statusmenü (`PlayerStatusScreen`) hat in EM bereits einen **nativen MC-Dialog-Pfad** (`PlayerStatusScreenDialog`, Java ≥1.21.6), aber EM zwingt Bedrock-Spieler zum Chest-Pfad. **Geyser rendert MC-Dialoge inzwischen als native Bedrock-Forms** (PR #5603). Spike (Bedrock-Chest cancel → EM-Dialog rufen) bestätigte: Bedrock zeigt eine native Form, Sub-Pages cascaden nativ. → Phase 7.3 = **Reroute** statt eigene Cumulus-Forms.

### Implementiert (Subagent-Driven, alle Tasks reviewed)
- `bridge/McVersions` — reine Versionsschwelle (MC ≥ 1.21.6), 3 Unit-Tests
- `bridge/MenuRerouteRegistry` — Titel-Normalisierung (Farbcodes strippen) + Titel→Invoker-Lookup, 4 Unit-Tests; registry-erweiterbar für weitere EM-Menüs
- `elite/EliteMobsHook` — Reflection-Wrapper `statusIndexMenuTitle()` + `openNativeStatusDialog(Player)` (isoliert die EM-Internals; markApiBroken NICHT bei Reflection-Miss, damit Phase-7.1-Features nicht mitabschalten)
- `bridge/BedrockMenuRerouteListener` — `InventoryOpenEvent` @HIGH: Bedrock + Titel-Match → cancel + next-tick EM-Dialog
- `FMMBedrockBridge#onEnable` — Registrierung gated auf `floodgate && em && MC≥1.21.6 && config phase73.bedrock-dialog-reroute`; Spike entfernt
- Config: `phase73.bedrock-dialog-reroute: true`
- `docs/upstream-bugs/em-route-bedrock-to-dialog.md` — Feature-Request an MagmaGuy (Bedrock nativ zum Dialog routen → Bridge-Reroute später entbehrlich)

### Verifiziert (2026-06-04, TestServer01 paper-1.21.10)
Boot `Phase 7.3: Bedrock menu dialog-reroute registered`. Bedrock-Client `/em` → native Form + Sub-Buttons funktionieren. 24 Unit-Tests grün. Branch `feat/em-cumulus-forms`.

### Spec + Plan
- `docs/superpowers/specs/2026-06-04-em-bedrock-dialog-reroute-design.md`
- `docs/superpowers/plans/2026-06-04-em-bedrock-dialog-reroute.md`

### Offen
- Branch `feat/em-cumulus-forms` → finishing (merge nach main + push)
- Upstream-Drafts (`docs/upstream-bugs/`) auf GitHub posten (macht Fabi)
- Weitere EM-Menüs (Shops/Quests) via Registry-Eintrag — nur falls Dialog-Äquivalent vorhanden

---

## Session: 2026-06-05 (Phase 7.3b — Bedrock NPC Quest-Menu Dialog-Reroute)

### Abgeschlossen

- **pom.xml Versions-Bump:** `paper-api` von `1.21.4-R0.1-SNAPSHOT` → `1.21.10-R0.1-SNAPSHOT` (entspricht live deploytem TestServer01). FMM 2.7.0, EliteMobs 10.4.0, Floodgate 2.2.5-SNAPSHOT waren bereits aktuell.

- **Phase 7.3b implementiert** (Branch `feat/em-quest-reroute`, Subagent-Driven Development, alle Tasks reviewed):
  - `bridge/RerouteDecision` — pure Resolver ohne Side Effects: erkennt ob ein Inventory-Open-Event zu einem Status-Dialog oder Quest-Dialog umgeleitet werden soll; kapselt Status>Quest-Priorität + per-Flag-Gating (`bedrock-dialog-reroute` vs. `bedrock-quest-reroute`). 5 Unit-Tests.
  - `elite/QuestMenuContext` — opaque Carrier-Record für den vom Reflection-Wrapper zurückgegebenen Quest-Menu-Kontext. 1 Unit-Test.
  - `elite/EliteMobsHook.tryRecoverQuestMenu` — Reflection in EM's `QuestInventoryMenu`-statische Maps; entfernt den Map-Eintrag nach Treffer (kein Leak, da cancelled Open kein Close-Event feuert).
  - `elite/EliteMobsHook.openNativeQuestDialog` — ruft EM's `QuestMenu.generateDialogMenu(Player, ...)` per Reflection auf; isoliert von `markApiBroken` (Failure schlägt nur lokal fehl, Phase-7.1-Features bleiben aktiv).
  - `bridge/BedrockMenuRerouteListener` — dispatcht Status vs. Quest via `RerouteDecision`; öffnet den passenden nativen Dialog im nächsten Tick.
  - `FMMBedrockBridge#onEnable` — Listener wird registriert wenn **mindestens eine** Reroute-Flag aktiv ist (`bedrock-dialog-reroute || bedrock-quest-reroute`).
  - Config: `phase73.bedrock-quest-reroute: true` (unabhängig von `bedrock-dialog-reroute` umschaltbar).

- **30 Unit-Tests grün** (vorher 24 → +6 für RerouteDecision + QuestMenuContext).

### Build
```
/usr/share/idea/plugins/maven/lib/maven3/bin/mvn clean package
```

### Spec + Plan
- `docs/superpowers/specs/2026-06-05-phase73b-quest-menu-reroute*.md`
- `docs/superpowers/plans/2026-06-05-phase73b-quest-menu-reroute*.md`

### Offen
- ~~Manuelle Bedrock-Verifikation ausstehend~~ → **erledigt 2026-06-13** (siehe unten)
- ~~Branch `feat/em-quest-reroute` → merge nach main~~ → gemerged
- Upstream-Drafts (`docs/upstream-bugs/`) auf GitHub posten (macht Fabi)

## Session: 2026-06-13 — Live-Verifikation 7.3/7.3b auf EM 10.5.0 + Plugin-Update-Welle

**Stack-Update (Fabi über AMP):** EM 10.4.0→**10.5.0**, RPM 2.0.1→**2.0.2**, FMM 2.7.0→**2.7.1**, BetterStructures 2.3.1→**2.4.0**, ResurrectionChest **2.1.1** neu.
RPM-Update-Quirks: Backend-JAR muss exakt `ResourcePackManager.jar` heißen (versehentliches Leerzeichen → Bukkit lädt zwei RPM-Instanzen). Velocity-Companion auf Proxy01 muss bei jedem RPM-Bump mit-aktualisiert werden — Quelle ist die vom Backend auto-extrahierte `plugins/ResourcePackManager/proxy-extension/ResourcePackManager-Velocity.jar` (kein manuelles `unzip -j` mehr nötig).

**Source-Analyse gegen die neuen Versionen (refs gepullt):**
- **7.3 + 7.3b Reflection in EM 10.5.0 INTAKT.** Alle Targets vorhanden; `getIndexChestMenuName()` ist Lombok-`@Getter`-generiert (Source-grep negativ ⇒ Fehlalarm, Methode existiert zur Laufzeit, intern in `CoverPage.java:72` genutzt).
- **EM 10.5.0 routet Bedrock weiter zwangsweise zum Chest** (`PlayerStatusScreen` L36 + `QuestMenu.generateQuestMenu` L47: `|| GeyserDetector.bedrockPlayer(player) ||`). Reroute bleibt nötig + valide. Das neue `useQuestDialogueBossBars` (default true) ist ein inerter Stub (kein Code-Leser), beeinflusst den Reroute-Output (`generateDialogMenu→DialogMaker.sendQuestMessage→DialogManager.sendDialog`) nicht.
- **RPM 2.0.2 schließt den 7.2b-`BaseItemResolver`-Gap (Code-Evidenz):** neuer `GenericJavaScanner.scanLegacyCustomModelOverrides` scannt `assets/minecraft/models/item/<base>.json`, leitet baseItem aus Dateiname ab (`emerald.json`→`minecraft:emerald`), liest CMD-overrides → synthetisiert range_dispatch-def. ⇒ Phase 7.2b voraussichtlich redundant — empirischer Check ausstehend.

**Live-Bedrock-Test (TestServer01, EM 10.5.0, Bedrock-Spieler `.Nightgame2272`):** `/em`, Quest-NPC single, Quest-NPC multi → **alle drei native Forms** (statt Chest). **0** `Phase 7.3`-/Reroute-Warnungen + **0** Bridge-Exceptions im Log ⇒ Reflection sauber. Code-Beweis bestätigt: Forms kommen vom Bridge-Reroute (EM allein hätte Chests gezeigt). 7.3 + 7.3b = **verifiziert, gepusht**.

### Offen nach diesem Stand
- ~~RPM-2.0.2 empirisch: gemergte Geyser-Mappings auf `minecraft:emerald`+CMD→bagofcoins prüfen + Bedrock-Render → wenn ok, **Phase 7.2b rausreißen**.~~ → erledigt 2026-06-14 (siehe unten)
- Upstream-Drafts auf GitHub posten (macht Fabi).

---

## Session: 2026-06-14 — Phase 7.2b Rückbau

### Hintergrund

RPM 2.0.2 hat den `BaseItemResolver`-Gap geschlossen: `GenericJavaScanner.scanLegacyCustomModelOverrides` scannt `assets/minecraft/models/item/<base>.json`, liest CMD-overrides aus und synthetisiert range_dispatch-Definitionen für Geyser. Die Bridge-eigene 2D-Item-Inject-Pipeline (Phase 7.2b, bridge_em Namespace) ist damit redundant. De-Risk-Gate-Ergebnis (Analyse gegen RPM-2.0.2-Source + Geyser-Limitation-Check): **10 von 12 EM-UI-Items** rendern nativ auf Bedrock. Die fehlenden 2 sind ein Upstream-Problem, kein Regressionsrisiko durch den Rückbau.

### Was wurde entfernt

**Branch:** `refactor/remove-phase72b`

**Quell-Dateien (10 Produktions- + 5 Test-Dateien gelöscht):**
- `bridge/EliteMobsItemScanner.java` — EM-Resourcepack-Scanner (CMD-Override-Erkennung)
- `bridge/EMCustomItem.java` — Record: javaMaterial, customModelData, sourceTexturePath, bedrockKey
- `bridge/BedrockInventoryRefresher.java` — Bukkit-Listener: erzwingt WINDOW_ITEMS-Resend für Bedrock-Spieler
- `bedrock/BedrockItemPackBuilder.java` — generiert `em_bridge_pack.mcpack`
- `bedrock/GeyserMappingsWriter.java` — schreibt `em_bridge_mappings.json`
- `maintenance/MaintenanceState.java`, `MaintenanceStateStore.java`, `MaintenanceTracker.java`, `OpDriftNotifier.java`, `PackHashCalculator.java` — Drift-Detection + Ops-Warn-Subsystem
- Dazugehörige JUnit-5-Tests (5 Dateien)

**Weitere Änderungen:**
- `PacketInterceptor.java` — 2D-item_model-Inject-Branch (SET_SLOT + WINDOW_ITEMS + ENTITY_METADATA) entfernt, totes `import java.util.Map;` entfernt
- `FMMBedrockBridge.java` — Phase-7.2b-Block in `onEnable()` entfernt (Scanner-Init, Artefakt-Generierung, Maintenance-Wiring)
- `commands/FMMBridgeCommand.java` — `maintenance`-Subkommando-Zweig entfernt (nur `debug` bleibt)
- `config.yml` default — `elite-items:`-Sektion entfernt
- README.md, CLAUDE.md, CLAUDE_SESSION.md — Dokumentation aktualisiert

### Warum (De-Risk-Gate-Ergebnis)

RPM 2.0.2 (`GenericJavaScanner.scanLegacyCustomModelOverrides`) konvertiert alle EM-UI-Items die auf Flat-Icon-Materialien liegen (emerald, stained_glass, redstone, paper etc.) nativ zu Bedrock. **10 von 12** EM-UI-Icons sind damit abgedeckt.

**Die 2 nicht abgedeckten Items — bekannte Bedrock/Geyser-Limitation:**
- `green_banner` + CMD 31173 → `boxinput` (Eingabe-Rahmen im Verzauberer-/Elite-Scroll-Menü)
- `red_banner` + CMD 31173 → `boxoutput` (Ausgabe-Rahmen)

Ursache: Bedrock kann `custom-item-v2` nicht auf Banner-Basis-Items anwenden — Banner sind als Block-Entity mit Pattern-Rendering implementiert, nicht als flache Item-Icons. RPM kann keinen Geyser-Custom-Item-Eintrag für Banner ausstellen der auf Bedrock greift. Diese Icons sind für Bedrock-Spieler unsichtbar, bis EM oder RPM die Basis-Items auf ein Flat-Icon-Material (z.B. `paper`) umzieht. Upstream-Draft: `docs/upstream-bugs/em-banner-ui-items-bedrock.md`.

Diese Einschränkung ist **akzeptiert** — die betroffenen Icons sind kosmetische Rahmen im Verzauberer-/Scroll-Menü, kein Kern-Gameplay. Das Entfernen der Bridge-Schicht reduziert die Codebasis erheblich (10+5 Klassen, Maintenance-Subsystem, SCP-Deploy-Prozess) ohne funktionale Regression.

### Tests

13 Unit-Tests (nach Entfernen der 5 Phase-7.2b-Tests von vormals 18) — alle grün.

### Commits

Zwei logische Commits auf `refactor/remove-phase72b`:
1. `refactor(7.2b): remove dead java.util.Map import from PacketInterceptor`
2. `docs(7.2b): record removal; EM UI items now native via RPM 2.0.2 (banner gap noted)`

---

## Session: 2026-07-10 — Merge `refactor/remove-phase72b` → `main`

### Was passiert ist
Die in den Sessions 2026-07-07/07-08 getroffene Grundsatzentscheidung (natives Mob-Rendering/Animation ✓ via FMM 2.10 + RPM 2.2 + EM 10.7; Combat-BossBar + HP-Nametag = Feature-Gap → Bridge bleibt, reduziert auf 7.1a/7.1b) wurde umgesetzt: der Phase-7.2b-Removal-Branch ist nach `main` gemerged.

- **Backup vor dem Merge:** Tag `backup/pre-72b-merge-main` auf den alten `main`-Stand (`4a277d8`) gesetzt + zu origin gepusht. (Zusätzlich weiterhin `archive/2026-05-24-pre-rpm18-pivot` als Pre-Pivot-Sicherung.)
- **Merge:** `git merge --no-ff refactor/remove-phase72b` → expliziter Merge-Commit `be08a2f` (bewusst kein Fast-Forward, damit der Refactor als Einheit revertierbar bleibt).
- Kein Plugin-Code in dieser Session — reiner Integrations-/Doku-Schritt.

### Git-Stand danach
- `main` enthält Phase-7.2b-Removal + FMM-2.10.1/EM-10.7.2-Bump + HANDOFF/Skills-Bundling.
- Rückweg im Notfall: `git reset --hard backup/pre-72b-merge-main` (alter main) bzw. Merge-Commit revert.

### Offen (siehe HANDOFF Abschnitt 3)
- BossBar/Nametag Live-Verify MIT aktivierter Bridge auf Bedrock.
- Upstream-Report an MagmaGuy (RPM-Geyser-Bridge hardcodet `ResourcePackManager` → Symlink-Zwang auf case-sensitivem FS).
- Waffen-Offset (separat, kein Bridge-Feature): legacy `custom_model_data` re-exportieren.

---

## Session: 2026-07-28/29 — Upstream-Changelogs, Server-Backup, Claude auf dem Server, Update-Tooling

Kein Plugin-Code. Diagnose, Server-Setup und Tooling.

### Neue Upstream-Releases (von Fabi aus dem MagmaGuy-Discord eingespielt)
FMM **2.10.2**, EM **10.7.3**, RPM **2.3.0**, BetterStructures **2.6.3** (+ EternalTD 1.6.4, ResurrectionChest 2.2.5, CannonRTP 1.1.4, BetterFood 1.4.4).

**Der Source dieser Builds ist NICHT auf GitHub.** `setup-references.sh` zeigt FMM/RPM/EM weiterhin auf `2.10.1 / 2.2.2 / 10.7.2` (letzter Push 28.06.); nur BetterStructures 2.6.3 ist gepusht. Diese Versionen lassen sich also nicht im Quellcode gegenprüfen — Verifikation nur live gegen die JARs.

### Was uns davon trifft
- **RPM 2.3.0** — „Bedrock custom-entity bridge updated for Geyser 2.11". Parallel hat GeyserModelEngine upstream `fix/geyser-2.11-sync` gemerged (HEAD `ae3b04e`): zwei unabhängige Projekte fixen denselben Bruch ⇒ **Geyser 2.11 hat die Custom-Entity-API geändert**.
- **Geyser auf Proxy01 ist `2.10.1-b1175`** (per SSH verifiziert, JAR vom 27.06.) — der Bruch trifft uns also **noch nicht**. Aber: sobald Geyser auf 2.11 geht, fällt Bedrock mit RPM 2.2.2 auf Pig-Fallback. Die `.b1107`/`.b1129`-Backups im Plugin-Ordner belegen einen aktiven Update-Mechanismus ⇒ **Geyser nicht anfassen, bis RPM 2.3.0 läuft**.
- **RPM 2.3.0** behauptet „proxy networks stay fully automatic" ⇒ könnte den Symlink-Workaround **und** den geplanten Upstream-Report erledigen. Nach dem Update testen, nicht annehmen.
- **EM 10.7.3** fasst unseren Rest-Scope an, beides Risiko statt Gewinn:
  - „Proximity boss bars no longer flicker and reorder wildly" → unsere First-Match-Heuristik + `BossBarRegistry` in `PacketInterceptor.java:120-145` hängen genau daran (7.1a).
  - „[New] Bedrock (Geyser) players now see NPC role tags (`bedrockNPCRoleYOffset`)" → EM sendet jetzt selbst Nametag-artiges an Bedrock; mögliche Doppelung mit `BedrockNametagController` (7.1b).
- **FMM 2.10.2** unkritisch (1.20.2-Spawn-Fix, MagmaCore).

### Server-Backup TestServer01
`/home/amp/backups/TestServer01-20260728-2211/` — 1,8 GB zstd (2,98 GB / 26.051 Einträge, Integrität geprüft): paper-JARs unkomprimiert, `plugins.tar.zst`, `server-configs.tar.gz`, `MANIFEST.txt` (alle Plugin-Versionen), `SHA256SUMS`.

### Claude Code auf dem Server
Fabi wollte Claude auf dem Server, gestartet als **root**, weil „die AMP-Console komisch ist". **Missverständnis aufgelöst:** die AMP-Web-Console ist keine Shell, sondern der stdin des Minecraft-Servers — deshalb gehen dort nur Server-Befehle. Der Systemuser `amp` hat eine normale bash (diese Session arbeitet die ganze Zeit damit). Root bringt nichts dazu; `amp` ist ohnehin in der **docker-Gruppe** und damit faktisch root-äquivalent. Der Claude-Installer weigert sich zudem selbst, unter `sudo` zu laufen.

Installiert: **Claude Code 2.1.220** (nativer Build, kein Node nötig) als `amp` unter `~/.local/bin/claude`, PATH in `~/.bashrc` ergänzt. Login steht aus (nur interaktiv möglich). RCON auf TestServer01 bewusst **nicht** aktiviert (`enable-rcon=false`) ⇒ der Server-Claude kann keine Server-Befehle absetzen.

### Neu im Repo: `server-tools/` (Commit `f1dd00c`)
- `plugin-update-check.sh` — meldet Plugin-Updates, ändert nichts, Exit 10 = Updates da (cron-tauglich). Trennt **MANUELL** (Discord/Premium) und **OHNE QUELLE** (ungeprüft) sichtbar von AKTUELL, statt Lücken als Entwarnung auszugeben. `[PP]`/Polymart werden übersprungen (eigener Updater).
- `backup-testserver.sh`, `server-CLAUDE.md` (Ziel: `~/.claude/CLAUDE.md`), `README.md`.

Beim Bau verifiziert und dabei **vier API-Fallstricke** gefunden (in `server-tools/README.md` dokumentiert):
1. `api.papermc.io/v2` ist abgeschaltet (`{"error":"sunset"}`) → `fill.papermc.io/v3`
2. Modrinth **ohne Loader-Filter** liefert für Bukkit-Plugins Velocity-/Fabric-Versionen (real bei LuckPerms, NoChatReports)
3. Geyser `.../builds/latest` antwortet **leer**; Buildnummern stehen in `.../versions/<v>` unter `.builds`
4. Versionsvergleich muss **numerisch** sein — sonst gilt ProtocolLib `5.4.1-SNAPSHOT` als veraltet gegenüber Release `5.4.0`, und Floodgate `b132` als „aktuell" gegenüber `b138` (Buildnummer muss vor dem Entfernen der Klammer ausgelesen werden)

### Update-Lage TestServer01 (Script-Output)
Paper **113 → 130**; Floodgate **b132 → b138**; EssentialsX 2.21.2 → 2.22.0; FAWE 2.14.1 → 2.15.3; LuckPerms 5.5.8 → 5.5.53; Skript 2.12.2 → 2.16.0; packetevents 2.12.1 → 2.13.0. ProtocolLib läuft als Dev-Build **neuer** als der Release (nicht downgraden, wird von LibsDisguises gebraucht). 14 Plugins manuell, 5 ohne Quelle, 15 fremdverwaltet.

### Altlast entdeckt
`GeyserModelEngine-1.0.3.jar` bringt ein geshadetes **packetevents 2.11.2** mit, während separat **2.12.1** installiert ist — zwei Versionen derselben Library auf einem Classpath. GeyserModelEngine hookt nur ModelEngine (Ticxo), nicht FMM ⇒ seit dem nativen Rendering vermutlich überflüssig. Wegwerf-Kandidat, vorher mit Fabi klären.

---

## Session: 2026-08-02 — Minecraft 26.x: Branch `feat/mc-26.2-readiness`

### Upstream-Check (Auftrag: „schau ob die Updates jetzt auf GitHub sind")
**Nein.** FMM/RPM/EM hängen weiter auf dem Stand vom **28.06.** (`dfd7aed3` 2.10.1 / `b2c36b7` 2.2.2 / `4c9bba73` 10.7.2) — die Discord-Builds 2.10.2 / 2.3.0 / 10.7.3 sind nicht gepusht. GitHub-Releases helfen nicht (letzte Tags uralt: FMM 05/2025, RPM 07/2024, EM 03/2023). Bewegt hat sich nur: **BetterStructures 2.6.3** (22.07., für uns irrelevant), **GeyserModelEngine** (31.07., nur Bukkit→Folia-Scheduler), GeyserUtils unverändert (loadSkin-Bug offen).

### Der eigentliche Fund: Minecraft hat die Versionierung umgestellt
Beim Auflösen der Frage „was ist die höchstmögliche MC-Version" kam heraus, dass Mojang das `1.x`-Schema abgeschafft hat: **kein 1.22**, sondern **26.1** („Tiny Takeover", 24.03.2026) und **26.2** („Chaos Cubed", Juni 2026), Format `YY.Drop.Hotfix`. Paper-Artefakt heißt jetzt `26.2.build.87-stable` statt `<mc>-R0.1-SNAPSHOT`.

**Der MagmaGuy-Stack ist da längst:** FMM 2.10.1, RPM 2.2.2 **und** EM 10.7.2 kompilieren alle bereits gegen `spigot-api:26.2` (in den `references/`-Poms nachgeprüft). Die Bridge war das hinterherhinkende Teil.

**Abhängigkeitskette für MC 26.2** — Java 25 war neu und stand auf keinem Zettel:

| Komponente | Nötig | Server (02.08.) |
|---|---|---|
| Java | **25** (Paper 26.2 = Class-File 69) | 21 ⚠️ |
| Geyser | 2.11.0 (26.2 gemerged 10.07., PR #6452) | 2.10.1-b1175 ⚠️ |
| RPM | 2.3.0 (wegen Geyser 2.11) | 2.2.2 ⚠️ |
| PacketEvents | 2.13.0 (26.2-Support, 22.06.) | 2.12.1 ⚠️ |
| FMM / EM | bauen schon gegen 26.2 | ok |

Damit ist die **Geyser-Sperre aus der Vorsession genau der Knoten**: MC 26.2 → Geyser 2.11 → RPM 2.3.0. Die geplante Reihenfolge (RPM zuerst) bleibt richtig.

### Branch `feat/mc-26.2-readiness`
Entscheidung mit Fabi: **ein JAR für beide Generationen**, damit sofort deploy- und testbar statt bis zur Server-Umstellung blind.

- `pom.xml`: `paper-api` → `${paper.api.version}` = `26.2.build.87-stable`; PacketEvents `2.12.1` → **`2.13.0`**; `maven.compiler.source/target` → **`maven.compiler.release=21`**
- **Maven-Profil `legacy-1.21`** kompiliert dieselben Quellen gegen `1.21.10-R0.1-SNAPSHOT`
- **`verify-both-apis.sh`** — beide Durchläufe + Assertion auf Bytecode-Version 65. Findet auch das JDK selbst (JRE 25 reicht **nicht**, die hat kein `javac`).
- `plugin.yml`: `api-version` bleibt **bewusst** `'1.21'` — Mindest-Angabe; ein 1.21.x-Server würde `'26.2'` ablehnen, ein 26.2-Server akzeptiert `'1.21'`. FMM/EM machen es genauso (deklarieren `1.21.4`, kompilieren gegen 26.2).

### Bugfix `McVersions` (hätte 7.3 still abgeschaltet)
Der Parser brach bei einem nicht-numerischen Segment mit `NumberFormatException` ab und lieferte `false`. Bei `26.2.build.87-stable` hätte der `>= 1.21.6`-Gate damit **den Phase-7.3-Reroute auf einem 26.x-Server lautlos deaktiviert** — kein Log, kein Fehler, das Feature einfach weg. Jetzt werden führende numerische Segmente geparst und Trailing-Junk ignoriert; ein nicht-numerisches *erstes* Segment bleibt `false` (fail-closed, da nicht ordenbar). Regressionstests: der neue Test fällt mit dem alten Parser (gegengeprüft), hält mit dem neuen.

### Verifikation
`bash verify-both-apis.sh`: **beide Durchläufe BUILD SUCCESS, je 16/16 Tests grün**, Bytecode-Version 65. Artefakt `FMMBedrockBridge-0.1.0-SNAPSHOT-20260802-1505.jar`.

Zusätzlich geprüft: alle 25 Bukkit/Paper-Imports existieren in 26.2; die riskanten Member (`Attribute.MAX_HEALTH`, `BarColor`/`BarStyle`, `Bukkit.createBossBar`, `getCustomName`, `getAttribute`, `getMaxHealth`) existieren in **beiden** Generationen. Deprecation-Diff 1.21.10 ↔ 26.2: **identisch, 4 Stück** (`getDescription`, `getMaxHealth`, `InventoryView.getTitle`, `getCustomName`) — Altlasten, **nichts neu durch 26.2**, kein Handlungsdruck.

### Offen
- Velocity-Kompatibilität mit 26.2 ungeprüft (3.5.1 und 4.0.0 sind draußen, Proxy auf 3.5.0-SNAPSHOT)
- Floodgate/ProtocolLib/LibsDisguises/FAWE/Skript unter Java 25 ungeprüft

### Nachtrag 2026-08-02 (abends): Update-Runde live — Geyser-2.11-Bruch gefixt

Fabi hat FMM **2.10.2**, EM **10.7.3**, RPM **2.3.0** aufs Backend deployt (17:24–17:25, Boot 17:28) und danach **Geyser auf 2.11.0-b1205** hochgezogen. Ergebnis: **die RPM-Geyser-Bridge war tot.**

```
Couldn't pass ProxyInitializeEvent to geyser 2.11.0-b1205 (git-master-3aeedfa)
java.lang.NoClassDefFoundError: org/geysermc/geyser/entity/EntityDefinition
    at GeyserExtensionManager.enableExtension(GeyserExtensionManager.java:85)
```
`bridge ready with …` kam gar nicht mehr (vorher 316), 89 Exceptions im Log, nur GME + GeyserUtils aktiviert.

**Ursache:** Backend auf 2.3.0, **Proxy-Seite noch 2.2.2 vom 07.07.** — deren Bridge-Extension referenziert eine Klasse, die Geyser 2.11 entfernt hat. Exakt die dokumentierte Kopplung.

**Warum das Backend-Update den Proxy nicht mitzog** (Fabis Erwartung): RPM schreibt nie in fremde Server-Verzeichnisse. Das Backend legt die Extension nur unter `plugins/ResourcePackManager/geyser-extension/` bereit und loggt es explizit („copy it into your Geyser's 'extensions' folder … you must ALSO install ResourcePackManager on the proxy"). Der Auto-Install läuft auf der **Proxy**-Seite — belegt durch die mtimes: Proxy-RPM-JAR 07.07. 21:56, Extension 07.07. 22:13 (17 Min später, also vom Proxy-Plugin geschrieben). Ein veraltetes Proxy-RPM installiert folglich weiter die alte Extension.

**Fix:** beide Proxy-Dateien ersetzt (Backups als `.bak-20260802-1826`), SHA-256-gleich zum Backend:
- `plugins/ResourcePackManager.jar` → 2.2.2 → **2.3.0**
- `plugins/Geyser-Velocity/extensions/ResourcePackManager-GeyserBridge.jar` → 377588 B (07.07.) → **375482 B (15.07.)**

**Nach einem** Neustart (nicht zwei) grün:
```
Erweiterung ResourcePackManagerGeyserBridge aktiviert
Preloaded 316 custom Bedrock entity identifiers and 281 property definition(s)
Registered 316 RSPM custom Bedrock entity definitions with Geyser
ResourcePackManager Geyser bridge ready with 316 custom entity definitions.
Geyser auf UDP-Port 25565 gestartet — Fertig (19,907s)!
```
0 NoClassDefFoundError, 0 Exceptions. **Stack jetzt: FMM 2.10.2 + EM 10.7.3 + RPM 2.3.0 + Geyser 2.11.0-b1205.** Die Geyser-Sperre ist damit aufgelöst.

**RPM 2.3.0 ist eine Universal-JAR** — enthält `plugin.yml` *und* `velocity-plugin.json` (beide 2.3.0); den Ordner `proxy-extension/` gibt es nicht mehr. Die alte Prozedur (`unzip -j … proxy-extension/ResourcePackManager-Velocity.jar`) ist hinfällig; `CLAUDE.md` entsprechend korrigiert.

**Offen:** Bedrock-Rendering in-game noch nicht verifiziert (Logs beweisen nur die Pipeline). Symlink `ResourcePackManager -> resourcepackmanager` weiterhin nötig — die Bridge liest aus dem großgeschriebenen Pfad, RPM schreibt in den kleingeschriebenen ⇒ Upstream-Report bleibt fällig. `NetworkSync: previous poll is still running` erscheint pro Boot 3–4× über 8 Backends (auch nach dem Merge) — laut eigener Meldung ein Hinweis auf ein hängendes Backend-Fetch; unkritisch, aber einen Blick wert.

## Session: 2026-08-08 — Netzwerk-Ausfall (GeyserUtils × Geyser 2.11.1) + Props-Diagnose

### Vorfall: Bedrock sah netzwerkweit KEINE Entities mehr

Fabi hatte Geyser aktualisiert (JAR-Tausch 07.08. 21:18, wirksam mit dem Proxy-Boot heute 14:07).
Danach sahen Bedrock-Spieler auf dem gesamten Netzwerk **gar keine Entities** — nicht nur keine
Custom Models.

**Root Cause:** die GeyserUtils-**Geyser-Extension** (Build 01.02.2026, `extension.yml: api: 2.4.1`)
**ersetzt** Geysers eigenen AddEntity-Translator und greift dabei auf `Registries.ENTITY_DEFINITIONS`
zu — ein Feld, das **Geyser 2.11.1-b1210** nicht mehr hat:

```
[geyser]: Konnte Paket ClientboundAddEntityPacket nicht übersetzen
java.lang.NoSuchFieldError: Class org.geysermc.geyser.registry.Registries
  does not have member field 'org.geysermc.geyser.registry.SimpleMappedRegistry ENTITY_DEFINITIONS'
	at me.zimzaza4.geyserutils.geyser.replace.JavaAddEntityTranslatorReplace.translate(...:57)
```

Damit starb **jedes** Entity-Spawn, bevor irgendetwas gerendert wurde. Kausalität sauber belegt:

| Log | Vorkommen |
|---|---|
| 01.08.–07.08. (alle Rotationen) | **0** |
| 2026-08-08-2.log.gz | 29.316 |
| latest.log (Boot 14:07) | 4.086 |

**Fix:** Extension deaktiviert (umbenannt, nicht gelöscht) →
`geyserutils-geyser-1.0-SNAPSHOT.jar.disabled-20260808-geyser2111`. Die Bridge nutzt GeyserUtils
post-Pivot nicht mehr (`grep` über `src/` leer), FMM/RPM rendern nativ. **Nach Restart verifiziert:**
0 AddEntity-Fehler, `bridge ready with 316`, GeyserModelEngineExtension lädt auch ohne GeyserUtils
sauber. Übrig nur die 3 bekannten `battlepass_*`-Item-Konflikte (kosmetisch).

Upstream hätte Commit `9dc686a` (12.07.2026, „Update to Geyser API 2.11.0") — Neubau nur nötig,
falls GeyserUtils je wieder gebraucht wird.

### Diagnose: Props erscheinen auf Bedrock als Schwein

Danach der eigentliche Rest-Befund von Fabi: Mobs rendern korrekt, **Props sind Schweine**.

Das Schwein ist FMMs **Träger-Entity** — `BedrockModeledEntity` nutzt für den Fake-Entity-Pfad
`carrierEntityType(EntityType.PIG)`, während DynamicEntity über `bindToUnderlyingEntity` den echten
Mob bindet. Schwein heißt also: die Custom-Entity-Zuordnung hat nicht gegriffen.

**Systematisch ausgeschlossen:**
1. *Java-Seite falsch?* Nein. `/fmm debug bedrock on` zeigt nur die `displayTo entry`-Zeile — die
   Fallback-Zeilen (`wasAlreadyViewing=`, `V2=false fallback`, `FALLBACK to UUID-broadcast`) fehlen
   alle. Da sie im Code **nach** dem Bedrock-Zweig stehen (der mit `return` endet), beweist ihr
   Fehlen, dass der Zweig genommen wurde und `isAvailable()` true war.
2. *Pack unvollständig?* Nein. Diff aller 315 lokalen `.bbmodel` gegen die 316 Entity-Defs im
   Merged Pack: **kein Prop-Modell fehlt**. (Zwei Fehlalarme im ersten Diff — Groß-/Kleinschreibung
   und Leerzeichen→Unterstrich; nach Korrektur bleiben nur 10 Item-Modelle übrig, die korrekt keine
   Entity-Def brauchen.)
3. *Zuordnung kommt zu spät?* Nein — sie kommt **gar nicht**. `RspmGeyserBridgeCore` führt
   `CUSTOM_ENTITIES: GeyserConnection → {javaEntityId → identifier}` und hat eigene Warnungen
   `loggedLateEntityReplacement` und `warnedUnregisteredSpawnDefinition`. **Beide haben nie gefeuert**
   — im ganzen Proxy-Log stehen nur die vier Boot-Zeilen der Extension.

**Verbleibender Verdacht:** `prepareEntitySpawn` geht im Fake-Entity-Pfad verloren — entweder stumm
geschluckt in `runBridgeSafely(...)` oder übersprungen im `pluginProvider`-Early-Return von
`FakeCustomEntityImpl.displayTo` (das ist der einzige Zweig, der `prepareBedrockSpawn` auslässt).
Der Bukkit-Pfad (Mobs) meldet dagegen und spawnt erst einen Tick später über den Entity-Tracker.

Nicht in der Bridge fixbar → **Upstream**. Caveat im Report vermerkt: Zeilenangaben stammen aus
FMM 2.10.1 / Magmacore-HEAD (28.06.), deployt ist 2.10.2 (Source nicht auf GitHub).

### Upstream-Report-Entwürfe

- **NEU** `docs/upstream-bugs/fmm-props-render-as-pig-carrier-on-bedrock.md`
- **NEU** `docs/upstream-bugs/rpm-geyser-bridge-case-sensitive-pack-path.md` — der Symlink-Bug, jetzt
  hart belegt: zwei Zeilen aus **demselben** Log von heute zeigen, dass das Plugin nach
  `plugins/resourcepackmanager/…` schreibt und die Extension aus `plugins/ResourcePackManager/…`
  liest. Damit ist bewiesen, dass er in **2.3.0** noch drin ist.
- `rpm-black-shadows-custom-models.md` als **✅ erledigt** markiert — MagmaGuy hat den Schatten-Bug
  gefixt (Info Fabi). Nie eingereicht, bleibt als Beleg liegen.

Fabi weiß noch nicht, wo er die Reports einreicht → bleiben vorerst Entwürfe.

### Weitere Erkenntnisse

- **Staging-Workflow geklärt (wichtig!):** TestServer01 ist Staging, Survival01 Produktion. Survival
  bleibt **bewusst** auf Dezember-2025-Ständen (FMM 2.3.14, EM 9.6.0, RPM 1.7.0, BS 2.1.0), bis auf
  Test alles läuft — dann wird der Plugin-Stand rübergezogen. Alte Versionen dort sind **kein Fund**.
- **`NetworkSync: previous poll is still running` ist damit erklärt und unkritisch:** `merging 1
  Bedrock zip(s) across 8 backend(s)` — nur TestServer01 liefert überhaupt ein Bedrock-Bundle, die
  anderen sieben haben planmäßig nichts zu liefern. Kein Timeout-Bug. Punkt kann von der Liste.
- **Server-Stand weicht von der HANDOFF ab:** Geyser jetzt **2.11.1-b1210** (statt 2.11.0-b1205),
  Velocity **4.1.0-SNAPSHOT** (statt 3.5.0) — letzteres war in der 26.2-Liste noch „ungeprüft".
- `references/Magmacore` neu geklont — EasyMinecraftGoals ist seit 19.03.2026 deprecated und in
  Magmacore aufgegangen; `BedrockCustomEntityBridgeRegistry` & Co. liegen jetzt dort.
  `setup-references.sh` kennt beides noch nicht.
- Diagnose-Werkzeug gelernt: `/fmm debug bedrock on|off` → Log-Stream `[FMM-BedrockDebug]`. Sehr
  gesprächig (~280 Zeilen in 2 Minuten) — danach wieder ausschalten.

### Kein Plugin-Code angefasst
Diese Session war Live-Diagnose + Doku. Der Branch `feat/mc-26.2-readiness` ist unverändert.

---

## Session: 2026-08-09

**Read-only Server-Audit** nach Fabis PluginPortal-Premium-Update-Runde. Kein Plugin-Code, keine
Server-Änderung, keine Restarts.

### 🎉 Hauptbefund: Java-25-Blocker ist aufgelöst

Die HANDOFF führte „Java 25" als **den** Blocker für MC 26.2. Tatsächlich:

- `/usr/lib/jvm/temurin-25-jdk-amd64` ist auf dem Host **installiert**.
- **Proxy01 läuft bereits darauf** — `Java.JavaVersion=/usr/lib/jvm/temurin-25-jdk-amd64/bin/java`,
  Velocity 4.1.0-SNAPSHOT-14, stabil seit 08.08. Java 25 ist damit **produktiv bewiesen**.
- Alle Paper-Backends stehen weiter auf `jdk-21.0.5-oracle-x64`. Umstellen ist ein Feld in AMP
  (`<instanz>/MinecraftModule.kvp`), keine Installation.
- ⇒ Offen bleibt nur der **Plugin-Test unter Java 25 auf Backend-Seite** — und der geht **jetzt
  schon auf Paper 1.21.10**, ohne MC-Versionswechsel. Damit lassen sich JVM-Wechsel und
  26.2-Wechsel als getrennte Risiken abarbeiten.

### Bedrock-Kette verifiziert

Geyser **2.11.1-b1210**, RPM **2.3.0** auf Backend + Proxy + GeyserBridge-Extension, FMM **2.10.2**,
EM **10.7.3**, BS **2.6.3**, Floodgate b138, Symlink `ResourcePackManager → resourcepackmanager`
vorhanden.

Letzter Proxy-Boot (08.08. 14:50, `logs/2026-08-08-4.log.gz`): **2** Extensions geladen (GeyserUtils
korrekt weg), `bridge ready with 316 custom entity definitions`, **kein** `NoSuchFieldError`. Im Boot
davor (14:07, mit GeyserUtils) steht der Fehler noch drin — der Fix vom 08.08. ist damit im Log
zweifelsfrei belegt, nicht nur behauptet.

Die 40 ERROR-Zeilen im aktuellen Proxy-Log sind ausschließlich `[initial connection]
/23.176.184.152:<port>: read timed out` — ein scannender Host, kein Serverproblem.
TestServer01: 0 Fehler.

### ⚠️ PacketEvents wurde von PluginPortal NICHT mitgezogen

Steht weiter auf **2.12.1** (JAR vom 02.05.) — der letzte echte offene Punkt der 26.2-Kette.
Modrinth-Abfrage: `2.13.0+spigot` deckt **1.8.8 … 26.2** ab (inkl. 1.21.10) ⇒ **Bump geht sofort**,
kein Paper 26.2 nötig. PP verwaltet das JAR, muss also dort angestoßen werden;
`plugin-update-check.sh` fasst PP-Plugins bewusst nicht an.

### GeyserModelEngine: vermutete Altlast → widerlegt, plus Bug im eigenen Tooling

Erster Eindruck war „GME shaded packetevents 2.11.2 neben 2.12.1 ⇒ Classpath-Konflikt, rauswerfen".
Auf Nachfrage von Fabi (GME + ModelEngine sollen für künftige Projekte drinbleiben) nachgeprüft —
**die Annahme war falsch:**

- Die gebundelten packetevents-Klassen sind **relociert** nach
  `re/imc/geysermodelengine/libs/io/github/retrooper/packetevents/…` (1767 Einträge) ⇒ **kein
  Konflikt** mit dem echten packetevents 2.12.1.
- GME hat eine **`paper-plugin.yml`** (`name: GeyserModelEngine`, `main:
  re.imc.geysermodelengine.GeyserModelEngine`, `load: STARTUP`), die Paper bevorzugt. Boot-Log
  08.08.: `Enabling GeyserModelEngine v1.0.3` **und** `Enabling packetevents v2.12.1` — beide laufen.
- Die **Root-`plugin.yml` von GME ist ein Shading-Artefakt** und wörtlich die von packetevents
  (`name: packetevents`, `version: 2.11.2`, `main: io.github.retrooper…PacketEventsPlugin` — eine
  Klasse, die im JAR gar nicht mehr unter diesem Namen liegt).

⇒ Der Report „packetevents 2.11.2 → 2.13.0 [GeyserModelEngine-1.0.3.jar]" war **ein Bug in
`server-tools/plugin-update-check.sh`**, kein Serverbefund. **Gefixt:** das Skript liest jetzt
`paper-plugin.yml` mit Vorrang und fällt nur ohne diese auf `plugin.yml` zurück. (Die Server-Kopie
unter `~/plugin-update-check.sh` muss noch per scp nachgezogen werden.)

⚠️ **Gelernt:** GMEs `paper-plugin.yml` deklariert `GeyserUtils: required: true`. Das Backend-Plugin
`geyserutils-spigot-1.0-SNAPSHOT.jar` muss liegen bleiben, solange GME drin ist — am 08.08.
deaktiviert wurde nur die **Proxy-Extension**, nicht das Spigot-Plugin.

### Weiteres

- **Paper 26.2 ist final** — `fill.papermc.io/v3` listet `26.2` und `26.2-rc-2`. Nebenbefund: der
  1.21-Zweig steht bei **1.21.11**, TestServer01 auf 1.21.10-130 (für seinen Branch aktuell).
- **Von PP aktualisiert (07./08.08.):** LuckPerms 5.5.71, EssentialsX + Spawn 2.22.0,
  FaweSchematicCloud, PluginPortal 3.8.6; manuell Floodgate, Geyser, Via* 5.12.0.
- **Floodgate b138 → b140** verfügbar (minor).
- **Survival01** erwartungsgemäß auf Dez-2025-Stand, nur Floodgate mitgezogen — Staging-Workflow,
  kein Fund.
- **Bridge-JAR auf dem Server ist der Build vom 10.07.** — der 26.2-ready-Branch ist nicht deployt.

### Doku

`HANDOFF.md` umstrukturiert: neuer Abschnitt **0** (dieser Audit) an den Anfang, alter Abschnitt 0
(Versionsschema/Abhängigkeitskette) → **0c** mit aktualisierter Tabelle, Abschnitt 3 in **Strang A
(Bridge-Rest-Scope)** und **Strang B (26.2-Vorbereitung)** geteilt.

### ✅ Java-25-Umstellung TestServer01 verifiziert (09.08., 15:43)

Fabi hat TestServer01 in AMP auf `temurin-25` umgestellt und gestartet, noch auf Paper 1.21.10 —
genau die Trennung von JVM- und MC-Versionswechsel, die in Strang B vorgesehen war.

**Ergebnis: Boot sauber, kein Plugin gefallen.**

- `[bootstrap] Running Java 25 (… Temurin-25.0.4+7)`, Paper 1.21.10-130, `Done (49.940s)`.
- **0** Treffer für `UnsupportedClassVersionError` / `Could not load 'plugins/…'` /
  `Error occurred while enabling` / `Ambiguous plugin name`.
- Plugin-Ladeliste per `comm` gegen den Java-21-Boot vom 08.08. verglichen ⇒ **identisch**. Die
  einzige Differenz war DriveBackupV2s Log-Zeile „Enabling automatic backups", die erst ~30 min
  nach Boot feuert — kein Plugin, sondern ein Artefakt meines `grep "Enabling "`-Zählens.
- Alle Wackelkandidaten laden: ProtocolLib 5.4.1, LibsDisguises 11.0.18, FAWE 2.15.4,
  MythicMobs 5.10.1, Skript, packetevents 2.12.1, GME 1.0.3, FMM 2.10.2, EM 10.7.3, RPM 2.3.0.
- Bridge sauber hoch: FMMEntityTracker, Sync-Task, PacketInterceptor, „PacketEvents: found",
  Phase 7.1c, Phase 7.3 (status=true, quest=true), FMM + Floodgate found.

**Drei Log-Auffälligkeiten geprüft — alle Alt-Befunde, keine davon Java-25-bedingt.** Methodik:
Trefferzahlen im neuen Boot gegen `logs/2026-08-08-2.log.gz` (Java 21) gezählt, jeweils identisch:

| Befund | J21 | J25 | Einordnung |
|---|---|---|---|
| `Failed to interpolate animations … em_goblin_premium_farmer` / Animation `fumble`, `ArrayIndexOutOfBoundsException: Index 55 out of bounds for length 55` in `AnimationBlueprint.interpolateTranslations:312` | 1 | 1 | FMM-Datenbug an einem Modell |
| `Script GK_SailorGoblin_{anchor_throw,overboard}.lua contains unsupported key 'name'` | 2 | 2 | EM-Content-Fehler im Goblin-King-Pack |
| Paper-Watchdog „server has not responded for 10 seconds" | 2 | 2 | EMs `CustomItem.regenerateCachedItemStacks` → `EliteItemLore.writeNewLore` auf dem Main-Thread; identischer Stack im alten Boot |

Der Watchdog-Stack lohnt eine Randnotiz: der Server-Thread hängt in
`EliteItemLore.writeNewLore` → `EliteItemManager.getDPS` → `ItemTagger.getEliteDamageAttribute` →
`ItemStack.getItemMeta` → `CraftMetaItem.buildEnchantments` → `NamespacedKey.validate`. Also EM,
das beim Start alle CustomItem-Lores samt DPS neu berechnet und dabei pro Item ItemMeta baut.
Startkosten, kein Java-Thema.

⇒ **Strang B Schritt 1 (Backend auf Java 25) ist für TestServer01 abgehakt.** Offen bleiben
PacketEvents 2.13.0, dann Paper 26.2, und später die übrigen Backends.

`server-tools/plugin-update-check.sh` wurde per scp auf den Server nachgezogen
(`~/plugin-update-check.sh`, md5 verifiziert identisch).

---

## Session: 2026-08-14 — Paper 26.2 live, MagmaGuy-Welle, 7.1a auf EMs BossBar-Pooling umgebaut

**Der Tag hat zwei Hälften:** vormittags Server-Stand nachziehen und die neuen Changelogs
auswerten, abends — während Fabi und der Server-Claude das Netz auf **Paper 26.2** hoben —
der erste Plugin-Code-Change seit dem 02.08.

### 1. SERVER-STATE.md nachgezogen (Repo hing auf 09.08.)

Repo-Kopie war 31 KB (Stand 09.08. 17:45), Server-Kopie 164 KB (14.08. 23:17). Nachgezogen,
md5 beidseitig geprüft, dazu `check-invsee.sh`, `paper-update-plan.md` und das aktualisierte
`backup-testserver.sh` neu ins Repo geholt.

### 2. MagmaGuy-Welle vom 13.08. — gegen den Quellcode geprüft, nicht gegen die Changelogs

`references/` gepullt (FMM, EM, RPM, BS, MagmaCore) und die Behauptungen verifiziert:

- **🔴 EM 10.8.0 bricht die Annahme hinter Phase 7.1a.** Neu ist
  `combatsystem/displays/BossHealthBarManager`: ein Pool von **max. 4 wiederverwendeten**
  Bukkit-BossBars pro Spieler (`MAX_VISIBLE_BARS_PER_PLAYER = 4`), die per `setTitle(...)` für
  **wechselnde Bosse** weiterbenutzt werden; `BossBarOrderManager.show()` erzwingt die
  Reihenfolge mit `tailBar.removePlayer(p); tailBar.addPlayer(p);`. Damit fällt „der erste
  titel-passende ADD ist unserer", und einmal unterdrückte UUIDs hätten fremde Bosse
  eingefroren. EM bringt weiterhin **keinen** Bedrock-Pfad für BossBars ⇒ die Bridge bleibt nötig.
- **✅ RPM 2.3.1 fixt unseren gemeldeten Case-Bug.** `RspmGeyserBridgeCore.BEDROCK_PACK_PATHS`
  probiert beide Schreibweisen durch, mit Kommentar *„Velocity's default data directory is
  lowercase"*. Report-Entwurf als erledigt markiert.
- **❌ Props-als-Schwein bleibt offen** — `BedrockModeledEntity.java:64` führt in 2.11.1 weiter
  `.carrierEntityType(EntityType.PIG)` im Fake-Entity-Pfad. Entwurf gilt weiter.
- **Keine API-Brüche.** Alle importierten FMM/EM-Typen existieren; auch **sämtliche
  Reflection-Ziele von Phase 7.3** überleben EMs Menü-Redesign (`PlayerStatusScreenDialog`,
  `QuestInventoryMenu` inkl. `questDirectories`/`questInventories` und der Inner-Class-Felder).
- FMM **2.11.0 hatte eine Animations-Regression**, gefixt erst in **2.11.1** → nie 2.11.0 nehmen.

### 3. Phase 7.1a umgebaut (`BossBarUuidResolver`, Registry-Eviction)

- **Neu `BossBarUuidResolver`** — liest die Wire-UUID der eigenen Bukkit-BossBar per Reflection
  (CraftBossBar → NMS-Handle → einziges `UUID`-Feld, **ohne** Feldnamen zu verdrahten, damit
  Mapping-Wechsel es nicht brechen). Fehlschlag → `null` → alte Heuristik + einmalige Log-Zeile.
  Notausstieg `phase71a.resolve-own-bossbar-uuid` (default true).
- **`BossBarRegistry` ist nicht mehr write-only:** Eviction bei REMOVE und bei einem ADD, dessen
  Titel keinem aktiven Controller gehört (recycelter Pool-Slot).
- **`exitCombat()` löscht die Eigen-UUID nicht mehr** — das BossBar-Objekt lebt so lange wie der
  Controller, seine UUID ist stabil; das Löschen erzwang bei jedem Combat ein neues Rennen gegen EM.
- **+5 Tests** (`BossBarRegistryTest`) ⇒ **21/21 grün**, `verify-both-apis.sh` beide Generationen grün.

### 4. Rebuild gegen den neuen Stack + Deploy

pom auf **FMM 2.11.1 / EM 10.8.0** (JARs vom Server via `install:install-file` — sie liegen nicht
im magmaguy-Maven-Repo). Deployt nach TestServer01, sha256 beidseitig geprüft, alter Build gesichert.

### 5. Live verifiziert (22:33–22:44, Bedrock `.Nightgame2272`, zwei EM-Bosse)

| | |
|---|---|
| `Resolved own BossBar UUID` | **9×** |
| `Could not read BossBar` / alte Heuristik | **0×** |
| `Suppressed stale-title` | 3×, an verschiedenen Pool-Slots |
| **`Released suppressed … on REMOVE`** | **2×** ⇒ Eviction greift |
| fremde Bars (Plugin-Ladebalken) | 3× korrekt durchgelassen |

Fabi in-game: „sah alles gut aus, eine Leiste pro Boss." `Suppressed EM BossBar` (exakter
Titel-Match) bleibt 0, weil EMs Leiste bei EVOKER-Bossen immer `Evoker | 2` heisst — es läuft
immer der Alias-Zweig. `Released recycled` blieb 0: EM gibt Slots sauber per REMOVE frei und legt
danach eine **neue** UUID an; der Recycle-Zweig ist Sicherheitsnetz.

### 6. Nebenfunde

- **`getBukkitVersion()` liefert auf Paper 26.2 `26.2.build.112-stable`.** Der alte 10.07.-Build
  konnte das nicht ordnen und hat den Dialog-Reroute **still abgeschaltet**
  (`Phase 7.3: reroute NOT registered … mc>=1.21.6=false`). Auf diesem Branch längst gefixt und in
  `McVersionsTest` abgedeckt — nach dem Deploy steht dort `registered`.
- **Bauen braucht zwingend JDK 25**, sonst *„Ungültige Klassendatei … paper-api"* (Class-File 69).
  Bytecode-Target bleibt 21. `verify-both-apis.sh` fand kein Maven, weil IntelliJ den Plugin-Ordner
  je nach Version `maven` **oder** `maven-plugin` nennt → Kandidatenliste statt festem Pfad.
- **CLAUDE.md zweimal korrigiert**, die zweite Korrektur war eine Korrektur meiner eigenen:
  Beim RPM-Update auf dem Proxy darf ab 2.3.1 die `…GeyserBridge.jar` **nicht** mehr mitkopiert
  werden (es gibt keine neue). Nur die Universal-JAR tauschen, **zweimal** neu starten;
  `loadedDefinitions=0` nach dem ersten Boot ist normal. Beleg: Proxy-Log des Server-Claude.

### 7. Offen: A/B-Test zum HP-Nametag

EM hat `EliteOverheadHealthDisplay` — Balken **und** numerische HP über dem Mob, funktional
identisch zu unserem 7.1b/7.1c-Overlay. Die Config-Keys (`displayVisualHealthBars`,
`displayNumericHealth`) gibt es **seit EM 9.6.0**; neu ist nur der Umbau in 10.8.0, der die
Anzeige zuverlässig macht — deshalb fällt die Dopplung erst jetzt auf. Fabi meldete entsprechend
„nicht dass jetzt zu viel gezeigt wird".

Dafür **neuer Schalter `phase71b.nametag-enabled`** (bewusst *nicht* `phase71c.combat-enabled`
zweckentfremdet — das hätte die BossBar auf „immer sichtbar" gestellt und den Test verfälscht).
Auf TestServer01 steht `false` + `debug: true`, wartet auf einen Neustart. Ergebnis entscheidet,
ob 7.1b/7.1c ausgebaut wird oder EMs Anzeige abgeschaltet gehört.
