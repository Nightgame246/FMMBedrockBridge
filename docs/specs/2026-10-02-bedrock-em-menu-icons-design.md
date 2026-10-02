# Design: EliteMobs-Menü-Symbole auf Bedrock (Phase 7.7)

> **Stand:** 02.10.2026 · **Status:** Entwurf, wartet auf Fabis Freigabe der schriftlichen Spec
> **Anlass:** Befund bei der Abnahme von Phase 7.6 (01.10.2026): Die Hintergründe stimmen, aber die
> Buttons zeigen auf Bedrock Vanilla-Items (Banner, rotes X, Smaragd, Redstone) statt EliteMobs'
> eigener Symbole (Pfeile mit Kiste, Verbotsschild, Amboss, Geldbeutel, Hand mit Münzen).
> **Ablauf:** Ursache im Quellcode von EliteMobs und RPM belegt, Annahme lesend gegen das
> RPM-Mapping auf Proxy01 geprüft (02.10.), Entwurf im Chat abgestimmt.

> **Screenshots** (lokal, nicht im Repo):
> `references/screenshots/alt/2026-10-01_phase76-em-menues-bedrock/3_abnahme-pack-v0.0.2/` —
> `rep neu.png` / `rep neu java.png`, `shop.png` / `shop_java.png`.

## 0. Ziel und Rahmen

**Was Fabi will:** Die Buttons in EliteMobs-Menüs sollen auf Bedrock dieselben Symbole zeigen wie
auf Java.

**Festgelegt im Gespräch:**

| Frage | Entscheidung |
|---|---|
| Lösungsweg | **A: Bridge tauscht für Bedrock das Grund-Item auf `PAPER`**, plus Upstream-Entwurf (§ 6) |
| Abschaltbar? | **Ja, Pflicht** (Vorgabe Fabi): falls EliteMobs/RPM/Geyser es später selbst lösen |
| Server-Konfigs ändern? | **Nein** — keine Instanz-Konfig, kein Pack |

### Befund — warum Bedrock Vanilla zeigt

1. **EliteMobs** setzt die Symbole seit 1.21.4 nur noch als `item_model`-Komponente
   (`CustomModelAdder.addCustomModel` → `ItemMeta.setItemModel`) auf ein **beliebiges Grund-Item**:
   `EMERALD` (Bestätigen, Kaufen), `REDSTONE` (Verkaufen), `BARRIER` (Abbrechen),
   `GREEN_BANNER`/`RED_BANNER` (Eingabe/Ergebnis). Auf Java ist das Grund-Item damit unsichtbar.
2. **Geyser** ordnet Custom-Items im Mapping-Format v2 **je Java-Grund-Item** zu. Ein Mapping unter
   `minecraft:paper` greift nicht für ein Smaragd-Item mit demselben `item_model`.
3. **RPM** kann aus der `items/*.json` nicht ablesen, auf welchem Grund-Item ein Plugin ein Modell
   benutzt, und **rät** (`BaseItemResolver`): Dateiname → Waffen/Rüstungs-Regeln, sonst die
   Standardliste `paper, stick, name_tag, compass, <6 Schwerter>` (`FilenameHeuristic.genericFallback`).
   Einen Weg, das Grund-Item ausdrücklich anzugeben, gibt es nur für das alte Custom-Model-Data-Format.
4. **Redstone und Banner** schließt RPM zusätzlich bewusst aus (`GeyserBaseItemCompatibility`):
   Geyser leitet für Block-Items einen `block_placer` ab, den Bedrock auf diesen Grund-Items ablehnt.
   **Auch eine bessere RPM-Heuristik würde diese Symbole nie erreichen.**

**Gegenprobe:** Das goldene „?" funktioniert auf Bedrock — EliteMobs setzt genau dort das Grund-Item
fest auf `PAPER` (`BuyOrSellMenu.java:37`).

**Gegen das Live-Mapping geprüft** (`Proxy01 … Geyser-Velocity/custom_mappings/rspm_geyser_mappings.json`,
format 2, Stand 02.10. 06:05, nur gelesen): **von den 23 `elitemobs:ui/*`-Modellen sind alle
außer den beiden Kronen (also 21) und alle 6 `nightbreak:ui/*`-Modelle unter `minecraft:paper` gemappt.** Die Kronen
(`redcrown`, `yellowcrown`) liegen unter Helmen. Smaragd, Redstone, Barriere und Banner kommen als
Grund-Item für diese Modelle nicht vor.

