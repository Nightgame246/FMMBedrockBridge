# Design: Phase 5.1–8 — Animation Fix, Modularisierung, Features, Polish

**Datum:** 2026-04-08
**Scope:** Alle verbleibenden Phasen bis Produktionsreife auf TestServer01

---

## Ausgangslage

Phase 1–4.5 funktionieren: Bedrock-Spieler sehen Custom 3D Models, Nametags, können interagieren. Phase 5 (Animationen) ist implementiert aber crasht mit StackOverflowError.

**Codebase:** ~2400 LOC Spigot Plugin (12 Dateien) + 614 LOC Geyser Extension (1 Datei)

---

## Phase 5.1: StackOverflow Fix + Animation Verification

### Root Cause

`GeyserUtils.registerProperties()` (Zeile 138–143 in `geyser/GeyserUtils.java`) hat einen Rekursions-Bug:

```java
public static void registerProperties(String entityId) {
    if (GEYSER_LOADED) {
        registerProperties(entityId);  // BUG: ruft sich selbst auf
    }
    ENTITIES_WAIT_FOR_LOAD.add(entityId);
}
```

Sollte `registerPropertiesForGeyser(entityId)` aufrufen statt sich selbst.

### Unser Code der den Bug triggert

`FMMEntityData.registerAndSendInitialAnimation()` (Zeile 138–161) ruft `EntityUtils.registerProperty()` auf, was intern per Plugin Message an den Proxy geht, wo `GeyserUtils.registerProperties()` aufgerufen wird → StackOverflow.

### Fix-Ansatz

**Option A (empfohlen): Properties in Entity Definition statt Runtime Registration**

Statt Properties zur Laufzeit per `EntityUtils.registerProperty()` zu registrieren, die Properties direkt in die Entity Definition einbauen die beim Server-Start registriert wird. GeyserModelEngine macht das genauso — Properties werden beim `addCustomEntity()` definiert, nicht zur Laufzeit.

Änderungen:
- `FMMBridgeExtension.registerCustomEntityWithDebug()` — Properties mit `GeyserUtils.addProperty()` registrieren BEVOR `addCustomEntity()` aufgerufen wird
- `FMMEntityData.registerAndSendInitialAnimation()` — `EntityUtils.registerProperty()` Aufrufe entfernen
- `FMMEntityData.syncAnimation()` — `EntityUtils.sendIntProperty()` beibehalten (sendet nur Werte, registriert nicht)

**Option B (Fallback): GeyserUtils forken und Bug fixen**

Zeile 140 von `registerProperties(entityId)` zu `registerPropertiesForGeyser(entityId)` ändern, GeyserUtils lokal neu bauen. Nachteil: Fork-Maintenance.

### Verifizierung

Nach dem Fix auf TestServer01 testen:
- Bedrock-Spieler in Nähe von FMM-Mobs → kein Crash
- Animationen sichtbar: idle (stehend), walk (laufend), attack (Angriff), death (Tod)
- Animations-Wechsel flüssig (blend_transition 0.1s)

---

## Phase 5.5: Code-Modularisierung

### Problem

`BedrockEntityBridge.java` (397 Zeilen) mischt zu viele Verantwortungen:
- Viewer-Tracking und Tick-Sync
- Packet-Suppression (PacketEvents Listener)
- Interact-Redirect
- Player Join/Quit Events
- Entity Spawn/Despawn Handling

`FMMBridgeExtension.java` (614 Zeilen) ist eine einzelne Klasse die alles macht:
- Resource Pack Generierung
- Entity Registration (Reflection)
- Downstream Monitor
- Channel Registration
- Cache-Logging

### Aufspaltung Spigot Plugin

| Neue Klasse | Verantwortung | Aus |
|------------|---------------|-----|
| `PacketInterceptor` | PacketEvents Listener: Spawn/Metadata-Suppression + Interact-Redirect | `BedrockEntityBridge.registerPacketSuppressor()` |
| `ViewerManager` | Bedrock-Player-Tracking, Join/Quit, Range-Checks | `BedrockEntityBridge.readyBedrockPlayers` + Events |
| `BedrockEntityBridge` | Nur noch Orchestrierung: Entity-Lifecycle, Tick-Loop | Bleibt, wird schlanker |

### Aufspaltung Geyser Extension

| Neue Klasse | Verantwortung | Aus |
|------------|---------------|-----|
| `ResourcePackBuilder` | Pack-Generierung, Entity Definitions, Manifest, ZIP | `FMMBridgeExtension.generatePackFiles()` etc. |
| `EntityRegistrar` | GeyserUtils Reflection, `addCustomEntity`, `addProperty` | `FMMBridgeExtension.registerCustomEntityWithDebug()` |
| `DownstreamMonitor` | Session-Tracking, Listener Re-Registration, Channel Registration | `FMMBridgeExtension.checkDownstream()` etc. |
| `FMMBridgeExtension` | Nur noch Event-Handler und Lifecycle | Bleibt, wird schlanker |

### Parallelisierbarkeit

Die 6 Extraktionen sind unabhängig voneinander — ideal für Codex-Tasks:
- 3x Spigot-Seite (Codex)
- 3x Extension-Seite (manuell oder separater Agent)

