#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
VERSION="8.14.3"
GRADLE="$ROOT/runtime/tools/gradle-$VERSION/bin/gradle"
if [[ ! -x "$GRADLE" ]]; then
  mkdir -p "$ROOT/runtime/tools"
  ZIP="$ROOT/runtime/tools/gradle-$VERSION-bin.zip"
  URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  if command -v curl >/dev/null; then curl -L "$URL" -o "$ZIP"; else wget "$URL" -O "$ZIP"; fi
  unzip -q -o "$ZIP" -d "$ROOT/runtime/tools"
  rm -f "$ZIP"
fi
"$GRADLE" --no-daemon clean build
NODE_JAR="$SCRIPT_DIR/build/libs/foundations_cluster_node-0.0.6a.R13.jar"
if [[ ! -f "$NODE_JAR" ]]; then
  echo "[Foundations Cluster] ERROR: Gradle returned success but Node R13 JAR was not produced: $NODE_JAR" >&2
  ls -la "$SCRIPT_DIR/build/libs" 2>/dev/null || true
  exit 3
fi
echo "[Foundations Cluster] Verified Node R13 artifact: $NODE_JAR"

if [[ "${1:-}" == "--install" ]]; then
  mkdir -p "$ROOT/server-template/mods"
  rm -f "$ROOT/server-template/mods"/foundations_cluster_node-*.jar
  cp build/libs/foundations_cluster_node-0.0.6a.R13.jar "$ROOT/server-template/mods/foundations_cluster_node-0.0.6a.R13.jar"
fi
