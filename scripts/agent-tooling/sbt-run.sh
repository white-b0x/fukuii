#!/bin/bash
# sbt-run.sh — run one or more sbt tasks with output logged to a file, never to the
# calling terminal/agent session.
#
# Usage: sbt-run.sh <log-name> <sbt-task> [<sbt-task2> ...]
#   log-name    basename (no extension) for the log file under .local/logs/
#   sbt-task    one or more sbt tasks, run as separate sbt CLI args (e.g. compile-all,
#               scalafmtAll) — same semantics as `sbt task1 task2`
#
# Example:
#   scripts/agent-tooling/sbt-run.sh ip-cl-a-batch4-scalafmt scalafmtAll
#   scripts/agent-tooling/sbt-run.sh ip-cl-a-batch5-compile compile-all
#
# Why this exists: running `sbt compile-all` directly through an agent's own shell
# tool previously froze the whole host (see memory: feedback_sbt_compile_operator_terminal).
# This script is meant to be invoked with the caller's shell tool in BACKGROUND mode —
# all sbt output goes straight to the log file, never streamed live — so the calling
# process is only notified on completion (exit code below), never blocked reading
# megabytes of interleaved sbt/dotc diagnostic output.
#
# Exit code matches sbt's exit code (0 = all given tasks succeeded).

set -uo pipefail

if [ "$#" -lt 2 ]; then
    printf 'Usage: %s <log-name> <sbt-task> [<sbt-task2> ...]\n' "$(basename "$0")" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$REPO_ROOT/.local/logs"
mkdir -p "$LOG_DIR"

LOG_NAME="$1"
shift
LOG_FILE="$LOG_DIR/${LOG_NAME}.log"

cd "$REPO_ROOT"

{
    printf '## sbt-run.sh started %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '## tasks: %s\n\n' "$*"
} > "$LOG_FILE"

sbt -no-colors -Dsbt.supershell=false "$@" >> "$LOG_FILE" 2>&1
SBT_EXIT=$?

{
    printf '\n## sbt-run.sh finished %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'EXIT CODE: %d\n' "$SBT_EXIT"
} >> "$LOG_FILE"

printf 'DONE log=%s exit=%d\n' "$LOG_FILE" "$SBT_EXIT"
exit "$SBT_EXIT"
