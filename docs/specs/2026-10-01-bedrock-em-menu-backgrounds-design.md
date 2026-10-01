# Design: EliteMobs-Menü-Hintergründe auf Bedrock (Phase 7.6)

> **Stand:** 01.10.2026 · **Status:** Entwurf, wartet auf Fabis Freigabe der schriftlichen Spec
> **Anlass:** Fabi möchte die Custom-GUIs, die Java-Spieler über das Resource Pack sehen, auch auf
> Bedrock. Vorbild ist NitroSetups, das 2024 eigene Menüs auf diese Weise nach Bedrock gebracht hat.
> **Ablauf:** Abschnitte einzeln im Chat abgestimmt; ein Machbarkeitstest ist bereits gelaufen (§ 1).

## 0. Ziel und Rahmen

**Was Fabi will:** Die Menüs von EliteMobs sollen auf Bedrock so aussehen wie auf Java — mit ihren
gezeichneten Hintergründen statt einer nackten grauen Kiste.

**Festgelegt im Gespräch:**

| Frage | Entscheidung |
|---|---|
| Welche GUIs zuerst? | **Die Menüs von EliteMobs.** Heute praktisch die einzigen Custom-GUIs im Netzwerk. |
| Welche Bedrock-Oberfläche? | **Classic** (Windows-PC, Konsole). Pocket (Touch) ist nicht im Umfang. |
| Welcher Lösungsweg? | **B: Bridge + Bedrock-Pack** (Begründung unten), mit Machbarkeitstest vorab. |
| Combat-HUD? | **Nicht im Umfang** (§ 8). |

**Befund, auf dem alles aufbaut:**

- Das zusammengeführte Java-Pack von ResourcePackManager enthält **173 Einträge** für Menü-Hintergründe,
  aber nur **16 verschiedene Menüs**: RPM wiederholt EliteMobs' Schrift je Inhalts-Paket. *(Korrigiert
  am 01.10.2026 beim ersten Generator-Lauf — vorher stand hier fälschlich „173 Menü-Hintergründe".)*
  Es gilt der erste Eintrag je Zeichen, wie bei Minecraft selbst.
- Jeder Hintergrund ist ein Schrift-Zeichen in `assets/minecraft/font/default.json`. **Alle liegen
  lückenlos in `U+F0E00`–`U+F0F0B`.** Bildgröße fast immer 256 × 256 px (zweimal 213/214 × 256),
  `height` 256, `ascent` 136 (147×), 130 (24×) oder 45 (2×).
- EliteMobs baut den Titel **für alle Spieler gleich**, sobald ResourcePackManager installiert ist
  (`DefaultConfig.useResourcePackModels()`), z. B. beim Reparatur-Menü:
  `§f` + `U+F0EF1` (−19 px) + `U+F0E01` (Hintergrund) + `U+F0EF5` (−185 px) + Leerzeichen + Name.
  Bedrock bekommt das Hintergrund-Zeichen also mitgeliefert.
- **Diese Zeichen liegen außerhalb der Basis-Ebene von Unicode.** Bedrock zeigt sie als Kästchen —
  im Spiel belegt: vor „[EM] Reparaturmenü!" stehen drei ▯▯▯ (Screenshot `rep.png`, 01.10.2026).
- ResourcePackManager erzeugt für Bedrock **weder `ui/` noch `font/`** — Bedrock sieht heute bei
  allen 16 Menüs die Standard-Kiste.

**Wie NitroSetups es gemacht hat** (Packs vom 31.08.2024; das „Addon v1.3.1" vom Februar 2026 enthält
byte-identische Packs, nur neuere Geyser-JARs): ein Pack überschreibt `ui/chest_screen.json` und
blendet pro Menü ein Bild ein, wenn der Titel ein bestimmtes Zeichen enthält —
`"visible": "(not ($atext - '<Zeichen>' = $atext))"`. Ein zweites Pack macht die Zeichen über
Glyphen-Seiten (`font/glyph_E0` … `E6`) unsichtbar. NitroSetups konnte seine Zeichen selbst wählen
und lag damit in der Basis-Ebene; EliteMobs' Zeichen liegen außerhalb — deshalb reicht ein reines
Pack hier nicht.

### Verworfene Wege

- **A — nur ein Bedrock-Pack, das EliteMobs' Zeichen direkt erkennt.** Die Kästchen im Titel blieben
  (oder der ganze Titel müsste ausgeblendet werden); ob Bedrocks Oberfläche 4-Byte-Zeichen per
  Textvergleich findet, ist offen.
- **C — Menüs als Formular umleiten wie Phase 7.3.** EliteMobs hat für Reparieren, Verschrotten,
  Shops usw. keine Formular-Variante; die Item-Slots gingen verloren.

## 1. Machbarkeitstest — gelaufen, erfolgreich

