# Design: Bedrock-Eingabe für EliteMobs' Advanced Combat System

> **Stand:** 11.09.2026 · **Status:** Entwurf, wartet auf Fabis Freigabe
> **Anlass:** EliteMobs 10.9.0 (erschienen 11.09.2026) bringt das [Alpha] Advanced Combat System.
> Seine Steuerung ist auf PC-Tastatur ausgelegt und für Bedrock-Spieler **nicht bedienbar**.

## 1. Das Problem — belegt am Artefakt

EliteMobs 10.9.0 steuert Klassen-Fähigkeiten über einen **Chord**: Die Taste `F`
(Offhand-Tausch) öffnet ein kurzes Zeitfenster, die zweite Eingabe darin wählt die Fähigkeit.

```
F, F        → Mobility
F + LMB     → Signature
F + RMB     → Utility
```

**Bedrock hat keine Offhand-Tausch-Taste** — weder auf Controller noch auf Touch. Geyser kann
folglich kein `PlayerSwapHandItemsEvent` erzeugen.

MagmaGuy kennt das Problem. In `AdvancedCombatModule` steht:

```java
public boolean fLayerSupported(Player p) {
    return !GeyserDetector.bedrockPlayer(p);   // Bedrock → false
}
```

**Entscheidend ist, wo diese Prüfung sitzt: ausschließlich in `onSwapHands`.** Die vier
Eingabewege des `ClassAbilityInputRouter` wurden im Bytecode einzeln nachgesehen:

| Methode | Event | Verhalten |
|---|---|---|
| `onSwapHands` | `PlayerSwapHandItemsEvent` | **öffnet** den Chord — hier und nur hier |
| `onAbilityHotbarSelection` | `PlayerItemHeldEvent` | steigt sofort aus, wenn keine `pendingGesture` offen ist |
| `onChordInteract` | `PlayerInteractEvent` | dito |
| `onAttack` | `EntityDamageByEntityEvent` | dito |

**Kein F → kein offener Chord → keine Fähigkeit.** Es gibt keinen zweiten Einstieg.

Eine zweite Sperre kommt hinzu: Außerhalb von Dungeons muss die Steuerung erst per `F,F`
scharfgeschaltet werden (`ClassControlMode.outsideEnabled`, ein Set pro Spieler). Auch das ist
für Bedrock unerreichbar. In Dungeons und Matches ist sie automatisch aktiv
(`controlsAlwaysAvailable` → `DungeonCombatRuntime.isEligiblePlayer`), was ohne Auslöser aber
nichts nützt.

**Folge für crazypandas.de:** Konsolen- und Bedrock-Spieler können Klassen wählen, Level
sammeln und Passives nutzen — aber **keine einzige aktive Fähigkeit auslösen**. Rund ein Drittel
des neuen Systems bliebe ihnen verschlossen.

### 1a. Zweiter, eigenständiger Befund (nicht Teil dieses Scopes)

EM prüft an neun Stellen auf Bedrock-Spieler. `CombatHud` und `ClassHudPresentation` gehören
**nicht** dazu. Das grafische Combat-HUD rendert über Java-Resource-Pack-Fonts
(`assets/elitemobs/font/combat_hud_*.json`); der im Changelog genannte „text fallback" ist ein
**globaler** Config-Schalter (`isEnableCombatHud()`), kein Bedrock-Zweig. Steht das HUD an,
erhalten Bedrock-Clients Font-Zeichen, die sie nicht auflösen können.

→ Geht in den Upstream-Report, wird hier **nicht** mitgelöst (Fabis Entscheidung 11.09.).

## 2. Scope

**Drin:** Ein Bedrock-tauglicher Eingabeweg, der EMs drei Fähigkeits-Slots auslöst.

**Nicht drin:**
- Der HUD-Fallback (s. 1a) — eigener Schritt, falls Upstream nicht liefert
- Eigene Fähigkeiten, eigene Balance, eigene Cooldowns — wir lösen nur aus, EM entscheidet alles
- Java-Spieler: deren Steuerung bleibt **unangetastet**
- Das Klassen-/Progressions-Menü (EM liefert dafür bereits Bedrock-taugliche Menüs, s. `MenuPresentation`)

## 3. Der Hebel

`AdvancedCombatModule` bietet beides öffentlich an:

