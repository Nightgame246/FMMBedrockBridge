# HANDOFF — FMMBedrockBridge

> Übergabe-Datei für Weiterarbeit an einem anderen PC. Stand: **2026-08-09**
> Branch: **`feat/mc-26.2-readiness`** — gepusht, **bewusst nicht gemerged**.
> `main` trägt nur einen Zeiger hierher (Commit `020aed4`).
>
> ⚠️ **Beim Wiedereinstieg zuerst Abschnitt 0 lesen** (aktueller Server-Stand, 26.2-Restliste),
> danach 0a (Vorfall 08.08.) + 0c (26.2-Kontext/Versionsschema).
>
> 🔎 **Zwei offene Stränge:**
> 1. **In-Game-Test auf Bedrock von 7.1a/7.1b** (Combat-BossBar + HP-Nametag) — Bridge-Scope.
> 2. **26.2-Vorbereitung:** PacketEvents 2.13.0 → GME-Altlast raus → Backends auf Java 25
>    (noch auf 1.21.10!) → dann erst Paper 26.2.

---

## 0. 🆕 Session 2026-08-09 — Server-Audit: Java-25-Blocker aufgelöst

Fabi hatte über **PluginPortal Premium** eine Update-Runde gefahren und um eine Bestandsaufnahme
gebeten. Read-only per SSH geprüft (keine Restarts, keine Änderungen).

### 🎉 Java 25 ist da — und läuft schon produktiv

Der als „eigentlicher Blocker" geführte Punkt ist **weitgehend erledigt**:

- **`/usr/lib/jvm/temurin-25-jdk-amd64` ist auf dem Host installiert.**
- **Proxy01 läuft bereits darauf** — `Java.JavaVersion=/usr/lib/jvm/temurin-25-jdk-amd64/bin/java`,
  Velocity 4.1.0-SNAPSHOT-14, seit dem 08.08. stabil. Java 25 ist damit **im Produktivbetrieb
  bewiesen**, nicht bloß installiert.
- **Alle Paper-Backends stehen weiter auf `jdk-21.0.5-oracle-x64`** (TestServer01, Survival01,
  hub01, farmwelt01, minigames01, challenges01). Umstellen = **ein Feld in AMP**
  (`Java.JavaVersion` in `<instanz>/MinecraftModule.kvp`), keine Installation mehr nötig.

⇒ Offen ist nur noch der **Plugin-Test unter Java 25 auf der Backend-Seite** — und der geht
**jetzt schon auf 1.21.10**, ohne Paper-Wechsel. Damit sind „Java 25" und „Paper 26.2" als
Risiken sauber getrennt.

### Bedrock-Kette: vollständig, letzter Boot sauber

| Komponente | Stand 09.08. | |
|---|---|---|
| Geyser | 2.11.1-b1210 (git-master-6e70ba2) | ✅ |
| RPM Backend / Proxy / GeyserBridge-Extension | **2.3.0** überall | ✅ |
| FMM / EM / BetterStructures | 2.10.2 / 10.7.3 / 2.6.3 | ✅ |
| Floodgate | b138 | ▲ b140 verfügbar (minor) |
| Symlink `ResourcePackManager → resourcepackmanager` | vorhanden (07.07.) | ✅ |
| Paper TestServer01 | 1.21.10-**130** | aktuell für 1.21.10 |

Letzter Proxy-Boot (08.08. 14:50, `logs/2026-08-08-4.log.gz`): **2** Extensions geladen
(GeyserModelEngineExtension + ResourcePackManagerGeyserBridge, GeyserUtils korrekt weg),
`bridge ready with 316 custom entity definitions`, **kein** `NoSuchFieldError` mehr.
Zum Vergleich im Boot davor (14:07, mit GeyserUtils): der Fehler ist da noch drin — der Fix
vom 08.08. ist damit im Log zweifelsfrei belegt.

**Die 40 ERROR-Zeilen im aktuellen Proxy-Log sind harmlos:** ausschließlich
`[initial connection] /23.176.184.152:<port>: read timed out` — ein scannender Host, kein
Serverproblem. TestServer01: **0 Fehler** im Log.

### Was PluginPortal aktualisiert hat (07./08.08.)

LuckPerms 5.5.71, EssentialsX + Spawn 2.22.0, FaweSchematicCloud, PluginPortal selbst (3.8.6);
manuell dazu Floodgate (Backend + Proxy), Geyser, ViaVersion/ViaBackwards 5.12.0.