Wegwerf-Pack `FMMBridgeGuiSpike.mcpack` auf dem Proxy, Test-Kiste per Denizen:
`/ex inventory open d:generic[size=54;title=<&chr[E900]>Reparieren]`.

| Prüfpunkt | Ergebnis (Screenshot `rep_neu.png`) |
|---|---|
| Erkennung über `U+E900` | ✅ Hintergrund erscheint |
| Unsichtbarkeit über `font/glyph_E9.png` (transparent) | ✅ Titel zeigt nur „Reparieren", kein Kästchen |
| Passform mit Java-Werten (x −11, y −134 im oberen Kistenteil) | ✅ weitgehend; ggf. Feinjustierung um wenige Pixel |
| Slots bleiben bedienbar | ✅ Raster liegt über dem Bild |

**Abweichung zu Java:** Auf Bedrock zeichnet das graue Slot-Raster über dem Bild. Auf Java deckt das
Bild die Slots ab und malt eigene. → § 4.

Einhängepunkt: `chest.large_chest_panel_top_half`, per `modifications` / `insert_front`
(nicht die ganze Datei überschreiben). Der obere Kistenteil sitzt bei y = 11.

## 2. Bridge: Titel für Bedrock umschreiben

- **Wo:** `PacketInterceptor`, Paket **OPEN_SCREEN**, **nur für Bedrock-Spieler** (Erkennung über
  `ViewerManager`, wie 7.1a/7.1d).
- **Was:** Enthält der Titel ein Hintergrund-Zeichen aus `U+F0E00`–`U+F0F0B`, wird die Präfix-Folge
  aus Abstands-Zeichen (`U+F0EF1`, `U+F0EF5` …), Hintergrund-Zeichen und den Leerzeichen danach
  ersetzt durch:
  - das **allgemeine Erkennungszeichen** `U+E8FF` (nur wenn § 4 eingeschaltet ist), und
  - das **menüspezifische Erkennungszeichen** nach der Regel in § 5.
  Farbcodes und Menüname bleiben. Beispiel:
  `§f▯▯▯           [EM] Reparaturmenü!` → `§f` + `U+E8FF` + `U+E901` + `[EM] Reparaturmenü!`
- **Abstands- vs. Hintergrund-Zeichen:** Beide liegen im selben Block. Die Abstands-Zeichen sind im
  Java-Pack daran erkennbar, dass sie `ascent -32768` und eine **negative** `height` haben; heute sind
  es genau `U+F0EF1` und `U+F0EF5`. Die Bridge kennt diese kleine Menge als Konstante. **Jedes andere
  Zeichen** aus dem Block im Titel-Präfix gilt als Hintergrund.
- **Unbekannte Zeichen** (im Block, aber ohne Bild im Pack — etwa nach einem EM-Update): Die Bridge
  schreibt trotzdem nach der Regel um. Ohne passendes Bild bleibt das Erkennungszeichen unsichtbar,
  der Titel ist sauber; es fehlt nur der Hintergrund. Das gilt auch für den Fall, dass EM ein **neues
  Abstands-Zeichen** einführt: Es würde fälschlich als Hintergrund gelesen — Folge ist nur ein
  unsichtbares Zeichen ohne Bild, kein Kästchen. Der Generator meldet neue Abstands-Zeichen, dann wird
  die Konstante nachgezogen.
- **Alle übrigen Zeichen** aus der Ebene `U+F0000`–`U+FFFFD` im Präfix werden entfernt, damit Bedrock
  nie Kästchen zeigt.
- **Schalter:** `phase76.bedrock-menu-backgrounds` (Standard `true`). Aus = Titel unverändert wie heute.
- **Java:** nie betroffen.
- **Aufbau:** Die Umschreibung ist eine **reine Funktion** (Titel-Text rein, Titel-Text raus), getrennt
  von der Paket-Behandlung — wie `RerouteDecision`/`BedrockDetection`.

## 3. Aufbau des Bedrock-Packs

**Ein Pack:** `FMMBridge-EliteMobsMenus.mcpack`, feste UUID (im Repo hinterlegt), Version steigt bei
jeder Neuerzeugung, damit Clients neu laden.

| Datei | Inhalt |
|---|---|
| `manifest.json` | Resources-Modul, feste UUID, Version |
| `ui/chest_screen.json` | pro Menü ein Bild per `modifications` in **`large_chest_panel_top_half` und `small_chest_panel_top_half`**, sichtbar nur bei seinem Erkennungszeichen; dazu die Regel aus § 4 |
| `font/glyph_E8.png`, `glyph_E9.png`, `glyph_EA.png` | transparent → `U+E8xx`, `U+E9xx`, `U+EAxx` unsichtbar |
| `textures/ui/fmmbridge_em/<menü>.png` | Hintergründe 1:1 aus dem Java-Pack |

