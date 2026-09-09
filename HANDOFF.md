# HANDOFF — FMMBedrockBridge

> Übergabe-Datei für Weiterarbeit an einem anderen PC. Stand: **2026-09-09**
> Branch: **`main`** — `feat/mc-26.2-readiness` ist am 16.08. gemerged (`--no-ff`).
> **Wieder ganz normal auf `main` weiterarbeiten.**
>
> ⚠️ **Seit 09.08. getrennt:** Server-/Betriebswissen steht in **`server-tools/SERVER-STATE.md`**
> (Arbeitskopie `~/SERVER-STATE.md` auf dem Host), weil dort eine zweite Claude-Instanz mitarbeitet.
> Diese Datei hier ist **nur noch Entwicklung**. Details in Abschnitt 0.
>
> ✅ **Auf der Entwicklungs-Seite ist nichts mehr offen.** Der Rest-Scope der Bridge
> (Combat-BossBar + HP-Nametag) ist auf dem 26.2-Stack **vollständig live verifiziert**:
> 7.1a am 14.08., 7.1b/7.1c am 16.08. per A/B-Test. Was übrig ist, liegt bei Fabi
> (Upstream-Reports) oder auf der Server-Seite — Abschnitt 3.

---

## 0. Server-Kontext — steht NICHT mehr hier

**Der Server-Stand hat eine eigene Datei bekommen: `server-tools/SERVER-STATE.md`**
(Arbeitskopie auf dem Host unter `~/SERVER-STATE.md`).

Grund: Auf dem Server läuft eine **zweite Claude-Instanz** als User `amp`, die Update-System,
Log-Analyse und Fehlersuche macht. Beide Seiten brauchen denselben Server-Stand, aber die
Server-Seite hat mit Branches, Builds und Plugin-Quellcode nichts zu tun. Also getrennt:

| | |
|---|---|
| **`HANDOFF.md`** (diese Datei) | Entwicklung: Branch, Build, Phasen, Bridge-Scope, Upstream-Drafts. **Nur Dev-Claude schreibt hier.** |
| **`SERVER-STATE.md`** | Betrieb: Instanzen, Plugin-Versionen, JVM-Zuordnung, offene Log-Fehler, Upgrade-Fortschritt, Änderungs-Log. **Beide Claudes schreiben dort.** |
| **`server-tools/server-CLAUDE.md`** (→ `~/.claude/CLAUDE.md`) | Dauerregeln für die Server-Seite. |

Die Server-Abschnitte, die früher hier standen (Audit 09.08., Netzwerk-Ausfall 08.08.,
Update-Runde 02.08., Abhängigkeitskette 26.2), sind vollständig nach `SERVER-STATE.md`
gewandert. Historie steckt in der Git-Historie dieser Datei.

### Was davon für die Entwicklung relevant bleibt

- **MC ist auf Jahresversionen umgestellt.** Kein 1.22 — die Linie läuft `1.21.11` → **26.1** →
  **26.2**, Format `YY.Drop.Hotfix`. Das Namensschema `-R0.1-SNAPSHOT` ist bei den 26.x-Artefakten
  weg (`26.2.build.87-stable`). Betrifft direkt `pom.xml` und `McVersions`.
- **`api-version` in `plugin.yml` bleibt bewusst `'1.21'`** — Mindestangabe, keine Zielangabe.
  Ein 1.21.x-Server würde `'26.2'` ablehnen; Paper 26.2 lädt `'1.21'` problemlos. FMM und
  EliteMobs machen es genauso. **Nicht "korrigieren".**
