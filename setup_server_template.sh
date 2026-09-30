#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
NEOFORGE_VERSION="21.1.250"
TEMPLATE="$PWD/server-template"
INSTALLER="neoforge-${NEOFORGE_VERSION}-installer.jar"
URL="https://maven.neoforged.net/releases/net/neoforged/neoforge/${NEOFORGE_VERSION}/${INSTALLER}"
mkdir -p "$TEMPLATE/.bootstrap" "$TEMPLATE/mods"
if [[ ! -f "$TEMPLATE/libraries/net/neoforged/neoforge/$NEOFORGE_VERSION/unix_args.txt" ]]; then
  if [[ ! -f "$TEMPLATE/.bootstrap/$INSTALLER" ]]; then
    echo "[Foundations Cluster] Downloading NeoForge $NEOFORGE_VERSION installer..."
    curl -fL "$URL" -o "$TEMPLATE/.bootstrap/$INSTALLER"
  fi
  echo "[Foundations Cluster] Installing NeoForge server template..."
  (cd "$TEMPLATE" && java -jar ".bootstrap/$INSTALLER" --installServer)
fi
[[ -f "$TEMPLATE/eula.txt" ]] || printf '# Review https://aka.ms/MinecraftEULA before changing this value.\neula=false\n' > "$TEMPLATE/eula.txt"
echo "[Foundations Cluster] Template installed. Review the Minecraft EULA, then set eula=true or use the dashboard Accept EULA button."