- **Versatz — am Slot-Raster ausgerichtet** *(korrigiert nach der Abnahme am 01.10.2026; vorher am Titel
  ausgerichtet: x −11, y −134, dadurch saß das Bild 3 Einheiten zu hoch)*: EliteMobs malt seine Kästchen
  dorthin, wo Javas Slots liegen (x 8, y 18). Bedrocks Raster liegt in der oberen Kistenhälfte bei
  x 7 / y 10 (groß) bzw. x 7 / y 9 (klein). Daraus: **x = −8; y = 5 − ascent (groß), 4 − ascent (klein)**
  (x **gemessen**: hergeleitet wären −12, im Spiel saßen die Kästchen damit 4 Einheiten zu weit links)
  — für `ascent` 136 also −131 bzw. −132. Ausnahmen in `overrides.json`.
- **Neben anderen Packs:** Das alte `NitroSetupsBedrockMenus.mcpack` bleibt **entfernt** (es ersetzt
  `chest_screen.json` komplett). NitroSetups' Glyphen- und Item-Pack belegen `E0`–`E6` und stören nicht.

## 4. Graue Slot-Kästchen ausblenden

- **Ziel:** In markierten Menüs blendet Bedrock die grauen Slot-Hintergründe aus, wie Java es durch das
  Bild tut. **Items, Stückzahl, Haltbarkeits-Balken, Hover-/Controller-Auswahl bleiben.**
- **Wie:** Die Slot-Vorlage der Kiste (`chest_grid_item`) bekommt per `modifications` **eine** Regel:
  grauer Hintergrund unsichtbar, wenn der Titel `U+E8FF` enthält. Das allgemeine Zeichen erspart
  16 Bedingungen pro Slot.
- **Laufzeit-Schalter:** `phase76.hide-slot-backgrounds` (Standard `true`) — steuert, ob die Bridge
  `U+E8FF` setzt. Aus = Bilder bleiben, graues Raster wieder darüber.
- **Absicherung:** zweiter Machbarkeitstest (§ 7) **vor** dem Generator.
- ✅ **Ergebnis 01.10.2026 (Spike-Pack v0.0.2, Screenshots `slot_regel.png` / `ohne slot regel.png`):**
  Mit `U+E8FF` ist das graue Raster weg und das Bild vollständig sichtbar (inkl. der gemalten Kästchen);
  Items mit Stückzahl sichtbar und an derselben Stelle wie ohne Regel; Auswahl-Hervorhebung bleibt.
  Ohne `U+E8FF` ist das Raster wie erwartet da. **Das Element heißt `cell_image`** (in
  `common.cell_image_panel`, ausgetauscht über `$background_images` von `common.container_item`); die
  Hervorhebung `cell_image_selected` bleibt unberührt. Controller-Bedienung (Konsole) noch offen → § 7.

## 5. Generator und Zuordnung

**Zuordnung als Rechenregel, keine Tabelle:**

```
Erkennungszeichen = U+E900 + (EliteMobs-Zeichen − U+F0E00)
  U+F0E00 … U+F0EFF  →  U+E900 … U+E9FF
  U+F0F00 … U+F0F0B  →  U+EA00 … U+EA0B
allgemeines Zeichen  =  U+E8FF
```

Die Rechnung gilt nur für **Hintergrund**-Zeichen; die Abstands-Zeichen (§ 2) bekommen kein
Erkennungszeichen. Die Bridge braucht nur diese Rechnung und die kleine Menge der Abstands-Zeichen —
neue EM-Menüs im selben Block funktionieren dort sofort.

**Generator:** `tools/bedrock-menus/generate.py` im Bridge-Repo.

- **Eingabe:** das zusammengeführte Java-Pack von ResourcePackManager (`ResourcePackManager_RSP.zip`)
  — damit sind die Menüs aus allen Inhalts-Paketen dabei.
- **Ausgabe** nach `target/` (nicht im Git): `chest_screen.json`, Glyphen-Seiten, Texturen, Manifest,
  verpackt als `.mcpack`. Meldet neue und entfallene Menüs gegenüber dem letzten Lauf **und neue
  Abstands-Zeichen** (negative `height`), die die Bridge noch nicht kennt.
- **Unterscheidung im Java-Pack:** Hintergrund = `height` ≥ 40; Abstand = negative `height`.
- **Rechte:** Die Texturen gehören MagmaGuy und landen **nie** im (öffentlichen) Bridge-Repo. Im Repo
  liegen nur Skript, `overrides.yml` und die feste Pack-UUID.
- **Wann:** nach jedem EliteMobs-Update und wenn Inhalts-Pakete dazukommen.

## 6. Auslieferung und Reihenfolge

- **Ziel:** das Pack in Geysers `packs/`-Ordner auf dem Proxy; danach ein Proxy-Neustart. Bedrock lädt
  beim nächsten Login (Größenordnung 1–2 MB).
