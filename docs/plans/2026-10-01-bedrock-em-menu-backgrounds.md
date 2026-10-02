# EliteMobs-Menü-Hintergründe auf Bedrock (Phase 7.6) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bedrock-Spieler (Classic-Oberfläche) sehen die 173 gezeichneten EliteMobs-Menü-Hintergründe statt einer grauen Kiste — ohne Kästchen im Titel.

**Architecture:** Die Bridge schreibt den Kistentitel im OPEN_WINDOW-Paket nur für Bedrock um: EliteMobs' Zeichen außerhalb der Basis-Ebene (`U+F0E00`–`U+F0F0B`) werden durch unsichtbare Erkennungszeichen (`U+E8FF` allgemein, `U+E900 + n` je Menü) ersetzt. Ein generiertes Bedrock-Pack (Python, nur Standardbibliothek) blendet per `ui/chest_screen.json`-Modifikation pro Erkennungszeichen das Bild ein, blendet bei `U+E8FF` die grauen Slot-Hintergründe aus und macht die Zeichen über transparente Glyphen-Seiten unsichtbar.

**Tech Stack:** Java 25 / Paper-API 26.2 + 1.21.10 / PacketEvents 2.14.0 / JUnit 5 / Gson (provided) · Python 3 Standardbibliothek (`json`, `zipfile`, `zlib`, `struct`, `unittest`) · Bedrock JSON-UI.

**Spec:** `docs/specs/2026-10-01-bedrock-em-menu-backgrounds-design.md`

## Global Constraints