### ⚠️ PacketEvents wurde NICHT mitgezogen

Steht weiter auf **2.12.1** (JAR vom 02.05.). Das ist der **letzte echte offene Punkt aus der
26.2-Kette**. Wichtig: **2.13.0 kann sofort rauf**, es braucht kein Paper 26.2 —
Modrinth-Abfrage vom 09.08. zeigt für `2.13.0+spigot` die Game-Versions **1.8.8 … 26.2**
(inkl. 1.21.10). PluginPortal verwaltet das JAR ⇒ muss **dort** angestoßen werden;
`plugin-update-check.sh` fasst PP-Plugins bewusst nicht an und meldet sie nur als
„fremdverwaltet".

### Altlast bestätigt: GeyserModelEngine

`GeyserModelEngine-1.0.3.jar` (13.02.) shaded **packetevents 2.11.2** neben dem separat
installierten 2.12.1 — zwei Versionen derselben Lib auf einem Classpath. Der Update-Check meldet
deshalb „packetevents 2.11.2 → 2.13.0" und zeigt auf die **GME-JAR**, nicht auf das PP-Plugin.
GME hookt nur ModelEngine, **nicht FMM** ⇒ für uns funktionslos. **Vor** der Java-25-/26.2-Runde
rauswerfen, sonst produziert genau das später einen unklaren Fehler.

### Paper 26.2 ist final verfügbar

`fill.papermc.io/v3` listet unter `26.2` die Builds **`26.2`** und `26.2-rc-2` — also kein RC
mehr. (Nebenbefund: der 1.21-Zweig ist inzwischen bei **1.21.11**, TestServer01 läuft auf 1.21.10.)

### Survival01 — erwartungsgemäß unangetastet

Dez-2025-Stand (EM 9.6.0, Paper 1.21.10-117, packetevents 2.10.0), nur Floodgate mitgezogen.
**Staging-Workflow, kein Fund** — siehe 0a.

---

## 0a. Session 2026-08-08 — Netzwerk-Ausfall gefixt, Props-Bug lokalisiert

### Vorfall: Bedrock sah netzwerkweit KEINE Entities (behoben)

Nach dem Geyser-Update auf **2.11.1-b1210** sahen Bedrock-Spieler gar keine Entities mehr.
Ursache war **nicht** RPM/FMM, sondern die alte **GeyserUtils-Geyser-Extension** (Build 01.02.2026,
`api: 2.4.1`): sie **ersetzt** Geysers AddEntity-Translator und greift auf
`Registries.ENTITY_DEFINITIONS` zu — in Geyser 2.11.1 entfernt. Jedes Entity-Spawn starb mit
`NoSuchFieldError`. Belegt: 0 Vorkommen vom 01.–07.08., 29.316 + 4.086 am 08.08.

**Fix (erledigt, verifiziert):** Extension umbenannt zu
`geyserutils-geyser-1.0-SNAPSHOT.jar.disabled-20260808-geyser2111`. Nach Restart 0 AddEntity-Fehler,
`bridge ready with 316`, GME lädt auch ohne GeyserUtils. Die Bridge nutzt GeyserUtils post-Pivot
nicht mehr.

> **Merker für künftige Geyser-Updates:** bei „Bedrock sieht nichts" **zuerst**
> `Geyser-Velocity/extensions/` auf alte Extensions prüfen, die Translator ersetzen — nicht bei
> RPM/FMM suchen.

### Aktueller Server-Stand (08.08. aus den Logs verifiziert)

| | Proxy01 | TestServer01 |
|---|---|---|
| Basis | **Velocity 4.1.0-SNAPSHOT** | **Paper 1.21.10**-130, **Java 21** |
| Geyser | **2.11.1-b1210** | — |
| Floodgate | 2.2.5-SNAPSHOT (b138) | 2.2.5-SNAPSHOT |
| RPM | **2.3.0** + GeyserBridge (14.07.) | **2.3.0** |
| FMM / EM / BS | — | **2.10.2** / **10.7.3** / **2.6.3** |
| PacketEvents | — | **2.12.1** (Bump auf 2.13.0 offen) |
| Via / ViaBackwards | 5.12.0-SNAPSHOT | — |
| GeyserUtils | **deaktiviert** | — |

