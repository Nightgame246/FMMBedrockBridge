# HANDOFF — FMMBedrockBridge

> Übergabe-Datei für Weiterarbeit an einem anderen PC. Stand: **2026-06-25**
> Branch: `refactor/remove-phase72b` (gepusht, **kein** Merge nach main)

---

## 🟢 FÜR CLAUDE: BOOTSTRAP (am Anfang JEDER Session zuerst lesen & ausführen)

Wenn der User sagt „lies die HANDOFF.md", dann:

0. **Rolle bewusst machen:** Du bist Minecraft-Java-Entwickler (Plugins + Mods). Bei jeder Aufgabe die passenden **Superpowers-Minecraft-Skills** laden (Einstieg: `superpowers:getting-started`) — siehe „Rolle & Arbeitsweise" in `CLAUDE.md`.
1. **Diese Datei komplett lesen** — Abschnitte 1–6 geben den vollständigen Stand.
2. **Git-Stand prüfen & richtigen Branch sicherstellen:**
   ```bash
   git status -sb
   git checkout refactor/remove-phase72b   # falls noch nicht drauf
   git pull                                # falls am anderen PC schon weitergearbeitet wurde
   ```
3. **Reference-Repos vorhanden & aktuell?** (gitignored, eigene Repos — kommen NICHT mit `git clone`):
   ```bash
   bash setup-references.sh
   ```
   Klont fehlende Refs und zieht Upstream-Updates (FMM/RPM/EliteMobs sind kritisch).
4. **Build verifizieren** (optional, bei Bedarf):
   ```bash
   mvn -o clean package -DskipTests
   ```
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

- **Aktiver Branch:** `refactor/remove-phase72b` — vollständig mit `origin` synchron (0 ahead / 0 behind), HEAD `bd32c78`
- **15 Commits vor `origin/main`**, als eigener Branch gepusht (**kein** Merge nach main)
- Working tree **sauber**
- Letzter lokaler Build-JAR: `target/FMMBedrockBridge-0.1.0-SNAPSHOT-20260613-2258.jar` (13. Juni) — Code unverändert seitdem, nur Doku-Commits dazu
- Build auf dem neuen PC zur Sicherheit nochmal laufen lassen: `mvn -o clean package -DskipTests`

### Was in der Session 2026-06-25 dazukam (alles nur Doku/Tooling, kein Code)
- `HANDOFF.md` (diese Datei) + Bootstrap/Session-Ende-Protokoll
- `setup-references.sh` — klont/aktualisiert die 6 Reference-Repos (gitignored)
- `references/` auf aktuellen Upstream gebracht: **FMM 2.9.1, RPM 2.2.1, EM 10.7.1, BetterStructures 2.6.1** (FMM/EM brauchten Hard-Reset wegen force-gepushter History)
- `CLAUDE.md`: „Rolle & Arbeitsweise" (Minecraft-Java-Dev für Plugins+Mods, Superpowers-Skills aktiv nutzen) + Multi-PC-Workflow-Regel

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

---

## 3. Nächste Schritte (Priorität)

1. **Refs aktualisieren & Changelogs lesen** — `bash setup-references.sh` ausführen (klont/aktualisiert alle 6 Refs, handhabt force-gepushte History automatisch). Dann volle FMM-2.8.0/RPM-2.1.0-Changelogs + neuen RPM-Geyser-Bridge-Code (`resourcepackmanager-geyser-bridge/`) durchgehen.
2. **Entscheidungs-Test auf dem Server:** FMM 2.9.1 + RPM 2.2.1 + EM 10.7.1 frisch deployen, Bridge **deaktiviert**, und prüfen welche der noch verbleibenden Bridge-Features (BossBar/Nametag) nativ schon da sind.
   → Ergebnis bestimmt, ob die Bridge eingestampft oder auf einen Rest-Scope reduziert wird.
3. Falls Bridge noch nötig: 7.2b-Removal-Branch nach `main` mergen, dann gegen FMM 2.9.1 API neu bauen/testen.
4. Falls Bridge obsolet: Archiv-Tag setzen, README als „superseded by native FMM/RPM/EM Bedrock support" markieren.

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
