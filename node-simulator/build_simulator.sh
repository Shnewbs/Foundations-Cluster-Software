#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes build
find src/main/java -name '*.java' > build/sources.txt
javac --release 21 -encoding UTF-8 -d build/classes @build/sources.txt
jar --create --file build/FoundationsClusterNodeSimulator.jar --main-class com.foundations.cluster.simulator.Main -C build/classes .
rm build/sources.txt
