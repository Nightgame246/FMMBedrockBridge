# Bedrock-Eingabe für EMs Advanced Combat System — Implementierungsplan

> **Für agentische Ausführung:** PFLICHT-SUB-SKILL: `superpowers:subagent-driven-development`
> (empfohlen) oder `superpowers:executing-plans`, Aufgabe für Aufgabe. Schritte sind als
> Checkboxen (`- [ ]`) geführt.

**Ziel:** Bedrock- und Konsolenspieler können EliteMobs' Klassen-Fähigkeiten über Schleichen
auslösen — die F-Chord-Steuerung von EM ist für sie nicht bedienbar.

**Architektur:** Ein Bukkit-Listener im Backend-Plugin fängt Schleichen, Angriff und Benutzen ab,
führt pro Spieler einen Chord-Zustandsautomaten und ruft bei Auslösung
`AdvancedCombatModule.useAbility(player, slot)` direkt auf. Keine Geyser-Extension, kein
Proxy-Deploy. Alles hinter einem Floodgate-Gate, scharf nur im Kampf.

**Tech-Stack:** Java 25, Paper 26.2 API, Maven, JUnit 5 (plain, keine Mock-Bibliothek),
EliteMobs 10.9.0 (`provided`), Floodgate API 2.2.5-SNAPSHOT (`provided`).

**Spec:** `docs/specs/2026-09-11-bedrock-ability-input-design.md` — der Plan argumentiert aus der
Spec; beide zusammen lesen.

## Globale Vorgaben

- **Bauen zwingend mit JDK 25:** `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk`. Mit JDK 21
  bricht der Build mit *„Ungültige Klassendatei … paper-api"* ab.
- **`mvn` liegt evtl. nicht im PATH:** `/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn`
- **Bauen und testen immer offline:** `mvn -o clean test`
- **Package-Wurzel:** `de.crazypandas.fmmbedrockbridge`
- **Konventionen:** camelCase für Methoden/Variablen, PascalCase für Klassen, Java Logger statt
  `System.out`, fremde Plugins immer `provided`, nie shaden.
- **Config-Block heißt `phase74`** — die Bridge benennt ihre Blöcke nach Phasen.
- ⚠️ **`com.magmaguy.elitemobs.advancedcombat.*` ist ein internes Alpha-Paket ohne
  API-Garantie.** Klassen daraus dürfen **nur** in `AdvancedCombatHook` importiert werden, und
  diese Klasse darf erst angefasst werden, nachdem `AdvancedCombatSupport.isPresent()` true
  ergeben hat. Sonst stirbt die Bridge beim Laden, wenn MagmaGuy umbenennt.
- **Kommentare auf Englisch**, wie im übrigen Bridge-Code. Spieler-sichtbare Texte auf Deutsch.
- **Der pom bleibt in diesem Plan unangetastet** (FMM 2.11.1 / EM 10.8.0). Der Versions-Nachzug
  auf 2.12.0 / 10.9.0 ist an die Server-Entscheidung gekoppelt und **nicht Teil dieses Plans** —
  siehe Aufgabe 6.

---

## Dateien

| Datei | Verantwortung |
|---|---|
| `bridge/BedrockAbilityGesture.java` (neu) | Chord-Zustandsautomat, reines Java, ohne Bukkit |
| `bridge/AdvancedCombatSupport.java` (neu) | Reine `Class.forName`-Prüfung, referenziert **keine** EM-Klasse |
| `bridge/AdvancedCombatHook.java` (neu) | Einzige Stelle, die EM-`advancedcombat` importiert |
| `bridge/AbilityFeedback.java` (neu) | `AbilityResult` → Anzeigetext |
| `bridge/BedrockAbilityListener.java` (neu) | Bukkit-Listener, Gates, Event-Cancelling |
| `FMMBedrockBridge.java` (ändern) | Verdrahtung + Config-Getter |
| `resources/config.yml` (ändern) | `phase74`-Block |
| `README.md` (ändern) | Feature dokumentieren |

---

## Aufgabe 1: Chord-Zustandsautomat

Das Herzstück, und das einzige Stück mit echter Logik. Vollständig ohne Server testbar — deshalb
zuerst und mit echtem TDD.