```java
AdvancedCombatModule.get()                       // public static
    .useAbility(player, AbilitySlot.SIGNATURE);  // public → AbilityResult
```

`AbilitySlot` ist ein Enum mit `MOBILITY`, `SIGNATURE`, `UTILITY`. `AbilityResult` ist ein Record
und liefert `successful()`, `abilityId()` und `failureReason()` zurück.

Wir müssen also **keine** Fähigkeitslogik nachbauen — nur auslösen. Alle Prüfungen (Klasse aktiv,
Ressourcen, Cooldowns) macht EM selbst.

## 4. Eingabe-Semantik

Schleichen ersetzt `F`. Es ist die einzige Modifikator-Eingabe, die auf **jedem** Bedrock-Gerät
existiert (Controller: B / Kreis / L3; Touch: Schleich-Knopf).

| EMs Java-Schema | Unsere Bedrock-Übersetzung | Slot |
|---|---|---|
| `F, F` | Schleichen **beginnen**, erneut beginnen | `MOBILITY` |
| `F + LMB` | Schleichen + Angriff | `SIGNATURE` |
| `F + RMB` | Schleichen + Benutzen | `UTILITY` |

### Warum ein Zustands-Fenster statt EMs 12 Ticks

EMs `CHORD_WINDOW_TICKS = 12` (0,6 s) ist auf einen **Tastendruck** zugeschnitten — `F` ist ein
Impuls. Schleichen ist ein **Zustand**. 0,6 s sind auf einem Controller zudem knapp.

**Entscheidung:** Der Chord bleibt offen, **solange der Spieler schleicht**, gedeckelt auf
`chord-max-ticks` (Default **40** = 2 s). Der Deckel verhindert, dass jemand beim
schleichenden Laufen dauerhaft im Fähigkeiten-Modus steht.

⚠️ Das ist eine **bewusste Abweichung** von EMs Timing. Der Wert ist ein Tuning-Parameter und
gehört im ersten Spieltest justiert.

**Wichtiges Detail:** Das Schleich-**Ende** darf den Chord nicht schließen — sonst ist
„Schleichen, Schleichen" nicht ausführbar, weil man zum zweiten Beginn erst loslassen muss.
Gezählt werden ausschließlich Schleich-**Beginne** (`event.isSneaking() == true`).

### Event-Unterdrückung

Löst der Chord aus, wird das auslösende Event **gecancelt** — sonst schlägt der Spieler
zusätzlich zu oder platziert einen Block. EM macht es über `Transition.consumesInput()` genauso.

Der reine Schleich-Vorgang wird **nie** gecancelt: Schleichen muss Schleichen bleiben.

## 5. Scharfschaltung

Damit normales Schleichen im Alltag keine Fähigkeiten auslöst, ist die Steuerung nur scharf,
wenn **EliteMobs selbst auf die Eingabe reagieren würde**. Das Gate sitzt in
`AdvancedCombatHook.canUseAbilities(Player)` und prüft in dieser Reihenfolge:

```java
AdvancedCombatModule.isInitialized()                                  // Modul überhaupt gebaut?
AdvancedCombatModule.get().mechanicsActive(player)                    // EMs eigene Vorbedingung
DungeonCombatRuntime.isEligiblePlayer(player)                         // Dungeon/Match
    || DungeonCombatRuntime.getInstance().isInCombat(player.getUniqueId())   // PlayerCombatState
```

⚠️ **Korrektur gegenüber dem ersten Entwurf (Abschluss-Review 11.09.2026):** Der Entwurf nannte
nur die beiden `DungeonCombatRuntime`-Aufrufe. Dieses Gate ist **breiter als EMs eigenes** und
damit falsch. `useAbility` beginnt mit `mechanicsActive(player)`, das `hasActiveClass` **und**
`controlModeEnabled` verlangt; außerhalb von Dungeons/Matches heißt Letzteres Mitgliedschaft in
`ClassControlMode.outsideEnabled` — eine Menge, der man nur durch einen **F-Doppeltipp beim
Schleichen** beitritt, also gerade nicht von Bedrock aus. Mit dem bloßen Kampf-Tag hätten wir im
offenen Gelände das auslösende Event gecancelt, während `useAbility` nichts tut: Schlag weg,
keine Fähigkeit.

