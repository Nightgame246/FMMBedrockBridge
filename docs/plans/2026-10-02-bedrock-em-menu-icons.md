# EliteMobs-Menü-Symbole auf Bedrock (Phase 7.7) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bedrock-Spieler sehen in EliteMobs-Menüs die EM-Symbole statt Vanilla-Items, indem die Bridge für sie das Grund-Item der Menü-Symbole auf `minecraft:paper` umschreibt.

**Architecture:** Reine Regel-Klasse `BedrockMenuIcons` (Strings rein, boolean raus, unit-getestet) + ein Haken in `PacketInterceptor` für `WINDOW_ITEMS`/`SET_SLOT`, der nur für Bedrock und nur in Container-Fenstern (`windowId > 0`) greift. Komponenten-Patches des Items bleiben unverändert; RPMs vorhandenes Papier-Mapping in Geyser übernimmt die Darstellung. Schalter + Listen in `config.yml`.

**Tech Stack:** Java 25, Paper 26.2, PacketEvents 2.14.0 (`provided`), JUnit 5.11.3, Maven.

**Spec:** `docs/specs/2026-10-02-bedrock-em-menu-icons-design.md`

## Global Constraints

- Bauen nur mit JDK 25: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk`
- Maven ist nicht im PATH: `MVN=/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn` (Ordner je nach IDEA-Version `maven` oder `maven-plugin`)
- Package-Wurzel `de.crazypandas.fmmbedrockbridge`; Logging nur über `FMMBedrockBridge.debugLog` / Java-Logger, kein `System.out`
- Fremde Plugins nie shaden — PacketEvents bleibt `provided`
- Java-Spieler sind **nie** betroffen; Spielerinventar (`windowId == 0`) und Cursor (`-1`) **nie** anfassen
- Schalter `phase77.bedrock-menu-icons: false` ⇒ Verhalten exakt wie vor 7.7
- Defaults: `item-model-prefixes: ["elitemobs:ui/", "nightbreak:ui/"]`, `exclude-models: ["elitemobs:ui/redcrown", "elitemobs:ui/yellowcrown"]`
- Vergleiche ohne Groß-/Kleinschreibung (`Locale.ROOT`)
- Kein Reload-Befehl: Listen werden in `onEnable` einmal gelesen

## Review Focus

1. **Leerer Slot / `ItemStack.EMPTY`** in `WINDOW_ITEMS` — muss unverändert durchgehen, kein NPE (Task 2, `MenuIconRebaser` prüft `isEmpty()` zuerst).
2. **Item ohne Komponenten-Patches** (normales Item im Spielerinventar-Teil eines Container-Pakets) — darf nicht neu gebaut werden, sonst ändern sich Pakete ohne Grund (Task 2: nur bei `shouldRebase == true` wird gebaut; Paket wird nur re-encodet, wenn sich etwas geändert hat).
3. **`item_model` als Entfernungs-Patch** (`Optional.empty()` in den Patches) — `getComponent(ITEM_MODEL)` liefert dann leer bzw. den Base-Default eines Vanilla-Items, z. B. `minecraft:emerald`; der fällt nicht unter die Präfixe (Task 1 Test `vanillaDefaultModel_false`).
4. **Präfix-Eintrag in der Konfig mit Großbuchstaben oder ohne `/`** — Fabi tippt `EliteMobs:UI/`; muss trotzdem greifen (Task 1 Test `caseInsensitive`).
5. **Klick auf einen umgebauten Button** — darf weder hängen noch das falsche auslösen; nur in-game prüfbar (Task 4, erster Prüfpunkt).

---

### Task 1: Regel `BedrockMenuIcons` (TDD)

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuIcons.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuIconsTest.java`

**Interfaces:**
- Produces: `public static final String PAPER = "minecraft:paper";` und
  `public static boolean shouldRebase(String baseItemId, String itemModel, List<String> prefixes, Set<String> excludes)`
  — `excludes` wird vom Aufrufer bereits klein geschrieben übergeben **oder** die Methode normalisiert selbst (hier: Methode normalisiert selbst, Aufrufer muss nichts wissen).

- [ ] **Step 1: Write the failing test**

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 7.7 — which EliteMobs menu icons get paper as base item for Bedrock. */
class BedrockMenuIconsTest {