- **Nicht über ResourcePackManager** — RPM baut sein Bedrock-Pack selbst und würde ein fremdes bei
  jedem Neuaufbau überschreiben oder vermischen. Ein eigenes Pack ist unabhängig.
- Das Wegwerf-Pack aus § 1 wird beim ersten echten Deploy entfernt.
- **Reihenfolge:**
  1. **Erst das Pack, dann die Bridge.** Bridge ohne Pack ⇒ `U+E8FF`/`U+E9xx` ohne transparente
     Glyphen-Seiten ⇒ Bedrock zeigt eigene Symbole. Pack ohne Bridge ist harmlos (sieht aus wie heute).
  2. **Erst TestServer01, dann Survival01** (dort mit dem gesammelten Upgrade: EliteMobs ist dort noch
     auf 10.8.1, die Bridge läuft dort noch nicht).
  3. Neue EM-Menüs: Generator neu laufen lassen und Pack neu ausliefern; bis dahin fehlt nur das Bild.
- **Rückweg:** Bridge-Schalter `phase76.bedrock-menu-backgrounds: false` (Titel wieder wie heute, mit
  Kästchen); vollständig: zusätzlich Pack aus `packs/` entfernen und Proxy neu starten.
- **Doku:** jeder Pack-Deploy mit Backup ins Änderungs-Log der SERVER-STATE, jeder Generator-Lauf mit
  Pack-Version ins HANDOFF.

## 7. Tests und Abnahme

**Automatisch:**

- **Java (Bridge):** Titel-Umschreibung — Reparatur-Titel, Titel ohne EM-Zeichen, unbekanntes Zeichen
  im Block, nur Abstands-Zeichen, Abstands-Zeichen werden nie zu Erkennungszeichen, fremdes Zeichen
  der Ebene `U+F0000`ff. wird entfernt, Farbcodes davor, Schalter aus. Dazu die Zuordnungsregel.
- **Python (Generator):** Zuordnung, Versatz aus `ascent`, Overrides, ein Lauf gegen ein Mini-Pack mit
  drei Menüs (je ein Bild in großer und kleiner Kiste, Glyphen-Seiten vorhanden).
- **Gemeinsame Testfälle** für Zuordnung und Umschreibung in einer Datei, die Java- und Python-Tests
  beide lesen — damit Bridge und Generator nachweislich gleich rechnen.
- `verify-both-apis.sh` bleibt grün (26.2 und 1.21.10).

**Zweiter Machbarkeitstest (vor dem Generator):** Denizen-Test-Kiste mit `U+E8FF` + `U+E900` und ein
paar Items: Grau weg? Items sichtbar und klickbar? Controller-Auswahl?

**Abnahme im Spiel** — Bedrock auf **PC und Konsole**, TestServer01:

| Menü | Warum |
|---|---|
| Reparieren | große Kiste, `ascent` 136 |
| ein Menü mit `ascent` 130 | anderer Versatz |
| ein Menü mit `ascent` 45 | stark abweichender Versatz |
| ein Menü mit kleiner Kiste | anderer Aufbau, bisher ungetestet |
| ein `nightbreak`-Menü | andere Herkunft, Breite 213/214 px |

Je Menü: keine Kästchen im Titel, richtiges Bild an der richtigen Stelle, Items sichtbar und klickbar,
Controller-Bedienung auf der Konsole. **Gegenprobe:** Java unverändert; mit Schalter aus sieht Bedrock
aus wie heute.

**Fertig, wenn** die fünf Menüs auf beiden Geräten passen. Die übrigen entstehen aus denselben Regeln;
Auffälligkeiten im Alltag kommen in `overrides.yml`.

## 8. Nicht im Umfang

- **Combat-HUD von EliteMobs.** EliteMobs schickt ihn seit 10.9.1 bewusst **nicht** an Bedrock
  (`!BedrockChecker.isBedrock(player)` in `ActionBarCompositor`); Bedrock bekommt eine Textzeile. Ein
  grafischer HUD müsste komplett nachgebaut werden, über die **Actionbar** und `hud_screen.json` statt
  über eine Kiste — mehrfach pro Sekunde aktualisiert, aus vielen Einzelzeichen. Eigenes, größeres
  Projekt mit eigenem Machbarkeitstest; frühestens nach dieser Phase.
- **Pocket-Oberfläche** (Touch). Kein Bedarf laut Fabi; Kistenaufbau dort anders.
- **Menüs anderer Plugins** (BattlePass, DeluxeMenus, Genesis …). Heute ohne Glyphen-Hintergründe; die
  Technik hier ließe sich später darauf übertragen.
- **NitroSetups' Glyphen- und Item-Pack** aufräumen — eigenes Aufräumthema.
