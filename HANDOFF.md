# HANDOFF — FMMBedrockBridge

> Übergabe-Datei für Weiterarbeit an einem anderen PC. Stand: **2026-06-25**
> Branch: `refactor/remove-phase72b` (gepusht, **kein** Merge nach main)

---

## 1. Wo wir gerade stehen (Git)

- **Aktiver Branch:** `refactor/remove-phase72b`
- **11 Commits vor `origin/main`**, als eigener Branch gepusht (nicht gemerged)
- Working tree war beim Push **sauber**
- Letzter lokaler Build-JAR: `target/FMMBedrockBridge-0.1.0-SNAPSHOT-20260613-2258.jar` (13. Juni)
- Build auf dem neuen PC zur Sicherheit nochmal laufen lassen: `mvn -o clean package -DskipTests`

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

1. **Refs aktualisieren & Changelogs lesen** — auf neuem PC in jedem `references/<repo>`:
   `git pull` (FMM, RPM, EliteMobs sind kritisch). Volle 2.8.0/2.1.0-Changelogs durchgehen.
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