- Nur Bedrock-Spieler, Erkennung über `ViewerManager.isBedrockPlayer` (wie 7.1a/7.1d). Java nie betroffen.
- EliteMobs-Block: `U+F0E00`–`U+F0F0B`. Abstands-Zeichen heute genau `U+F0EF1`, `U+F0EF5` (im Java-Pack: negative `height`, `ascent -32768`).
- Erkennungszeichen = `U+E900 + (EM-Zeichen − U+F0E00)` → `U+E900`–`U+E9FF`, `U+EA00`–`U+EA0B`. Allgemeines Zeichen `U+E8FF`.
- Hintergründe im Java-Pack: `height` ≥ 40 (alle 256). Abstände: `height` < 0.
- Versatz: x = −11; y = 13 − ascent − top, mit top = 11 (große Kiste), 12 (kleine Kiste). Ausnahmen per Overrides-Datei.
- Schalter: `phase76.bedrock-menu-backgrounds` (Standard `true`), `phase76.hide-slot-backgrounds` (Standard `true`), beide pro Paket gelesen.
- Pack: eine feste UUID für Header und Modul, Version steigt pro Generator-Lauf; Name `FMMBridge-EliteMobsMenus.mcpack`.
- Texturen von MagmaGuy landen **nie** im Git (Bridge-Repo ist öffentlich). Generator-Ausgabe nach `target/bedrock-menus/`.
- Generator nur Python-Standardbibliothek (kein pytest, kein PyYAML) — **Abweichung von der Spec:** Overrides als `overrides.json` statt `overrides.yml`, weil PyYAML nicht auf jedem PC vorhanden ist.
- Auslieferung: erst Pack auf den Proxy, dann Bridge. Erst TestServer01.
- `bash verify-both-apis.sh` muss grün bleiben (26.2 und 1.21.10).
- Commit-Trailer: `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Review Focus

1. **Titel mit Hex-Farben (`§x§r§r§g§g§b§b`)** — ein Rundweg über einen Legacy-Serializer ohne Hex-Unterstützung würde die Farbe verlieren. Erwartung: Titel ohne EM-Zeichen bleiben byte-gleich, und das Paket wird dann gar nicht neu kodiert. → Test in Task 2 (`hexColourTitleWithoutEmCharsIsUntouched`) und Serializer-Wahl in Task 3.
2. **Fremde Plugin-Titel mit Zeichen der Ebene `U+F0000`ff. außerhalb des EM-Blocks** — Bedrock würde Kästchen zeigen. Erwartung: Zeichen entfernt, kein Erkennungszeichen, Name bleibt. → Task 2, Fall `foreignPlanePuaRemoved`.
3. **Leerzeichen im Menünamen** — nur die Leerzeichen direkt hinter dem EM-Präfix dürfen weg. → Task 2, Fall `spacesInsideNameKept`.
4. **Generator-Eingabe ohne Menüs oder ohne `default.json`** (falsches ZIP erwischt) — Erwartung: Abbruch mit klarer Meldung statt leerem Pack, das beim Deploy das Test-Pack verdrängt. → Task 4, Test `test_refuses_pack_without_menus`.
5. **Mehrere Hintergrund-Zeichen in einem Titel** — Erwartung: das erste zählt, die übrigen werden entfernt, nie zwei Erkennungszeichen. → Task 2, Fall `onlyFirstBackgroundCounts`.

---

## Dateistruktur

| Datei | Verantwortung |
|---|---|
| `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuTitle.java` | reine Funktion: Zuordnung EM-Zeichen → Erkennungszeichen, Titel-Umschreibung |
| `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuTitleTest.java` | JUnit, liest die gemeinsamen Fälle |
| `src/test/resources/bedrock-menus/cases.json` | gemeinsame Testfälle für Java **und** Python |
| `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java` | Modify: OPEN_WINDOW-Zweig für Bedrock |
| `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java` | Modify: zwei Config-Accessoren, Boot-Zeile |
| `src/main/resources/config.yml` | Modify: Block `phase76` |
| `tools/bedrock-menus/generate.py` | Generator (Lesen des Java-Packs, Pack bauen) |
| `tools/bedrock-menus/test_generate.py` | `unittest`, liest `cases.json` mit |
| `tools/bedrock-menus/pack-meta.json` | feste UUIDs + Versionszähler (im Git) |
| `tools/bedrock-menus/overrides.json` | Versatz-Ausnahmen je Menü (im Git, anfangs leer) |
| `README.md`, `HANDOFF.md`, `CLAUDE_SESSION.md` | Doku |

---

### Task 1: Zweiter Machbarkeitstest — graue Slots ausblenden (Wegwerf)

Klärt Spec § 4 im Spiel, **bevor** der Generator die Regel fest einbaut. Kein Code bleibt.

**Files:**
- Modify (nur im Scratchpad, nicht im Repo): Wegwerf-Pack `FMMBridgeGuiSpike.mcpack`

**Interfaces:**
- Produces: Entscheidung „Slot-Regel funktioniert so" oder Fehlerbild. Task 4 übernimmt die JSON-Blöcke unten wörtlich, wenn der Test besteht.

- [ ] **Step 1: Spike-`chest_screen.json` um die Slot-Regel erweitern**

Zum vorhandenen Inhalt (Bild in `large_chest_panel_top_half`) kommen zwei Top-Level-Einträge. `U+E8FF` muss als echtes Zeichen in der Datei stehen (Python schreibt `''` mit `ensure_ascii=False`):

```json
"chest_grid_item@common.container_item": {
  "$item_collection_name": "container_items",
  "$background_images": "chest.fmmbridge_cell_images"
},
"fmmbridge_cell_images": {
  "type": "panel",
  "controls": [
    {
      "fmmbridge_plain_bg": {
        "type": "panel",
        "$atext": "$container_title",
        "visible": "($atext - '' = $atext)",
        "controls": [
          {
            "cell_image@common.cell_image": {
              "$cell_selected_binding_name|default": "#is_selected_slot",
              "visible": true,
              "bindings": [
                {
                  "binding_name": "(not $cell_selected_binding_name)",
                  "binding_name_override": "#visible",
                  "binding_type": "collection",
                  "binding_collection_name": "$item_collection_name"
                }
              ]
            }
          }
        ]
      }
    },
    {
      "cell_image_selected@common.cell_image_selected": {
        "$cell_selected_binding_name|default": "#is_selected_slot",
        "visible": false,
        "bindings": [
          {
            "binding_name": "$cell_selected_binding_name",
            "binding_name_override": "#visible",
            "binding_type": "collection",
            "binding_collection_name": "$item_collection_name"
          }
        ]
      }
    }
  ]
}
```

Dazu `font/glyph_E8.png` (transparent, 256 × 256) ins Pack, Version in `manifest.json` auf `[0,0,2]` (Header und Modul).

- [ ] **Step 2: Pack auf den Proxy, Backup und SERVER-STATE-Eintrag**

Altes Spike-Pack sichern (`~/backups/gui-spike-20261001/FMMBridgeGuiSpike-v1.mcpack`), neues nach `Geyser-Velocity/packs/`. Eintrag ins Änderungs-Log, alle Kopien abgleichen (md5 vorher prüfen). Fabi startet den Proxy neu.

- [ ] **Step 3: Test im Spiel (Fabi, Bedrock PC und Konsole)**

Test-Kiste mit beiden Zeichen und Items, aus dem Bedrock-Chat:

```
/ex inventory open d:generic[size=54;title=<&chr[E8FF]><&chr[E900]>Reparieren;contents=diamond_sword|emerald|stone[quantity=12]]
```

Gegenprobe ohne `U+E8FF` (Grau muss da sein):

```
/ex inventory open d:generic[size=54;title=<&chr[E900]>Reparieren;contents=diamond_sword|emerald|stone[quantity=12]]
```

Erwartet: im ersten Fall kein graues Raster, Brett voll sichtbar, Items mit Stückzahl sichtbar und verschiebbar, Controller-Auswahl hebt Slots hervor; im zweiten Fall graues Raster wie im ersten Spike.

- [ ] **Step 4: Ergebnis festhalten**

Ergebnis (bestanden / Fehlerbild mit Screenshot) als kurzer Absatz in Spec § 4 nachtragen, committen:

```bash
git add docs/specs/2026-10-01-bedrock-em-menu-backgrounds-design.md
git commit -m "docs(spec): 7.6 -- Ergebnis Machbarkeitstest graue Slots"
```

Besteht der Test **nicht**: Plan anhalten, Fabi fragen. Fallback ohne Neuplanung: `phase76.hide-slot-backgrounds` Standard `false`, Slot-Regel bleibt aus dem Pack draußen.

---

### Task 2: Gemeinsame Testfälle + `BedrockMenuTitle` (reine Funktion)

**Files:**
- Create: `src/test/resources/bedrock-menus/cases.json`
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuTitle.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuTitleTest.java`

**Interfaces:**
- Produces:
  - `BedrockMenuTitle.EM_BLOCK_START = 0xF0E00`, `EM_BLOCK_END = 0xF0F0B`, `MARKER_BASE = 0xE900`, `GENERIC_MARKER = 0xE8FF`, `SPACING_CODEPOINTS = Set.of(0xF0EF1, 0xF0EF5)`
  - `static int markerFor(int emCodepoint)` — wirft `IllegalArgumentException` außerhalb des Blocks oder für Abstands-Zeichen
  - `static String rewrite(String legacyTitle, boolean hideSlots)` — gibt **dieselbe Instanz** zurück, wenn kein Zeichen der Ebenen 15/16 vorkommt
- Consumes: nichts.

- [ ] **Step 1: Gemeinsame Fälle schreiben**

`src/test/resources/bedrock-menus/cases.json` (JSON-Escapes für Ersatzpaare; `§` = `§`):