    private static final List<String> PREFIXES = List.of("elitemobs:ui/", "nightbreak:ui/");
    private static final Set<String> EXCLUDES = Set.of("elitemobs:ui/redcrown", "elitemobs:ui/yellowcrown");

    private static boolean rebase(String base, String model) {
        return BedrockMenuIcons.shouldRebase(base, model, PREFIXES, EXCLUDES);
    }

    @Test
    void emIconOnEmerald_true() {
        assertTrue(rebase("minecraft:emerald", "elitemobs:ui/anvilhammer"));
    }

    @Test
    void emIconOnBanner_true() {
        assertTrue(rebase("minecraft:green_banner", "elitemobs:ui/boxinput"));
    }

    @Test
    void emIconOnRedstone_true() {
        assertTrue(rebase("minecraft:redstone", "elitemobs:ui/handwithcoins"));
    }

    @Test
    void nightbreakCrossOnBarrier_true() {
        assertTrue(rebase("minecraft:barrier", "nightbreak:ui/redcross"));
    }

    @Test
    void alreadyPaper_false() {
        assertFalse(rebase("minecraft:paper", "elitemobs:ui/goldenquestionmark"));
    }

    @Test
    void excludedCrown_false() {
        assertFalse(rebase("minecraft:golden_helmet", "elitemobs:ui/redcrown"));
    }

    @Test
    void noItemModel_false() {
        assertFalse(rebase("minecraft:emerald", null));
    }

    @Test
    void vanillaDefaultModel_false() {
        assertFalse(rebase("minecraft:emerald", "minecraft:emerald"));
    }

    @Test
    void gearNamespace_false() {
        assertFalse(rebase("minecraft:iron_sword", "elitemobs:equipment/bronze_sword"));
        assertFalse(rebase("minecraft:leather_horse_armor", "freeminecraftmodels:some/model"));
    }

    @Test
    void caseInsensitive() {
        assertTrue(BedrockMenuIcons.shouldRebase("minecraft:emerald", "elitemobs:ui/anvilhammer",
                List.of("EliteMobs:UI/"), Set.of()));
        assertFalse(BedrockMenuIcons.shouldRebase("minecraft:golden_helmet", "elitemobs:ui/redcrown",
                PREFIXES, Set.of("ELITEMOBS:UI/REDCROWN")));
        assertFalse(BedrockMenuIcons.shouldRebase("MINECRAFT:PAPER", "elitemobs:ui/update",
                PREFIXES, EXCLUDES));
    }