**Dateien:**
- Neu: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockAbilityGesture.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockAbilityGestureTest.java`

**Schnittstellen:**
- Verbraucht: nichts
- Produziert: `BedrockAbilityGesture(long maxOpenTicks)` · `Outcome sneakStart(long tick)` ·
  `Outcome attack(long tick)` · `Outcome use(long tick)` · `void close()` ·
  `boolean isOpen(long tick)` · `enum Outcome { NONE, MOBILITY, SIGNATURE, UTILITY }`

**Semantik** (aus Spec Abschnitt 4):

| Eingang | Chord zu / abgelaufen | Chord offen |
|---|---|---|
| `sneakStart` | öffnet, liefert `NONE` | liefert `MOBILITY`, schließt |
| `attack` | liefert `NONE` | liefert `SIGNATURE`, schließt |
| `use` | liefert `NONE` | liefert `UTILITY`, schließt |

Nach jeder Auslösung **schließt** der Chord — sonst feuert die nächste Eingabe sofort erneut.
Ein Schleich-**Ende** kommt hier gar nicht vor: Der Automat kennt es nicht, und genau das ist
beabsichtigt (sonst wäre „Schleichen, Schleichen" unmöglich, weil man zum zweiten Beginn erst
loslassen muss).

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

`src/test/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockAbilityGestureTest.java`:

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static de.crazypandas.fmmbedrockbridge.bridge.BedrockAbilityGesture.Outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EliteMobs binds its three ability slots to an F-chord that Bedrock clients cannot produce.
 * This mirrors that chord onto sneak. The state machine is the only part with real logic, so
 * it is kept free of Bukkit types and covered here rather than in-game.
 */
class BedrockAbilityGestureTest {

    private static final long MAX = 40L;

    @Test
    void firstSneakOpensTheChordWithoutFiring() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);

        assertEquals(Outcome.NONE, gesture.sneakStart(100L), "opening must not fire an ability");
        assertTrue(gesture.isOpen(100L));
    }

    @Test
    void secondSneakInsideTheWindowFiresMobility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.MOBILITY, gesture.sneakStart(110L));
    }

    @Test
    void attackInsideTheWindowFiresSignature() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(105L));
    }

    @Test
    void useInsideTheWindowFiresUtility() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.UTILITY, gesture.use(105L));
    }

    @Test
    void firingClosesTheChordSoTheNextInputDoesNotFireAgain() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(105L));
        assertEquals(Outcome.NONE, gesture.attack(106L), "a closed chord must stay quiet");
        assertFalse(gesture.isOpen(106L));
    }

    @Test
    void inputAfterTheWindowExpiredDoesNotFire() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        // 41 ticks later: the cap (40) has passed.
        assertEquals(Outcome.NONE, gesture.attack(141L));
        assertFalse(gesture.isOpen(141L));
    }

    @Test
    void theWindowBoundaryItselfStillCounts() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.SIGNATURE, gesture.attack(140L), "tick 100+40 is still inside");
    }

    @Test
    void sneakingAgainAfterExpiryReopensRatherThanFiring() {
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        assertEquals(Outcome.NONE, gesture.sneakStart(200L), "expired chord reopens, never fires");
        assertTrue(gesture.isOpen(200L));
    }

    @Test
    void closeSilencesAnOpenChord() {
        // Used on death, world change and quit.
        BedrockAbilityGesture gesture = new BedrockAbilityGesture(MAX);
        gesture.sneakStart(100L);

        gesture.close();

        assertFalse(gesture.isOpen(101L));
        assertEquals(Outcome.NONE, gesture.attack(101L));
    }
}
```

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
cd /run/media/fabi/SSD/codeing/minecraft/FMMBedrockBridge
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o clean test
```

Erwartet: Compile-Fehler — `BedrockAbilityGesture` existiert nicht.

- [ ] **Schritt 3: Minimale Implementierung**

`src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockAbilityGesture.java`:

```java
package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Phase 7.4 — sneak-based replacement for EliteMobs' F-chord, which Bedrock clients cannot
 * produce (Bedrock has no offhand-swap control).
 *
 * <p>Deliberate deviation from EliteMobs: EM's {@code CHORD_WINDOW_TICKS = 12} is tuned for a
 * key <i>press</i>. Sneak is a <i>state</i>, and 0.6s is tight on a controller, so the chord
 * stays open while the player sneaks, capped at {@code maxOpenTicks}.
 *
 * <p>Sneak <i>end</i> is intentionally not an input: closing on it would make
 * "sneak, sneak" impossible, since the second start requires releasing first.
 *
 * <p>No Bukkit types here — the tick is passed in, so this is unit-testable without a server.
 */
public final class BedrockAbilityGesture {

    public enum Outcome { NONE, MOBILITY, SIGNATURE, UTILITY }

    private final long maxOpenTicks;

    private boolean open;
    private long openedAtTick;

    public BedrockAbilityGesture(long maxOpenTicks) {
        this.maxOpenTicks = maxOpenTicks;
    }

    /** Sneak start: opens the chord, or fires MOBILITY when one is already open. */
    public Outcome sneakStart(long tick) {
        if (isOpen(tick)) {
            close();
            return Outcome.MOBILITY;
        }
        open = true;
        openedAtTick = tick;
        return Outcome.NONE;
    }

    public Outcome attack(long tick) {
        return fire(tick, Outcome.SIGNATURE);
    }

    public Outcome use(long tick) {
        return fire(tick, Outcome.UTILITY);
    }

    private Outcome fire(long tick, Outcome outcome) {
        if (!isOpen(tick)) return Outcome.NONE;
        close();
        return outcome;
    }

    public boolean isOpen(long tick) {
        return open && tick - openedAtTick <= maxOpenTicks;
    }

