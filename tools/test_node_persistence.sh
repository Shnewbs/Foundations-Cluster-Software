#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/minecraft-agent/build/persistence-test-classes"
mkdir -p "$out"
javac --release 21 -d "$out" "$root/minecraft-agent/src/main/java/com/foundations/cluster/node/AuthorityHydration.java" "$root/tests/AuthorityHydrationSelfTest.java"
java -cp "$out" com.foundations.cluster.node.AuthorityHydrationSelfTest