```json
{
  "markers": [
    { "em": "F0E00", "marker": "E900" },
    { "em": "F0E01", "marker": "E901" },
    { "em": "F0EFF", "marker": "E9FF" },
    { "em": "F0F00", "marker": "EA00" },
    { "em": "F0F0B", "marker": "EA0B" }
  ],
  "spacing": ["F0EF1", "F0EF5"],
  "titles": [
    { "name": "repairWithSlotsHidden", "hideSlots": true,
      "input": "§f󰻱󰸁󰻵           [EM] Reparaturmenü!",
      "expected": "§f[EM] Reparaturmenü!" },
    { "name": "repairWithSlotsShown", "hideSlots": false,
      "input": "§f󰻱󰸁󰻵           [EM] Reparaturmenü!",
      "expected": "§f[EM] Reparaturmenü!" },
    { "name": "plainTitleUntouched", "hideSlots": true,
      "input": "§6Shop", "expected": "§6Shop" },
    { "name": "hexColourTitleWithoutEmCharsIsUntouched", "hideSlots": true,
      "input": "§x§f§f§8§8§0§0Gilde", "expected": "§x§f§f§8§8§0§0Gilde" },
    { "name": "unknownBackgroundInBlockStillMapped", "hideSlots": true,
      "input": "§f󰺀  Neu", "expected": "§fNeu" },
    { "name": "lastBlockCharacter", "hideSlots": false,
      "input": "󰼋Arena", "expected": "Arena" },
    { "name": "onlySpacingRemovedNoMarker", "hideSlots": true,
      "input": "§f󰻱   Name", "expected": "§fName" },
    { "name": "foreignPlanePuaRemoved", "hideSlots": true,
      "input": "󱈴 Fremd", "expected": "Fremd" },
    { "name": "spacesInsideNameKept", "hideSlots": false,
      "input": "§f󰸁   §c[EM] Mein  Menü", "expected": "§f§c[EM] Mein  Menü" },
    { "name": "onlyFirstBackgroundCounts", "hideSlots": false,
      "input": "󰸁󰸂 Zwei", "expected": "Zwei" }
  ]
}
```

Prüfung der Escapes: `󰸁` = `U+F0E01`, `󰻱` = `U+F0EF1`, `󰻵` = `U+F0EF5`, `󰺀` = `U+F0E80`, `󰼋` = `U+F0F0B`, `󱈴` = `U+F1234`.

- [ ] **Step 2: Failing test schreiben**

`src/test/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuTitleTest.java`:

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 7.6. The cases live in a JSON file that the Python generator's tests read too, so the
 * bridge and the pack provably agree on which marker belongs to which EliteMobs menu.
 */
class BedrockMenuTitleTest {

    private static JsonObject cases() {
        var in = BedrockMenuTitleTest.class.getResourceAsStream("/bedrock-menus/cases.json");
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @TestFactory
    List<DynamicTest> markerMapping() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement e : cases().getAsJsonArray("markers")) {
            int em = Integer.parseInt(e.getAsJsonObject().get("em").getAsString(), 16);
            int marker = Integer.parseInt(e.getAsJsonObject().get("marker").getAsString(), 16);
            tests.add(DynamicTest.dynamicTest(Integer.toHexString(em),
                    () -> assertEquals(marker, BedrockMenuTitle.markerFor(em))));
        }
        return tests;
    }

    @Test
    void spacingCharactersNeverGetAMarker() {
        JsonArray spacing = cases().getAsJsonArray("spacing");
        for (JsonElement e : spacing) {
            int cp = Integer.parseInt(e.getAsString(), 16);
            assertThrows(IllegalArgumentException.class, () -> BedrockMenuTitle.markerFor(cp));
        }
    }

    @Test
    void outsideTheBlockHasNoMarker() {
        assertThrows(IllegalArgumentException.class, () -> BedrockMenuTitle.markerFor(0xF0DFF));
        assertThrows(IllegalArgumentException.class, () -> BedrockMenuTitle.markerFor(0xF0F0C));
    }

    @TestFactory
    List<DynamicTest> titleRewrites() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement e : cases().getAsJsonArray("titles")) {
            JsonObject c = e.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(c.get("name").getAsString(), () -> assertEquals(
                    c.get("expected").getAsString(),
                    BedrockMenuTitle.rewrite(c.get("input").getAsString(), c.get("hideSlots").getAsBoolean()))));
        }
        return tests;
    }

    @Test
    void untouchedTitleIsTheSameInstance() {
        // The interceptor re-encodes only when the title changed; identity makes that check exact.
        String title = "§6Shop";
        assertSame(title, BedrockMenuTitle.rewrite(title, true));
    }
}
```

- [ ] **Step 3: Test laufen lassen — muss scheitern**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk /usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -q test -Dtest=BedrockMenuTitleTest`
Expected: Kompilierfehler „Symbol nicht gefunden: BedrockMenuTitle".

- [ ] **Step 4: Implementierung**

`src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuTitle.java`:

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import java.util.Set;

/**
 * Phase 7.6 — rewrites EliteMobs' menu titles for Bedrock.
 *
 * <p>EliteMobs draws its menu backgrounds as font glyphs in the chest title, at code points
 * {@code U+F0E00}–{@code U+F0F0B}, i.e. outside the Basic Multilingual Plane. Bedrock shows those
 * as boxes. This replaces them by markers inside the BMP that the generated Bedrock pack
 * recognises ({@code ui/chest_screen.json}) and renders invisible (transparent glyph pages):
 * {@code U+E8FF} = "slot backgrounds off", {@code U+E900 + n} = "background n".
 *
 * <p>Pure on purpose — no Bukkit, no PacketEvents — so the rule is tested against the same cases
 * as the Python generator ({@code src/test/resources/bedrock-menus/cases.json}).
 */
public final class BedrockMenuTitle {

