#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/reference/build/classes"
rm -rf "$ROOT/reference/build"
mkdir -p "$BUILD"
find "$ROOT/reference/src/main/java" "$ROOT/reference/src/test/java" -name '*.java' -print0 | xargs -0 javac --release 21 -d "$BUILD"
java -cp "$BUILD" com.foundations.cluster.transfer.RegionTransferCoordinatorSelfTest
java -cp "$BUILD" com.foundations.cluster.persistence.PersistentRegionTransferSelfTest
java -cp "$BUILD" com.foundations.cluster.replication.ReplicationProtocolSelfTest
java -cp "$BUILD" com.foundations.cluster.replication.AuthorityResultJournalSelfTest
