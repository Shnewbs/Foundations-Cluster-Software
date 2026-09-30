#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
compiler="${FOUNDATIONS_JDK25:-}/bin/javac"
packager="${FOUNDATIONS_JDK25:-}/bin/jar"
if [[ ! -x "$compiler" || ! -x "$packager" ]]; then echo 'Set FOUNDATIONS_JDK25 to a full JDK 25.' >&2; exit 2; fi
core="$root/velocity/velocity.jar"
if [[ ! -f "$core" ]]; then echo 'Prepare the Conduit 1.8.0 runtime first (velocity/velocity.jar).' >&2; exit 2; fi
classes="$root/velocity-gateway/build/r19-classes"
mkdir -p "$classes" "$root/velocity-gateway/build/libs"
"$compiler" --release 21 -proc:none -cp "$core" -d "$classes" "$root/velocity-gateway/source/FoundationsVelocityGateway.java"
cp "$root/velocity-gateway/resources/velocity-plugin.json" "$classes/velocity-plugin.json"
"$packager" --create --file "$root/velocity-gateway/build/libs/foundations_velocity_gateway-0.0.6a.R19.jar" -C "$classes" .
bridge="$root/velocity-gateway/build/bridge-classes"
mkdir -p "$bridge"
"$compiler" -proc:none -cp "$core" -d "$bridge" "$root/tools/conduit-r15-patch/"*.java
cp "$bridge/com/velocitypowered/proxy/connection/backend/"*.class "$root/tools/conduit-r15-patch/"
echo 'Gateway R19 (Java 21 bytecode) and Conduit guarded replay overlay (Java 25) built.'