    public static final int EM_BLOCK_START = 0xF0E00;
    public static final int EM_BLOCK_END = 0xF0F0B;
    public static final int MARKER_BASE = 0xE900;
    public static final int GENERIC_MARKER = 0xE8FF;
    /** Negative-advance glyphs in EliteMobs' font: ascent -32768, negative height. */
    public static final Set<Integer> SPACING_CODEPOINTS = Set.of(0xF0EF1, 0xF0EF5);

    private BedrockMenuTitle() {}

    public static int markerFor(int emCodepoint) {
        if (emCodepoint < EM_BLOCK_START || emCodepoint > EM_BLOCK_END
                || SPACING_CODEPOINTS.contains(emCodepoint)) {
            throw new IllegalArgumentException("no menu background: U+" + Integer.toHexString(emCodepoint));
        }
        return MARKER_BASE + (emCodepoint - EM_BLOCK_START);
    }

    /**
     * @param legacyTitle title in legacy §-format
     * @param hideSlots   also emit {@link #GENERIC_MARKER} (Bedrock hides the grey slot cells)
     * @return the rewritten title, or {@code legacyTitle} itself when it holds no plane-15/16 character
     */
    public static String rewrite(String legacyTitle, boolean hideSlots) {
        if (legacyTitle == null || !containsSupplementaryPua(legacyTitle)) return legacyTitle;

        StringBuilder out = new StringBuilder(legacyTitle.length());
        boolean markerWritten = false;
        boolean skipSpaces = false;
        int i = 0;
        while (i < legacyTitle.length()) {
            int cp = legacyTitle.codePointAt(i);
            i += Character.charCount(cp);

            if (isSupplementaryPua(cp)) {
                if (!markerWritten && isBackground(cp)) {
                    if (hideSlots) out.appendCodePoint(GENERIC_MARKER);
                    out.appendCodePoint(markerFor(cp));
                    markerWritten = true;
                }
                skipSpaces = true;   // spaces right after the prefix only pushed the name right on Java
                continue;
            }
            if (skipSpaces && cp == ' ') continue;
            skipSpaces = false;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static boolean isBackground(int cp) {
        return cp >= EM_BLOCK_START && cp <= EM_BLOCK_END && !SPACING_CODEPOINTS.contains(cp);
    }

    private static boolean isSupplementaryPua(int cp) {
        return (cp >= 0xF0000 && cp <= 0xFFFFD) || (cp >= 0x100000 && cp <= 0x10FFFD);
    }

    private static boolean containsSupplementaryPua(String s) {
        return s.codePoints().anyMatch(BedrockMenuTitle::isSupplementaryPua);
    }
}
```

- [ ] **Step 5: Tests laufen lassen — müssen grün sein**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk /usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -q test -Dtest=BedrockMenuTitleTest`
Expected: alle Fälle PASS (5 Zuordnungen, 10 Titel, 3 Einzeltests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuTitle.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuTitleTest.java \
        src/test/resources/bedrock-menus/cases.json
git commit -m "feat(phase76): EliteMobs-Menuetitel fuer Bedrock umschreiben (reine Funktion)"
```

---

### Task 3: Bridge-Verdrahtung — OPEN_WINDOW, Schalter, Config

**Files:**
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java` (Listener in `register()`, neue Methode)
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java` (Accessoren neben `isPhase74*`, Boot-Zeile nach 7.4)
- Modify: `src/main/resources/config.yml` (Block nach `phase74`)

**Interfaces:**
- Consumes: `BedrockMenuTitle.rewrite(String, boolean)`.
- Produces: `FMMBedrockBridge.isPhase76MenuBackgroundsEnabled()`, `FMMBedrockBridge.isPhase76HideSlotBackgrounds()`.

- [ ] **Step 1: Config-Accessoren**

In `FMMBedrockBridge.java` direkt vor `isPhase74Enabled()`:

```java
    public static boolean isPhase76MenuBackgroundsEnabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase76.bedrock-menu-backgrounds", true);
    }

    public static boolean isPhase76HideSlotBackgrounds() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase76.hide-slot-backgrounds", true);
    }
```

Boot-Zeile im `onEnable()` nach dem Phase-7.4-Block:

```java
        log.info("Phase 7.6: Bedrock menu backgrounds " + (isPhase76MenuBackgroundsEnabled() ? "on" : "off")
                + " (hide-slot-backgrounds=" + isPhase76HideSlotBackgrounds()
                + ", needs the FMMBridge-EliteMobsMenus pack in Geyser's packs folder)");
```

- [ ] **Step 2: OPEN_WINDOW-Zweig im PacketInterceptor**

Import ergänzen:

```java
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
```

Feld neben `nameFix`:

```java
    // Phase 7.6 — keeps hex colours (§x§r§r§g§g§b§b) intact across the legacy round trip.
    private static final LegacyComponentSerializer MENU_TITLE_LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
```

Im Listener, vor dem 7.1a-Block:

```java
                // Phase 7.6 — EliteMobs menu backgrounds for Bedrock
                if (event.getPacketType() == PacketType.Play.Server.OPEN_WINDOW
                        && Boolean.TRUE.equals(isBedrock(playerObj))) {
                    rewriteMenuTitle(event);
                }
```

Neue Methode:

```java
    /**
     * Phase 7.6 — replaces EliteMobs' background glyphs in the chest title by the markers the
     * FMMBridge-EliteMobsMenus pack recognises. Untouched titles are not re-encoded at all.
     */
    private void rewriteMenuTitle(PacketSendEvent event) {
        if (!FMMBedrockBridge.isPhase76MenuBackgroundsEnabled()) return;
        try {
            WrapperPlayServerOpenWindow wrapper = new WrapperPlayServerOpenWindow(event);
            Component title = wrapper.getTitle();
            if (title == null) return;
            String legacy = MENU_TITLE_LEGACY.serialize(title);
            String rewritten = BedrockMenuTitle.rewrite(legacy, FMMBedrockBridge.isPhase76HideSlotBackgrounds());
            if (rewritten == legacy) return;
            wrapper.setTitle(MENU_TITLE_LEGACY.deserialize(rewritten));
            event.markForReEncode(true);
            FMMBedrockBridge.debugLog("[PHASE76] menu title rewritten for Bedrock: '" + rewritten + "'");
        } catch (Throwable t) {
            FMMBedrockBridge.debugLog("[PHASE76] title rewrite failed: " + t);
        }
    }
```

Klassen-Javadoc oben ergänzen: ` *  - Phase 7.6: rewrites EliteMobs' menu titles for Bedrock (background markers).`

- [ ] **Step 3: config.yml**

Nach dem `phase74`-Block:

```yaml
# Phase 7.6 — EliteMobs-Menü-Hintergründe auf Bedrock
#
# EliteMobs zeichnet seine Menü-Hintergründe über Schrift-Zeichen im Kistentitel. Bedrock zeigt
# diese Zeichen als Kästchen. Die Bridge ersetzt sie für Bedrock durch unsichtbare
# Erkennungszeichen; das Pack "FMMBridge-EliteMobsMenus" im Geyser-packs-Ordner blendet daraufhin
# den Hintergrund ein. Java ist nie betroffen.
#
# REIHENFOLGE: erst das Pack auf den Proxy, dann diesen Schalter an. Ohne Pack zeigt Bedrock die
# Erkennungszeichen als Symbole.
phase76:
  bedrock-menu-backgrounds: true
  # Graue Slot-Kästchen in solchen Menüs ausblenden, wie Java es durch das Bild tut. Items und
  # Auswahl bleiben sichtbar. Auf false, falls ein Menü ohne Kästchen schwer bedienbar ist.
  hide-slot-backgrounds: true
```

- [ ] **Step 4: Beide API-Generationen bauen**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk bash verify-both-apis.sh`
Expected: `BEIDE API-Generationen grün.`, Testzahl = vorher + 18.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java \
        src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java src/main/resources/config.yml
git commit -m "feat(phase76): Kistentitel im OPEN_WINDOW fuer Bedrock umschreiben, Schalter phase76"
```

---

### Task 4: Generator für das Bedrock-Pack

**Files:**
- Create: `tools/bedrock-menus/generate.py`
- Create: `tools/bedrock-menus/test_generate.py`
- Create: `tools/bedrock-menus/pack-meta.json`
- Create: `tools/bedrock-menus/overrides.json`

**Interfaces:**
- Consumes: `src/test/resources/bedrock-menus/cases.json` (Task 2), Slot-JSON aus Task 1.
- Produces: `python3 tools/bedrock-menus/generate.py <ResourcePackManager_RSP.zip>` → `target/bedrock-menus/FMMBridge-EliteMobsMenus.mcpack`, `target/bedrock-menus/last-run.json`; erhöht `version` in `pack-meta.json`.

- [ ] **Step 1: pack-meta.json und overrides.json anlegen**

UUIDs **einmal** erzeugen (`python3 -c "import uuid;print(uuid.uuid4());print(uuid.uuid4())"`) und eintragen:

```json
{
  "header_uuid": "<erste erzeugte UUID>",
  "module_uuid": "<zweite erzeugte UUID>",
  "version": 0
}
```

`overrides.json` (Schlüssel = EM-Code-Point hex, Werte überschreiben x/y je Kistengröße):

```json
{
  "_doc": "Versatz-Ausnahmen je Menue, z. B. \"F0E01\": {\"large\": {\"x\": -11, \"y\": -134}, \"small\": {\"x\": -11, \"y\": -135}}"
}
```

- [ ] **Step 2: Failing tests schreiben**

`tools/bedrock-menus/test_generate.py`:

```python
"""Tests fuer generate.py — nur Standardbibliothek. Lauf: python3 -m unittest discover -s tools/bedrock-menus"""
import io, json, os, struct, sys, tempfile, unittest, zipfile, zlib

sys.path.insert(0, os.path.dirname(__file__))
import generate as g

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CASES = json.load(open(os.path.join(ROOT, "src/test/resources/bedrock-menus/cases.json"), encoding="utf-8"))


def png(w, h):
    raw = b"".join(b"\x00" + b"\x00\x00\x00\x00" * w for _ in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def java_pack(providers, textures):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("assets/minecraft/font/default.json", json.dumps({"providers": providers}))
        for path, data in textures.items():
            z.writestr(path, data)
    buf.seek(0)
    return buf


MINI = [
    {"type": "bitmap", "file": "elitemobs:gui/repairmenu.png", "ascent": 136, "height": 256, "chars": [chr(0xF0E01)]},
    {"type": "bitmap", "file": "elitemobs:gui/shop.png", "ascent": 130, "height": 256, "chars": [chr(0xF0E02)]},
    {"type": "bitmap", "file": "nightbreak:gui/setup.png", "ascent": 45, "height": 256, "chars": [chr(0xF0F0B)]},
    {"type": "bitmap", "file": "elitemobs:ui/space_split.png", "ascent": -32768, "height": -19, "chars": [chr(0xF0EF1)]},
]
MINI_TEX = {
    "assets/elitemobs/textures/gui/repairmenu.png": png(256, 256),
    "assets/elitemobs/textures/gui/shop.png": png(256, 256),
    "assets/nightbreak/textures/gui/setup.png": png(214, 256),
}


class MarkerTest(unittest.TestCase):
    def test_shared_marker_cases(self):
        for c in CASES["markers"]:
            self.assertEqual(int(c["marker"], 16), g.marker_for(int(c["em"], 16)), c)

    def test_spacing_has_no_marker(self):
        for s in CASES["spacing"]:
            with self.assertRaises(ValueError):
                g.marker_for(int(s, 16))


class OffsetTest(unittest.TestCase):
    def test_large_chest_matches_spike(self):
        self.assertEqual((-11, -134), g.offset(136, "large", None))

    def test_small_chest_one_lower_top(self):
        self.assertEqual((-11, -135), g.offset(136, "small", None))

    def test_ascent_shifts_down(self):
        self.assertEqual((-11, -128), g.offset(130, "large", None))

    def test_override_wins(self):
        self.assertEqual((-5, -100), g.offset(136, "large", {"large": {"x": -5, "y": -100}}))


class ReadTest(unittest.TestCase):
    def test_reads_backgrounds_and_spacing(self):
        menus, spacing = g.read_menus(zipfile.ZipFile(java_pack(MINI, MINI_TEX)))
        self.assertEqual([0xF0E01, 0xF0E02, 0xF0F0B], [m.codepoint for m in menus])
        self.assertEqual({0xF0EF1}, spacing)
        self.assertEqual("elitemobs_repairmenu", menus[0].texture_name)

    def test_refuses_pack_without_menus(self):
        with self.assertRaises(SystemExit):
            g.read_menus(zipfile.ZipFile(java_pack([MINI[3]], {})))


class BuildTest(unittest.TestCase):
    def test_pack_contents(self):
        with tempfile.TemporaryDirectory() as tmp:
            meta = {"header_uuid": "00000000-0000-0000-0000-000000000001",
                    "module_uuid": "00000000-0000-0000-0000-000000000002", "version": 4}
            out = g.build(java_pack(MINI, MINI_TEX), tmp, meta, {})
            z = zipfile.ZipFile(out)
            names = set(z.namelist())
            for page in ("E8", "E9", "EA"):
                self.assertIn(f"font/glyph_{page}.png", names)
            for t in ("elitemobs_repairmenu", "elitemobs_shop", "nightbreak_setup"):
                self.assertIn(f"textures/ui/fmmbridge_em/{t}.png", names)
            ui = json.loads(z.read("ui/chest_screen.json").decode("utf-8"))
            for half in ("large_chest_panel_top_half", "small_chest_panel_top_half"):
                images = [list(v.keys())[0] for v in ui[half]["modifications"][0]["value"]]
                self.assertEqual(3, len(images), half)
            self.assertEqual("chest.fmmbridge_cell_images",
                             ui["chest_grid_item@common.container_item"]["$background_images"])
            large = {list(v.keys())[0]: list(v.values())[0]
                     for v in ui["large_chest_panel_top_half"]["modifications"][0]["value"]}
            self.assertEqual([214, 256], large["fmmbridge_em_nightbreak_setup"]["size"])
            self.assertEqual([256, 256], large["fmmbridge_em_elitemobs_repairmenu"]["size"])
            manifest = json.loads(z.read("manifest.json"))
            self.assertEqual([0, 0, 5], manifest["header"]["version"])
            self.assertEqual(5, meta["version"])

    def test_visibility_uses_marker_character(self):
        with tempfile.TemporaryDirectory() as tmp:
            meta = {"header_uuid": "a", "module_uuid": "b", "version": 0}
            out = g.build(java_pack(MINI, MINI_TEX), tmp, meta, {})
            ui = zipfile.ZipFile(out).read("ui/chest_screen.json").decode("utf-8")
            self.assertIn("", ui)
            self.assertIn("", ui)
            self.assertIn("", ui)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 3: Tests laufen lassen — müssen scheitern**

Run: `python3 -m unittest discover -s tools/bedrock-menus -v`
Expected: `ModuleNotFoundError: No module named 'generate'`.

- [ ] **Step 4: Generator implementieren**

`tools/bedrock-menus/generate.py`:

```python
#!/usr/bin/env python3
"""Phase 7.6 — baut das Bedrock-Pack FMMBridge-EliteMobsMenus aus dem Java-Pack von RPM.

Aufruf:  python3 tools/bedrock-menus/generate.py <ResourcePackManager_RSP.zip>
Ausgabe: target/bedrock-menus/FMMBridge-EliteMobsMenus.mcpack (+ last-run.json)
Erhoeht die Version in tools/bedrock-menus/pack-meta.json — danach committen.

Die Texturen gehoeren MagmaGuy und duerfen nie ins (oeffentliche) Repo. Nur Standardbibliothek.
Zuordnung und Versatz muessen zu BedrockMenuTitle.java passen (gemeinsame Faelle in cases.json).
"""
import json, os, struct, sys, zipfile, zlib
from dataclasses import dataclass

EM_BLOCK_START, EM_BLOCK_END = 0xF0E00, 0xF0F0B
MARKER_BASE, GENERIC_MARKER = 0xE900, 0xE8FF
KNOWN_SPACING = {0xF0EF1, 0xF0EF5}          # muss BedrockMenuTitle.SPACING_CODEPOINTS entsprechen
X_DEFAULT = -11                              # 8 (Titel-x) − 19 (U+F0EF1)
TOP = {"large": 11, "small": 12}             # y der oberen Kistenhaelfte in Bedrocks chest_screen
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


@dataclass
class Menu:
    codepoint: int
    file: str            # z. B. elitemobs:gui/repairmenu.png
    ascent: int
    texture_name: str    # z. B. elitemobs_repairmenu
    png: bytes
    width: int           # aus dem PNG-Kopf — zwei nightbreak-Bilder sind 213/214 px breit
    height: int


def marker_for(cp):
    if cp < EM_BLOCK_START or cp > EM_BLOCK_END or cp in KNOWN_SPACING:
        raise ValueError(f"kein Menue-Hintergrund: U+{cp:X}")
    return MARKER_BASE + (cp - EM_BLOCK_START)


def offset(ascent, chest, override):
    if override and chest in override:
        return override[chest]["x"], override[chest]["y"]
    return X_DEFAULT, 13 - ascent - TOP[chest]


def read_menus(z):
    if "assets/minecraft/font/default.json" not in z.namelist():
        sys.exit("FEHLER: assets/minecraft/font/default.json fehlt — falsches ZIP? Erwartet: ResourcePackManager_RSP.zip")
    providers = json.loads(z.read("assets/minecraft/font/default.json"))["providers"]
    menus, spacing = [], set()
    for p in providers:
        if p.get("type") != "bitmap":
            continue
        for row in p.get("chars", []):
            for ch in row:
                cp = ord(ch)
                if not (EM_BLOCK_START <= cp <= EM_BLOCK_END):
                    continue
                if p.get("height", 0) < 0:
                    spacing.add(cp)
                elif p.get("height", 0) >= 40:
                    ns, path = p["file"].split(":", 1)
                    tex = f"assets/{ns}/textures/{path}"
                    name = ns + "_" + os.path.splitext(os.path.basename(path))[0]
                    data = z.read(tex)
                    w, h = struct.unpack(">II", data[16:24])
                    menus.append(Menu(cp, p["file"], p.get("ascent", 0), name, data, w, h))
    if not menus:
        sys.exit("FEHLER: keine Menue-Hintergruende im Pack gefunden — Abbruch, es wird kein leeres Pack gebaut")
    menus.sort(key=lambda m: m.codepoint)
    return menus, spacing


def transparent_png(size=256):
    raw = b"".join(b"\x00" + b"\x00\x00\x00\x00" * size for _ in range(size))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def _image(m, chest, overrides):
    x, y = offset(m.ascent, chest, overrides.get(f"{m.codepoint:X}"))
    marker = chr(marker_for(m.codepoint))
    return {f"fmmbridge_em_{m.texture_name}": {
        "type": "image", "texture": f"textures/ui/fmmbridge_em/{m.texture_name}",
        "size": [m.width, m.height], "anchor_from": "top_left", "anchor_to": "top_left",
        "offset": [x, y], "layer": 1,
        "$atext": "$container_title", "visible": f"(not ($atext - '{marker}' = $atext))"}}


def _cell_binding(name_expr):
    return [{"binding_name": name_expr, "binding_name_override": "#visible",
             "binding_type": "collection", "binding_collection_name": "$item_collection_name"}]


def chest_screen(menus, overrides):
    ui = {"namespace": "chest"}
    for chest in ("large", "small"):
        ui[f"{chest}_chest_panel_top_half"] = {"modifications": [{
            "array_name": "controls", "operation": "insert_front",
            "value": [_image(m, chest, overrides) for m in menus]}]}
    ui["chest_grid_item@common.container_item"] = {
        "$item_collection_name": "container_items",
        "$background_images": "chest.fmmbridge_cell_images"}
    ui["fmmbridge_cell_images"] = {"type": "panel", "controls": [
        {"fmmbridge_plain_bg": {"type": "panel", "$atext": "$container_title",
                                "visible": f"($atext - '{chr(GENERIC_MARKER)}' = $atext)",
                                "controls": [{"cell_image@common.cell_image": {
                                    "$cell_selected_binding_name|default": "#is_selected_slot",
                                    "visible": True,
                                    "bindings": _cell_binding("(not $cell_selected_binding_name)")}}]}},
        {"cell_image_selected@common.cell_image_selected": {
            "$cell_selected_binding_name|default": "#is_selected_slot",
            "visible": False,
            "bindings": _cell_binding("$cell_selected_binding_name")}}]}
    return ui


def build(java_zip, out_dir, meta, overrides):
    menus, spacing = read_menus(zipfile.ZipFile(java_zip))
    unknown = spacing - KNOWN_SPACING
    if unknown:
        print("WARNUNG: neue Abstands-Zeichen, BedrockMenuTitle.SPACING_CODEPOINTS nachziehen: "
              + ", ".join(f"U+{c:X}" for c in sorted(unknown)))
    meta["version"] += 1
    version = [0, 0, meta["version"]]
    manifest = {"format_version": 2,
                "header": {"name": "FMMBridge EliteMobs Menus",
                           "description": "EliteMobs-Menue-Hintergruende fuer Bedrock (FMMBedrockBridge Phase 7.6)",
                           "uuid": meta["header_uuid"], "version": version, "min_engine_version": [1, 21, 0]},
                "modules": [{"type": "resources", "uuid": meta["module_uuid"], "version": version}]}
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "FMMBridge-EliteMobsMenus.mcpack")
    glyph = transparent_png()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        z.writestr("ui/chest_screen.json", json.dumps(chest_screen(menus, overrides), ensure_ascii=False, indent=1))
        for page in ("E8", "E9", "EA"):
            z.writestr(f"font/glyph_{page}.png", glyph)
        for m in menus:
            z.writestr(f"textures/ui/fmmbridge_em/{m.texture_name}.png", m.png)
    _report(out_dir, menus)
    return out