### Verworfene Wege

- **B — EliteMobs-Menü-Konfigs auf `PAPER` stellen.** Kein Code, Java unverändert. Aber: Instanz-Konfigs
  gehören dem Server-Claude, ein Teil der Buttons ist im EM-Code fest verdrahtet, und jedes neue
  Inhalts-Paket bringt eigene Konfigs mit.
- **Bedrock-Pack mit eigenen Mappings je Grund-Item.** Hilft bei Smaragd/Barriere, scheitert an
  Redstone und Bannern (Befund 4), und kollidiert mit RPMs eigener Mapping-Datei.

## 1. Regel: `BedrockMenuIcons` (rein, testbar)

Neue Klasse `de.crazypandas.fmmbedrockbridge.bridge.BedrockMenuIcons`, Muster wie `BedrockMenuTitle`
und `RerouteDecision`: **keine** Abhängigkeit zu Bukkit oder PacketEvents, nur Strings.

```java
/** @return true, wenn das Item für Bedrock auf minecraft:paper umgestellt werden soll */
static boolean shouldRebase(String baseItemId, String itemModel,
                            List<String> prefixes, Set<String> excludes)
```

Ergebnis `true` genau dann, wenn:
- `itemModel` nicht `null` ist (Item trägt eine `item_model`-Komponente **als Patch**),
- `itemModel` mit einem der `prefixes` beginnt,
- `itemModel` nicht in `excludes` steht,
- `baseItemId` nicht schon `minecraft:paper` ist.

Vergleich ohne Groß-/Kleinschreibung (`Locale.ROOT`), weil Namespaced Keys klein sind, Konfig-Einträge
aber von Hand kommen.

## 2. Haken im `PacketInterceptor`

Neuer Zweig in `onPacketSend`, wie 7.6 nur für `isBedrock(player) == TRUE`:

| Paket | Behandlung |
|---|---|
| `WINDOW_ITEMS` | nur `windowId > 0`; jedes Item der Liste durch die Regel, Liste zurückschreiben |
| `SET_SLOT` | nur `windowId > 0` (`0` = Spielerinventar, `-1` = Cursor); ein Item |

- **`windowId == 0` (Spielerinventar) wird nie angefasst** — dort liegen echte Items des Spielers.
  EliteMobs-Menüsymbole gibt es nur im oberen Teil eines Containers. (`WINDOW_ITEMS` eines Containers
  enthält auch die Spielerinventar-Slots; die werden zwar durchlaufen, matchen aber nicht, weil kein
  echtes Item ein `elitemobs:ui/`-Modell trägt.)
- **Umbau eines Items:** neues PacketEvents-`ItemStack` mit Typ `ItemTypes.PAPER`, gleicher Anzahl
  und **den Komponenten-Patches des Originals** (Name, Lore, `item_model`, CMD usw.). Ob der
  Builder die `PatchableComponentMap` direkt übernimmt oder die Patches einzeln kopiert werden müssen,
  klärt der Plan per `javap` gegen packetevents-api 2.14.0.
- **Nur wenn sich etwas geändert hat**, `event.markForReEncode(true)` — unveränderte Pakete werden
  nicht neu kodiert (wie 7.6).
- **Fehler:** `try/catch (Throwable)` um den ganzen Zweig, Ausgabe nur über `FMMBedrockBridge.debugLog`
  mit Präfix `[PHASE77]`; das Paket geht dann unverändert raus.
- Debug-Zeile bei Umbau: `[PHASE77] rebased <n> menu icon(s) for Bedrock in window <id>`.

## 3. Schalter

`config.yml`:

```yaml
phase77:
  # EliteMobs-Menü-Symbole für Bedrock auf Papier umsetzen, damit RPMs Geyser-Mapping greift.
  # Auf false, sobald EliteMobs/RPM/Geyser das selbst lösen — dann ist alles wie vorher.
  bedrock-menu-icons: true
  item-model-prefixes: ["elitemobs:ui/", "nightbreak:ui/"]
  # Liegen bei RPM unter Helmen statt Papier — auf Papier würden sie kaputtgehen.
  exclude-models: ["elitemobs:ui/redcrown", "elitemobs:ui/yellowcrown"]
```

Der Schalter wird wie die übrigen Phasen-Schalter über einen statischen Getter in `FMMBedrockBridge`
gelesen (`getConfig()` bei jedem Zugriff). Die beiden Listen werden **beim Start einmal** gelesen und
gehalten, weil die Regel für jedes Inventar-Paket läuft. Einen Reload-Befehl gibt es in der Bridge
nicht — Änderungen an der Konfig brauchen einen Neustart (macht Fabi).