    @Test
    void nullBase_false() {
        assertFalse(rebase(null, "elitemobs:ui/anvilhammer"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk; $MVN -q -o test -Dtest=BedrockMenuIconsTest`
Expected: Kompilierfehler „cannot find symbol: BedrockMenuIcons".

- [ ] **Step 3: Write minimal implementation**

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 7.7 — decides which EliteMobs menu icons are rebased onto paper for Bedrock.
 *
 * <p>EliteMobs puts its menu icons on arbitrary base items (emerald, redstone, barrier, banners) and
 * draws them through the {@code item_model} component. Geyser maps custom items per Java base item,
 * and ResourcePackManager can only guess that base item — for these icons it registers paper (among
 * others), never emerald/barrier, and Geyser cannot override redstone or banners at all. Rebasing the
 * icon onto paper for Bedrock makes RPM's existing mapping apply. Java never sees this.
 *
 * <p>Pure on purpose — no Bukkit, no PacketEvents.
 */
public final class BedrockMenuIcons {

    public static final String PAPER = "minecraft:paper";

    private BedrockMenuIcons() {}

    /**
     * @param baseItemId Java base item, e.g. {@code minecraft:emerald}
     * @param itemModel  the item's {@code item_model}, or {@code null} if it has none
     * @param prefixes   model prefixes that mark a menu icon, e.g. {@code elitemobs:ui/}
     * @param excludes   models mapped under a different base by RPM (crowns under helmets)
     * @return true if the item should be sent to Bedrock with {@link #PAPER} as base item
     */
    public static boolean shouldRebase(String baseItemId, String itemModel,
                                       List<String> prefixes, Set<String> excludes) {
        if (baseItemId == null || itemModel == null) return false;
        if (PAPER.equals(baseItemId.toLowerCase(Locale.ROOT))) return false;
        String model = itemModel.toLowerCase(Locale.ROOT);
        for (String exclude : excludes) {
            if (model.equals(exclude.toLowerCase(Locale.ROOT))) return false;
        }
        for (String prefix : prefixes) {
            if (model.startsWith(prefix.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `$MVN -q -o test -Dtest=BedrockMenuIconsTest`
Expected: `Tests run: 11, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuIcons.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockMenuIconsTest.java
git commit -m "feat(phase77): Regel fuer EliteMobs-Menue-Symbole auf Bedrock"
```

---

### Task 2: Konfig, Umbau des Items und Haken im `PacketInterceptor`

**Files:**
- Create: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/MenuIconRebaser.java`
- Modify: `src/main/resources/config.yml` (Block nach `phase76:` anhängen)
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java` (Getter neben `isPhase76HideSlotBackgrounds`, ~Zeile 241; Listen in `onEnable` nach `saveDefaultConfig()`, ~Zeile 52)
- Modify: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java` (Zweig in `onPacketSend` nach dem 7.6-Zweig; Klassen-Javadoc ergänzen)

**Interfaces:**
- Consumes: `BedrockMenuIcons.shouldRebase(String, String, List<String>, Set<String>)`, `BedrockMenuIcons.PAPER`
- Produces:
  - `FMMBedrockBridge.isPhase77MenuIconsEnabled(): boolean`
  - `FMMBedrockBridge.getPhase77Prefixes(): List<String>`, `FMMBedrockBridge.getPhase77Excludes(): Set<String>`
  - `MenuIconRebaser.rebase(ItemStack item, List<String> prefixes, Set<String> excludes): ItemStack` — gibt **dieselbe Instanz** zurück, wenn nichts zu tun ist

`MenuIconRebaser` braucht die PacketEvents-Registries (`ItemTypes`) und ist deshalb nicht ohne laufendes PacketEvents unit-testbar — die Logik liegt in Task 1, hier nur das Umsetzen. Geprüft wird es durch Build + In-game-Test (Task 4).

- [ ] **Step 1: config.yml — Block anhängen**

```yaml

# Phase 7.7 — EliteMobs-Menü-Symbole auf Bedrock
#
# EliteMobs setzt seine Button-Symbole auf Smaragd, Redstone, Barriere oder Banner. Geyser ordnet
# Custom-Items je Grund-Item zu, und ResourcePackManager hat die Symbole nur unter Papier gemappt
# (Redstone und Banner kann Geyser gar nicht überschreiben). Die Bridge setzt das Grund-Item für
# Bedrock deshalb auf Papier; das Symbol selbst bleibt gleich. Java ist nie betroffen.
#
# Auf false, sobald EliteMobs/RPM/Geyser das selbst lösen — dann ist alles wie vorher.
# Änderungen an den Listen brauchen einen Neustart.
phase77:
  bedrock-menu-icons: true
  item-model-prefixes: ["elitemobs:ui/", "nightbreak:ui/"]
  # Liegen bei RPM unter Helmen statt Papier — auf Papier würden sie kaputtgehen.
  exclude-models: ["elitemobs:ui/redcrown", "elitemobs:ui/yellowcrown"]
```

- [ ] **Step 2: Getter und Listen in `FMMBedrockBridge`**

Felder (bei den übrigen statischen Feldern der Klasse):

```java
    // Phase 7.7 — read once in onEnable; the rule runs for every inventory packet.
    private static volatile List<String> phase77Prefixes = List.of();
    private static volatile Set<String> phase77Excludes = Set.of();
```

In `onEnable`, direkt nach `saveDefaultConfig();`:

```java
        phase77Prefixes = List.copyOf(getConfig().getStringList("phase77.item-model-prefixes"));
        phase77Excludes = Set.copyOf(getConfig().getStringList("phase77.exclude-models"));
        log.info("Phase 7.7: Bedrock menu icons enabled=" + isPhase77MenuIconsEnabled()
                + ", prefixes=" + phase77Prefixes + ", excludes=" + phase77Excludes.size());
```

(Falls `log` an dieser Stelle noch nicht definiert ist, `getLogger()` verwenden.)

Getter neben `isPhase76HideSlotBackgrounds()`:

```java
    public static boolean isPhase77MenuIconsEnabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase77.bedrock-menu-icons", true);
    }

    public static List<String> getPhase77Prefixes() {
        return phase77Prefixes;
    }

    public static Set<String> getPhase77Excludes() {
        return phase77Excludes;
    }
```

Imports `java.util.List`, `java.util.Set` ergänzen, falls nicht vorhanden.

- [ ] **Step 3: `MenuIconRebaser` anlegen**

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import com.github.retrooper.packetevents.protocol.component.ComponentType;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.PatchableComponentMap;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 7.7 — applies {@link BedrockMenuIcons} to a PacketEvents item: same amount, same component
 * patches (name, lore, item_model, …), base item paper. Returns the same instance when nothing changes.
 */
final class MenuIconRebaser {

    private MenuIconRebaser() {}

    static ItemStack rebase(ItemStack item, List<String> prefixes, Set<String> excludes) {
        if (item == null || item.isEmpty()) return item;
        String model = item.getComponent(ComponentTypes.ITEM_MODEL)
                .map(ItemModel::getModelLocation)
                .map(Object::toString)
                .orElse(null);
        String base = item.getType().getName().toString();
        if (!BedrockMenuIcons.shouldRebase(base, model, prefixes, excludes)) return item;

        Map<ComponentType<?>, Optional<?>> patches = new HashMap<>(item.getComponents().getPatches());
        PatchableComponentMap components = new PatchableComponentMap(ItemTypes.PAPER.getComponents(), patches);
        return ItemStack.builder()
                .type(ItemTypes.PAPER)
                .amount(item.getAmount())
                .components(components)
                .build();
    }
}
```

- [ ] **Step 4: Haken im `PacketInterceptor`**

Imports ergänzen:

```java
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
```

In `onPacketSend`, direkt nach dem `// Phase 7.6`-Block:

```java
                // Phase 7.7 — EliteMobs menu icons for Bedrock
                if ((event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS
                        || event.getPacketType() == PacketType.Play.Server.SET_SLOT)
                        && Boolean.TRUE.equals(isBedrock(playerObj))) {
                    rebaseMenuIcons(event);
                }
```

Neue Methode neben `rewriteMenuTitle`:

```java
    /**
     * Phase 7.7 — puts EliteMobs' menu icons on paper for Bedrock, so ResourcePackManager's Geyser
     * mapping (registered under paper) applies. Only container windows; the player's own inventory
     * (window 0) and the cursor (-1) are never touched. Unchanged packets are not re-encoded.
     */
    private void rebaseMenuIcons(PacketSendEvent event) {
        if (!FMMBedrockBridge.isPhase77MenuIconsEnabled()) return;
        List<String> prefixes = FMMBedrockBridge.getPhase77Prefixes();
        Set<String> excludes = FMMBedrockBridge.getPhase77Excludes();
        try {
            if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
                if (wrapper.getWindowId() <= 0) return;
                List<ItemStack> items = wrapper.getItems();
                List<ItemStack> out = new ArrayList<>(items.size());
                int changed = 0;
                for (ItemStack item : items) {
                    ItemStack rebased = MenuIconRebaser.rebase(item, prefixes, excludes);
                    if (rebased != item) changed++;
                    out.add(rebased);
                }
                if (changed == 0) return;
                wrapper.setItems(out);
                event.markForReEncode(true);
                FMMBedrockBridge.debugLog("[PHASE77] rebased " + changed
                        + " menu icon(s) for Bedrock in window " + wrapper.getWindowId());
            } else {
                WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
                if (wrapper.getWindowId() <= 0) return;
                ItemStack item = wrapper.getItem();
                ItemStack rebased = MenuIconRebaser.rebase(item, prefixes, excludes);
                if (rebased == item) return;
                wrapper.setItem(rebased);
                event.markForReEncode(true);
                FMMBedrockBridge.debugLog("[PHASE77] rebased 1 menu icon(s) for Bedrock in window "
                        + wrapper.getWindowId());
            }
        } catch (Throwable t) {
            FMMBedrockBridge.debugLog("[PHASE77] icon rebase failed: " + t);
        }
    }
```

Klassen-Javadoc oben um eine Zeile ergänzen:

```java
 *  - Phase 7.7: puts EliteMobs' menu icons on paper for Bedrock (Geyser custom-item mapping).
```

- [ ] **Step 5: Build + alle Tests**

Run: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk; $MVN -o clean package`
Expected: `BUILD SUCCESS`, alle Tests grün (vorher vorhandene + 11 neue). Falls `-o` an einer fehlenden Dependency scheitert: ohne `-o` wiederholen.

Ob das neu gebaute `ItemStack` (ohne Version/Registry am Builder) sauber kodiert, zeigt erst die Laufzeit: in Task 4 bei aktivem Debug-Modus auf `[PHASE77] icon rebase failed` achten.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/config.yml \
        src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java \
        src/main/java/de/crazypandas/fmmbedrockbridge/bridge/MenuIconRebaser.java \
        src/main/java/de/crazypandas/fmmbedrockbridge/bridge/PacketInterceptor.java
git commit -m "feat(phase77): EliteMobs-Menue-Symbole fuer Bedrock auf Papier umsetzen"
```

---

### Task 3: Upstream-Entwurf und Doku

**Files:**
- Create: `docs/upstream-bugs/2026-10-02-elitemobs-menu-icons-base-item.md`
- Modify: `HANDOFF.md` (neuer Abschnitt „Phase 7.7" vor „Phase 7.6", Kopf-Hinweis, Testzahl)
- Modify: `README.md` (Feature-Liste: Phase 7.7 ergänzen, falls dort Phasen aufgelistet sind)

- [ ] **Step 1: Upstream-Entwurf schreiben** (Englisch, wie die übrigen Entwürfe im Ordner; vorher einen bestehenden Entwurf dort als Stilvorlage lesen)

Inhalt, gegliedert:
- **Title:** „Menu icons use non-paper base items — Bedrock (Geyser) shows vanilla emerald/redstone/barrier/banners"
- **Versions:** EliteMobs 10.9.7, ResourcePackManager 2.4.5, Geyser 2.11.x, Paper 26.2
- **What happens:** On Bedrock, menu buttons show vanilla emerald, redstone, barrier, green/red banner instead of the `elitemobs:ui/*` icons. The golden question mark works.
- **Why:** `CustomModelAdder.addCustomModel` only sets `item_model`. Geyser maps custom items per Java base item. RPM's `BaseItemResolver` cannot know the base item and registers `elitemobs:ui/*` under its generic fallback (`paper`, `stick`, `name_tag`, `compass`, swords). Emerald and barrier are not in that list; redstone and banners are explicitly excluded in RPM's `GeyserBaseItemCompatibility` because Geyser cannot override them.
- **Evidence:** `BuyOrSellMenu` sets `Material.PAPER` for the info item → it renders on Bedrock. Live `rspm_geyser_mappings.json` lists all 21 non-crown `elitemobs:ui/*` models under `minecraft:paper`.
- **Suggested fix (EliteMobs):** when `useResourcePackModels()` is on, use `PAPER` as base for menu icons (defaults in `RepairMenuConfig`, `SellMenuConfig`, `ScrapperMenuConfig`, `BuyOrSellMenuConfig`, `ItemEnchantmentMenuConfig`, `EliteScrollMenuConfig`, `UnbinderMenuConfig`), or force it in `CustomModelAdder`.
- **Alternative (RPM):** let plugins declare the base item of an `item_model`.
- **Workaround we use:** packet-level rebase to paper for Bedrock players only.

- [ ] **Step 2: HANDOFF.md — Abschnitt Phase 7.7**

Vor `### Phase 7.6 — …` einfügen:

```markdown
### Phase 7.7 — EliteMobs-Menü-Symbole auf Bedrock (gebaut 02.10.2026, in-game offen)

Spec `docs/specs/2026-10-02-bedrock-em-menu-icons-design.md`, Plan `docs/plans/2026-10-02-bedrock-em-menu-icons.md`.
Bedrock zeigte Vanilla-Items (Smaragd, Redstone, Barriere, Banner) statt EMs Symbolen. Ursache: EM setzt
nur `item_model` auf beliebige Grund-Items, Geyser mappt je Grund-Item, RPM hat die Symbole nur unter Papier
registriert (Redstone/Banner kann Geyser gar nicht). Die Bridge setzt das Grund-Item für Bedrock in
Container-Fenstern auf Papier (`BedrockMenuIcons` + `MenuIconRebaser`, `WINDOW_ITEMS`/`SET_SLOT`).
- **Rückweg:** `phase77.bedrock-menu-icons: false`. Listen `item-model-prefixes`/`exclude-models` (Neustart).
- **Upstream:** `docs/upstream-bugs/2026-10-02-elitemobs-menu-icons-base-item.md` — setzt EM das um, Schalter aus.
- **Offen:** In-game-Abnahme (Symbole + Buttons bedienbar), Kronen bewusst ausgenommen.
```

Testzahl im Kopf/Status auf den neuen Stand aus Task 2 Step 5 setzen.

- [ ] **Step 3: Commit**

```bash
git add docs/upstream-bugs/2026-10-02-elitemobs-menu-icons-base-item.md HANDOFF.md README.md
git commit -m "docs(phase77): Upstream-Entwurf und Arbeitsstand"
```

---

### Task 4: Deploy auf TestServer01 und In-game-Abnahme

**Remote-Aktion — vorher Fabi fragen** (CLAUDE.md, harte Regel 1). Neustart macht Fabi.

- [ ] **Step 1: Fabi um Freigabe für den JAR-Tausch fragen**

- [ ] **Step 2: Backup + Tausch** (nach Freigabe)

```bash
P=$INSTANCES/TestServer01/Minecraft/plugins
ssh $MC_SSH "cp $P/FMMBedrockBridge.jar $P/FMMBedrockBridge.jar.bak-$(date +%Y%m%d)-pre77 && sha256sum $P/FMMBedrockBridge.jar"
scp target/FMMBedrockBridge-*.jar $MC_SSH:$P/FMMBedrockBridge.jar
ssh $MC_SSH "sha256sum $P/FMMBedrockBridge.jar"
sha256sum target/FMMBedrockBridge-*.jar
```

Expected: beide sha256 der neuen JAR gleich. (Falls `target/` mehrere JARs enthält, die neueste mit Zeitstempel wählen.)

- [ ] **Step 3: Änderungs-Log in `../server-tools/SERVER-STATE.md`** — Eintrag mit Commit, sha256, Backup-Name; danach Server-Kopie per Drift-Verfahren (Remote-md5 gegen Ausgangsstand prüfen, dann `scp`, `md5sum` vergleichen).

- [ ] **Step 4: Fabi startet TestServer01 neu; Boot-Log prüfen**

```bash
ssh $MC_SSH "grep -E 'Phase 7.7|PHASE77|FMMBedrockBridge.*(ERROR|Exception)' $INSTANCES/TestServer01/Minecraft/logs/latest.log | tail -20"
```

Expected: `Phase 7.7: Bedrock menu icons enabled=true, prefixes=[elitemobs:ui/, nightbreak:ui/], excludes=2`, keine Exceptions.

- [ ] **Step 5: In-game-Abnahme mit Fabi (Bedrock am PC)** — zuerst Klick-Verhalten:

| Menü | Erwartet | Bedienen |
|---|---|---|
| Reparieren | Verbotsschild, Pfeil-Kisten, Amboss | Abbrechen + Reparieren auslösen |
| Kaufen oder Verkaufen | Geldbeutel, Hand mit Münzen | beide Buttons |
| Verschrotter, Verkaufen | EM-Bestätigen/-Abbrechen | Bestätigen |
| Verzauberer / Elite-Schriftrolle | Eingabe-/Ergebnis-Kisten | Item einlegen |

Java-Gegenprobe: unverändert. Bei Klemmen: `phase77.bedrock-menu-icons: false`, Neustart, untersuchen.
Debug-Zeilen `[PHASE77]` nur mit Debug-Modus sichtbar.

- [ ] **Step 6: Ergebnis dokumentieren** — HANDOFF-Abschnitt 7.7 auf „abgenommen" setzen (oder Befund), Screenshots nach `../references/screenshots/alt/2026-10-02_phase77-em-symbole-bedrock/`, Commit.