---

## Phase 6: Static Entities

### Problem

Aktuell werden nur `DynamicEntity` gebridgt (haben ein `underlyingEntity` = Vanilla-Mob). `StaticEntity` und `PropEntity` (Möbel, Dekorationen) haben kein Vanilla-Mob als Basis — der Fake-PIG-Trick funktioniert nicht weil es keine Real-Entity gibt die man verstecken muss.

### Ansatz

Für Static Entities brauchen wir:
1. **Erkennung:** `FMMEntityTracker` muss auch `StaticEntity`/`PropEntity` tracken (aktuell nur `DynamicEntity`)
2. **Spawn:** Direkt ein Fake-Entity an der Position spawnen, ohne Real-Entity zu verstecken
3. **Kein Interact-Redirect:** Static Entities haben keine Interaktion (oder eigene)
4. **Position:** Einmalig setzen, kein Tick-Sync nötig (statisch)

Änderungen:
- `FMMEntityTracker` — Filter erweitern auf `StaticEntity`
- `BedrockEntityBridge.onEntitySpawn()` — Zweig für Static Entities ohne `underlyingEntity`
- `FMMEntityData` — Optionales `realEntity` (null für Static)
- Converter muss Static-Model .bbmodels ebenfalls konvertieren

### Offene Fragen (zu klären bei Implementierung)

- Haben Static/Prop Entities in FMM eine feste Position oder können sie bewegt werden?
- Wie werden Props in EliteMobs genutzt? (Dungeon-Dekoration?)
- Brauchen Props Hitboxen für Bedrock?

---

## Phase 7: EliteMobs UI/UX

### Bekannte Probleme

1. **BossBar:** Zeigt bei manchen Mobs den Vanilla-Typ (z.B. "Evoker | 2") statt Custom Name
2. **Nametags:** Funktionieren grundsätzlich, aber nicht optimal in der offenen Welt
3. **GUIs:** EliteMobs Inventar-GUIs (Shops, Quests, NPC-Dialoge) funktionieren nicht auf Bedrock
4. **Sonstiges UI:** Unbekannt — muss getestet werden

### Vorgehen

**Schritt 1: Bestandsaufnahme** (Fabi als Tester mit Bedrock-Client)
- Systematisch alle EliteMobs-Features durchgehen
- Dokumentieren was funktioniert und was nicht
- Screenshots/Videos von Bedrock vs. Java Unterschieden

**Schritt 2: Priorisierung**
- Was ist spielkritisch? (BossBar, Interaktion)
- Was ist nice-to-have? (Partikel, Kosmetik)
- Was liegt außerhalb unserer Kontrolle? (Geyser-Limitierungen)

**Schritt 3: Implementierung**
- BossBar: Packet-Interception via PacketEvents, BossBar-UUID zu Entity matchen, Titel ersetzen
- Nametags: Ursache für "nicht optimal" identifizieren und fixen
- GUIs: Wahrscheinlich Geyser-Limitierung — ggf. Bedrock Forms als Alternative via GeyserUtils

### Gemini-Einsatz

Gemini analysiert wie GeyserModelEngine die BossBar/UI-Problematik löst (falls überhaupt) und welche Geyser-APIs für Bedrock Forms verfügbar sind.

---

## Phase 8: Polish

### Scope

- **Partikel-Interception:** Bedrock sieht Schneebälle statt Rauch — Geyser-Limitierung evaluieren
- **Config:** Admin-freundliche config.yml (enable/disable Bridge, View-Distance, Log-Level)
- **Performance:** Profiling auf TestServer01 mit >10 Bedrock-Spielern
- **Auto-Reload:** FMM Model Changes automatisch erkennen und neu konvertieren
- **Dokumentation:** Setup-Guide für andere Server-Betreiber
- **Produktionsreife:** Edge Cases (Server-Switch, Disconnect während Spawn, viele Entities gleichzeitig)

---

## Multi-AI Einsatz

| Phase | Claude | Gemini | Codex |
|-------|--------|--------|-------|
| 5.1 | Fix implementieren | GeyserUtils `registerProperties` vs `registerPropertiesForGeyser` Analyse | — |
| 5.5 | Architektur-Entscheidungen | — | Parallele Extraktions-Tasks (3x Spigot) |
| 6 | Design + Implementierung | FMM StaticEntity/PropEntity API-Analyse | Isolierte Converter-Tasks |
| 7 | BossBar/Nametag Fix | GeyserModelEngine UI-Referenz, Geyser Forms API | — |
| 8 | Integration + Final Review | — | Einzelne Polish-Tasks |

---

## Erfolgs-Kriterien (TestServer-reif)

- Bedrock-Spieler sehen alle FMM Dynamic Entities mit korrektem Model
- Animationen spielen (idle, walk, attack, death)
- Static Entities (Props) sind sichtbar
- EliteMobs BossBar zeigt korrekten Namen
- EliteMobs GUIs sind benutzbar (oder haben Bedrock-Alternative)
- Kein Crash, kein Log-Spam, kein spürbarer Lag
- Code ist modular und wartbar (keine Datei >300 Zeilen)