⚠️ Zwei Abweichungen zum Stand vom 02.08.: **Geyser 2.11.1** (nicht 2.11.0-b1205) und
**Velocity 4.1.0-SNAPSHOT** (nicht 3.5.0) — Velocity war in der 26.2-Liste noch als „ungeprüft"
geführt, das ist jetzt überholt.

### Staging-Workflow (wichtig, war vorher nirgends dokumentiert)

**TestServer01 = Staging, Survival01 = Produktion.** Survival bleibt **bewusst** auf
Dezember-2025-Ständen (FMM 2.3.14, EM 9.6.0, RPM 1.7.0, BS 2.1.0), bis auf Test alles läuft — dann
wird der Plugin-Stand rübergezogen. **Alte Versionen auf Survival sind kein Fund und nicht zu
melden.** Folgerungen: auf Survival sehen Bedrock-Spieler planmäßig keine Custom-Models, und
`NetworkSync: previous poll is still running` / `merging 1 Bedrock zip(s) across 8 backend(s)` ist
**erwartetes Verhalten** — nur TestServer01 liefert überhaupt ein Bundle. Der Punkt ist damit
erledigt, kein Timeout-Bug.

### Props erscheinen auf Bedrock als Schwein (offen, upstream)

Mobs rendern korrekt, **Props als Schwein**. Das Schwein ist FMMs Träger-Entity
(`BedrockModeledEntity` → `carrierEntityType(EntityType.PIG)` im Fake-Entity-Pfad; DynamicEntity
bindet stattdessen den echten Mob). Systematisch ausgeschlossen: Java-Seite nimmt den richtigen
Zweig (Debug-Log, über die *fehlenden* Fallback-Zeilen bewiesen), Pack vollständig (315 bbmodels vs
316 Defs, kein Prop fehlt), Zuordnung kommt nicht zu spät sondern **gar nicht** an (die
Extension-Warnungen `loggedLateEntityReplacement` / `warnedUnregisteredSpawnDefinition` feuern nie).
Verdacht: `prepareEntitySpawn` geht im Fake-Pfad verloren — stumm geschluckt in `runBridgeSafely(...)`
oder übersprungen im `pluginProvider`-Early-Return von `FakeCustomEntityImpl.displayTo`.
**Nicht in der Bridge fixbar.** Details: `docs/upstream-bugs/fmm-props-render-as-pig-carrier-on-bedrock.md`

Diagnose-Werkzeug: `/fmm debug bedrock on|off` → Log-Stream `[FMM-BedrockDebug]`, sehr gesprächig,
danach wieder ausschalten.

### Upstream-Reports — liegen als Entwurf, NICHT eingereicht

Fabi weiß noch nicht, wo er sie einreicht (08.08.). Kanäle: GitHub-Issues der jeweiligen Repos
(`MagmaGuy/FreeMinecraftModels`, `MagmaGuy/ResourcePackManager`) oder MagmaGuys Discord, aus dem die
Builds kommen.

- `docs/upstream-bugs/fmm-props-render-as-pig-carrier-on-bedrock.md` — **neu**
- `docs/upstream-bugs/rpm-geyser-bridge-case-sensitive-pack-path.md` — **neu**, Symlink-Bug jetzt hart
  belegt (zwei Zeilen aus demselben Log: Plugin schreibt `resourcepackmanager/`, Extension liest
  `ResourcePackManager/`) ⇒ in 2.3.0 nachweislich noch drin
- `docs/upstream-bugs/rpm-black-shadows-custom-models.md` — **✅ erledigt**, von MagmaGuy gefixt, nie
  eingereicht, bleibt als Beleg

---

## 0c. NEU 2026-08-02: Minecraft ist auf jahresbasierte Versionen umgestellt

