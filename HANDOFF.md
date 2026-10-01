# HANDOFF — FMMBedrockBridge

> Arbeitsstand **dieses Plugins**. Stand: **2026-09-27**  ·  Branch: **`main`** — Phase 7.4 am 27.09. per Squash gemerged (`d2444bd`)
>
> ⚠️ **Der Einstieg steht eine Ebene höher: `../HANDOFF.md`.**
> Dort liegen Bootstrap, Session-Ende-Protokoll, Build-Vorbereitung am neuen PC und die
> Übersicht über alle Plugins. Diese Datei hier ist nur noch die Bridge-Tiefe.
> **Claude Code aus `codeing/minecraft/` starten, nicht aus diesem Ordner.**
>
> Server-/Betriebswissen: `../server-tools/SERVER-STATE.md` (gemeinsam mit dem Server-Claude).
>
> 🔴 **Die Historie dieses Repos wurde am 09.09.2026 neu geschrieben und das GitHub-Repo
> gelöscht + neu angelegt. ALLE Commit-SHAs sind anders.** Ein älterer Klon teilt mit dem
> Remote keinen Commit mehr und lässt sich nicht pullen — er muss weg und frisch geklont
> werden. Anleitung und Grund: `../HANDOFF.md`, Kopfblock und „Sicherheitsvorfall".
>
> 🔵 **Neu seit 11.09.: Phase 7.4 auf eigenem Branch, deployt auf TestServer01,
> in-game noch NICHT final abgenommen.** Die älteren Phasen (BossBar + HP-Nametag) sind
> unverändert live verifiziert: 7.1a am 14.08., 7.1b/7.1c am 16.08. per A/B-Test,
> Boot-Gegenprobe am 09.09.
>
> 🔴 **Phase 7.5 ist am 16.09.2026 wieder ausgebaut worden — EliteMobs 10.9.1 macht das
> selbst.** Details im Abschnitt unten.
>
> 🔵 **Neu am 20.09.: die Bedrock-Erkennung liegt jetzt auf EMs Stand** (`BedrockDetection`,
> 10 neue Tests, 53 gesamt). Vorher konnte EliteMobs einen Spieler als Bedrock behandeln,
> während die Bridge ihn für Java hielt — dann bekommt er **von keiner Seite** Eingabe.
> Abschnitt „Bedrock-Erkennung" unten.

> 🟢 **Neu am 27.09.: Phase 7.4 ist in-game abgenommen** — Schleichen + Hotbar-Taste
> (1/7 · 2/8 · 3/9) als zweite Geste, weil Rechtsklick in die Luft mit leerer Hand gar kein
> Paket schickt. Dazu pom auf FMM 2.12.3 / EM 10.9.5 / PacketEvents 2.14.0. Abschnitt
> „HIER WEITERMACHEN" unten.

### Bedrock-Erkennung — angeglichen am 20.09.2026

`ViewerManager.isBedrockPlayer()` fragte nur Floodgate. Genau diese Form hat MagmaGuy in der
Welle vom 16.09. ersetzt (*„Improved Bedrock player detection behind proxies"*, gleichlautend in
EM 10.9.5, FMM 2.12.3 und RPM 2.4.4).

**Warum das die Bridge trifft:** EMs `fLayerSupported()` ist `!BedrockChecker.isBedrock(player)`.
Beide Seiten entscheiden anhand derselben Frage, ob ein Spieler Eingabe bekommt. Sagt EM
„Bedrock" (F-Chord gesperrt) und die Bridge „Java" (Schleich-Geste nicht registriert), hat der
Spieler **gar keine** Eingabe — die Aussperrung aus dem Upstream-Report vom 11.09., nur still
und hausgemacht.

Die Entscheidung liegt jetzt in **`BedrockDetection`** (rein, testbar ohne Bukkit, Muster wie
`RerouteDecision`), Reihenfolge wie in EMs `BedrockChecker`:

