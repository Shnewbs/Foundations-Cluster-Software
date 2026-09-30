#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
bash "$root/cluster-manager/build_manager.sh"
mkdir -p "$root/cluster-manager/build/test-classes"
find "$root/cluster-manager/src/test/java" -name '*.java' -print0 | xargs -0 javac --release 21 -cp "$root/cluster-manager/build/FoundationsClusterManager.jar:$root/cluster-manager/lib/bcprov.jar" -d "$root/cluster-manager/build/test-classes"
java -cp "$root/cluster-manager/build/test-classes:$root/cluster-manager/build/FoundationsClusterManager.jar:$root/cluster-manager/lib/bcprov.jar" com.foundations.cluster.manager.R16SelfTest
java -cp "$root/cluster-manager/build/test-classes:$root/cluster-manager/build/FoundationsClusterManager.jar:$root/cluster-manager/lib/bcprov.jar" com.foundations.cluster.manager.R17SelfTest

java -cp "$root/cluster-manager/build/test-classes:$root/cluster-manager/build/FoundationsClusterManager.jar:$root/cluster-manager/lib/bcprov.jar" com.foundations.cluster.manager.R18SelfTest

java -cp "$root/cluster-manager/build/test-classes:$root/cluster-manager/build/FoundationsClusterManager.jar:$root/cluster-manager/lib/bcprov.jar" com.foundations.cluster.manager.R19SelfTest
java -cp "$root/cluster-manager/build/test-classes:$root/cluster-manager/build/FoundationsClusterManager.jar:$root/cluster-manager/lib/bcprov.jar" com.foundations.cluster.manager.NodeUpgradeSelfTest "$root"

java -cp "$root/cluster-manager/build/test-classes:$root/cluster-manager/build/FoundationsClusterManager.jar:$root/cluster-manager/lib/bcprov.jar" com.foundations.cluster.manager.R19HydrationSelfTest
java -cp "$root/cluster-manager/build/test-classes:$root/cluster-manager/build/FoundationsClusterManager.jar:$root/cluster-manager/lib/bcprov.jar" com.foundations.cluster.manager.SupportArchivesSelfTest
java -cp "$root/cluster-manager/build/test-classes:$root/cluster-manager/build/FoundationsClusterManager.jar:$root/cluster-manager/lib/bcprov.jar" com.foundations.cluster.manager.RestartAvailabilitySelfTest
