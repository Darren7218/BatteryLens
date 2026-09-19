#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_classes_dir=$(mktemp -d)
trap 'rm -rf "$test_classes_dir"' EXIT
javac -d "$test_classes_dir" app/src/main/java/com/darren/batterylens/MeasurementRules.java tests/MeasurementRulesTest.java
java -cp "$test_classes_dir" com.darren.batterylens.MeasurementRulesTest
