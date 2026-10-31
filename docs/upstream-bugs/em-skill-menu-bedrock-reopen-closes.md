# [EliteMobs] Skill menu closes on Bedrock when re-entering a weapon type

**Status:** ✅ gepostet von Fabi am 31.10.2026 — Antwort von MagmaGuy offen (MagmaGuys Discord, Suggestions/Bugs — gleicher Kanal wie
`em-advanced-combat-bedrock-input-lockout.md`).
**Version:** EliteMobs 10.9.12 (Modrinth), Paper 26.3, Velocity + Geyser 2.12.1 / Floodgate.
**Screenshots (lokal, nicht im Repo):** `references/screenshots/alt/2026-10-30_em-skillmenue-bedrock/`.

## Befund (Fabi, 30.10.)

- Gilde → Begrüßer-NPC (`GUILD_GREETER`) → Waffenauswahl (`SkillBonusMenu.openWeaponSelectMenu`, 36 Slots)
  → Waffe anklicken → Fähigkeiten-Menü (`openSkillSelectMenu`, 54 Slots) → Zurück-Pfeil → Waffenauswahl
  → **dieselbe oder eine andere Waffe erneut anklicken → das Menü schließt sich komplett.**
- Nur Bedrock. Auf Java derselbe Ablauf ohne Schließen. Kein Fehler im Server-Log.

## Belegt am Artefakt (`javap`, EliteMobs 10.9.12)

- `SkillBonusMenu$SkillBonusMenuEvents.onInventoryClick` ruft `openSkillSelectMenu` / `openWeaponSelectMenu` **direkt** im
  `InventoryClickEvent` auf — kein `runTask`, kein Tick Verzögerung.
- `openWeaponSelectMenu` erzeugt `createInventory(…, 36, …)`, `openSkillSelectMenu` `createInventory(…, 54, …)`.
- `onInventoryClose` entfernt das geschlossene Inventar aus `weaponSelectMenus` / `skillSelectMenus`.

## Vermutung (nicht am Geyser-Code belegt)

Wechselt das Menü die Größe (36 ↔ 54), muss Geyser den Bedrock-Container schließen und einen neuen öffnen. Die
Schließ-Bestätigung des Bedrock-Clients kommt verzögert und wird dem **neuen** Inventar zugeordnet → der Server schließt es.
Java braucht diesen Umweg nicht. Übliche Abhilfe in Plugins: das Folgemenü einen Tick später öffnen.

---

# Discord-Fassung — zum Kopieren

<!-- BEGIN -->
**[EliteMobs 10.9.12] Skill menu closes on Bedrock when you re-enter a weapon type**

Thanks again for the Bedrock class controls, they work great on our console players!

Found one more Bedrock-only thing in the skill menu:
1. Adventurers' Guild greeter → weapon select menu
2. Click a weapon → its skill menu opens
3. Back arrow → weapon select menu again
4. Click a weapon again → **the whole menu closes**

Java does the same steps without closing, and there's nothing in the server log. Setup: Paper 26.3, Velocity + Geyser 2.12.1 + Floodgate.

What I see in the 10.9.12 jar: `SkillBonusMenuEvents.onInventoryClick` opens the next menu directly inside the click event (`openSkillSelectMenu` / `openWeaponSelectMenu`, no `runTask`), and the two menus have different sizes (36 and 54 slots). My guess: when the size changes, Geyser has to close the Bedrock window and open a new one, and the client's delayed close lands on the freshly opened inventory, so the server closes it. Java doesn't need that round trip.

If that's it, opening the next menu one tick later (at least for Bedrock players) should fix it. Happy to test a build on real consoles again.
<!-- END -->