    /** Drops an open chord — used on death, world change and quit. */
    public void close() {
        open = false;
    }
}
```

- [ ] **Schritt 4: Tests laufen lassen, grün erwartet**

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o clean test
```

Erwartet: `Tests run: 30, Failures: 0, Errors: 0` (21 vorhandene + 9 neue).

- [ ] **Schritt 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockAbilityGesture.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockAbilityGestureTest.java
git commit -m "feat(phase74): Chord-Zustandsautomat fuer die Bedrock-Eingabe"
```

---

## Aufgabe 2: Verfügbarkeitsprüfung ohne harte Kopplung

Diese Klasse ist die Brandmauer. Sie darf **keine** EM-Klasse importieren, sonst löst schon ihr
Laden die Verknüpfung aus, die sie verhindern soll.

**Dateien:**
- Neu: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/AdvancedCombatSupport.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/AdvancedCombatSupportTest.java`

**Schnittstellen:**
- Verbraucht: nichts
- Produziert: `static boolean isPresent()` · `static String missingReason()`

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * EliteMobs' advancedcombat package is internal and marked Alpha. The bridge must survive it
 * disappearing, so availability is probed by name and never by import.
 *
 * <p>In the test JVM EliteMobs is on the classpath as a provided dependency, but the pom still
 * points at 10.8.0, which predates the Advanced Combat System — so the probe must report false
 * here. That is exactly the "class is gone" case this guard exists for.
 */
class AdvancedCombatSupportTest {

    @Test
    void reportsAbsentWhenTheAlphaPackageIsNotOnTheClasspath() {
        assertFalse(AdvancedCombatSupport.isPresent(),
                "EliteMobs 10.8.0 has no advancedcombat package — the probe must not claim otherwise");
    }

    @Test
    void alwaysExplainsWhyItIsUnavailable() {
        assertNotNull(AdvancedCombatSupport.missingReason());
        assertFalse(AdvancedCombatSupport.missingReason().isBlank(),
                "the startup log needs a usable reason");
    }
}
```

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o clean test
```

Erwartet: Compile-Fehler — `AdvancedCombatSupport` existiert nicht.

- [ ] **Schritt 3: Minimale Implementierung**

```java
package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Phase 7.4 — probes whether EliteMobs' Advanced Combat System is present.
 *
 * <p><b>This class must never import anything from
 * {@code com.magmaguy.elitemobs.advancedcombat}.</b> That package is internal and Alpha; naming
 * it in an import would link it at class-load time and take the whole bridge down with it when
 * MagmaGuy renames something. Everything here goes through reflection on string names.
 *
 * <p>The lesson behind this: on 08.08.2026 a GeyserUtils build compiled against an older Geyser
 * API killed every entity spawn for Bedrock network-wide.
 */
public final class AdvancedCombatSupport {

    private static final String MODULE_CLASS =
            "com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule";
    private static final String SLOT_CLASS =
            "com.magmaguy.elitemobs.advancedcombat.classes.AbilitySlot";

    private static String missingReason = "not probed yet";

    private AdvancedCombatSupport() {
    }

    /** True only when both classes the hook needs can be resolved by name. */
    public static boolean isPresent() {
        for (String className : new String[]{MODULE_CLASS, SLOT_CLASS}) {
            try {
                Class.forName(className, false, AdvancedCombatSupport.class.getClassLoader());
            } catch (Throwable t) {
                missingReason = className + " not resolvable (" + t.getClass().getSimpleName() + ")";
                return false;
            }
        }
        missingReason = "";
        return true;
    }

    /** Why the last {@link #isPresent()} call said no — for the startup log. */
    public static String missingReason() {
        return missingReason;
    }
}
```

- [ ] **Schritt 4: Tests laufen lassen, grün erwartet**

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o clean test
```

Erwartet: `Tests run: 32, Failures: 0, Errors: 0`.

⚠️ Schlägt `reportsAbsentWhenTheAlphaPackageIsNotOnTheClasspath` fehl, wurde der pom bereits auf
EM 10.9.0 gezogen. Dann den Test umdrehen (`assertTrue`) und den Kommentar anpassen — der Test
prüft die Brandmauer, nicht eine bestimmte EM-Version.

- [ ] **Schritt 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/AdvancedCombatSupport.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bridge/AdvancedCombatSupportTest.java
git commit -m "feat(phase74): Verfuegbarkeitspruefung ohne harte Kopplung an EMs Alpha-Paket"
```

---

## Aufgabe 3: Anzeigetexte