`isInitialized()` muss zwingend **vor** `get()` stehen: `AdvancedCombatModule.get()` liefert nie
`null`, sondern wirft `IllegalStateException`. EM initialisiert das Modul nur bei
`AdvancedCombatSystemConfig.isEnabled()`, startet `DungeonCombatRuntime` aber bedingungslos —
der Kampf-Tag beweist also nichts über das Modul.

⚠️ **Praktische Folge:** Solange der Opt-in außerhalb von Dungeons nur per F-Doppeltipp erreichbar
ist, **wirkt Phase 7.4 faktisch nur in Dungeons und Matches.** Alles andere muss upstream kommen.

`PlayerCombatState.addListener(...)` erlaubt zusätzlich, auf Kampfbeginn und -ende zu reagieren,
statt zu pollen.

⚠️ **Nicht verwechseln:** Unser vorhandener `BedrockCombatTrigger` (Phase 7.1c) führt den
Kampfzustand **pro Elite-Mob**, nicht pro Spieler. Er ist hier **nicht** wiederverwendbar.

## 6. Architektur

Alles läuft im **Backend-Plugin**. Geyser übersetzt Schleichen, Angriff und Benutzen bereits in
normale Java-Pakete, die als gewöhnliche Bukkit-Events ankommen — **keine Geyser-Extension, kein
Proxy-Deploy, keine Geyser-Versionskopplung.**

```
Bedrock-Spieler schleicht (im Kampf)
   └→ PlayerToggleSneakEvent (Beginn)
        └→ BedrockAbilityGesture: Chord offen
             ├→ zweiter Schleich-Beginn   → MOBILITY
             ├→ EntityDamageByEntityEvent → SIGNATURE   (Event gecancelt)
             └→ PlayerInteractEvent       → UTILITY     (Event gecancelt)
                  └→ AdvancedCombatHook.fire(player, outcome)
                       └→ FireResult(handled, message)
                            └→ nur bei handled: Event canceln (+ optionale ActionBar)
```

### Komponenten

| Klasse | Verantwortung | Testbar ohne Server |
|---|---|---|
| `BedrockAbilityGesture` | Zustandsautomat des Chords: offen/geschlossen, Deckel, Slot-Entscheidung. Reines Java, keine Bukkit-Abhängigkeit | **ja** |
| `BedrockAbilityListener` | Die drei Bukkit-Listener, Event-Cancelling, Floodgate- und Kampf-Gate | nein |
| `AdvancedCombatHook` | Kapselt `AdvancedCombatModule`, Verfügbarkeitsprüfung, Fehlerbehandlung | teilweise |
| `AbilityFeedback` | Übersetzt `AbilityResult` in eine ActionBar-Zeile (ohne Custom-Fonts) | **ja** |

### Defensive Kopplung — wichtig

`com.magmaguy.elitemobs.advancedcombat.*` ist ein **internes Paket ohne API-Garantie**, anders
als die `com.magmaguy.elitemobs.api.*`-Klassen, die die Bridge sonst benutzt. Es ist außerdem
als **[Alpha]** gekennzeichnet, ändert sich also wahrscheinlich noch.

Die Prüfung ist deshalb zweistufig — **einmal beim Start**, per Namen und ohne Import:

1. `Class.forName("com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule")` und dasselbe für
   `…advancedcombat.classes.AbilitySlot` (`AdvancedCombatSupport`). Schlägt das fehl, registriert
   sich die Phase gar nicht erst und loggt **eine** Zeile mit dem Grund.

…und **bei jeder Eingabe** über `canUseAbilities(Player)` (Abschnitt 5):

2. `AdvancedCombatModule.isInitialized()` — tritt an die Stelle von
   `AdvancedCombatSystemConfig.isEnabled()`, weil EM das Modul genau dann baut.
3. `AdvancedCombatModule.get().mechanicsActive(player)` — EMs eigene Vorbedingung.

⚠️ **Korrektur:** Der erste Entwurf stellte 2. und 3. als **Startprüfungen** dar. Das wäre falsch:
EM initialisiert sein Modul nicht garantiert vor unserem `onEnable`, und der Schalter kann im
Betrieb umgelegt werden — eine Startprüfung würde die Phase bis zum Server-Neustart fälschlich
abschalten. Beide gehören an die Eingabe, nicht an den Start.