## 4. Risiko: Klicks auf umgebaute Buttons

Geyser merkt sich den Inhalt des Containers so, wie er ihn bekommen hat — also als Papier — und schickt
bei einem Klick den vorhergesagten Zustand mit. Der Server stellt eine Abweichung fest und sendet den
Slot neu (der dann wieder durch die Regel läuft). EliteMobs bricht Klicks in seinen Menüs ohnehin ab und
wertet nur den Slot aus. **Erwartung:** kein sichtbarer Effekt, höchstens kurzes Flackern.

**Das ist der erste Punkt des In-game-Tests (§ 5).** Klemmt ein Button oder löst er nichts aus:
Schalter auf `false`, untersuchen.

## 5. Tests und Abnahme

**Unit-Tests (TDD), `BedrockMenuIconsTest`:**
- EM-Symbol auf Smaragd → `true`; auf Banner → `true`; `nightbreak:ui/redcross` auf Barriere → `true`
- schon Papier → `false`
- ausgeschlossene Krone → `false`
- kein `item_model` (`null`) → `false`
- fremder Namespace (`elitemobs:equipment/…`, `freeminecraftmodels:…`) → `false`
- Groß-/Kleinschreibung in Konfig-Einträgen egal

**Build:** `mvn test` grün, Gesamtzahl der Tests im Bridge-HANDOFF nachziehen.

**In-game auf TestServer01, Bedrock am PC** (Konsole wie bei 7.6 nicht separat):

| Menü | Erwartet auf Bedrock |
|---|---|
| Reparieren | Verbotsschild statt X, Pfeil-Kisten statt Banner, Amboss statt Smaragd |
| Kaufen oder Verkaufen | Geldbeutel statt Smaragd, Hand mit Münzen statt Redstone |
| Verschrotter, Verkaufen | Bestätigen/Abbrechen als EM-Symbol |
| Verzauberer / Elite-Schriftrolle | Eingabe-/Ergebnis-Kisten statt Banner |

Je Menü: **Buttons bedienen** (Kaufen, Reparieren auslösen, Abbrechen) — funktioniert alles wie vorher?
Java-Gegenprobe: unverändert. Schalter-Gegenprobe: `bedrock-menu-icons: false` → wieder Vanilla.

## 6. Upstream-Entwurf

`docs/upstream-bugs/` — an EliteMobs (MagmaGuy):

- Bei `useResourcePackModels()` das Grund-Item der Menü-Symbole auf `PAPER` setzen, wie es
  `BuyOrSellMenu` für das Info-Symbol schon tut — betrifft `CustomModelAdder.addCustomModel` bzw. die
  Defaults in `*MenuConfig` (`EMERALD`, `REDSTONE`, `BARRIER`, `*_BANNER`).
- Begründung: RPMs Grund-Item-Heuristik (Standardliste ohne diese Items) und die Geyser-Grenze bei
  Redstone/Bannern, die RPM selbst in `GeyserBaseItemCompatibility` dokumentiert.
- Alternativ in RPM: eine Möglichkeit für Plugins, das Grund-Item eines `item_model` zu deklarieren.

Wird das upstream umgesetzt: `bedrock-menu-icons: false`, Phase 7.7 später ausbauen.

## 7. Auslieferung

Nur die Bridge-JAR — kein Pack, keine Konfig auf dem Server. Deploy per SCP nach TestServer01
(Backup der alten JAR, Eintrag im Änderungs-Log von `SERVER-STATE.md`), Neustart macht Fabi.
Survival01 erst nach der Abnahme, zusammen mit dem gesammelten Upgrade.

## 8. Nicht im Umfang

- Die beiden Kronen (`redcrown`, `yellowcrown`) — Helm-Mapping, eigenes Thema falls je sichtbar falsch
- Symbole außerhalb von Containern (Spielerinventar, Hand) — EliteMobs nutzt `ui/`-Modelle dort nicht
- Auffälligkeit im „Eigener Shop" (`shop2.png`): die Waren erscheinen auf Bedrock als Leder, Barren,
  Stöcke. Ob das falsch ist, ist offen — es gibt keinen Java-Screenshot desselben Menüs. Eigenes Thema,
  bei Bedarf separat untersuchen
- Pocket-UI (Touch)