**Dateien:**
- Neu: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/AbilityFeedback.java`
- Test: `src/test/java/de/crazypandas/fmmbedrockbridge/bridge/AbilityFeedbackTest.java`

**Schnittstellen:**
- Verbraucht: nichts
- Produziert: `static String forSuccess(String abilityName)` ·
  `static String forFailure(String failureReasonName)` — liefert `null`, wenn nichts angezeigt
  werden soll

⚠️ Der Parameter ist bewusst der **Enum-Name als String**, nicht der Enum selbst: sonst müsste
diese Klasse `AbilityFailureReason` importieren und stünde hinter der Brandmauer aus Aufgabe 2.

Die echten Werte von `AbilityFailureReason` (per `javap` an der 10.9.0-JAR): `NONE`,
`WRONG_THREAD`, `INVALID_PLAYER`, `INVALID_LEVEL`, `ABILITY_NOT_REGISTERED`, `NO_VALID_TARGET`,
`NO_CORPSE`, `PATH_BLOCKED`, `UNSAFE_DESTINATION`, `ENGINE_CLOSED`. **Cooldowns und Ressourcen
sind nicht darunter** — die meldet EM selbst.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bedrock cannot render EliteMobs' font-based combat HUD, so the bridge falls back to plain
 * action-bar text. Only player-relevant failures are shown; technical ones would be noise.
 */
class AbilityFeedbackTest {

    @Test
    void successShowsTheAbilityName() {
        assertEquals("§a▶ Schattenschritt", AbilityFeedback.forSuccess("Schattenschritt"));
    }

    @Test
    void playerRelevantFailuresGetAText() {
        assertEquals("§7Kein Ziel", AbilityFeedback.forFailure("NO_VALID_TARGET"));
        assertEquals("§7Weg blockiert", AbilityFeedback.forFailure("PATH_BLOCKED"));
        assertEquals("§7Weg blockiert", AbilityFeedback.forFailure("UNSAFE_DESTINATION"));
        assertEquals("§7Fähigkeit noch nicht freigeschaltet",
                AbilityFeedback.forFailure("INVALID_LEVEL"));
    }

    @Test
    void technicalFailuresStaySilent() {
        // These say nothing a player could act on.
        assertNull(AbilityFeedback.forFailure("WRONG_THREAD"));
        assertNull(AbilityFeedback.forFailure("ENGINE_CLOSED"));
        assertNull(AbilityFeedback.forFailure("INVALID_PLAYER"));
        assertNull(AbilityFeedback.forFailure("ABILITY_NOT_REGISTERED"));
        assertNull(AbilityFeedback.forFailure("NONE"));
    }

    @Test
    void unknownReasonsStaySilentInsteadOfLeakingEnumNames() {
        // EliteMobs is Alpha and may add values; a raw enum name must never reach a player.
        assertNull(AbilityFeedback.forFailure("SOME_FUTURE_REASON"));
        assertNull(AbilityFeedback.forFailure(null));
    }

    @Test
    void successTextSurvivesAnEmptyName() {
        // abilityName() can return null or blank if EM has no display name for the slot.
        assertTrue(AbilityFeedback.forSuccess("").endsWith("▶"));
        assertTrue(AbilityFeedback.forSuccess(null).endsWith("▶"));
    }
}
```

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o clean test
```

Erwartet: Compile-Fehler — `AbilityFeedback` existiert nicht.

- [ ] **Schritt 3: Minimale Implementierung**

```java
package de.crazypandas.fmmbedrockbridge.bridge;

/**
 * Phase 7.4 — action-bar text for Bedrock players.
 *
 * <p>Plain text on purpose: EliteMobs' graphical combat HUD renders through Java resource-pack
 * font providers, which Bedrock cannot resolve.
 *
 * <p>Takes the failure reason as a <b>string</b> rather than the enum, so this class stays on
 * the safe side of the firewall described in {@link AdvancedCombatSupport}.
 */
public final class AbilityFeedback {

    private AbilityFeedback() {
    }

    /** Confirmation line for a fired ability. */
    public static String forSuccess(String abilityName) {
        if (abilityName == null || abilityName.isBlank()) return "§a▶";
        return "§a▶ " + abilityName;
    }

    /**
     * Text for a failed attempt, or {@code null} when the player should see nothing —
     * technical and unknown reasons stay silent rather than leaking enum names.
     */
    public static String forFailure(String failureReasonName) {
        if (failureReasonName == null) return null;
        return switch (failureReasonName) {
            case "NO_VALID_TARGET" -> "§7Kein Ziel";
            case "PATH_BLOCKED", "UNSAFE_DESTINATION" -> "§7Weg blockiert";
            case "INVALID_LEVEL" -> "§7Fähigkeit noch nicht freigeschaltet";
            default -> null;
        };
    }
}
```

- [ ] **Schritt 4: Tests laufen lassen, grün erwartet**

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o clean test
```

Erwartet: `Tests run: 37, Failures: 0, Errors: 0`.

- [ ] **Schritt 5: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/AbilityFeedback.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bridge/AbilityFeedbackTest.java
git commit -m "feat(phase74): Anzeigetexte fuer die Bedrock-Faehigkeiten"
```

---

## Aufgabe 4: Der EliteMobs-Hook

Die **einzige** Klasse, die `advancedcombat` importieren darf. Sie wird nur erzeugt, wenn
`AdvancedCombatSupport.isPresent()` true ergeben hat.

Nicht unit-testbar (braucht einen laufenden EM-Kontext) — die Absicherung liegt darin, dass jeder
Aufruf gekapselt ist.

**Dateien:**
- Neu: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/AdvancedCombatHook.java`