Jeder Aufruf von `useAbility` liegt zusätzlich in `try/catch` gegen `LinkageError` und
`RuntimeException` — **einschließlich** der Übersetzung `Outcome → AbilitySlot`, denn ein
upstream umbenannter Enum-Konstantenname wirft `NoSuchFieldError`. `fire(...)` liefert einen
`FireResult(handled, message)`: Das auslösende Event wird **nur bei `handled == true`** gecancelt,
sonst verliert der Spieler Schlag oder Interaktion für nichts.

**Die Bridge darf davon niemals beim Laden sterben** — das ist die Lehre aus dem
GeyserUtils-Vorfall vom 08.08.2026, als eine gegen eine alte API gebaute Extension jedes
Entity-Spawn für Bedrock getötet hat.

## 7. Rückmeldung an den Spieler

`AbilityResult` liefert Erfolg und Fehlergrund frei Haus. Für den **Anzeigenamen** ist
`AdvancedCombatModule.abilityName(Player, AbilitySlot)` zuständig — `AbilityResult.abilityId()`
ist eine technische ID und gehört nicht vor den Spieler.

Das geht als schlichter ActionBar-Text heraus — **ohne** Custom-Fonts, damit Bedrock ihn
darstellen kann.

⚠️ **Standardmäßig ausgeschaltet (`phase74.feedback: false`, Abschluss-Review 11.09.2026):**
EliteMobs schreibt seine Skill-Rückmeldung selbst über den `ActionBarCompositor` — mit
Keepalive-Re-Render — und meldet genau die vier Fehlerfälle, die wir hier abbilden. Zwei
Schreiber im selben Tick flackern. Der Code-Pfad bleibt erhalten und lässt sich per Config
einschalten, falls EMs Anzeige auf Bedrock doch nicht ankommt.

⚠️ **Korrektur gegenüber dem ersten Entwurf:** `AbilityFailureReason` kennt **keine** Cooldown-
oder Ressourcen-Gründe. Die tatsächlichen Werte sind `NONE`, `WRONG_THREAD`, `INVALID_PLAYER`,
`INVALID_LEVEL`, `ABILITY_NOT_REGISTERED`, `NO_VALID_TARGET`, `NO_CORPSE`, `PATH_BLOCKED`,
`UNSAFE_DESTINATION`, `ENGINE_CLOSED`. Cooldowns und Ressourcen meldet EM selbst über
`CombatHudFeedback`; wir mischen uns da nicht ein.

| Fall | Anzeige |
|---|---|
| Erfolg | `▶ Schattenschritt` |
| `NO_VALID_TARGET` | `Kein Ziel` |
| `PATH_BLOCKED` / `UNSAFE_DESTINATION` | `Weg blockiert` |
| `INVALID_LEVEL` | `Fähigkeit noch nicht freigeschaltet` |
| alle übrigen (technisch) | *(nichts anzeigen, nur `debugLog`)* |

⚠️ **Die Bridge hat keine Sprachdatei** (nur `config.yml`). Die vier Textbausteine kommen
deshalb in die Config, damit sie ohne Neubau änderbar sind — eine eigene Sprachdatei-Infrastruktur
wäre für vier Zeilen unverhältnismäßig.

## 8. Konfiguration

Die Bridge benennt ihre Blöcke nach Phasen (`phase71a`, `phase71b`, `phase71c`, `phase73`).
Dieser Konvention folgend wird es **`phase74`**:

```yaml
phase74:
  bedrock-abilities: true    # Gesamtschalter
  chord-max-ticks: 40        # Deckel des Chord-Fensters (s. 4)
  require-combat: true       # false = immer scharf (nicht empfohlen)
  feedback: true             # ActionBar-Rückmeldung
```

## 9. Teststrategie

**Unit-Tests** (ohne Server, wie TntRuns `FloorEngine`): Fenster öffnet nur bei Schleich-Beginn ·
Schleich-Ende schließt **nicht** · zweiter Beginn → `MOBILITY` · Angriff → `SIGNATURE` ·
Benutzen → `UTILITY` · Deckel läuft ab · Eingaben nach Ablauf lösen nichts aus · kein Doppel-Feuern.

**Nur in-game prüfbar** (Bedrock-Gerät, idealerweise mit Controller):
1. Löst Schleichen auf einem echten Controller sauber `PlayerToggleSneakEvent` aus?
2. Ist der Doppel-Schleich-Rhythmus auf dem Controller treffbar? (→ `chord-max-ticks` justieren)
3. Wird der Angriff wirklich unterdrückt oder schlägt der Spieler doppelt?
4. Kollidiert Schleichen + Benutzen mit Kisten und Blockplatzierung im Kampf?
5. Java-Spieler: EMs Original-Steuerung unverändert?