def _report(out_dir, menus):
    path = os.path.join(out_dir, "last-run.json")
    now = {f"{m.codepoint:X}": m.file for m in menus}
    before = json.load(open(path)) if os.path.exists(path) else {}
    added = sorted(set(now) - set(before))
    removed = sorted(set(before) - set(now))
    print(f"{len(menus)} Menues. Neu: {added or '-'}  Entfallen: {removed or '-'}")
    json.dump(now, open(path, "w"), indent=1)


def main(argv):
    if len(argv) != 2:
        sys.exit(__doc__)
    meta_path = os.path.join(HERE, "pack-meta.json")
    meta = json.load(open(meta_path))
    overrides = {k: v for k, v in json.load(open(os.path.join(HERE, "overrides.json"))).items() if not k.startswith("_")}
    out = build(argv[1], os.path.join(ROOT, "target", "bedrock-menus"), meta, overrides)
    json.dump(meta, open(meta_path, "w"), indent=2)
    print(f"Pack: {out}  Version: 0.0.{meta['version']}  (pack-meta.json committen)")


if __name__ == "__main__":
    main(sys.argv)
```

- [ ] **Step 5: Tests laufen lassen — müssen grün sein**

Run: `python3 -m unittest discover -s tools/bedrock-menus -v`
Expected: alle Tests OK (Marker, Offsets, Lesen, Bauen, Sichtbarkeit).

- [ ] **Step 6: Commit**

```bash
git add tools/bedrock-menus/generate.py tools/bedrock-menus/test_generate.py \
        tools/bedrock-menus/pack-meta.json tools/bedrock-menus/overrides.json
