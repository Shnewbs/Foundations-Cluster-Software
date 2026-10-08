#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
compiler="${FOUNDATIONS_JDK25:-}/bin/javac"
packager="${FOUNDATIONS_JDK25:-}/bin/jar"
if [[ ! -x "$compiler" || ! -x "$packager" ]]; then echo 'Set FOUNDATIONS_JDK25 to a full JDK 25.' >&2; exit 2; fi
core="$root/velocity/velocity.jar"
if [[ ! -f "$core" ]]; then echo 'Prepare the Conduit 1.8.0 runtime first (velocity/velocity.jar).' >&2; exit 2; fi
mkdir -p "$root/velocity-gateway/build/libs"
stage="$(mktemp -d "$root/velocity-gateway/build/gateway-XXXXXXXX")"
trap 'rm -rf -- "$stage"' EXIT
classes="$stage/plugin"
mkdir -p "$classes"
"$compiler" --release 21 -encoding UTF-8 -proc:none -cp "$core" -d "$classes" "$root/velocity-gateway/source/FoundationsVelocityGateway.java"
cp "$root/velocity-gateway/resources/velocity-plugin.json" "$classes/velocity-plugin.json"
"$packager" --create --file "$stage/foundations_velocity_gateway-0.0.6a.R19.jar" -C "$classes" .
bridge="$stage/bridge"
mkdir -p "$bridge"
"$compiler" --release 25 -encoding UTF-8 -proc:none -cp "$core" -d "$bridge" "$root/tools/conduit-r15-patch/"*.java
cp "$root/tools/conduit-r15-patch/GPL-3.0.txt" "$bridge/GPL-3.0.txt"
"$packager" --create --file "$stage/foundations_conduit_bridge-0.0.6a.R19.jar" -C "$bridge" .
mv -f "$stage/"*.jar "$root/velocity-gateway/build/libs/"
echo 'Gateway R19 (Java 21 bytecode) and Conduit guarded replay overlay (Java 25) built.'