- **Der Ziel-Stack, gegen den die Bridge laufen muss** (Stand 09.09., aus `SERVER-STATE.md`):
  Paper **26.2-112**, Java 25, Geyser **2.11.1**, RPM **2.3.1**, FMM **2.11.2**, EM **10.8.1**,
  packetevents **2.13.0**.
  - ⚠️ **Der pom hängt bewusst eine Patch-Version zurück** (FMM 2.11.1 / EM 10.8.0). Beides sind
    `provided`-Deps und seit dem 14.08.-Build gab es keinen Plugin-Code-Change, also steht kein
    Redeploy an. **Entscheidung Fabi, 09.09.: nicht bumpen, nur notieren** — beim nächsten
    echten Build-Anlass mitziehen (Rezept in Abschnitt 3, „Build & Deploy").
  - **TestServer01 steht seit 08.09. 23:10 auf FMM 2.11.2 / EM 10.8.1, wurde aber nicht neu
    gestartet** — der Boot dazu fehlt noch (macht Fabi in AMP). Bis dahin läuft dort real
    weiterhin 2.11.1 / 10.8.0.
- ⏸️ **Der 26.2-Rollout ist seit 09.09. pausiert** (Fabi: „parke minigames01"). Fabi klärt mit dem
  Co-Owner einen **Umbau der Instanz-Struktur + Netzwerk-Einstellungen**. Stand: **4 von 6 Backends
  auf 26.2** (TestServer01, hub01, farmwelt01, Survival01), offen minigames01 + challenges01.
  **Dev-Claude stößt dafür keine Vorarbeit an.**
- **Die 14.08.-Bridge-JAR ist deployt** (`…-20260814-2101.jar`, gegen packetevents 2.13.0). Der
  frühere Merker „auf TestServer01 liegt noch der 10.07.-Build" ist damit erledigt.
- **Bedrock-Rendering ist nie im Log verifizierbar**, nur in-game.

---

## 🟢 FÜR CLAUDE: BOOTSTRAP (am Anfang JEDER Session zuerst lesen & ausführen)

Wenn der User sagt „lies die HANDOFF.md", dann:

0. **Rolle bewusst machen:** Du bist Minecraft-Java-Entwickler (Plugins + Mods). Bei jeder Aufgabe die passenden **Superpowers-Minecraft-Skills** laden (Einstieg: `superpowers:getting-started`) — siehe „Rolle & Arbeitsweise" in `CLAUDE.md`.
1. **Diese Datei komplett lesen** — Abschnitte 1–5 geben den Entwicklungs-Stand.
   Für den **Server**-Stand zusätzlich `server-tools/SERVER-STATE.md` lesen.
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
   ⚠️ **Vorher am NEUEN PC einmalig:** Der pom baut gegen **FMM 2.11.1 / EM 10.8.0**, die aber **NICHT im magmaguy-Maven-Repo publiziert** sind (Repo endet bei FMM 2.7.1 / EM 10.5.0). Ohne die JARs im lokalen `.m2` schlägt der Build mit „Could not find artifact" fehl. Einmalig die echten JARs vom Server holen + installieren — **PluginPortal benennt sie dort um, der Dateiname trägt ein `[PP] …`-Präfix**:
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-25-openjdk   # PFLICHT, sonst „Ungültige Klassendatei"
   scp 'amp@mc.crazypandas.de:.ampdata/instances/TestServer01/Minecraft/plugins/[PP] Free Minecraft Models (MODRINTH).jar' /tmp/fmm.jar
   scp 'amp@mc.crazypandas.de:.ampdata/instances/TestServer01/Minecraft/plugins/[PP] EliteMobs (MODRINTH).jar'              /tmp/em.jar
   mvn install:install-file -Dfile=/tmp/fmm.jar -DgroupId=com.magmaguy -DartifactId=FreeMinecraftModels -Dversion=2.11.1 -Dpackaging=jar
   mvn install:install-file -Dfile=/tmp/em.jar  -DgroupId=com.magmaguy -DartifactId=EliteMobs           -Dversion=10.8.0 -Dpackaging=jar
   ```
   ⚠️ **Auf dem Server liegt inzwischen FMM 2.11.2 / EM 10.8.1** — die JARs tragen also eine neuere
   Version als der `-Dversion=`-Wert oben. Das ist so gewollt (Patch-Drift, s. Abschnitt 0); die
   `-Dversion=`-Werte müssen zum pom passen, nicht zur JAR. Vor dem `install-file` mit
   `unzip -p <jar> plugin.yml | grep version` gegenprüfen, welche Version wirklich in der JAR steckt.
   (Falls kein `mvn` im PATH: IntelliJ bündelt eins unter `/usr/share/idea/plugins/maven/lib/maven3/bin/mvn`
   — Ordner je nach Version `maven` **oder** `maven-plugin`; `verify-both-apis.sh` probiert beide.)
   Danach läuft auch `-o` durch.
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
3. **Upstream-Reports einreichen** (nur Fabi — Zugang zu GitHub-Issues/Discord). Noch offen:
   **Props-als-Schwein (FMM)** — in 2.11.1 unverändert, `BedrockModeledEntity.java:64` führt
   weiter `.carrierEntityType(EntityType.PIG)` im Fake-Entity-Pfad.
   ~~Case-Sensitivity im RPM-Geyser-Bridge-Pfad~~ ✅ **von MagmaGuy in RPM 2.3.1 gefixt**
   (`BEDROCK_PACK_PATHS` probiert beide Schreibweisen, Kommentar *„Velocity's default data
   directory is lowercase"*) — Entwurf als erledigt markiert, nicht mehr einreichen.
4. **Beim nächsten echten Build-Anlass: pom auf FMM 2.11.2 / EM 10.8.1 mitziehen.** Aktuell
   bewusst eine Patch-Version zurück (s. Abschnitt 0). Kein eigener Anlass — nur nicht vergessen,
   wenn ohnehin gebaut wird.
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

## 4. Server / Deploy-Kontext (Erinnerung)

- SSH: `amp@mc.crazypandas.de` (`~/.ssh/id_ed25519`)
- **Vor jeder Remote-Aktion erst fragen.** Server-Restarts/Console macht Fabi selbst über AMP.
  JAR-Deploy via SCP ist ok. Deploy-Pfade: Memory `deployment_paths.md`.
- **Der Server-Stand selbst steht in `server-tools/SERVER-STATE.md`** — Instanzen, Versionen,
  JVM-Zuordnung, offene Log-Fehler, Änderungs-Log. Vor Server-Arbeit dort reinschauen.
- **Auf dem Server arbeitet eine zweite Claude-Instanz** (als `amp`, zuständig für Update-System
  und Fehlersuche). Analysieren dürfen beide parallel, **schreiben nur einer** — und wer schreibt,
  trägt es in `SERVER-STATE.md` ein.

## 5. Wichtige Doku-Dateien

**Im Repo (Entwicklung):**
- `CLAUDE.md` — Projektüberblick + Konventionen + Erkenntnisse
- `CLAUDE_SESSION.md` — detaillierter Session-Verlauf
- `README.md` — Status-Tabelle + Build/Deploy-Schritte
- `docs/upstream-bugs/` — Report-Entwürfe an MagmaGuy
- Memory-Index: `~/.claude/projects/.../memory/MEMORY.md`

**Server-Seite (in `server-tools/` versioniert, Arbeitskopien auf dem Host):**
- `SERVER-STATE.md` → `~/SERVER-STATE.md` — lebender Server-Stand, **beide Claudes**
- `server-CLAUDE.md` → `~/.claude/CLAUDE.md` — Dauerregeln für den Server-Claude
- `plugin-update-check.sh` → `~/plugin-update-check.sh`
- `backup-testserver.sh` → `~/backup-testserver.sh`

> Änderungen an den Server-Dateien im Repo **und** per `scp` auf den Host nachziehen, sonst
> driften die zwei Kopien.