git commit -m "feat(phase76): Generator fuer das Bedrock-Pack FMMBridge-EliteMobsMenus"
```

---

### Task 5: Pack erzeugen und ausliefern — erst Pack, dann Bridge

**Files:**
- Modify: `tools/bedrock-menus/pack-meta.json` (Versionszähler durch den Lauf)
- Server: Proxy01 `Geyser-Velocity/packs/`, TestServer01 `plugins/FMMBedrockBridge.jar`, `SERVER-STATE.md` (Umbrella)

**Interfaces:**
- Consumes: Generator (Task 4), Bridge-JAR (Task 3).

- [ ] **Step 1: Generator gegen das echte Java-Pack laufen lassen**

```bash
mkdir -p target/bedrock-menus
scp "$MC_SSH:$INSTANCES/TestServer01/Minecraft/plugins/ResourcePackManager/output/ResourcePackManager_RSP.zip" target/bedrock-menus/RSP.zip
python3 tools/bedrock-menus/generate.py target/bedrock-menus/RSP.zip
```

(`target/` ist gitignored — das Java-Pack mit MagmaGuys Texturen landet so nie im Git.)

Expected: `173 Menues.` (oder die aktuelle Zahl), keine Warnung zu Abstands-Zeichen, Pack in `target/bedrock-menus/`.

- [ ] **Step 2: Pack auf den Proxy (mit Backup), Spike-Pack weg**

`FMMBridgeGuiSpike.mcpack` nach `~/backups/gui-spike-20261001/` verschieben, `FMMBridge-EliteMobsMenus.mcpack` nach `Geyser-Velocity/packs/`, sha256 beidseitig prüfen. SERVER-STATE-Eintrag (md5 aller Kopien vorher/nachher). **Fabi startet den Proxy neu.**

- [ ] **Step 3: Bridge bauen und auf TestServer01 (mit Backup)**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk bash verify-both-apis.sh` → grün. JAR nach Backup des laufenden (`FMMBedrockBridge.jar.bak-<datum>-<commit>`) deployen, sha256 prüfen, SERVER-STATE-Eintrag. **Fabi startet TestServer01 neu.** Boot-Zeile prüfen: `Phase 7.6: Bedrock menu backgrounds on (hide-slot-backgrounds=true, …)`.

