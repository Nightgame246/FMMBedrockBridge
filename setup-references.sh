#!/usr/bin/env bash
#
# setup-references.sh — klont/aktualisiert die MagmaGuy/Geyser Reference-Repos.
#
# Diese Repos sind NICHT Teil des Bridge-Repos (gitignored, eigene Git-Repos).
# Sie dienen Claude Code als Quellcode-Referenz. Regelmäßig neu ausführen, um
# Upstream-Updates zu ziehen (FMM/RPM/EliteMobs sind die kritischen Deps).
#
# Verwendung:
#   ./setup-references.sh
#
# Bei force-gepushter Upstream-History (kommt bei MagmaGuy vor) macht das Script
# einen Hard-Reset auf den Remote-Stand — die references/ enthalten keine eigene
# Arbeit, daher ist das verlustfrei.

set -euo pipefail

# Ins Verzeichnis dieses Scripts wechseln (= Repo-Root)
cd "$(dirname "$0")"
mkdir -p references

# repo-verzeichnis  branch  url
REPOS=(
  "FreeMinecraftModels master https://github.com/MagmaGuy/FreeMinecraftModels.git"
  "ResourcePackManager master https://github.com/MagmaGuy/ResourcePackManager.git"
  "EliteMobs           master https://github.com/MagmaGuy/EliteMobs.git"
  "BetterStructures    master https://github.com/MagmaGuy/BetterStructures.git"
  "GeyserUtils         main   https://github.com/zimzaza4/GeyserUtils.git"
  "GeyserModelEngine   main   https://github.com/zimzaza4/GeyserModelEngine.git"
)

for entry in "${REPOS[@]}"; do
  read -r name branch url <<<"$entry"
  dir="references/$name"
  echo "===== $name ($branch) ====="

  if [ ! -d "$dir/.git" ]; then
    git clone --branch "$branch" "$url" "$dir"
    continue
  fi

  git -C "$dir" fetch --quiet origin
  if git -C "$dir" merge-base --is-ancestor HEAD "origin/$branch" 2>/dev/null; then
    # Fast-forward möglich
    git -C "$dir" checkout --quiet "$branch"
    git -C "$dir" merge --ff-only --quiet "origin/$branch"
  else
    # Divergiert (Upstream force-push) -> hart auf Remote-Stand setzen
    echo "  Upstream divergiert — Hard-Reset auf origin/$branch"
    git -C "$dir" checkout --quiet "$branch" 2>/dev/null || git -C "$dir" checkout --quiet -B "$branch" "origin/$branch"
    git -C "$dir" reset --hard --quiet "origin/$branch"
  fi

  git -C "$dir" log -1 --format='  HEAD: %h %s' | cut -c1-100
done

echo
echo "Fertig. Alle Reference-Repos sind auf dem aktuellen Upstream-Stand."
