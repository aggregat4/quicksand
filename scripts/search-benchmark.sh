#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"

mvn -q -DskipTests package

exec java --enable-native-access=ALL-UNNAMED -cp "target/quicksand.jar:target/libs/*" \
  net.aggregat4.quicksand.tools.SearchPerformanceBenchmark \
  "$@"
