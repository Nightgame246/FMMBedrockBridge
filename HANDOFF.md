# HANDOFF — FMMBedrockBridge

> Übergabe-Datei für Weiterarbeit an einem anderen PC. Stand: **2026-08-09**
> Branch: **`feat/mc-26.2-readiness`** — gepusht, **bewusst nicht gemerged**.
> `main` trägt nur einen Zeiger hierher (Commit `020aed4`).
>
> ⚠️ **Seit 09.08. getrennt:** Server-/Betriebswissen steht in **`server-tools/SERVER-STATE.md`**
> (Arbeitskopie `~/SERVER-STATE.md` auf dem Host), weil dort eine zweite Claude-Instanz mitarbeitet.
> Diese Datei hier ist **nur noch Entwicklung**. Details in Abschnitt 0.
>
> 🔎 **Offen auf der Entwicklungs-Seite:**
> 1. **In-Game-Test auf Bedrock von 7.1a/7.1b** (Combat-BossBar + HP-Nametag) — der letzte
>    offene Punkt am eigentlichen Bridge-Scope.
> 2. **Bridge-JAR neu bauen/deployen** — auf dem Server liegt der Build vom 10.07. (gegen
>    packetevents 2.12.x), auf TestServer01 läuft seit 09.08. packetevents 2.13.0.

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
- **Der Ziel-Stack, gegen den die Bridge laufen muss** (Stand 09.08.): Paper 1.21.10 bzw. 26.2,
  Java 25, Geyser 2.11.1, RPM 2.3.0, FMM 2.10.2, EM 10.7.3, packetevents 2.13.0.
- **Auf TestServer01 liegt noch die Bridge-JAR vom 10.07.**, gebaut gegen packetevents 2.12.x.
  Seit 09.08. läuft dort packetevents **2.13.0** → nach dem nächsten Neustart im Log prüfen, ob
  `[FMMBedrockBridge] PacketEvents: found — packet interception active` noch kommt. Wenn nicht:
  den 26.2-Branch-Build deployen, der baut bereits gegen 2.13.0.
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

- **Aktiver Branch:** `feat/mc-26.2-readiness`, HEAD **`e3374d9`** (09.08., mit origin sync,
  working tree sauber) — enthält alles aus `main` plus den 26.2-Build-Umbau, den
  `McVersions`-Bugfix und die Doku-Sessions vom 02.08., 08.08. und 09.08.
  `main` (`020aed4`) trägt nur einen Zeiger hierher. **Plugin-Code seit dem 02.08. unverändert** —
  die Sessions vom 08.08. und 09.08. waren Live-Diagnose, Server-Audit und Doku.
- **Session 09.08. kurz:** Server-Audit (Java-25-Blocker aufgelöst), drei Server-Fixes auf Fabis
  Anweisung (packetevents 2.13.0, 2 EM-Lua-Skripte, 1 FMM-Modell-Keyframe), und die **Trennung
  von Server- und Entwicklungs-Doku** — Details in `server-tools/SERVER-STATE.md`.
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

> **Alles, was Server-Betrieb ist** — Paper-26.2-Umstellung, Plugin-Beschaffung, JVM-Wechsel der
> übrigen Instanzen, Survival↔Test-Abgleich — steht ab 09.08. in **`SERVER-STATE.md`** und wird
> dort mit dem Server-Claude gemeinsam gepflegt. Hier nur noch, was am Plugin selbst zu tun ist.

### Bridge-Rest-Scope

1. **Combat-BossBar (7.1a) + HP-Nametag (7.1b) auf Bedrock prüfen** — nur in-game möglich, Logs
   reichen nicht. Bei EM 10.7.3 auf **Doppelung** mit den neuen NPC-Rollen-Tags achten.
   Das ist der letzte offene Punkt am eigentlichen Bridge-Scope.
2. **Upstream-Reports einreichen** (nur Fabi — Zugang zu GitHub-Issues/Discord). Zwei Entwürfe
   liegen fertig unter `docs/upstream-bugs/`: Props-als-Schwein (FMM) und
   Case-Sensitivity im RPM-Geyser-Bridge-Pfad.
3. **Symlink-Test** (optional, nach einem künftigen RPM-Update): `plugins/ResourcePackManager →
   resourcepackmanager` auf dem Proxy probeweise entfernen, Proxy neu. Bleibt `bridge ready with
   <n>` ≠ 0, ist der Upstream-Bug gefixt → Report zurückziehen.

### Build & Deploy

4. **Bridge gegen packetevents 2.13.0 neu bauen und deployen.** Auf TestServer01 liegt der Build
   vom **10.07.** (gegen 2.12.x), dort läuft seit 09.08. **2.13.0**. Der Branch-`pom` zeigt bereits
   auf 2.13.0 — `bash verify-both-apis.sh` deckt beide MC-Generationen ab. Nach dem nächsten
   Server-Neustart zuerst im Log prüfen, ob die alte JAR überhaupt stolpert:
   `[FMMBedrockBridge] PacketEvents: found — packet interception active`.
5. **Branch-Entscheidung:** `feat/mc-26.2-readiness` ist weiterhin **nicht gemerged**. Sinnvoller
   Zeitpunkt: wenn der 26.2-Build einmal real auf einem 26.2-Server gelaufen ist.

> **Kein Dep-Bump im pom auf FMM 2.10.2 / EM 10.7.3** (Entscheidung Fabi, 02.08.). Die Bridge baut
> weiter gegen 2.10.1 / 10.7.2 — beide APIs sind stabil, und die neuen JARs liegen ohnehin nicht im
> Maven-Repo (müssten einzeln per `install:install-file` eingespielt werden). Erst nachziehen, wenn
> ein konkreter API-Bedarf auftaucht.

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

**⚠️ Deploy-Merker (siehe Memory `native-bedrock-deploy-gotchas`):** Nach jedem RPM-Update den **Proxy zweimal neustarten** (Extension wird erst im ersten Boot geschrieben). Der Symlink `plugins/ResourcePackManager → resourcepackmanager` auf dem Proxy ist Pflicht, solange der Upstream-Bug offen ist.

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