**Schnittstellen:**
- Verbraucht: `BedrockAbilityGesture.Outcome` (Aufgabe 1), `AbilityFeedback` (Aufgabe 3)
- Produziert: `AdvancedCombatHook(Logger log)` · `boolean isInCombat(Player player)` ·
  `String fire(Player player, BedrockAbilityGesture.Outcome outcome)` — liefert den Anzeigetext
  oder `null`

⚠️ **Bewusste Abweichung von Spec Abschnitt 6.** Dort stehen drei Startprüfungen:
`Class.forName`, `AdvancedCombatSystemConfig.isEnabled()` und `AdvancedCombatModule.isInitialized()`.
Umgesetzt wird nur die erste beim Start — die anderen beiden **zur Laufzeit**, über den
`module == null`-Zweig in `fire(...)`. Grund: EliteMobs initialisiert sein Modul nicht garantiert
vor unserem `onEnable`, und ein Schalter kann im Betrieb umgelegt werden. Eine Startprüfung würde
die Phase in beiden Fällen fälschlich abschalten, bis der Server neu startet.

- [ ] **Schritt 1: Implementierung schreiben**

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import com.magmaguy.elitemobs.advancedcombat.AdvancedCombatModule;
import com.magmaguy.elitemobs.advancedcombat.abilities.AbilityResult;
import com.magmaguy.elitemobs.advancedcombat.classes.AbilitySlot;
import com.magmaguy.elitemobs.combatsystem.combattag.DungeonCombatRuntime;
import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Phase 7.4 — the only class allowed to touch EliteMobs' internal {@code advancedcombat}
 * package. Instantiate it ONLY after {@link AdvancedCombatSupport#isPresent()} returned true.
 *
 * <p>Every call into EliteMobs is wrapped: the package is Alpha and carries no API guarantee,
 * and a LinkageError must degrade this one feature rather than the whole bridge.
 */
public final class AdvancedCombatHook {

    private final Logger log;

    public AdvancedCombatHook(Logger log) {
        this.log = log;
    }

    /**
     * Whether the player is in combat or inside a dungeon/match — the bridge only arms the
     * sneak controls then, so ordinary sneaking never fires an ability.
     */
    public boolean isInCombat(Player player) {
        try {
            if (DungeonCombatRuntime.isEligiblePlayer(player)) return true;
            DungeonCombatRuntime runtime = DungeonCombatRuntime.getInstance();
            return runtime != null && runtime.isInCombat(player.getUniqueId());
        } catch (Throwable t) {
            FMMBedrockBridge.debugLog("[PHASE74] combat probe failed: " + t);
            return false;
        }
    }

    /**
     * Fires the ability bound to the outcome.
     *
     * @return action-bar text to show, or {@code null} for "say nothing"
     */
    public String fire(Player player, BedrockAbilityGesture.Outcome outcome) {
        AbilitySlot slot = toSlot(outcome);
        if (slot == null) return null;

        try {
            AdvancedCombatModule module = AdvancedCombatModule.get();
            if (module == null) return null;

            AbilityResult result = module.useAbility(player, slot);
            if (result == null) return null;

            if (result.successful()) {
                return AbilityFeedback.forSuccess(module.abilityName(player, slot));
            }
            String reason = result.failureReason() == null ? null : result.failureReason().name();
            FMMBedrockBridge.debugLog("[PHASE74] " + player.getName() + " " + slot + " failed: " + reason);
            return AbilityFeedback.forFailure(reason);
        } catch (Throwable t) {
            // Alpha package: degrade this feature, never the plugin.
            log.warning("[PHASE74] useAbility failed, disabling feedback for this attempt: " + t);
            return null;
        }
    }

    private static AbilitySlot toSlot(BedrockAbilityGesture.Outcome outcome) {
        return switch (outcome) {
            case MOBILITY -> AbilitySlot.MOBILITY;
            case SIGNATURE -> AbilitySlot.SIGNATURE;
            case UTILITY -> AbilitySlot.UTILITY;
            case NONE -> null;
        };
    }
}
```

- [ ] **Schritt 2: Bauen — und den erwarteten Fehlschlag verstehen**

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o clean test
```

⚠️ **Erwartet: Compile-Fehler**, solange der pom auf EM 10.8.0 steht — dort gibt es
`advancedcombat` noch nicht. **Das ist kein Planfehler, sondern der Beweis dafür, warum die
Brandmauer aus Aufgabe 2 existiert.**

Zum Weiterarbeiten die 10.9.0-JAR lokal bereitstellen:

```bash
# Die JAR liegt im Scratchpad der Analyse-Session; sonst von Modrinth laden:
#   https://modrinth.com/plugin/elitemobs/versions
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o install:install-file \
  -Dfile=/pfad/zu/EliteMobs-10.9.0.jar \
  -DgroupId=com.magmaguy -DartifactId=EliteMobs -Dversion=10.9.0 -Dpackaging=jar
```

Dann im `pom.xml` die EliteMobs-Version von `10.8.0` auf `10.9.0` ziehen (Zeile ~133).

⚠️ **Dieser pom-Schritt ist der einzige im Plan, der den geprüften Stack anfasst.** Er gehört mit
Fabi abgestimmt (siehe Aufgabe 6) — er koppelt die Bridge an eine EM-Version, die auf dem Server
noch nicht installiert ist.

- [ ] **Schritt 3: Erneut bauen, grün erwartet**

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o clean test
```

Erwartet: `BUILD SUCCESS`, `Tests run: 37`. Der Test aus Aufgabe 2 kippt jetzt — er erwartet
`isPresent() == false`. Ihn wie dort beschrieben auf `assertTrue` umstellen und den Kommentar
nachziehen.

- [ ] **Schritt 4: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/AdvancedCombatHook.java \
        src/test/java/de/crazypandas/fmmbedrockbridge/bridge/AdvancedCombatSupportTest.java pom.xml
git commit -m "feat(phase74): Hook auf EMs useAbility, gekapselt gegen das Alpha-Paket"
```

---

## Aufgabe 5: Der Bukkit-Listener

**Dateien:**
- Neu: `src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockAbilityListener.java`

**Schnittstellen:**
- Verbraucht: `BedrockAbilityGesture` (1), `AdvancedCombatHook` (4)
- Produziert: `BedrockAbilityListener(AdvancedCombatHook hook, long maxOpenTicks, boolean requireCombat, boolean feedback)`

**Gates, in dieser Reihenfolge** — die billigste Prüfung zuerst:
1. Floodgate: nur Bedrock-Spieler (Java behält EMs Original-Steuerung)
2. Kampf: `hook.isInCombat(player)`, wenn `requireCombat`
3. Chord-Zustand

- [ ] **Schritt 1: Implementierung schreiben**

```java
package de.crazypandas.fmmbedrockbridge.bridge;

import de.crazypandas.fmmbedrockbridge.FMMBedrockBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 7.4 — maps sneak-based input onto EliteMobs' three ability slots for Bedrock players.
 *
 * <p>EliteMobs binds them to an F-chord; Bedrock has no offhand-swap control, so those players
 * cannot trigger a single ability. See docs/upstream-bugs/em-advanced-combat-bedrock-input-lockout.md
 *
 * <p>Java players are never touched — they keep EliteMobs' original scheme.
 */
public final class BedrockAbilityListener implements Listener {

    private final AdvancedCombatHook hook;
    private final long maxOpenTicks;
    private final boolean requireCombat;
    private final boolean feedback;

    private final Map<UUID, BedrockAbilityGesture> gestures = new ConcurrentHashMap<>();

    public BedrockAbilityListener(AdvancedCombatHook hook, long maxOpenTicks,
                                  boolean requireCombat, boolean feedback) {
        this.hook = hook;
        this.maxOpenTicks = maxOpenTicks;
        this.requireCombat = requireCombat;
        this.feedback = feedback;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        // Only sneak STARTS count. Closing on sneak end would make "sneak, sneak" impossible,
        // because the second start requires releasing first.
        if (!event.isSneaking()) return;

        Player player = event.getPlayer();
        if (!armed(player)) return;

        dispatch(player, gestureFor(player).sneakStart(Bukkit.getCurrentTick()), null);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!armed(player)) return;

        BedrockAbilityGesture.Outcome outcome = gestureFor(player).attack(Bukkit.getCurrentTick());
        // Cancel the swing that opened the chord, otherwise the player also hits.
        dispatch(player, outcome, () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!armed(player)) return;

        BedrockAbilityGesture.Outcome outcome = gestureFor(player).use(Bukkit.getCurrentTick());
        // Cancel so the player does not also place a block or open a container.
        dispatch(player, outcome, () -> event.setCancelled(true));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gestures.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        closeFor(event.getEntity());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        closeFor(event.getPlayer());
    }

    private void closeFor(Player player) {
        BedrockAbilityGesture gesture = gestures.get(player.getUniqueId());
        if (gesture != null) gesture.close();
    }

    /** Bedrock-only, and only while EliteMobs considers the player to be in combat. */
    private boolean armed(Player player) {
        if (!FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) return false;
        return !requireCombat || hook.isInCombat(player);
    }

    private BedrockAbilityGesture gestureFor(Player player) {
        return gestures.computeIfAbsent(player.getUniqueId(),
                uuid -> new BedrockAbilityGesture(maxOpenTicks));
    }

    private void dispatch(Player player, BedrockAbilityGesture.Outcome outcome, Runnable consumeInput) {
        if (outcome == BedrockAbilityGesture.Outcome.NONE) return;
        if (consumeInput != null) consumeInput.run();

        String message = hook.fire(player, outcome);
        FMMBedrockBridge.debugLog("[PHASE74] " + player.getName() + " -> " + outcome);
        if (feedback && message != null) {
            player.sendActionBar(message);
        }
    }
}
```

- [ ] **Schritt 2: Bauen**

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o clean test
```

Erwartet: `BUILD SUCCESS`, `Tests run: 37` (der Listener braucht einen Server und hat keine
Unit-Tests — seine Logik steckt im getesteten Zustandsautomaten).

- [ ] **Schritt 3: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/bridge/BedrockAbilityListener.java
git commit -m "feat(phase74): Bukkit-Listener fuer die Schleich-Steuerung"
```

---

## Aufgabe 6: Verdrahtung, Config und Doku

**Dateien:**
- Ändern: `src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java`
- Ändern: `src/main/resources/config.yml`
- Ändern: `README.md`

**Schnittstellen:**
- Verbraucht: alles aus 1–5
- Produziert: `static boolean isPhase74Enabled()` · `static long getPhase74ChordMaxTicks()` ·
  `static boolean isPhase74RequireCombat()` · `static boolean isPhase74FeedbackEnabled()`

- [ ] **Schritt 1: Config-Block anhängen**

Ans Ende von `src/main/resources/config.yml`:

```yaml
# Phase 7.4 — Bedrock-Eingabe für EliteMobs' Advanced Combat System
#
# EliteMobs 10.9.0 bindet seine drei Fähigkeits-Slots an einen F-Chord
# (F,F / F+LMB / F+RMB). F ist der Offhand-Tausch — den gibt es auf Bedrock nicht,
# weder auf Controller noch auf Touch. Bedrock-Spieler können damit KEINE aktive
# Fähigkeit auslösen. Details: docs/upstream-bugs/em-advanced-combat-bedrock-input-lockout.md
#
# Diese Phase übersetzt den Chord auf Schleichen:
#   Schleichen, Schleichen  -> Mobility
#   Schleichen + Angriff    -> Signature
#   Schleichen + Benutzen   -> Utility
#
# Java-Spieler sind nicht betroffen und behalten EMs Originalsteuerung.
phase74:
  # Gesamtschalter. Wirkt nur, wenn EliteMobs das Advanced Combat System überhaupt
  # geladen hat — sonst schaltet sich die Phase beim Start selbst ab (siehe Log).
  bedrock-abilities: true

  # Wie lange der Chord nach dem Schleich-Beginn offen bleibt, in Ticks (20 = 1s).
  # EM selbst nutzt 12 Ticks, das ist aber auf einen Tastendruck gemünzt; Schleichen
  # ist ein Zustand und auf einem Controller träger. 40 = 2s ist der Startwert —
  # im Spieltest justieren.
  chord-max-ticks: 40

  # Wenn true, ist die Steuerung nur im Kampf bzw. in Dungeons scharf. Auf false
  # gesetzt löst auch normales Schleichen beim Bauen Fähigkeiten aus — nicht empfohlen.
  require-combat: true

  # Rückmeldung in der Actionbar (reiner Text — EMs grafisches HUD nutzt
  # Java-Font-Provider, die Bedrock nicht darstellen kann).
  feedback: true
```

- [ ] **Schritt 2: Config-Getter ergänzen**

In `FMMBedrockBridge.java`, hinter `getPhase71cDamageTimeoutTicks()`:

```java
    public static boolean isPhase74Enabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase74.bedrock-abilities", true);
    }

    public static long getPhase74ChordMaxTicks() {
        FMMBedrockBridge plugin = instance;
        return plugin != null ? plugin.getConfig().getLong("phase74.chord-max-ticks", 40L) : 40L;
    }

    public static boolean isPhase74RequireCombat() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase74.require-combat", true);
    }

    public static boolean isPhase74FeedbackEnabled() {
        FMMBedrockBridge plugin = instance;
        return plugin != null && plugin.getConfig().getBoolean("phase74.feedback", true);
    }
```

- [ ] **Schritt 3: Registrierung einhängen**

In `onEnable()`, direkt vor dem `FMMBridgeCommand cmd = ...`-Block:

```java
        // Phase 7.4 — sneak-based ability input for Bedrock players.
        // EliteMobs' F-chord cannot be produced by Bedrock clients; see the upstream report.
        if (floodgateAvailable && elitemobsAvailable && isPhase74Enabled()) {
            if (AdvancedCombatSupport.isPresent()) {
                try {
                    de.crazypandas.fmmbedrockbridge.bridge.AdvancedCombatHook hook =
                            new de.crazypandas.fmmbedrockbridge.bridge.AdvancedCombatHook(log);
                    getServer().getPluginManager().registerEvents(
                            new de.crazypandas.fmmbedrockbridge.bridge.BedrockAbilityListener(
                                    hook,
                                    getPhase74ChordMaxTicks(),
                                    isPhase74RequireCombat(),
                                    isPhase74FeedbackEnabled()),
                            this);
                    log.info("Phase 7.4: Bedrock ability input registered (sneak chord, max "
                            + getPhase74ChordMaxTicks() + " ticks, require-combat="
                            + isPhase74RequireCombat() + ")");
                } catch (Throwable t) {
                    // Alpha package: this feature may break, the plugin must not.
                    log.warning("Phase 7.4: registration failed, Bedrock ability input disabled. Cause: " + t);
                }
            } else {
                log.info("Phase 7.4: EliteMobs Advanced Combat System not available ("
                        + AdvancedCombatSupport.missingReason() + ") — Bedrock ability input off");
            }
        } else {
            log.info("Phase 7.4: NOT registered (floodgate=" + floodgateAvailable
                    + ", em=" + elitemobsAvailable + ", enabled=" + isPhase74Enabled() + ")");
        }
```

⚠️ `AdvancedCombatSupport` wird **ohne** vollqualifizierten Namen benutzt — dafür oben einen
Import ergänzen:

```java
import de.crazypandas.fmmbedrockbridge.bridge.AdvancedCombatSupport;
```

- [ ] **Schritt 4: Bauen und testen**

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
/usr/share/idea/plugins/maven-plugin/lib/maven3/bin/mvn -o clean test
```

Erwartet: `BUILD SUCCESS`, `Tests run: 37, Failures: 0, Errors: 0`.

- [ ] **Schritt 5: README ergänzen**

Diesen Abschnitt in `README.md` aufnehmen, hinter dem letzten Phasen-Abschnitt:

```markdown
### Phase 7.4 — Bedrock-Eingabe für EliteMobs' Klassen-Fähigkeiten

EliteMobs 10.9.0 bindet die drei Fähigkeits-Slots seines Advanced Combat System an einen
F-Chord (`F,F` / `F+LMB` / `F+RMB`). `F` ist der Offhand-Tausch — **den gibt es auf Bedrock
nicht**, weder auf Controller noch auf Touch. Bedrock- und Konsolenspieler können dort ohne
diese Phase keine einzige aktive Fähigkeit auslösen.

Die Bridge übersetzt den Chord auf Schleichen:

| Geste | Fähigkeit |
|---|---|
| Schleichen, Schleichen | Mobility |
| Schleichen + Angriff | Signature |
| Schleichen + Benutzen | Utility |

Scharf ist die Steuerung **nur im Kampf bzw. in Dungeons**, damit normales Schleichen beim
Bauen nichts auslöst. Java-Spieler sind nicht betroffen und behalten EMs Originalsteuerung.

**Voraussetzungen:** EliteMobs **10.9.0+** mit eingeschaltetem Advanced Combat System und
Floodgate. Fehlt eines davon, schaltet sich die Phase beim Start selbst ab und schreibt den
Grund ins Log — die übrige Bridge läuft normal weiter.

Konfiguration: Block `phase74` in der `config.yml`. Der Startwert für `chord-max-ticks` (40 =
2 s) ist bewusst großzügiger als EMs 12 Ticks und im Spieltest zu justieren.

- Design: `docs/specs/2026-09-11-bedrock-ability-input-design.md`
- Upstream gemeldet: `docs/upstream-bugs/em-advanced-combat-bedrock-input-lockout.md`
```

- [ ] **Schritt 6: Commit**

```bash
git add src/main/java/de/crazypandas/fmmbedrockbridge/FMMBedrockBridge.java \
        src/main/resources/config.yml README.md
git commit -m "feat(phase74): Verdrahtung, Config und Doku"
```

---

## Nach dem Plan: in-game abnehmen

Unit-Tests decken den Zustandsautomaten ab. **Nicht** abgedeckt und nur auf der Zielinstanz mit
einem echten Bedrock-Gerät prüfbar:

1. Löst Schleichen auf einem **Controller** sauber `PlayerToggleSneakEvent` aus? (Auf Bedrock ist
   Schleichen oft ein Toggle, kein Halten.)
2. Ist der Doppel-Schleich-Rhythmus treffbar? → `chord-max-ticks` justieren
3. Wird der Angriff wirklich unterdrückt, oder schlägt der Spieler zusätzlich zu?
4. Kollidiert Schleichen + Benutzen im Kampf mit Kisten und Blockplatzierung?
5. Java-Spieler: ist EMs Originalsteuerung unverändert?
6. Feuert eine Fähigkeit tatsächlich, und stimmt der Anzeigename?

⚠️ **Voraussetzung für den Test:** EliteMobs 10.9.0 muss auf der Instanz liegen **und**
`AdvancedCombatSystemConfig.isEnabled` eingeschaltet sein. Der gekoppelte Satz (Geyser · RPM ·
FMM · EM · Floodgate) bewegt sich gemeinsam — das Update ist Fabis Entscheidung und gehört auf
TestServer01, nicht direkt auf Survival01.
