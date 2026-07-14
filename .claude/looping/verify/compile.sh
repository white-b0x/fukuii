#!/bin/sh
# compile.sh — gate: sbt compile-all exits 0
# Prints GATE:compile RESULT:PASS or GATE:compile RESULT:FAIL detail=<reason>
#
# Runs via sbt-run.sh (log-to-file, no live-streamed/captured output) instead of
# capturing full sbt output into a shell variable — see background-script-execution.md.
# Fixed 2026-07-02 (QUEUE.md SBT-RUN-LOOP chase item): this was the same invocation
# shape ($(...) capturing full sbt output) that caused the IP-CL-A host freeze.

set -eu

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || printf '/media/dev/2tb/dev/fukuii')"
SBT_RUN="$REPO_ROOT/scripts/agent-tooling/sbt-run.sh"

if [ ! -x "$SBT_RUN" ]; then
    printf 'GATE:compile RESULT:FAIL detail=sbt-run-script-not-found:%s\n' "$SBT_RUN"
    exit 1
fi

LOG_NAME="looping-gate-compile-$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$REPO_ROOT/.local/logs/${LOG_NAME}.log"

"$SBT_RUN" "$LOG_NAME" compile-all || {
    ERRORS=$(grep -c '\[error\]' "$LOG_FILE" 2>/dev/null || printf 'unknown')
    printf 'GATE:compile RESULT:FAIL detail=sbt-compile-all-failed:errors=%s\n' "$ERRORS"
    exit 1
}

printf 'GATE:compile RESULT:PASS\n'
