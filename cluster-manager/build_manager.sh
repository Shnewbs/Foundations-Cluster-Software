#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes build
find src/main/java ../protocol/src/main/java -name '*.java' > build/sources.txt
javac --release 21 -encoding UTF-8 -cp "lib/bcprov.jar" -d build/classes @build/sources.txt
jar --create --file build/FoundationsClusterManager.jar --main-class com.foundations.cluster.manager.Main -C build/classes .
rm build/sources.txt
echo "Built cluster-manager/build/FoundationsClusterManager.jar"