## 10. Risiken

| Risiko | Bewertung |
|---|---|
| EM ändert `advancedcombat` (Alpha, internes Paket) | **hoch** — abgefedert durch 6/Defensive Kopplung; bei jedem EM-Update gegenprüfen |
| Fehlauslösung beim Schleichen im Kampf | mittel — Kampf-Gate begrenzt es; im Spieltest bewerten |
| Doppel-Schleichen auf Controller schwer treffbar | mittel — `chord-max-ticks` justierbar |
| Upstream löst es selbst | **erwünscht** — dann bauen wir unseren Pfad wieder aus |

## 11. Upstream-Report (parallel, eigener Vorgang)

Gemeldet wird, mit Bytecode-Belegen:
1. `fLayerSupported` sperrt Bedrock aus, ohne Ersatzpfad — alle anderen Eingaben brauchen den in
   `onSwapHands` geöffneten Chord
2. `CombatHud`/`ClassHudPresentation` prüfen nicht auf Bedrock (s. 1a)

Entwurf gehört nach `docs/upstream-bugs/`, wie der FMM-Props-Report.

## 12. Offene Entscheidungen

Freigaben vom 11.09.2026: Bridge bauen **und** Upstream melden · Schleichen als F-Ersatz ·
Scharfschaltung an EMs eigene Vorbedingungen gekoppelt · HUD-Fallback später.

### ⏸️ Vertagt: die zweite Sperre in der offenen Welt

**Der Befund** (11.09.2026 am Artefakt geprüft): `isInEligibleCombatContent` ist ein ODER aus
„in einer `DungeonInstance`" und „`EliteMobsWorld.isEliteMobsWorld(weltUUID)`". Letzteres umfasst
**jede Welt, die EM aus einem Content-Package angelegt hat** — also auch permanente Dungeon- und
Arena-Welten, nicht nur instanzierte Runs. Phase 7.4 wirkt dort vollständig.

**Nicht** darunter fallen selbst angelegte Welten (`survival`, `farmwelt`, deren Nether/End). Dort
verlangt EM den Opt-in per F-Doppeltipp — **auch von Java-Spielern**. Der Unterschied ist also
nicht „Java kann, Bedrock nicht", sondern „Java muss einmal pro Sitzung eine Taste drücken,
Bedrock kann diese Taste nicht drücken".

**Die drei Wege, geprüft:**

| Weg | Ergebnis |
|---|---|
| Synthetisches `PlayerSwapHandItemsEvent` feuern | ❌ tot — `pressF` prüft `fSupported` als erstes und liefert für Bedrock sofort `PASS_THROUGH` |
| Config `allowClassAbilitiesOutsideEliteMobsWorlds` | ❌ regelt nur die *Erlaubnis*; der Opt-in-Akt bleibt der F-Doppeltipp |
| `GeyserDetector` täuschen | ❌ würde EMs Bedrock-Menüs brechen — neun Klassen hängen an der Prüfung |
| **Reflection auf `outsideEnabled`** | ⚠️ machbar: `AdvancedCombatModule` → `inputRouter` → `controlMode` → `Set<UUID>`, drei private Felder tief, davon eines in einer package-private Klasse. Absicherbar, indem man danach über das öffentliche `mechanicsActive(player)` prüft, ob es gewirkt hat |

**Stand der Entscheidung (Fabi, 11.09.):** vertagt — erst das Grundsystem fertigstellen.

⚠️ **Warum der Punkt wiederkommt:** Fabi plant, EM-Monster-Content auch **außerhalb** der EM-Maps
einzusetzen (Open-World-Spawns, perspektivisch Event-Spawns aus allen Paketen). In dem Moment
trifft die zweite Sperre den Hauptanwendungsfall, nicht mehr den Randfall.

**Wie die Entscheidung vorzubereiten ist:** Sobald die Content-Packages installiert sind, am
laufenden Server auswerten, welche Welten EM als eigene führt und wo tatsächlich Elites spawnen.
Erst dann ist beurteilbar, ob der Reflection-Weg seinen Preis wert ist.
