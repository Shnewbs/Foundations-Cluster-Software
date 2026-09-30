#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
core="${1:-$root/velocity/velocity.jar}"
jdk="${FOUNDATIONS_JDK25:?Set FOUNDATIONS_JDK25 to a full JDK 25}"
classes="$root/velocity-gateway/build/handshake-tests"
mkdir -p "$classes"
"$jdk/bin/javac" -proc:none -cp "$core" -d "$classes" "$root/tools/conduit-r15-patch/"*.java "$root/tests/HandshakeReplaySelfTest.java" "$root/tests/HandshakePacketIntegritySelfTest.java" "$root/tests/SeamlessPingSelfTest.java" "$root/tests/RegistryReplaySelfTest.java" "$root/tests/TagReplaySelfTest.java" "$root/tests/NeoForgeDeclarationSelfTest.java"
for test in HandshakeReplaySelfTest HandshakePacketIntegritySelfTest SeamlessPingSelfTest RegistryReplaySelfTest TagReplaySelfTest NeoForgeDeclarationSelfTest; do
  "$jdk/bin/java" --enable-native-access=ALL-UNNAMED -cp "$classes:$core" "com.velocitypowered.proxy.connection.backend.$test"
done
