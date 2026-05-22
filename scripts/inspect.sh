#!/usr/bin/env bash
# Nexus Flow quality-gate script (Bash / Linux / macOS / Git-Bash)
# Usage: bash scripts/inspect.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

export JAVA_HOME="${JAVA_HOME:-/opt/jdk-25}"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$REPO_ROOT"

echo "========================================"
echo " Nexus Flow — quality gate"
echo "========================================"
echo "JAVA_HOME : $JAVA_HOME"
echo "Java      : $(java -version 2>&1 | head -1)"
echo ""

# Run full check (excluding SpotBugs — run separately when needed)
./gradlew :project:core:check \
    -x spotbugsMain -x spotbugsTest \
    --no-daemon \
    --warning-mode summary \
    2>&1 | tee /dev/stderr | tail -30

BUILD_STATUS=$?

echo ""
echo "========================================"
echo " Summary"
echo "========================================"

# PMD
PMD_XML="project/core/build/reports/pmd/main.xml"
if [[ -f "$PMD_XML" ]]; then
    VIOLATION_COUNT=$(grep -c '<violation' "$PMD_XML" 2>/dev/null || echo 0)
    echo "PMD violations (main):   $VIOLATION_COUNT"
else
    echo "PMD violations (main):   report not found"
fi

PMD_TEST_XML="project/core/build/reports/pmd/test.xml"
if [[ -f "$PMD_TEST_XML" ]]; then
    TEST_VIOLATION_COUNT=$(grep -c '<violation' "$PMD_TEST_XML" 2>/dev/null || echo 0)
    echo "PMD violations (test):   $TEST_VIOLATION_COUNT"
fi

# Spotless
SPOTLESS_REPORT="project/core/build/reports/spotless"
if find "$SPOTLESS_REPORT" -name "*.txt" 2>/dev/null | grep -q .; then
    echo "Spotless:                violations found — run './gradlew :project:core:spotlessApply'"
else
    echo "Spotless:                clean"
fi

# Tests
TEST_RESULTS_DIR="project/core/build/test-results/test"
if [[ -d "$TEST_RESULTS_DIR" ]]; then
    TOTAL=0; FAILED=0; ERRORS=0
    for xml in "$TEST_RESULTS_DIR"/*.xml; do
        [[ -f "$xml" ]] || continue
        t=$(grep -oP 'tests="\K[0-9]+' "$xml" | head -1 || echo 0)
        f=$(grep -oP 'failures="\K[0-9]+' "$xml" | head -1 || echo 0)
        e=$(grep -oP 'errors="\K[0-9]+' "$xml" | head -1 || echo 0)
        TOTAL=$((TOTAL + t)); FAILED=$((FAILED + f)); ERRORS=$((ERRORS + e))
    done
    echo "Tests:                   total=$TOTAL  failed=$FAILED  errors=$ERRORS"
else
    echo "Tests:                   no results found"
fi

echo "----------------------------------------"
if [[ $BUILD_STATUS -eq 0 ]]; then
    echo "BUILD: PASSED"
else
    echo "BUILD: FAILED (exit code $BUILD_STATUS)"
fi
echo "========================================"

exit $BUILD_STATUS
