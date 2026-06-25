#!/bin/bash
# Restore Minecraft superpowers skills after plugin update

BACKUP_DIR="$HOME/.claude/custom-skills/minecraft-superpowers"
TARGET_BASE="$HOME/.claude/plugins/cache/claude-plugins-official/superpowers"

# Find the latest version directory
LATEST=$(ls -1d "$TARGET_BASE"/*/skills 2>/dev/null | tail -1)

if [ -z "$LATEST" ]; then
    echo "No superpowers skills directory found"
    exit 1
fi

# Check if minecraft skills are already present
if [ -d "$LATEST/plugin-development" ]; then
    exit 0
fi

# Copy minecraft skills
cp -r "$BACKUP_DIR"/* "$LATEST/"
echo "$(date): Restored Minecraft skills to $LATEST"