1. Floodgate-UUID (`getMostSignificantBits() == 0`) — trägt auch ohne installiertes Plugin
2. Namensmuster `^\..*\d{4}$` — ein Java-Name kann nie mit `.` beginnen
3. Floodgate, **dann** Geyser — ein `false` bricht die Kette nicht mehr ab (der eigentliche
   „behind proxies"-Fix)

Dazu: `isEnabled()` statt bloßer Anwesenheit, und eine fehlende API zählt als „nicht Bedrock",
statt zu fliegen. Geyser wird reflektiv gefragt (API bewusst nicht im pom; in diesem Netz läuft
Geyser auf dem Proxy, hier findet die Abfrage also normalerweise nichts).

⚠️ **In-game nicht verifiziert** — wie jede Bedrock-Frage.


### Was Phase 7.4 ist

**7.4 — Bedrock-Eingabe für EMs Klassen-Fähigkeiten.** EliteMobs 10.9.0 bindet sie an einen
F-Chord; `F` ist der Offhand-Tausch, den Bedrock auf keinem Gerät hat. Konsolenspieler können
ohne die Bridge **keine einzige** aktive Fähigkeit auslösen. Wir übersetzen das auf Schleichen.

Das bleibt nötig: EliteMobs kennt das Problem (`fLayerSupported()` ist schlicht
`!BedrockChecker.isBedrock(player)`, im Input-Router steht *„Bedrock has no F-key input"*),
liefert aber **keinen** Ersatz.

### Phase 7.5 — ausgebaut am 16.09.2026

**Was sie war.** EMs Combat-HUD wird aus einer Java-Resource-Pack-Schrift gezeichnet; 689 der
703 Glyphen liegen in der Private Use Area, wo Bedrock seine eigenen Item-Symbole hat. Ergebnis
in-game: hunderte Rüstungs- und Karotten-Icons über dem halben Bildschirm, fünfmal pro Sekunde
neu — der Client laggt sich fest. Screenshots: `../references/screenshots/alt/2026-09-11_phase75-hud-glyphen/`. Die Bridge fing
deshalb Actionbar-Pakete ab, filterte die Glyphen und ersetzte das HUD durch eine eigene
Textzeile aus EMs öffentlichen Snapshots.

**Warum sie weg ist.** EliteMobs 10.9.1 hat genau das selbst eingebaut
(*„Fixed … combat HUD fallback for Bedrock players"*). In `ActionBarCompositor.render()` steht
die Bedingung für den grafischen HUD jetzt so, dass Bedrock gar nicht erst hineinläuft:

```java
// CLASS_HUD and ability feedback remain published below as the resource-pack-free fallback.
if (isEnableCombatHud() && !BedrockChecker.isBedrock(player) && … ) { … grafisch …; return; }
```

Bedrock bekommt damit nie wieder Glyphen aus dem Combat-HUD. Unser Filter wäre ein reiner
No-Op geworden — und kein billiger: er serialisierte **jedes** Actionbar-Paket an jeden
Bedrock-Spieler auf dem Netty-Thread, bevor er überhaupt prüfen konnte, ob Glyphen drin sind.
EM sendet rund fünfmal pro Sekunde.

Entfernt wurden `BedrockGlyphFilter` (+ 18 Tests), `AdvancedCombatHook.hudLine()`, der
Glyph-Zweig im `PacketInterceptor` samt Selbstabschaltung und der Config-Block `phase75`.
Historie: dieser Branch vor dem Commit vom 16.09.2026 („Phase 7.5 ausgebaut, Rueckmeldung
ueber EMs Compositor").

Design: `docs/specs/2026-09-11-bedrock-ability-input-design.md` ·
Plan: `docs/plans/2026-09-11-bedrock-ability-input.md` ·
Upstream: `docs/upstream-bugs/em-advanced-combat-bedrock-input-lockout.md`
(am 11.09. von Fabi im Discord gepostet, Thread-Link steht drin; **Nachtrag 4 ist noch nicht
gepostet** — er beschreibt die zweite Sperre und ist durch die Arbeit an 7.4 belegt).

### Vier Irrwege, die nicht noch einmal nötig sind

Die Gesten-Erkennung hat vier Anläufe gebraucht. Alle vier scheiterten am selben
Missverständnis — EliteMobs' Chord-Metapher ist für einen **Tastendruck** gemacht, Schleichen
ist ein **Zustand**:

1. Festes 2-Sekunden-Fenster → lief beim Zielen ab
2. Chord wird von der ersten Fähigkeit verbraucht → zweite Fähigkeit unmöglich
3. Arming über Sneak-Events → **Geyser liefert Bedrocks Ducken als Flatter-Folge**
   (gemessen: 5 × „armed" gegen 20 × „disarmed" in Sekunden)
4. Jetzt: Klicks lesen `player.isSneaking()` direkt; nur Mobility wertet noch Events aus,
   mit 4-Tick-Untergrenze gegen das Flattern

⚠️ **Merksatz:** Bei Bedrock-Eingaben den **Zustand** abfragen, nicht dem Eventstrom trauen.

### Die EliteMobs-Seite, die uns nicht gehört

`INVALID_PLAYER` im Log heißt „Class controls are not active here" — das kommt von EliteMobs,
nicht von uns. Tritt es **in** einer EM-Welt mit aktiver Klasse auf, ist es ein Upstream-Thema.
Klassen setzen ohne Instructor-Trial: `/em class test set <player> <class> <level>`.

---

## ▶ HIER WEITERMACHEN (Stand 27.09.2026, Sitzungsende)

### Stand: Phase 7.4 in-game abgenommen ✅

Spieltest von Fabi am 27.09. im Dungeon, mit leerer Hand, auf dem Test-JAR `c080ed5`:
17 × `handled=true` (Utility 4, Signature 3, Mobility 10), **keine** `interact`-Zeile — Utility
kam also nachweislich über die Hotbar-Taste. Actionbar-Hinweis laut Fabi „hat gepasst".

| Geste (geduckt) | löst aus |
|---|---|
| **Linksklick** (Luft oder Block) | Signature |
| **Rechtsklick auf einen Block** | Utility |
| **Ducken, loslassen, wieder ducken** | Mobility |
| **Hotbar-Taste 1 / 7** | Mobility |
| **Hotbar-Taste 2 / 8** | Signature |
| **Hotbar-Taste 3 / 9** | Utility |
| Hotbar-Taste 4–6 | nichts, normaler Item-Wechsel |

Beim bewussten Ducken steht die Belegung in der Actionbar, mit den Fähigkeitsnamen der Klasse:
`[1/2×Ducken] Dash  [2/Links] Wirbel  [3/Rechts] Schild`. Hält der Spieler gerade Slot 1–3,
wird die Spiegeltaste 7–9 angezeigt — **von EliteMobs übernommen** (`openGesture()`), weil ein
Client für den bereits gehaltenen Slot keinen Wechsel meldet.

### Der fünfte Irrweg: Rechtsklick in die Luft

Utility ging bis zum 27.09. nur mit einem Block im Fadenkreuz. Im Log kam Rechtsklick
**ausschließlich** als `RIGHT_CLICK_BLOCK` an. Grund: Ein Rechtsklick in die Luft ist im Protokoll
nur ein „Item benutzen"-Paket, und das schickt der Client mit leerer Hand gar nicht (Java genauso).
Geyser würde es weiterleiten, bekommt es aber nie. **Nicht in der Bridge reparierbar** — deshalb
die Hotbar-Tasten, nach EliteMobs' eigener Zweitbelegung (`ClassAbilityGestureState.selectHotbar`,
in den Artefakten 10.9.0 und 10.9.5 per `javap` belegt).

### Phase 7.3c — Classes-Menü als Formular (27.09., in-game offen)

Befund aus dem Spieltest: `/em` kam als Formular, der Klassen-Knopf darin als **Kiste**.
EliteMobs baut jede Seite des Klassenmenüs zweimal (Dialog + Kiste) und wählt pro Aufruf in
`ClassMenuCoordinator.renderer(player)` — für Bedrock immer die Kiste
(`MenuPresentation.supportsDialogs` enthält `!BedrockChecker.isBedrock`).

Lösung `ClassMenuReroute`: ersetzt beim Start den Kisten-Renderer im Coordinator durch einen
Stellvertreter (Dynamic Proxy), der Bedrock-Spieler an EMs Dialog-Renderer gibt. Deckt Übersicht,
Klassenseiten, Steuerungsseite, Trainer-NPC und jede Button-Aktion ab — ohne Titelvergleich, ohne
aufblitzende Kiste. Nur wenn Bedrock der **einzige** Grund für die Kiste ist. Beim Disable wird
der Original-Renderer zurückgesetzt. Mechanik (Proxy auf package-private Interface + `private
final`-Feld tauschen) an einem Nachbau unter Java 25.0.4 geprüft; Feldnamen per `javap` in
EM 10.9.0 und 10.9.5 identisch.

**Bestandsaufnahme aller Bedrock→Kiste-Stellen in EM (Master 16.09.):** Status (7.3) · Quests
(7.3b) · **Classes (7.3c)** — das waren alle mit Dialog-Alternative. Party-Menüs haben für Java
nur klickbare Chat-Buttons, keinen Dialog; dort ist die Kiste für Bedrock die bessere Wahl.
Quest-Dialog-BossBar, Patrol-Editor, HUD, Wormhole-Marker sind keine Menüs.

### Phase 7.6 — EliteMobs-Menü-Hintergründe auf Bedrock (abgenommen 01.10.2026, Branch `phase-7.6-menu-backgrounds`)

Spec `docs/specs/2026-10-01-bedrock-em-menu-backgrounds-design.md`, Plan `docs/plans/2026-10-01-bedrock-em-menu-backgrounds.md`.
Bedrock zeigte bei EliteMobs-Menüs eine graue Kiste und ▯▯▯ im Titel. Jetzt: Bridge schreibt den Titel
für Bedrock um (`BedrockMenuTitle`, OPEN_WINDOW), das Pack **`FMMBridge-EliteMobsMenus.mcpack`** in
Geysers `packs/` blendet den Hintergrund ein und die grauen Slot-Kästchen aus.

- **16 Menüs** (nicht 173 — RPM wiederholt EMs Schrift je Inhalts-Paket; erster Eintrag gewinnt).
- **Versatz** am Slot-Raster: x **−8** (gemessen, hergeleitet wären −12), y **5 − ascent** (große Kiste),
  **4 − ascent** (kleine Kiste). Ausnahmen in `tools/bedrock-menus/overrides.json`.
- **Abnahme** (PC): Reparieren, Verschrotter, Kaufen/Verkaufen (kleine Kiste), Eigener Shop, Prozeduraler
  Shop — Titel ohne Kästchen, Bild passt, Items bedienbar. **Konsole nicht separat geprüft** (Fabis
  Entscheidung: Classic-UI identisch, Konsolenspieler melden Fehler).
- **Neues Pack erzeugen** (nach EM-Update / neuen Inhalts-Paketen): `ResourcePackManager_RSP.zip` von
  TestServer01 nach `target/bedrock-menus/RSP.zip`, dann `python3 tools/bedrock-menus/generate.py
  target/bedrock-menus/RSP.zip`, `pack-meta.json` committen, Pack nach `Geyser-Velocity/packs/`, Proxy neu.
- **Reihenfolge:** erst Pack, dann Bridge. **Rückweg:** `phase76.bedrock-menu-backgrounds: false` bzw.
  `hide-slot-backgrounds: false` (nur graue Kästchen zurück); ganz: Pack entfernen.
- **Nicht Teil davon:** die Item-Symbole der Menü-Buttons (Vanilla statt EM) — eigenes Thema im Umbrella-HANDOFF.

### Phase 7.1b ausgebaut — 01.10.2026

A/B-Test auf TestServer01 (Bridge-Overlay per `phase71b.nametag-enabled: false` aus): über dem
Eis-Elementar blieb **eine** HP-Anzeige — EliteMobs' eigene (`76.41 / 79.58` + Balken-Zeilen,
`FakeText`). Am 16.08. erreichte sie Bedrock noch nicht; was sich geändert hat (vermutlich Geyser
mit Text-Display-Unterstützung), ist für die Entscheidung egal. Mit beiden an stand alles doppelt.
Ausgebaut: `BedrockNametagController`, `NametagTextBuilder`, die Java-Unterdrückung im
`PacketInterceptor`, der Nametag-Teil in `BedrockCombatTrigger`/`FMMBedrockBridge`/Debug-Command und
der Config-Block. Der Schalter in der Server-Config ist danach wirkungslos.
⚠️ Die Overlays waren **echte** TextDisplays (`world.spawn`, nicht `setPersistent(false)`). Sauber
entfernt wurden sie beim Stopp; nach einem Absturz könnten verwaiste in der Welt von TestServer01
stehen — schwebender HP-Text ohne Mob. Das gab es schon vorher, der Ausbau ändert daran nichts.

### Phase 7.1d — nur EIN Name über Bossen auf Bedrock (umgebaut 30.09., in-game offen)

Bedrock zeigte über Evoker-Bossen „Evoker". Erste Fassung (27.09., `ab5914f`) hat den Namen des
Mobs umbenannt — Spieltest 30.09.: danach stand der richtige Name **zweimal** über dem Boss.
**Falsche Annahme:** Bedrock sehe nur den Mob-Namen. Tatsächlich zeichnet FMM sein Namensschild
(`StackedText`, Zeilen pro Zuschauer, Bedrock nur anders skaliert) **auch für Bedrock**; Bedrock
zeigt zusätzlich den Custom-Namen des Mobs darunter (EM gibt den YAML-Namen nur ans Modell,
`setName(name, false)`; FMM bindet das Bedrock-Modell ans Mob, `bindToUnderlyingEntity`).

Jetzt: `BedrockNameFix` **blendet den Mob-Namen für Bedrock aus** (Metadaten-Index 2 → leer),
sobald das Modell einen echten Namen hat — FMMs Schild bleibt als einziges, wie auf Java.
FMMs Platzhalter **„Default Name"** zählt nicht als Name (die erste Fassung hat ihn fälschlich
übernommen). Bleibt richtig, falls EM den Namen je aufs Mob schreibt; falsch nur, wenn FMM sein
Schild für Bedrock nicht mehr zeigt → `phase71d.bedrock-name-fix: false`.
Debug: `[PHASE71D] entity … : hid '…' for Bedrock` einmal pro Mob.

### Deploy-Stand TestServer01

- Läuft: **Test-JAR `c080ed5`, gegen PacketEvents 2.13.0 gebaut** (sha256 `bc47e341…`), seit
  27.09. 18:47. Backup des 20.09.-Builds: `FMMBedrockBridge.jar.bak-20260927`.
- **Noch nicht drauf:** `36ebc6c` (nur die Diagnose-Zeile `[PHASE74] hotbar from …`).
- ⚠️ **Der Repo-Stand baut gegen PacketEvents 2.14.0** und darf erst auf eine Instanz, die
  mindestens 2.14.0 hat — TestServer01 und Survival01 haben 2.13.0. Für einen Zwischen-Deploy
  wie am 27.09. die Version im pom kurz auf 2.13.0 setzen, bauen, pom zurücksetzen.

### MagmaGuy-Welle vom 29.09.2026 — geprüft am 30.09.

EM **10.9.7** · FMM **2.12.5** · RPM **2.4.5** · BS **2.7.6**. pom auf EM 10.9.7 / FMM 2.12.5 gehoben,
beide API-Generationen grün (73 Tests). Am 10.9.7-Artefakt per `javap` geprüft, dass **alle
Reflection-Ziele** unverändert da sind: `PlayerStatusScreenDialog.showPlayerStatusDialog`,
`PlayerStatusMenuConfig.getIndexChestMenuName`, `QuestInventoryMenu`-Maps + `QuestMenu.generateDialogMenu`
(7.3/7.3b), `ClassSelectionMenu.COORDINATOR` + `dialogs`/`inventories` + 4-Methoden-`ClassMenuRenderer`,
`PlayerData.getUseBookMenus`, `DefaultConfig.isOnlyUseBedrockMenus` (7.3c), `ActionBarCompositor.show`
+ `Source.ABILITY_INPUT`, `AdvancedCombatModule` (7.4).
**Kein Fix wird überflüssig:** `CustomBossEntity.setPluginName` ruft weiter `setName(name, false)`
(7.1d bleibt nötig); keine neue Bedrock-Eingabe und keine neue Bedrock-Kisten-Stelle (die Klassen mit
`BedrockChecker`-Bezug sind bis auf EM-interne Umbenennungen dieselben wie in 10.9.5).

### ▶ Nächste Sitzung

✅ **Zwei HP-Balken geklärt (01.10.):** EMs Anzeige erreicht Bedrock, 7.1b ist ausgebaut (Abschnitt oben).


Reihenfolge von Fabi, Details in `../HANDOFF.md` („NÄCHSTE SITZUNG"): **1.** Spieltest des JARs
`ab5914f` (7.1d Nametag, 7.3c Klassenmenü, 7.4 leere Ausdauer) · **2.** Upgrade inkl.
PacketEvents 2.14.0, dann Bridge gegen 2.14.0 bauen · **3.** Plan für Custom-GUIs auf Bedrock
(Vorbild NitroSetups).

### Noch offen

1. **Hotbar-Tasten auf Handy/Konsole** — dort blättert LB/RB Slot für Slot; wer dabei schleicht,
   löst jede Fähigkeit auf dem Weg aus. Nicht getestet. Notfalls `phase74.hotbar-keys: false`.
2. **Server auf PacketEvents 2.14.0 + EM 10.9.5 / FMM 2.12.3 / BS 2.7.4** — Server-Claude-Sache,
   PacketEvents läuft über PluginPortal (Tausch-Verfahren vom 09.08. in SERVER-STATE).
3. ~~**`BedrockAbilityListener.isBedrock()` fragt noch nur Floodgate**~~ ✅ **erledigt 27.09.** —
   fragt jetzt `ViewerManager.isBedrockPlayer()`, also dieselbe Reihenfolge wie EliteMobs.
   Am selben Tag nachgezogen: **`BedrockMenuRerouteListener` und `PacketInterceptor`** fragen
   ebenfalls `ViewerManager` — kein direkter `FloodgateApi`-Aufruf mehr außerhalb von
   `ViewerManager`. Dafür cacht `ViewerManager` die Antwort **pro Spieler und Sitzung**
   (geleert auf Quit, MONITOR): der `PacketInterceptor` fragt bei jedem Entity-Paket auf dem
   Netty-Thread, und ein Java-Spieler liefe sonst jedes Mal bis zur Geyser-Reflection durch.
   Phasen 7.3 und 7.4 registrieren sich weiter nur, wenn Floodgate installiert ist.
4. ~~`INVALID_PLAYER` … vermutlich kurz außerhalb des freigegebenen Bereichs~~ — **widerlegt am
   27.09.:** EliteMobs meldet `INVALID_PLAYER` auch bei **zu wenig Ausdauer**
   (`AdvancedCombatModule.useAbility`, „Not enough Stamina (x/y required)"), außerdem bei Transport,
   inaktiven Klassen-Controls und ohne gewählte Klasse. Aus dem Grund allein ist also nicht ablesbar,
   warum abgelehnt wurde — EMs eigene Chat-/Actionbar-Meldung sagt es.
   Folge-Fix: Hotbar-Tasten werden seitdem **immer** verbraucht, wenn sie eine Fähigkeit meinen —
   vorher wechselte bei leerer Ausdauer das Item.

### Wenn etwas nicht stimmt

`debug: true` steht in der Instanz-Config. Im Log stehen dann:

- `[PHASE74] interact from …: action=… hand=… sneaking=… cancelled=…` — was Geyser wirklich
  schickt, **vor** jeder Prüfung
- `[PHASE74] hotbar from …: slot a -> b sneaking=… enabled=…` — jeder Slot-Wechsel eines
  Bedrock-Spielers, ebenfalls vor jeder Prüfung (ab `36ebc6c`)
- `[PHASE74] … failed: <GRUND>` — EliteMobs hat abgelehnt, nicht wir
- `[PHASE74] compositor call failed, falling back to sendActionBar: …` — EMs
  `ActionBarCompositor` war da, hat aber beim Aufruf geworfen; die Rückmeldung geht ab da
  wieder direkt raus und wird vom Klassen-HUD überschrieben

Diese Zeilen sind aus Fehlern entstanden: Ohne sie war jedes Mal unklar, ob ein Event
fehlt, verworfen wird oder EliteMobs ablehnt — und jede Vermutung darüber war falsch.

Im Boot-Log steht außerdem, welchen Weg die Rückmeldung nimmt:
`Phase 7.4: Bedrock ability input registered (…, feedback=true, compositor=ABILITY_INPUT)`.
Steht dort `compositor=unavailable`, hat MagmaGuy die Klasse verschoben — dann schreibt die
Bridge wieder direkt und das Feedback flackert.

### Danach: Branch abschließen

✅ **Erledigt am 27.09.2026:** `phase-7.4-bedrock-ability-input` (36 Commits) per **Squash** nach
`main` gemerged — `d2444bd`. Die Einzelhistorie (inkl. der vier Irrwege) bleibt auf dem Branch
erhalten, der deshalb **nicht gelöscht** wird. Das lokale `main` stand vorher auf vier nie
gepushten Doku-Commits; die waren alle im Branch enthalten und sind im Squash drin.

---

## 0. Randbedingungen für dieses Plugin

- **Der geprüfte Stack steht in `../CLAUDE.md`** („Geprüfter Stack") — nicht hier, damit es
  nur eine Quelle gibt.
- **Der pom steht seit 27.09.2026 auf dem aktuellen Upstream-Stand:** FMM **2.12.3** /
  EM **10.9.5** / PacketEvents **2.14.0**. Beide API-Generationen grün, 53 Tests.
  ⚠️ Das JAR braucht auf dem Server **PacketEvents ≥ 2.14.0**.
- **`api-version` in `plugin.yml` bleibt `'1.21'`** — Mindestangabe, keine Zielangabe.
  **Nicht „korrigieren".** (Begründung in `../CLAUDE.md`.)
- **Bedrock-Rendering ist nie im Log verifizierbar**, nur in-game.
- **Die 14.08.-JAR ist deployt** (`…-20260814-2101.jar`, `sha 57753139…`), gebaut gegen
  packetevents 2.13.0.

---

## 1. Wo wir gerade stehen (Git)

### Session 2026-09-09 (Teil 3) — Historie neu geschrieben, Repo neu angelegt

**Kein Plugin-Code.** `server-tools/` ist aus der gesamten Historie entfernt worden, weil dort
`SERVER-STATE.md` mit Instanz-Bestand, zwei DB-Host-IPs und einem PluginPortal-Key lag — in
einem **öffentlichen** Repo.

- `git filter-repo --invert-paths --path server-tools`, **196 → 191 Commits** (fünf Commits
  betrafen nur server-tools und sind entfallen).
- Gegenprobe lokal: 0 Commits und 0 Blobs unter `server-tools`, 0 Treffer auf beide IPs und den
  Key. Plugin unversehrt — 24 Java-Dateien, 5 Tests, alle Kerndateien.
- Force-Push allein hat **nicht gereicht**: die alten Commits blieben über ihre SHA öffentlich
  abrufbar. Auch der Umweg „privat und zurück auf public" hielt nicht.
- **Repo gelöscht und neu angelegt**, bereinigte Historie gepusht. Danach liefert die API
  „No commit found for SHA" und raw **404 bei `x-cache: MISS`**. Alle **11 Branches + 2 Tags**
  sind wieder drauf, URL unverändert, `main` ist Default.
- HEAD ist jetzt **`00f9022`** (vorher `5701eec`) — dieselbe Arbeit, neue SHA.

⚠️ **Folge für den zweiten PC:** alter Klon unbrauchbar, neu klonen. S. Kopfblock.

Details, Messmethode und die Fastly-Cache-Falle: `../HANDOFF.md` → „Sicherheitsvorfall",
Regel dauerhaft in `../CLAUDE.md`.

### Session 2026-09-09 — Server-State nachgezogen, Doku entdriftet (KEIN Plugin-Code)

`server-tools/SERVER-STATE.md` war **26 Tage im Rückstand** (Repo 14.08. / Server 09.09.). Die
Server-Kopie ist per `scp` geholt und **bit-identisch** übernommen (`md5 168ed030…`, +1946 Zeilen);
die 10 nur im Repo vorhandenen Zeilen waren Alttexte, die drüben nach Schreibregel 2 überschrieben
wurden — **kein Verlust**. Damit sind beide Kopien wieder synchron.

**Was sich seither geändert hat** (die ersten drei Zeilen standen so in der Dev-Doku, die vierte
nur in `SERVER-STATE.md` und ist dort schon aufgelöst):

| | stand da | Ist (09.09.) |
|---|---|---|
| FMM · EliteMobs | 2.11.1 · 10.8.0 | **2.11.2 · 10.8.1** (TestServer01 seit 08.09., **Boot fehlt**) |
| 26.2-Rollout | „nächster Schritt Survival01" | Survival01 ✅ 08.09. · **Reihe pausiert** (Umbau) |
| GeyserModelEngine | 1.0.3, geshadetes packetevents 2.11.2 ≠ 2.12.1 | **1.0.9**, geshadetes PE **2.13.0** = installiert ⇒ **kein Konflikt** |
| `dungeons01` | „seit 06.07.2025 durchgehend online" | **leere Hülle** — keine ServerJAR, kein Prozess |

**Angepasst:** Abschnitt 0 (Ziel-Stack, Rollout-Pause, pom-Drift), der Bootstrap-Build-Block
(stand noch auf FMM 2.10.1 / EM 10.7.2, während der pom seit 14.08. auf 2.11.1 / 10.8.0 baut —
wer ihn wörtlich abgearbeitet hätte, wäre am „Could not find artifact" hängengeblieben),
`CLAUDE.md` (Instanz-Pfadliste) und `server-tools/server-CLAUDE.md` (Altlast-Eintrag).

**Auf den Server zurückgespielt** (beides mit Backup + md5-Gegenprobe, keine Instanz/JAR/Config
angefasst):
- `~/.claude/CLAUDE.md` ← `server-tools/server-CLAUDE.md` (`32eed842…` → **`9bc4256a…`**,
  Backup `~/.claude/CLAUDE.md.bak-20260909-devclaude`). Vorher geprüft: die Server-Kopie war
  bit-identisch mit dem Repo-Stand vor der Änderung ⇒ **kein Server-Claude-Edit überschrieben.**
- `~/SERVER-STATE.md` — Änderungs-Log-Eintrag „09.09. 15:30 Dev-Claude" ergänzt (Schreibregel:
  wer schreibt, trägt es dort ein). Beide Kopien jetzt **`eb160f06…`**,
  Backup `~/SERVER-STATE.md.bak-20260909-devclaude`.

**Kein Plugin-Code angefasst, kein Rebuild.** Der pom bleibt auf FMM 2.11.1 / EM 10.8.0 —
**Fabis Entscheidung vom 09.09.**, weil beides `provided` und Patch-Level ist und kein
Deploy-Anlass besteht.

#### ✅ Boot-Verifikation 09.09. 15:33 (Fabi hat TestServer01 neu gestartet)

Paper **26.2-112**, `Done (52.820s)`, **5 ERROR** (Polymart, ShopGUIPlus, Genesis-Config — alle
vorbestehend, keiner neu), `Ambiguous plugin name`: **0**. Live jetzt FMM **2.11.2** ·
EM **10.8.1** · RPM 2.3.1 · packetevents 2.13.0 · GME 1.0.9 · Floodgate 2.2.5.

**Die Bridge (JAR vom 14.08., `sha 57753139…`) meldet sich vollständig:** `FMMEntityTracker
started` · `Sync task started` · `PacketInterceptor registered` · **`PacketEvents: found`** ·
`Phase 7.1c: combat trigger registered` · **`Phase 7.3: … reroute registered (status=true,
quest=true)`** · `FreeMinecraftModels: found` · `Floodgate: found`.
**Null WARN, null Exception aus der Bridge**, und netzwerkweit **kein** `NoSuchMethodError` /
`NoSuchFieldError` / `NoClassDefFoundError` ⇒ **FMM 2.11.2 und EM 10.8.1 brechen unsere API-
Berührungspunkte nicht.** Das ist der harte Beleg für „pom-Bump nicht nötig".

> 🔶 **Nebenfund, KEIN Bridge-Thema (Server-Content):** FMM 2.11.2 hat eine neue
> Kollisionsprüfung für normalisierte Model-IDs und wirft daraufhin **zwei Modelle komplett weg**
> — `em_goblin_coins` und `em_goblin_treasure` liegen je doppelt (`models/` Root **und**
> `models/em_events_goblins_free/`), normalisieren auf dieselbe ID, `no colliding model was
> loaded`. Die beiden Goblin-Event-Modelle fehlen also im Pack, auf Java **wie** auf Bedrock.
> Fix: je Paar eine Datei umbenennen/löschen. **Gehört Fabi/Server-Claude, nicht der Bridge.**

### Session 2026-08-16 — A/B-Test entschieden: 7.1b/7.1c bleiben (KEIN Plugin-Code)

**Die Frage war falsch gestellt — es gab nie eine Dopplung.** Der Test lief sauber: Bridge-Overlay
aus (`phase71b.nametag-enabled: false`), EMs eigene Anzeige an (`displayVisualHealthBars: true`,
`displayNumericHealth: true` — beidseitig verifiziert, sonst hätte der Test nichts gemessen).

**Fabis Beobachtung in-game:** Auf **Java** Balken + Zahl über dem Mob **plus** ein
Schaden-Popup je Treffer. Auf **Bedrock** nur das Popup und die BossBar — **kein Overhead-HP**.

| | Java | Bedrock |
|---|---|---|
| EMs Overhead-HP (`EliteOverheadHealthDisplay`) | ✅ | ❌ **kommt nicht an** |
| Bridge-Overlay 7.1b/7.1c | **nie sichtbar** (weggefiltert) | ✅ |

Der entscheidende Punkt steht im Code: `BedrockNametagController` ist **Bedrock-only** —
`PacketInterceptor.hideFromJava()` unterdrückt die TextDisplay-Pakete für alle
Nicht-Floodgate-Spieler (Klassen-Doc Z. 13–18). **Java-Spieler haben unser Overlay nie gesehen.**
Die beiden Anzeigen bedienen disjunkte Client-Gruppen; das ist genau die Arbeitsteilung, für die
7.1b gebaut wurde.

> ⚠️ **Die früher hier notierte Konsequenz „Nein → dann EMs Anzeige abschalten" war falsch** und
> wurde **nicht** ausgeführt. Sie setzte eine Dopplung voraus, die es nicht gibt. EMs Anzeige
> abzuschalten hätte nur den **Java**-Spielern etwas weggenommen, ohne auf Bedrock irgendetwas zu
> gewinnen. Gleiches gilt für die Memory-Notiz `em_overhead_health_duplicates_bridge` — korrigiert.

**⇒ 7.1b/7.1c bleiben unverändert. Rest-Scope der Bridge endgültig: Combat-BossBar + HP-Nametag.**

**Nebenbefunde aus dem Boot-Log (16:38/16:39, Paper 26.2):**
- `PacketEvents: found — packet interception active` ⇒ der Verdacht aus Abschnitt 0 (2.13.0
  bricht die alte Erkennung) ist **ausgeräumt**.
- `Phase 7.3: Bedrock menu dialog-reroute registered (status=true, quest=true)` ⇒ der
  `McVersions`-Fix greift auf `26.2.build.112-stable`; der 10.07.-Build stand hier noch auf
  `NOT registered`. **Damit ist 7.3b nebenbei live-verifiziert.**

**Offene Beobachtung, kein Auftrag:** EMs Overhead-Balken **und** die Combat-Popups laufen beide
über `VisualDisplay.createStyledFakeText` → `FakeText` (EasyMinecraftGoals, paket-basierte
Fake-Entities). Trotzdem kommt nur das Popup auf Bedrock an. Da DamageIndicator 2.0.5 laut Fabi
„nie wirklich funktioniert hat", stammt das Popup von EM selbst ⇒ der Unterschied liegt
vermutlich an der **Bindung an den Mob** (der Overhead-Text hängt am Mob und kollidiert mit FMMs
Bedrock-Custom-Entity), nicht am Render-Mechanismus. Nur relevant, falls das Overhead-Display
jemals doch auf Bedrock gebraucht wird.

**Server-Config nach dem Test zurückgestellt** (`debug: false`, `nametag-enabled: true`,
Backup `config.yml.bak-20260816-abtest`) — **wirkt erst nach dem nächsten Neustart.**

### Session 2026-08-14 — 26.2 ist live, 7.1a umgebaut und verifiziert

**Der Stack hat sich an einem Tag komplett gedreht.** Fabi und der Server-Claude haben das
Netz auf **Paper 26.2** gehoben und danach die MagmaGuy-Kette gezogen. Ziel-Stack jetzt:

| | |
|---|---|
| TestServer01 | **paper-26.2-112**, Java 25 |
| MagmaGuy | FMM **2.11.1** · EM **10.8.0** · RPM **2.3.1** · BS **2.7.0** |
| Proxy01 | Geyser 2.11.1 · RPM **2.3.1**, `loadedDefinitions=314` |
| packetevents | 2.13.0 |

**Was am Plugin passiert ist (erster Code-Change seit 02.08.):**

- **7.1a auf EMs neues BossBar-Pooling umgebaut.** EM 10.8.0 hat `BossHealthBarManager`:
  ein Pool von **max. 4 wiederverwendeten** Bars pro Spieler, die für wechselnde Bosse
  um-betitelt werden, plus Reordering per removePlayer+addPlayer. Damit fiel die alte
  Annahme „der erste titel-passende ADD ist unserer".
  - **Neu `BossBarUuidResolver`** — liest die Wire-UUID der eigenen Bukkit-BossBar per
    Reflection (CraftBossBar → NMS-Handle → einziges `UUID`-Feld, **ohne** Feldnamen zu
    verdrahten). Schlägt sie fehl → `null` → alte Heuristik + einmalige Log-Zeile.
  - **`BossBarRegistry` ist nicht mehr write-only:** Eviction bei REMOVE und bei einem ADD,
    dessen Titel keinem aktiven Controller gehört (recycelter Slot). Ohne das würden fremde
    Bosse auf Bedrock einfrieren.
  - **`exitCombat()` löscht die Eigen-UUID nicht mehr** — das BossBar-Objekt lebt so lange
    wie der Controller, die UUID ist stabil.
  - Notausstieg `phase71a.resolve-own-bossbar-uuid` (default true).
    **Symptom einer falsch aufgelösten UUID: Bedrock sieht GAR KEINE Bar.**
- **Neuer Schalter `phase71b.nametag-enabled`** für den offenen A/B-Test (s. Kopf).
- **pom auf FMM 2.11.1 / EM 10.8.0** — die frühere Entscheidung „kein Dep-Bump" ist damit
  überholt. Beide JARs liegen **nicht** im magmaguy-Maven-Repo → vom Server ziehen und
  `mvn install:install-file` (Rezept in Abschnitt „Build & Deploy").
- **21/21 Tests grün** (5 neue in `BossBarRegistryTest`), `verify-both-apis.sh` beide
  Generationen grün, Bytecode-Target 21.

**Live verifiziert am 14.08. 22:33–22:44** (Bedrock `.Nightgame2272`, zwei EM-Bosse):
`Resolved own BossBar UUID` **9×**, `Could not read` **0×**, alte Heuristik **0×**;
`Suppressed stale-title` 3× an verschiedenen Pool-Slots; **`Released suppressed … on REMOVE` 2×**
⇒ die Eviction greift. Fremde Bars (Plugin-Ladebalken) 3× korrekt durchgelassen.
Fabi in-game: „sah alles gut aus, eine Leiste pro Boss."

**Zwei Nebenfunde, beide dokumentiert:**
- **`getBukkitVersion()` liefert auf 26.2 `26.2.build.112-stable`.** Der alte 10.07.-Build
  konnte das nicht ordnen und hat den **Dialog-Reroute still abgeschaltet**
  (`Phase 7.3: reroute NOT registered … mc>=1.21.6=false`). Auf diesem Branch längst gefixt
  und in `McVersionsTest` abgedeckt — nach dem Deploy steht dort `registered`.
- **Bauen braucht zwingend JDK 25** (`JAVA_HOME=/usr/lib/jvm/java-25-openjdk`), sonst
  *„Ungültige Klassendatei … paper-api"* — Paper 26.2 liefert Class-File-Version 69.
  Bytecode-Target bleibt 21. `verify-both-apis.sh` findet Maven jetzt auch unter
  `plugins/maven-plugin/` (IntelliJ benennt den Ordner je nach Version um).

- **Aktiver Branch: `main`.** `feat/mc-26.2-readiness` wurde am **16.08.** mit `--no-ff` gemerged
  (Merge-Commit `e191039`) — bewusst als revertierbare Einheit, wie beim 7.2b-Merge.
  Der Feature-Branch bleibt auf `origin` stehen.
  - **Backup vor dem Merge:** Branch **`backup/main-pre-26.2-merge`** → alter main-Stand
    (`020aed4`), auf `origin` gepusht. Dient als Rückweg **und** als Nachschlage-Quelle für den
    Stand vor 26.2. Notfall: `git reset --hard backup/main-pre-26.2-merge` oder
    `git revert -m 1 e191039`.
  - **Konflikt beim Merge:** nur `HANDOFF.md` — `main` trug seit 02.08. den
    „Datei veraltet"-Zeigerkasten, der Branch die gepflegte Fassung. Zugunsten des Branches
    aufgelöst; der Zeiger war durch den Merge ohnehin erledigt.
  - Verifiziert nach dem Merge: `main` ist **inhaltlich identisch** mit dem Branch
    (`git diff` leer), 21/21 Tests grün, beide API-Generationen bauen.
- **Session 09.08. kurz:** Server-Audit (Java-25-Blocker aufgelöst), drei Server-Fixes auf Fabis
  Anweisung (packetevents 2.13.0, 2 EM-Lua-Skripte, 1 FMM-Modell-Keyframe), und die **Trennung
  von Server- und Entwicklungs-Doku** — Details in `server-tools/SERVER-STATE.md`.
- ~~Auf dem Server liegt noch das JAR vom 10.07.~~ **überholt** — seit 14.08. läuft dort
  `…-20260814-2101.jar` (sha `57753139…`, beidseitig geprüft).
- (historisch) Vor dem 26.2-Branch war `main` bei `f1dd00c` (`tooling(server)`: `server-tools/`),
  darunter die Doku-Commits vom 10.07. und Merge-Commit `be08a2f`
- **Phase-7.2b-Removal ist nach `main` gemerged** (2026-07-10, `--no-ff`, bewusst als revertierbare Einheit). Der Feature-Branch `refactor/remove-phase72b` existiert weiter (auf `origin`), ist aber jetzt in main enthalten.
- **Backup vor dem Merge:** Tag `backup/pre-72b-merge-main` → alter main-Stand (`4a277d8`), auf `origin` gepusht. Notfall-Rückweg: `git reset --hard backup/pre-72b-merge-main` oder `git revert -m 1 be08a2f`. (Zusätzlich weiter vorhanden: `archive/2026-05-24-pre-rpm18-pivot`.)
- Working tree **sauber**
- Build 2026-07-10 verifiziert (offline gegen echte Server-JARs FMM 2.10.1 / EM 10.7.2): **BUILD SUCCESS, 13 Tests grün.** Artefakt: `target/FMMBedrockBridge-0.1.0-SNAPSHOT-20260710-1454.jar`. **Plugin-Code unverändert seit 13. Juni** — Sessions danach waren Doku/Tooling/Diagnose + dieser Merge.
- Build auf dem neuen PC zur Sicherheit nochmal laufen lassen: `mvn -o clean package -DskipTests`

### Was in der Session 2026-07-28/29 dazukam (Upstream-Check, Server-Setup, Tooling — KEIN Plugin-Code)
- **Neue Upstream-Releases** (Fabi aus dem MagmaGuy-Discord): FMM **2.10.2**, EM **10.7.3**, RPM **2.3.0**, BetterStructures **2.6.3**. ⚠️ **Der Source dieser Builds ist nicht auf GitHub** — `references/` zeigt FMM/RPM/EM weiter auf 2.10.1 / 2.2.2 / 10.7.2 (letzter Push 28.06.). Nicht im Code gegenprüfbar, nur live gegen die JARs.
- **Geyser-Kopplung entdeckt:** RPM 2.3.0 fixt „Bedrock custom-entity bridge for **Geyser 2.11**"; GeyserModelEngine hat parallel `fix/geyser-2.11-sync` gemerged. Zwei Projekte, derselbe Bruch. **Proxy01 läuft auf Geyser `2.10.1-b1175`** (per SSH verifiziert) → trifft uns noch nicht, aber **Geyser nicht hochziehen, solange RPM auf 2.2.2 steht** (sonst Pig-Fallback auf Bedrock).
- **EM 10.7.3 berührt unseren Rest-Scope** — beides Risiko, kein Gewinn: „Proximity boss bars no longer flicker/reorder" trifft unsere First-Match-Heuristik (`PacketInterceptor.java:120-145`, 7.1a); „NPC role tags auf Bedrock (`bedrockNPCRoleYOffset`)" kann mit `BedrockNametagController` doppeln (7.1b).
- **Vollbackup TestServer01:** `/home/amp/backups/TestServer01-20260728-2211/` (1,8 GB zstd, Integrität geprüft, Manifest mit allen Plugin-Versionen).
- **Claude Code auf dem Server installiert** — 2.1.220 als User `amp` (nicht root; die AMP-Web-Console ist keine Shell, sondern Server-stdin — `amp` hat eine normale bash). Login steht noch aus, RCON bewusst aus.
- **Neu im Repo: `server-tools/`** (Commit `f1dd00c`) — `plugin-update-check.sh`, `backup-testserver.sh`, `server-CLAUDE.md` (→ `~/.claude/CLAUDE.md`), README mit vier dokumentierten API-Fallstricken.
- **Update-Lage TestServer01:** Paper 113 → 130, Floodgate b132 → b138, EssentialsX/FAWE/LuckPerms/Skript/packetevents ebenfalls veraltet. ProtocolLib ist ein Dev-Build **neuer** als der Release — nicht downgraden (LibsDisguises braucht ihn).

### Was in der Session 2026-07-07 dazukam (Live-Server-Diagnose, KEIN Plugin-Code)
**Entscheidungs-Test aus Abschnitt 3 DURCHGEFÜHRT:** FMM 2.10.1 + RPM 2.2.2 + EM 10.7.2 frisch deployt, Bridge **deaktiviert**, auf TestServer01/Proxy01 getestet. Ergebnis: **der native Stack rendert Custom-Mobs auf Bedrock** — nach Behebung von zwei Deploy-Fallstricken (per SSH live diagnostiziert, Logs in `references/logs/`):
- **Root Cause A** „Bedrock sah GAR keine Monster": RPM-Geyser-Bridge-Extension lädt nach RPM-Update nicht (Write zu spät im ersten Boot) → **Fix: Proxy ein zweites Mal neustarten**. Bestätigt: `Erweiterung ResourcePackManagerGeyserBridge aktiviert`.
- **Root Cause B** „Monster ohne Animation": Extension sucht Pack unter `plugins/ResourcePackManager/...` (groß), Velocity-Ordner heißt `resourcepackmanager` (klein) → Linux case-sensitive → `bridge ready with 0` → keine Property/Animation-Schemas. **Fix: Symlink `ResourcePackManager → resourcepackmanager` auf Proxy + Restart**. Bestätigt: `Preloaded 316 … Registered 281 property schema(s) … bridge ready with 316`.
- **In-Game-Animations-Check steht noch aus** (Fabi wollte nicht mehr testen) — Pipeline ist aber log-seitig komplett bestätigt.
- SSH-Zugang dieses PCs (`lappi windows`) am Server autorisiert (siehe Memory `proxy-ssh-access`).
- **references/ auf Upstream:** FMM 2.10.1, RPM 2.2.2, EM 10.7.2, BetterStructures 2.6.2 (via `setup-references.sh`).
- Details + Deploy-Regeln in Memory: `native-bedrock-deploy-gotchas`, `fmmbridge-status`.

### Was in der Session 2026-07-08 dazukam (Diagnose-Abschluss + Grundsatzentscheidung, KEIN Plugin-Code)
**Die offenen Verify-Punkte aus 2026-07-07 sind beantwortet — Grundsatzentscheidung steht:**
- **Punkt 1 (In-Game-Animation) ✓** — EM-Boss animiert auf Bedrock nativ. Native Pipeline damit auch visuell bestätigt.
- **Punkt 2 (Combat-BossBar + HP-Nametag) ✗ nativ** — Bedrock sieht sie NICHT. Java zeigt sie (Java-natives Feature). = **Feature-Gap**, nicht Lag (siehe unten). → **bleibt Bridge-Scope (7.1a/7.1b).**
- **Lag-Verdacht geklärt:** Server-**TPS = 20** (Server-Thread sauber). Der von Fabi gefühlte Lag trifft **Java UND Bedrock lokal gleichermaßen** → **lokales Internet-Problem auf Fabis Seite**, KEIN Server-/Geyser-/Bridge-Thema. Vom Tisch. (Symmetrischer Lag kann Punkt 2 nicht erklären, da Java die BossBar trotzdem zeigt.)
- **`references/` per `setup-references.sh`/`git pull` auf Upstream:** FMM **2.10.1**, RPM **2.2.2**, EM **10.7.2**, BetterStructures 2.6.2, GeyserModelEngine (translucent-textures), GeyserUtils unverändert (loadSkin-Bug offen).

**⇒ GRUNDSATZENTSCHEIDUNG: Bridge wird NICHT archiviert.** Mob-Rendering/Animation/3D-Items/UI-Items laufen nativ (FMM 2.10 + RPM 2.2 + EM 10.7). Übrig bleibt der Rest-Scope **Combat-BossBar + HP-Nametag** (7.1a/7.1b). Der Branch `refactor/remove-phase72b` (entfernt das 2D-Item-Subsystem, weil RPM es nativ kann) ist damit inhaltlich bestätigt und **merge-reif nach `main`**.

### Was in der Session 2026-07-10 dazukam (Merge nach main, KEIN Plugin-Code)
Die Grundsatzentscheidung wurde umgesetzt:
- **Backup-Tag `backup/pre-72b-merge-main`** auf den alten main-Stand gesetzt + gepusht (vor dem Merge, für den Fall der Fälle).
- **`refactor/remove-phase72b` → `main` gemerged** (`git merge --no-ff`, Merge-Commit `be08a2f`), Doku-Commit `3d4ae90` obendrauf.
- **Build + Tests offline verifiziert:** BUILD SUCCESS, 13/13 grün, Artefakt `…-20260710-1454.jar`.
- `main` gepusht, synchron mit `origin/main`.

### Was in der Session 2026-06-25 dazukam (alles Doku/Tooling, KEIN Plugin-Code)
- `HANDOFF.md` (diese Datei) + Bootstrap/Session-Ende-Protokoll
- `setup-references.sh` — klont/aktualisiert die 6 Reference-Repos (gitignored)
- `references/` auf aktuellen Upstream gebracht: **FMM 2.9.1, RPM 2.2.1, EM 10.7.1, BetterStructures 2.6.1** (FMM/EM brauchten Hard-Reset wegen force-gepushter History)
- `CLAUDE.md`: „Rolle & Arbeitsweise" (Minecraft-Java-Dev für Plugins+Mods, Superpowers-Skills aktiv nutzen) + Multi-PC-Workflow-Regel + Skill-Portabilität
- **`claude-skills/` + `install-skills.sh`** — die 7 Minecraft-Custom-Skills sind jetzt im Repo gebündelt (waren vorher nur lokal auf einem PC, nicht im Marketplace) und per `bash install-skills.sh` auf jeden PC spielbar

### Was dieser Branch macht (Phase 7.2b Removal)
Vollständige Entfernung des **EM-2D-UI-Item-Subsystems** (`bridge_em` Namespace), weil **RPM 2.0.2 diese Items nativ konvertiert** (`scanLegacyCustomModelOverrides`). Entfernt:
- `bridge_em` item_model-Inject aus `PacketInterceptor` — **7.1a/7.1b (BossBar/Nametag) bleiben erhalten**
- EM Item-Scan / Pack-Generierung / Geyser-Mappings-Klassen
- Maintenance-Subsystem + `/fmmbridge maintenance` Subcommand
- `elite-items` Config-Section, tote GeyserUtils-Dep, stale Strings
- Docs aktualisiert (Design-Spec + Plan + Gate-Outcome)

**Bekannte Lücke:** Banner werden von Bedrock nicht als custom-item gerendert → EM boxinput/boxoutput (Verzauberer) fehlen nativ. **10/12 EM-UI-Items ok.**

### Offene Punkte aus 7.2b
- [ ] Live-Verify auf Server: rendert RPM 2.0.2 die 10/12 Items wirklich nativ?
- [ ] Upstream-Report an MagmaGuy zur Banner-Lücke (als Task in Docs vermerkt)

---

## 2. ⚠️ KRITISCH: MagmaGuy-Stack macht jetzt natives Bedrock-Bridging

Seit der letzten Session (lokale Refs waren vom **5. Juni**) hat MagmaGuy massiv geliefert.
**Konsequenz: Die Existenzberechtigung dieser Bridge muss neu bewertet werden — evtl. wird sie ganz überflüssig.**

### Upstream-Versionssprünge (Stand 2026-06-25, via `git fetch` in references/)

| Plugin | War (lokal, 5. Juni) | Jetzt upstream | Relevante Neuerung |
|---|---|---|---|
| **FreeMinecraftModels** | 2.7.1 | **2.9.1** | 2.8.0: **„Export models as a Bedrock entity bundle for Bedrock/Geyser integrations"** · 2.9.1: „Fixed Bedrock custom entity backend initialization for content entities" + Floodgate als soft-dependency |
| **ResourcePackManager** | 2.0.2 | **2.2.1** | 2.1.0: **„Bedrock entity bridge"** + „Fix Bedrock vanilla item scanning and relay polling" |
| **EliteMobs** | 10.5.0 | **10.7.1** | (schon 10.3.1: „**Bedrock players can now see custom-modeled bosses and NPCs through Geyser** — requires latest FMM + RPM") |
| **BetterStructures** | 2.5.0 | 2.6.1 | setup overhaul |
| **GeyserUtils** | (main, 11.01.) | unverändert | loadSkin-Bug weiter offen |

### Was das bedeutet
Die Kombi **FMM 2.8.0+ (Bedrock entity bundle export) + RPM 2.1.0+ (Bedrock entity bridge) + EM 10.3.1+ (Bedrock-Bosse durch Geyser)** deckt nativ genau das ab, wofür die Bridge ursprünglich gebaut wurde:
- Custom-Modelle für Bedrock-Clients sichtbar machen → **nativ in FMM/RPM**
- EM-Bosse/NPCs auf Bedrock → **nativ in EM 10.3.1+**
- EM-UI-Items → **nativ in RPM 2.0.2 (war schon Grund für 7.2b-Removal)**

Was von der Bridge **vielleicht** noch übrig bleibt (zu prüfen!):
- 7.1a/7.1b: Combat-styled **BossBar** + Combat-**Nametag** (HP/Bar) — macht FMM/EM das jetzt auch nativ auf Bedrock? **UNGEPRÜFT.**
- Banner-basierte UI-Items (boxinput/boxoutput) — RPM-Lücke, aber das ist eine *Lücke*, kein Bridge-Feature.

**Update 2026-07-08 (ENTSCHIEDEN):** Rest-Scope-Fragen aus 07-07 sind geklärt.

| Scope | Status |
|---|---|
| Mob-Rendering + Animation auf Bedrock | ✅ **nativ** (FMM 2.10 + RPM 2.2 + EM 10.7) — Bridge obsolet |
| EM-UI-Items (10/12, ohne Banner) | ✅ nativ (RPM 2.0.2) |
| Combat-**BossBar** + HP-**Nametag** | ❌ **nicht nativ** → **bleibt Bridge-Scope (7.1a/7.1b)** |
| Waffen-Offset | RPM-Item-Konvertierungsproblem (legacy pre-1.21.4 `custom_model_data`), **KEIN Bridge-Feature** |

**⇒ Bridge NICHT archivieren, sondern auf 7.1a/7.1b (BossBar + Nametag) reduzieren.** `refactor/remove-phase72b` ist merge-reif.

---

## 3. Nächste Schritte (Priorität)

> **Alles, was Server-Betrieb ist** — Paper-26.2-Umstellung, Plugin-Beschaffung, JVM-Wechsel der
> übrigen Instanzen, Survival↔Test-Abgleich — steht ab 09.08. in **`SERVER-STATE.md`** und wird
> dort mit dem Server-Claude gemeinsam gepflegt. Hier nur noch, was am Plugin selbst zu tun ist.

### Bridge-Rest-Scope

1. ~~**A/B-Test HP-Nametag auswerten**~~ ✅ **erledigt 16.08.** — EMs Overhead-Anzeige erreicht
   Bedrock **nicht**, und eine Dopplung gab es ohnehin nie (unser Overlay ist Bedrock-only).
   **7.1b/7.1c bleiben unverändert**, EM-Config **nicht** angefasst. Details in Abschnitt 1.
2. ~~**Combat-BossBar (7.1a) auf Bedrock prüfen**~~ ✅ **erledigt 14.08.**, s. Abschnitt 1.
3. ~~**Upstream-Reports einreichen**~~ ✅ **erledigt 10.09.** — **nichts mehr offen.**
   **Props-als-Schwein (FMM)** ist bereits **von dritter Seite gemeldet** worden (Fabi,
   10.09.); wir reichen nicht nach. Der Entwurf bleibt als Analyse liegen und ist oben mit
   „nicht mehr einreichen" markiert. Technisch unverändert: `BedrockModeledEntity.java:64`
   führt weiter `.carrierEntityType(EntityType.PIG)` im Fake-Entity-Pfad.
   ~~Case-Sensitivity im RPM-Geyser-Bridge-Pfad~~ ✅ **von MagmaGuy in RPM 2.3.1 gefixt**
   (`BEDROCK_PACK_PATHS` probiert beide Schreibweisen, Kommentar *„Velocity's default data
   directory is lowercase"*) — Entwurf als erledigt markiert, nicht mehr einreichen.
4. ~~**pom auf aktuellen Stand ziehen**~~ ✅ **erledigt 27.09.** — FMM 2.12.3 / EM 10.9.5 /
   PacketEvents 2.14.0.
5. ~~**Symlink-Test**~~ ✅ **erledigt 16.08. — der Workaround ist weg und bleibt weg.**
   Symlink deaktiviert, Proxy-Boot 17:51 ohne ihn:
   `Preloaded 316 … from …/plugins/`**`resourcepackmanager`**`/work/merged/Bedrock.zip`,
   `Registered 316 …`, `loadedDefinitions=316` (statt 0 — und zwei mehr als die 314 vom 14.08.).
   Der Log nennt den **kleingeschriebenen** Pfad, also greift MagmaGuys Fix real.
   - **Vorher am Artefakt belegt statt am GitHub-Master** (eure „Commit ≠ Artefakt"-Lehre):
     `javap` auf die laufende `RspmGeyserBridgeCore.class` (10.08.) zeigt `BEDROCK_PACK_PATHS`
     als `List.of` dreier Pfade — Kleinschreibung **an erster Stelle**. Zusätzlich abgesichert:
     im ganzen `geyserbridge`-Package enthält **nur diese eine Klasse** das Literal `plugins`,
     es kann also keine zweite Stelle geben, die weiter auf Großschreibung besteht.
   - **Nur bei einem Downgrade auf RPM ≤ 2.3.0 muss der Symlink zurück.**

### Build & Deploy

5. ~~**Bridge gegen packetevents 2.13.0 neu bauen und deployen**~~ ✅ **erledigt 14.08.**
   Deployt ist `…-20260814-2101.jar` (sha `57753139…`, beidseitig geprüft). Backups auf dem
   Server: `FMMBedrockBridge.jar.bak-20260814-2150` (alter 10.07.-Build) und `.bak-20260814-2300`.
6. ~~**Branch mergen**~~ ✅ **erledigt 16.08.** — `--no-ff` nach `main` (`e191039`),
   Backup-Branch `backup/main-pre-26.2-merge`. Details in Abschnitt 1.
7. **Deploy-Stand vs. `main`:** Auf TestServer01 läuft der Build vom **14.08.**; seither kam
   **kein Plugin-Code** dazu (16.08. war Test + Doku). Ein Redeploy ist also **nicht nötig** —
   erst wieder, wenn tatsächlich Code geändert wird. Die Server-Config wurde am 16.08. nach dem
   A/B-Test zurückgestellt (`debug: false`, `phase71b.nametag-enabled: true`,
   Backup `config.yml.bak-20260816-abtest`) und **wirkt erst ab dem nächsten Neustart**.

> **Build-Rezept auf einem frischen PC** (der frühere Merker „kein Dep-Bump" ist **überholt** —
> seit 14.08. baut die Bridge gegen FMM 2.11.1 / EM 10.8.0):
> ```bash
> export JAVA_HOME=/usr/lib/jvm/java-25-openjdk    # PFLICHT, sonst "Ungültige Klassendatei"
> # FMM/EM liegen NICHT im magmaguy-Maven-Repo → vom Server holen:
> scp 'amp@mc.crazypandas.de:.ampdata/instances/TestServer01/Minecraft/plugins/[PP] Free Minecraft Models (MODRINTH).jar' /tmp/fmm.jar
> scp 'amp@mc.crazypandas.de:.ampdata/instances/TestServer01/Minecraft/plugins/[PP] EliteMobs (MODRINTH).jar' /tmp/em.jar
> mvn install:install-file -Dfile=/tmp/fmm.jar -DgroupId=com.magmaguy -DartifactId=FreeMinecraftModels -Dversion=2.11.1 -Dpackaging=jar
> mvn install:install-file -Dfile=/tmp/em.jar  -DgroupId=com.magmaguy -DartifactId=EliteMobs           -Dversion=10.8.0 -Dpackaging=jar
> bash verify-both-apis.sh
> ```
> ⚠️ Die Plugin-JARs heissen auf dem Server **`[PP] …`** (PluginPortal benennt um) — nie über den
> Dateinamen auf ein Plugin schliessen, immer `unzip -p <jar> plugin.yml` lesen.
> `mvn` liegt evtl. nicht im PATH; IntelliJ bündelt eins unter
> `/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn` (Ordnername je nach Version
> `maven` **oder** `maven-plugin` — `verify-both-apis.sh` probiert beide).

**Danach / unabhängig:**
- **Waffen-Offset (KEIN Bridge-Feature):** legacy pre-1.21.4 `custom_model_data`-Item-Format
  re-exportieren ins 1.21.4+-Format (`assets/<namespace>/items/*.json`) — RPM-Backend-Warnung.
- **Follow-up (kein Blocker):** 4 deprecated Aufrufe ablösen — `getDescription`,
  `Damageable.getMaxHealth`, `InventoryView.getTitle`, `Nameable.getCustomName`. Bei
  `getCustomName` Vorsicht: hängt an der EM-Namenslogik (EVOKER-Boss-Fall).

**Erledigt 2026-07-10 (Deploy + Live-Verify):**
- ~~JAR (`…-20260710-1454.jar`) auf TestServer01 deployt~~ ✓ (SHA-verifiziert)
- ~~Server-Config auf sauberes neues Format gebracht~~ ✓ (tote `elite-items`/`maintenance` raus, `phase73` rein; alte als `config.yml.bak-20260710` gesichert)
- ~~Bridge-Boot geprüft~~ ✓ (alle Subsysteme registriert, FMM/Floodgate found, **0 Exceptions**)
- ~~**BossBar/Nametag Live-Verify MIT aktiver Bridge**~~ ✓ **Fabi bestätigt in-game: Combat-BossBar + HP-Nametag auf Bedrock „sah alles gut aus".** Rest-Scope (7.1a/7.1b) funktioniert auf FMM 2.10.1 + RPM 2.2.2 + EM 10.7.2.
- ~~Branch nach main mergen~~ ✓ (`--no-ff`, Backup-Tag `backup/pre-72b-merge-main` gesetzt, Docs nachgezogen, gepusht).
**Erledigt 2026-07-08:** ~~Rebuild gegen FMM 2.10.x API~~ ✓ (BUILD SUCCESS, 13 grün) · ~~In-Game-Animation~~ ✓ nativ · ~~BossBar/Nametag-Frage~~ ✓ geklärt (Feature-Gap) · ~~Lag-Verdacht~~ ✓ lokales Internet · ~~Grundsatzentscheidung~~ ✓ Bridge bleibt.

**⚠️ Deploy-Merker:** Nach jedem RPM-Update den **Proxy zweimal neustarten** — das gilt weiter.
**Ab RPM 2.3.1 aber NUR noch `plugins/ResourcePackManager.jar` tauschen**; die
`…GeyserBridge.jar` NICHT mitkopieren (es gibt keine neue — RPM installiert die Bridge selbst
über Geysers `extensions/update/`-Queue). `loadedDefinitions=0` nach dem **ersten** der beiden
Neustarts ist **normal**. Details in `CLAUDE.md`. **Der Symlink auf dem Proxy ist seit 16.08.
entfernt** und der Wegfall live verifiziert (Aufgabe 4 oben) — nur bei einem Downgrade auf
RPM ≤ 2.3.0 muss er zurück.

---
## 4. Server / Deploy-Kontext

Die Dauerregeln (SSH, „vor jeder Remote-Aktion fragen", Neustarts macht Fabi, fremde JARs nicht
anfassen, Download-Quellen, Arbeitsteilung mit dem Server-Claude) stehen in **`../CLAUDE.md`**.
Hier nur das Bridge-Spezifische:

- Deploy-Ziel ist `TestServer01/Minecraft/plugins/FMMBedrockBridge.jar` — **nur diese JAR gehört
  Dev-Claude**, alles andere auf der Instanz nicht.
- Deploy per SCP ist ok; den Neustart macht Fabi über AMP.
- Weitere Pfade: Memory `deployment_paths.md`.

## 5. Wichtige Doku-Dateien dieses Plugins

| Datei | Inhalt |
|---|---|
| `CLAUDE.md` | Bridge-Fachliches: Architektur, FMM-Interna, bekannte Probleme |
| `CLAUDE_SESSION.md` | Session-für-Session-Historie (lang, gewachsen) |
| `README.md` | Feature-Übersicht, Klassen-Tabelle, Deployment |
| `docs/upstream-bugs/` | Report-Entwürfe für MagmaGuy/zimzaza4 |
| `../CLAUDE.md` · `../HANDOFF.md` | Workspace-weit — Rolle, Server, Stack, Einstieg |
