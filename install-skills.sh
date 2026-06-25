#!/usr/bin/env bash
#
# install-skills.sh — installiert die projekt-eigenen Minecraft-Custom-Skills auf
# diesem PC, damit `superpowers:plugin-development`, `superpowers:geyser-bridge-development`
# usw. per Skill-Tool verfügbar sind.
#
# HINTERGRUND: Es gibt zwei Skill-Ebenen:
#   1. Allgemeine Superpowers-Skills (brainstorming, TDD, debugging) → offizielles
#      Plugin `superpowers@claude-plugins-official`, per /plugin installierbar. NICHT hier.
#   2. Die 7 Minecraft-Skills (getting-started, geyser-bridge-development, plugin-development,
#      resourcepack-conversion, minecraft-debugging, minecraft-server-admin, mod-porting)
#      → Custom-Skills, leben NICHT im Marketplace. Dieses Repo trägt sie in claude-skills/
#      und dieses Script spielt sie auf den jeweiligen PC.
#
# Voraussetzung: Superpowers-Plugin muss installiert sein (per /plugin im Claude-Code-CLI,
# Marketplace `anthropics/claude-plugins-official`). Sonst gibt es kein Ziel zum Injizieren.
#
# Verwendung:
#   bash install-skills.sh
#
# Idempotent — mehrfach ausführbar.

set -euo pipefail
cd "$(dirname "$0")"

REPO_SKILLS="claude-skills/minecraft-superpowers"
REPO_RESTORE="claude-skills/restore-minecraft-skills.sh"
CUSTOM_BASE="$HOME/.claude/custom-skills"

if [ ! -d "$REPO_SKILLS" ]; then
  echo "FEHLER: $REPO_SKILLS nicht gefunden — im Repo-Root ausführen." >&2
  exit 1
fi

# 1. Skills + Restore-Script in das maschinen-globale custom-skills-Backup spiegeln
mkdir -p "$CUSTOM_BASE/minecraft-superpowers"
cp -r "$REPO_SKILLS/." "$CUSTOM_BASE/minecraft-superpowers/"
cp "$REPO_RESTORE" "$CUSTOM_BASE/restore-minecraft-skills.sh"
chmod +x "$CUSTOM_BASE/restore-minecraft-skills.sh"
echo "Custom-Skills nach $CUSTOM_BASE/minecraft-superpowers/ gespiegelt."

# 2. In den aktiven Superpowers-Plugin-Cache injizieren (falls Plugin installiert)
PLUGIN_BASE="$HOME/.claude/plugins/cache/claude-plugins-official/superpowers"
if ls -1d "$PLUGIN_BASE"/*/skills >/dev/null 2>&1; then
  # restore-Script kopiert nur, wenn noch nicht vorhanden → für Force erst Marker entfernen
  for skills_dir in "$PLUGIN_BASE"/*/skills; do
    cp -r "$CUSTOM_BASE/minecraft-superpowers/." "$skills_dir/"
    echo "Injiziert nach: $skills_dir"
  done
  echo "Fertig. Starte Claude Code neu, damit die Skills in der Skill-Liste erscheinen."
else
  echo
  echo "HINWEIS: Superpowers-Plugin nicht gefunden unter $PLUGIN_BASE"
  echo "  → Erst im Claude-Code-CLI installieren:  /plugin  → marketplace anthropics/claude-plugins-official → superpowers"
  echo "  → Danach dieses Script erneut ausführen."
fi