**Das ändert die Update-Planung grundlegend.** Mojang hat das `1.x`-Schema abgeschafft:
es gibt **kein 1.22**, sondern **26.1** („Tiny Takeover", März 2026) und **26.2**
(„Chaos Cubed", Juni 2026). Format `YY.Drop.Hotfix`. Höchste Version aktuell: **26.2**,
Paper-Artefakt `26.2.build.87-stable` (das `-R0.1-SNAPSHOT`-Namensschema ist weg).

### Die Abhängigkeitskette für MC 26.2

Stand **nach** dem Server-Audit vom 09.08. (siehe Abschnitt 0):

| Komponente | Für 26.2 nötig | Auf dem Server (09.08.) | |
|---|---|---|---|
| **Java-Laufzeit** | **25** (Paper 26.2 = Class-File 69) | **temurin-25 installiert**, Proxy01 läuft drauf | ✅ vorhanden |
| **Backends auf Java 25** | alle Paper-Instanzen | noch `jdk-21.0.5-oracle` | ⚠️ **umstellen + Plugins testen** |
| Paper | 26.2 (final, kein RC mehr) | 1.21.10-130 | offen |
| Geyser | 2.11.0 (26.2 gemerged 10.07.) | **2.11.1-b1210** | ✅ erledigt |
| RPM | 2.3.0 (wegen Geyser 2.11) | **2.3.0** (Backend **und** Proxy) | ✅ erledigt |
| FMM / EM | bauen schon gegen spigot-api 26.2 | **2.10.2 / 10.7.3** | ✅ ok |
| **PacketEvents** | **2.13.0** (deckt 1.8.8–26.2 ab) | 2.12.1 | ⚠️ **Bump offen** |
| Velocity | — | **4.1.0-SNAPSHOT-14 unter Java 25** | ✅ läuft produktiv |
| ProtocolLib, LibsDisguises, FAWE, Essentials, Skript, MythicMobs | unter **Java 25** ungeprüft | — | ❓ offen (auf 1.21.10 testbar) |

**Die Geyser-Sperre ist aufgelöst** (seit 02.08., `bridge ready with 316`), **und die Java-Sperre
ebenfalls** (09.08.): die JVM 25 liegt auf dem Host und trägt bereits den Proxy.

**Damit bleiben für MC 26.2 genau zwei Arbeitspakete:**
1. **PacketEvents 2.13.0** (über PluginPortal, geht sofort — kein Paper-Wechsel nötig)
2. **Backends von Java 21 auf temurin-25 umstellen und die Plugins darunter verifizieren** —
   bewusst **noch auf Paper 1.21.10**, damit JVM-Wechsel und MC-Versionswechsel nicht
   gleichzeitig passieren.

Erst danach Paper 26.2.

### Was der Branch `feat/mc-26.2-readiness` schon macht

Die Bridge ist auf 26.2 vorbereitet, **bleibt aber auf 1.21.x lauffähig** (bewusste Entscheidung,
damit sofort deploybar/testbar, statt bis zur Server-Umstellung blind zu sein):
- `pom.xml`: `paper-api` über `${paper.api.version}` = `26.2.build.87-stable`, PacketEvents → **2.13.0**,
  `maven.compiler.release=21` (Bytecode 21 → läuft auf Java 21 **und** 25)
- neues Maven-Profil **`legacy-1.21`** — kompiliert dieselben Quellen gegen 1.21.10.
  Ein einzelner Compile kann Doppel-Kompatibilität nicht beweisen, zwei schon.
- **`verify-both-apis.sh`** — fährt beide Durchläufe + prüft die Bytecode-Version des Artefakts
- **Bugfix `McVersions`**: der Parser brach bei nicht-numerischen Segmenten ab
  (`26.2.build.87-stable` → `NumberFormatException` → `false`) und hätte damit den
  Phase-7.3-Reroute auf einem 26.x-Server **still deaktiviert**. Mit Regressionstests belegt
  (Test fällt ohne den Fix). 16 Tests grün im Legacy-Durchlauf.
- `plugin.yml`: `api-version` bleibt **bewusst** `'1.21'` (Mindest-Angabe; ein 1.21.x-Server
  würde `'26.2'` ablehnen). Genau so machen es FMM/EM auch.

**Verifiziert (02.08., `bash verify-both-apis.sh`):** beide Durchläufe **BUILD SUCCESS,
je 16/16 Tests grün**, Bytecode-Version 65. Artefakt `…-20260802-1505.jar`.
Zusatzprüfungen: alle 25 Bukkit/Paper-Imports und alle riskanten Member
(`Attribute.MAX_HEALTH`, `BarColor`/`BarStyle`, `createBossBar`, `getCustomName`,
`getAttribute`, `getMaxHealth`) existieren in **beiden** Generationen; Deprecation-Diff
1.21.10 ↔ 26.2 **identisch** (4 Altlasten, nichts neu durch 26.2).

⚠️ **Am neuen PC einmalig:** `sudo pacman -S jdk25-openjdk` — `jre25-openjdk` reicht
**nicht**, die hat kein `javac`. Ohne JDK 25 schlägt der Default-Build fehl
(Paper 26.2 = Class-File 69, javac 21 kann sie nicht lesen).

**Offen auf dem Branch:**
- [ ] Nicht gemerged, nicht deployt. Das JAR läuft auch auf dem aktuellen 1.21.x —
      kann also jederzeit früh mitgetestet werden.
- [ ] Follow-up (kein Blocker): 4 deprecated Aufrufe ablösen — `getDescription`,
      `Damageable.getMaxHealth`, `InventoryView.getTitle`, `Nameable.getCustomName`.
      Bei `getCustomName` Vorsicht: hängt an der EM-Namenslogik (EVOKER-Boss-Fall).

---

## 0b. Update-Runde 02.08. abends — Server steht, Geyser-Bruch gefixt

Fabi hat FMM **2.10.2**, EM **10.7.3**, RPM **2.3.0** aufs Backend gespielt und Geyser auf
**2.11.0-b1205** hochgezogen. Dabei ging die Bedrock-Darstellung kaputt und wurde live repariert:

- **Symptom:** `NoClassDefFoundError: org/geysermc/geyser/entity/EntityDefinition` beim
  `ProxyInitializeEvent`; `bridge ready with …` kam gar nicht mehr, 89 Exceptions im Proxy-Log.
- **Ursache:** nur das **Backend** war aktualisiert, die **Proxy-Seite lief noch auf RPM 2.2.2**.
  Deren Bridge-Extension referenziert eine Klasse, die Geyser 2.11 entfernt hat.
- **Warum das Backend-Update den Proxy nicht mitzieht:** RPM schreibt nie in fremde
  Server-Verzeichnisse. Das Backend legt die Extension nur unter
  `plugins/ResourcePackManager/geyser-extension/` bereit und loggt einen Hinweis. Der
  Auto-Install läuft auf der **Proxy**-Seite — ein veraltetes Proxy-RPM installiert also
  weiter die alte Extension.
- **Fix:** auf Proxy01 **beide** Dateien ersetzt (Backups `.bak-20260802-1826`):
  `plugins/ResourcePackManager.jar` und
  `plugins/Geyser-Velocity/extensions/ResourcePackManager-GeyserBridge.jar`.
  **Ein** Neustart reichte → `bridge ready with 316 custom entity definitions`, 0 Exceptions.
- **RPM 2.3.0 ist eine Universal-JAR** (`plugin.yml` **und** `velocity-plugin.json`) — die alte
  `proxy-extension/`-Prozedur ist hinfällig, man kopiert dieselbe JAR auf den Proxy.
  `CLAUDE.md` ist entsprechend korrigiert.

**Offen aus dieser Runde (Stand 08.08.):**
- [ ] **Bedrock in-game verifizieren** — Mob-Rendering ist inzwischen bestätigt (08.08.), offen
      bleiben **Combat-BossBar (7.1a) → HP-Nametag (7.1b)**. Bei EM 10.7.3 auf **Doppelung** mit
      den neuen NPC-Rollen-Tags achten.
- [x] ~~Symlink-Bug an MagmaGuy melden~~ → Report-**Entwurf** liegt
      (`docs/upstream-bugs/rpm-geyser-bridge-case-sensitive-pack-path.md`), am 08.08. hart belegt.
      Der Symlink `ResourcePackManager → resourcepackmanager` bleibt bis zum Fix Pflicht.
      **Einreichen steht noch aus.**
- [x] ~~`NetworkSync: previous poll is still running` prüfen~~ → **erklärt und unkritisch**: nur
      TestServer01 liefert ein Bedrock-Bundle, die anderen 7 Backends planmäßig nichts
      (Staging-Workflow, siehe 0a). Kein Bug.

---

## 🟢 FÜR CLAUDE: BOOTSTRAP (am Anfang JEDER Session zuerst lesen & ausführen)

Wenn der User sagt „lies die HANDOFF.md", dann:

0. **Rolle bewusst machen:** Du bist Minecraft-Java-Entwickler (Plugins + Mods). Bei jeder Aufgabe die passenden **Superpowers-Minecraft-Skills** laden (Einstieg: `superpowers:getting-started`) — siehe „Rolle & Arbeitsweise" in `CLAUDE.md`.
1. **Diese Datei komplett lesen** — Abschnitte 1–6 geben den vollständigen Stand.
2. **Git-Stand prüfen & richtigen Branch sicherstellen:**
   ```bash
   git status -sb
   git checkout main                       # Refactor ist seit 2026-07-10 nach main gemerged
   git pull                                # falls am anderen PC schon weitergearbeitet wurde
   ```
3. **Reference-Repos vorhanden & aktuell?** (gitignored, eigene Repos — kommen NICHT mit `git clone`):
   ```bash
   bash setup-references.sh
   ```
   Klont fehlende Refs und zieht Upstream-Updates (FMM/RPM/EliteMobs sind kritisch).
3b. **Minecraft-Skills installiert?** (einmalig pro PC — die 7 Custom-Skills liegen NUR im Repo, nicht im Marketplace):
   ```bash
   bash install-skills.sh   # danach Claude Code neustarten
   ```
   Voraussetzung: Superpowers-Plugin per `/plugin` installiert (Marketplace `anthropics/claude-plugins-official`). Ohne diesen Schritt schlagen `superpowers:geyser-bridge-development` & Co. am neuen PC fehl.
4. **Build verifizieren** (optional, bei Bedarf):
   ```bash
   mvn -o clean package -DskipTests
   ```
   ⚠️ **Vorher am NEUEN PC einmalig:** Der pom baut gegen **FMM 2.10.1 / EM 10.7.2**, die aber **NICHT im magmaguy-Maven-Repo publiziert** sind (Repo endet bei FMM 2.7.1 / EM 10.5.0). Ohne die JARs im lokalen `.m2` schlägt der Build mit „Could not find artifact" fehl. Einmalig die echten JARs vom Server holen + installieren:
   ```bash
   scp amp@mc.crazypandas.de:'/home/amp/.ampdata/instances/TestServer01/Minecraft/plugins/{FreeMinecraftModels,EliteMobs}.jar' /tmp/
   mvn install:install-file -Dfile=/tmp/FreeMinecraftModels.jar -DgroupId=com.magmaguy -DartifactId=FreeMinecraftModels -Dversion=2.10.1 -Dpackaging=jar
   mvn install:install-file -Dfile=/tmp/EliteMobs.jar         -DgroupId=com.magmaguy -DartifactId=EliteMobs         -Dversion=10.7.2 -Dpackaging=jar
   ```
   (Falls kein `mvn` im PATH: IntelliJ bündelt eins unter `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn`.) Danach läuft auch `-o` durch.
5. Dann dem User den aktuellen Stand + die nächsten Schritte aus Abschnitt 3 zusammenfassen und auf seine Anweisung warten.

## 🔴 FÜR CLAUDE: BEIM SESSION-ENDE (Pflicht, damit PC-Wechsel funktioniert)

Bevor die Session endet bzw. wenn der User signalisiert, dass er aufhört / den PC wechselt:

1. **`git status` prüfen** — uncommittete Arbeit committen (nicht mergen, auf dem Feature-Branch bleiben).
2. **Diese HANDOFF.md aktualisieren:**
   - `Stand:`-Datum oben anpassen
   - Abschnitt 1 (Git-Stand) auf aktuellen Branch/Commit-Stand bringen
   - Abschnitt 3 (Nächste Schritte) so umschreiben, dass das **nächste Ich** (an irgendeinem PC) sofort weiß, wo es weitergeht — erledigte Punkte raus/abhaken, neue rein
   - Neue Erkenntnisse in den passenden Abschnitt
3. **`git push`** — sonst sieht der andere PC die Änderungen nicht.
4. Dem User bestätigen: „HANDOFF aktualisiert + gepusht, du kannst am anderen PC mit `lies die HANDOFF.md` weitermachen."

> Dieser Hin-und-Her-Workflow (PC A ↔ PC B) lebt davon, dass HANDOFF.md am Session-Ende IMMER aktuell + gepusht ist. Das ist die Single Source of Truth für den Arbeitsstand.

---

## 1. Wo wir gerade stehen (Git)

- **Aktiver Branch:** `feat/mc-26.2-readiness` (Stand 09.08.) — enthält alles aus `main` plus den
  26.2-Build-Umbau, den `McVersions`-Bugfix und die Doku-Sessions vom 02.08., 08.08. und 09.08.
  `main` (`020aed4`) trägt nur einen Zeiger hierher. **Plugin-Code seit dem 02.08. unverändert** —
  die Sessions vom 08.08. und 09.08. waren reine Live-Diagnose/Server-Audit + Doku.
- **Auf dem Server liegt noch das JAR vom 10.07.** (`FMMBedrockBridge.jar`, TestServer01) — der
  26.2-ready-Build vom Branch ist **nicht deployt**.
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

Die Update-Runde auf TestServer01 ist **durch** (02.08. + 08.08. + PP-Runde 07./08.08.):
FMM 2.10.2 + EM 10.7.3 + RPM 2.3.0 + Geyser 2.11.1 laufen, Mob-Rendering auf Bedrock bestätigt,
letzter Boot fehlerfrei (Audit 09.08., Abschnitt 0).

**Zwei Stränge, laufen unabhängig voneinander.**

### Strang A — Bridge-Rest-Scope (unverändert)

1. **Combat-BossBar (7.1a) + HP-Nametag (7.1b) auf Bedrock prüfen** — nur in-game möglich, Logs
   reichen nicht. Bei EM 10.7.3 auf **Doppelung** mit den neuen NPC-Rollen-Tags achten.
   Das ist der letzte offene Punkt am eigentlichen Bridge-Scope.
2. **Upstream-Reports einreichen** (nur Fabi — Zugang zu GitHub-Issues/Discord). Zwei Entwürfe
   liegen fertig unter `docs/upstream-bugs/`, siehe Abschnitt 0a.
3. **Symlink-Test** (optional, nach einem künftigen RPM-Update): `plugins/ResourcePackManager →
   resourcepackmanager` probeweise entfernen, Proxy neu. Bleibt `bridge ready with <n>` ≠ 0, ist
   der Bug gefixt → Report zurückziehen.
4. **Danach:** Plugin-Stand von TestServer01 auf Survival01 übertragen (Staging-Workflow, 0a).

### Strang B — 26.2-Vorbereitung (empfohlene Reihenfolge, Stand 09.08.)

Bewusst so geschnitten, dass **jeder Schritt einzeln verifizierbar** ist und der JVM-Wechsel
**nicht** mit dem MC-Versionswechsel zusammenfällt:

1. **PacketEvents 2.12.1 → 2.13.0** über PluginPortal. Geht sofort, 2.13.0 kann 1.8.8–26.2.
2. **`GeyserModelEngine-1.0.3.jar` entfernen** (Backend TestServer01). Shaded packetevents 2.11.2,
   hookt nur ModelEngine — für uns funktionslos, aber ein Classpath-Konflikt in Wartestellung.
   Kurz mit Fabi gegenchecken, ob ModelEngine-Modelle auf dem Testserver noch gebraucht werden.
3. **TestServer01 in AMP auf `temurin-25` umstellen — noch auf Paper 1.21.10.** Danach Log auf
   Plugin-Ladefehler prüfen (Kandidaten: ProtocolLib 5.4.1, LibsDisguises 11.0.18, FAWE 2.15.4,
   Skript 2.12.2, MythicMobs 5.10.1, ShopGUI+ 1.111.0). **Vorher Vollbackup**
   (`server-tools/backup-testserver.sh`).
4. **Erst dann Paper auf 26.2** (final verfügbar). Proxy läuft bereits auf Velocity 4.1.0/Java 25.
5. **Bridge-JAR aus `feat/mc-26.2-readiness` deployen** — auf dem Server liegt noch der Build vom
   **10.07.**. Das JAR läuft auch auf 1.21.x, kann also schon in Schritt 1–3 mitlaufen.

Optional nebenher: **Floodgate b138 → b140** (minor, Backend + Proxy im Gleichschritt).

> **Kein Dep-Bump im pom auf FMM 2.10.2 / EM 10.7.3** (Entscheidung Fabi, 02.08.). Die Bridge baut
> weiter gegen 2.10.1 / 10.7.2 — beide APIs sind stabil, und die neuen JARs liegen ohnehin nicht im
> Maven-Repo (müssten einzeln per `install:install-file` eingespielt werden). Erst nachziehen, wenn
> ein konkreter API-Bedarf auftaucht.

> ~~**Wenn danach auf MC 26.2 umgestellt werden soll**~~ — die alte 5-Schritt-Liste (Java 25
> klären → PacketEvents → Geyser/RPM → Paper → Bridge-JAR) ist durch **Strang B oben** ersetzt.
> Grund: Geyser/RPM stehen seit 02.08., und das Audit vom 09.08. hat gezeigt, dass Java 25
> längst installiert ist und den Proxy trägt. Übrig sind PacketEvents + der Backend-JVM-Wechsel.

**Danach / unabhängig:**
- **Waffen-Offset (KEIN Bridge-Feature):** legacy pre-1.21.4 `custom_model_data`-Item-Format re-exportieren ins 1.21.4+-Format (`assets/<namespace>/items/*.json`) — RPM-Backend-Warnung Z. 2594.
- ~~**Altlast aufräumen:** `GeyserModelEngine-1.0.3.jar`~~ → **hochgezogen zu Strang B, Schritt 2** (09.08. bestätigt: shaded packetevents 2.11.2 neben separatem 2.12.1; der Update-Check zeigt deshalb auf die GME-JAR, nicht auf das PP-Plugin).
- **Server-Claude:** Login steht noch aus (`ssh amp@mc.crazypandas.de` → `claude`). Notiz liegt unter `~/.claude/CLAUDE.md`; bei Änderungen an den Deploy-Regeln aus `server-tools/server-CLAUDE.md` per scp nachziehen.
- **Update-Check jederzeit:** `ssh amp@mc.crazypandas.de '~/plugin-update-check.sh TestServer01'`

**Erledigt 2026-07-10 (Deploy + Live-Verify):**
- ~~JAR (`…-20260710-1454.jar`) auf TestServer01 deployt~~ ✓ (SHA-verifiziert)
- ~~Server-Config auf sauberes neues Format gebracht~~ ✓ (tote `elite-items`/`maintenance` raus, `phase73` rein; alte als `config.yml.bak-20260710` gesichert)
- ~~Bridge-Boot geprüft~~ ✓ (alle Subsysteme registriert, FMM/Floodgate found, **0 Exceptions**)
- ~~**BossBar/Nametag Live-Verify MIT aktiver Bridge**~~ ✓ **Fabi bestätigt in-game: Combat-BossBar + HP-Nametag auf Bedrock „sah alles gut aus".** Rest-Scope (7.1a/7.1b) funktioniert auf FMM 2.10.1 + RPM 2.2.2 + EM 10.7.2.
- ~~Branch nach main mergen~~ ✓ (`--no-ff`, Backup-Tag `backup/pre-72b-merge-main` gesetzt, Docs nachgezogen, gepusht).
**Erledigt 2026-07-08:** ~~Rebuild gegen FMM 2.10.x API~~ ✓ (BUILD SUCCESS, 13 grün) · ~~In-Game-Animation~~ ✓ nativ · ~~BossBar/Nametag-Frage~~ ✓ geklärt (Feature-Gap) · ~~Lag-Verdacht~~ ✓ lokales Internet · ~~Grundsatzentscheidung~~ ✓ Bridge bleibt.

**⚠️ Deploy-Merker (siehe Memory `native-bedrock-deploy-gotchas`):** Nach jedem RPM-Update den **Proxy zweimal neustarten** (Extension wird erst im ersten Boot geschrieben). Der Symlink `plugins/ResourcePackManager → resourcepackmanager` auf dem Proxy ist Pflicht, solange der Upstream-Bug offen ist.

---

## 4. Server / Deploy-Kontext (Erinnerung)

- Proxy: Velocity (Hetzner) · Backend: Paper über AMP · Geyser+Floodgate auf Proxy, Floodgate auch Backend
- SSH: `amp@mc.crazypandas.de` (`~/.ssh/id_ed25519`)
- **Vor jeder Remote-Aktion erst fragen.** Server-Restarts/Console macht Fabi selbst über AMP. JAR-Deploy via SCP ist ok.
- Deploy-Pfade siehe CLAUDE.md + Memory `deployment_paths.md`

## 5. Wichtige Doku-Dateien im Repo
- `CLAUDE.md` — Projektüberblick + Server-Setup + Erkenntnisse
- `CLAUDE_SESSION.md` — detaillierter Session-Verlauf (zuletzt 14. Juni)
- `README.md` — Status-Tabelle + Deploy-Schritte
- Memory-Index: `~/.claude/projects/.../memory/MEMORY.md`
