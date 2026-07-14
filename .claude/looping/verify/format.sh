#!/bin/sh
# format.sh — gate: scalafmt check passes (no unformatted files)
# Uses sbt scalafmtCheck (verify without writing).
# Prints GATE:format RESULT:PASS or GATE:format RESULT:FAIL detail=<reason>
#
# Note: sbt task name confirmed as scalafmtCheck in build.sbt; if the build
# renames it, update here. See open assumption in DISCOVERY.md.
#
# Runs via sbt-run.sh (log-to-file, no live-streamed/captured output) instead of
# capturing full sbt output into a shell variable — see background-script-execution.md.
# Fixed 2026-07-02 (QUEUE.md SBT-RUN-LOOP chase item), same class of fix as compile.sh.

set -eu

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || printf '/media/dev/2tb/dev/fukuii')"
SBT_RUN="$REPO_ROOT/scripts/agent-tooling/sbt-run.sh"

if [ ! -x "$SBT_RUN" ]; then
    printf 'GATE:format RESULT:FAIL detail=sbt-run-script-not-found:%s\n' "$SBT_RUN"
    exit 1
fi

LOG_NAME="looping-gate-format-$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$REPO_ROOT/.local/logs/${LOG_NAME}.log"

"$SBT_RUN" "$LOG_NAME" scalafmtCheck || {
    UNFORMATTED=$(grep -E 'not formatted|error' "$LOG_FILE" | head -5 || printf 'see output')
    printf 'GATE:format RESULT:FAIL detail=scalafmt-violations-detected\n'
    printf '%s\n' "$UNFORMATTED"
    exit 1
}

printf 'GATE:format RESULT:PASS\n'