- [ ] **Step 4: Commit + Push**

```bash
git add tools/bedrock-menus/pack-meta.json
git commit -m "build(phase76): Pack-Version 0.0.<n> erzeugt und ausgeliefert"
git push origin main
```

---

### Task 6: Abnahme im Spiel und Doku

**Files:**
- Modify: `README.md` (Feature-Tabelle + Klassen-Tabelle), `HANDOFF.md`, `CLAUDE_SESSION.md`
- Modify (bei Bedarf): `tools/bedrock-menus/overrides.json`

- [ ] **Step 1: Abnahme (Fabi, Bedrock PC und Konsole, TestServer01)**

| Menü | Prüfen |
|---|---|
| Reparieren | große Kiste, `ascent` 136 |
| ein Menü mit `ascent` 130 | Versatz |
| ein Menü mit `ascent` 45 | Versatz |
| ein Menü mit kleiner Kiste | Aufbau |
| ein `nightbreak`-Menü | Breite 213/214 px |

Je Menü: keine Kästchen im Titel, richtiges Bild an richtiger Stelle, Items sichtbar und klickbar, Controller-Bedienung. Log: `[PHASE76] menu title rewritten for Bedrock` (bei `debug: true`). Gegenprobe Java unverändert; `phase76.bedrock-menu-backgrounds: false` → Bedrock wie vorher.

- [ ] **Step 2: Abweichungen beheben**

Versatz-Fehler einzelner Menüs → `overrides.json` ergänzen, Generator neu laufen lassen, Pack neu ausliefern (Task 5 Step 1–2). Systematischer Versatz aller Menüs → `X_DEFAULT`/`TOP` in `generate.py` **und** die Erwartungen in `OffsetTest` anpassen.

- [ ] **Step 3: Doku**

README: Feature-Zeile „Phase 7.6 — EliteMobs menu backgrounds on Bedrock" und Klassen-Zeile `bridge/BedrockMenuTitle`; Hinweis auf `tools/bedrock-menus/`. HANDOFF: Abschnitt 7.6 mit Abnahme-Ergebnis, Pack-Version, Rückweg. CLAUDE_SESSION: Sitzungseintrag.

- [ ] **Step 4: Commit + Push**

```bash
git add README.md HANDOFF.md CLAUDE_SESSION.md tools/bedrock-menus/overrides.json
git commit -m "docs(phase76): Abnahme EliteMobs-Menue-Hintergruende auf Bedrock"
git push origin main
```
