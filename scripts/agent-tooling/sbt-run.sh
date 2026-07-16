#!/bin/bash
# sbt-run.sh — run one or more sbt tasks with output logged to a file, never to the
# calling terminal/agent session.
#
# Usage: sbt-run.sh <log-name> <sbt-task> [<sbt-task2> ...]
#   log-name    basename (no extension) for the log file under .local/logs/
#   sbt-task    one or more sbt tasks, given as separate CLI args (e.g. compile-all,
#               scalafmtAll) for caller convenience — internally joined into a single
#               "; task1; task2" command string before being handed to sbt (see below)
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
# sbtn multi-arg-drop quirk (verified empirically, sbt 2.0.2 / sbtn 2.0.0-69fa1968): passing
# multiple separate CLI args straight through to `sbt` (e.g. `sbt task1 task2`) does NOT run
# them as two sequential commands the way sbt 1's shell did — the thin client instead
# concatenates them with a bare space into ONE command string ("task1 task2"), which either
# silently drops everything after the first parseable token or fails outright with
# "Expected whitespace character". The only reliable fix is to join every task arg ourselves
# into a single semicolon-delimited command (`; task1; task2`) and pass THAT as one CLI arg —
# confirmed to execute both tasks correctly. Do not pass multiple task args through unjoined.
#
# --- Stale-detached-server hardening (2026-07-16) --------------------------------
# Incident: a long-lived detached sbt server (left running from a prior session)
# answered clean/compile/testCompile requests with fast `[success]` while doing no
# real recompilation — the build's actual output tree never changed. Root cause:
# sbt's persistent server does not reload build.sbt/project/*.scala/build.properties
# changes on its own; a server that has been sitting since before the last
# build-definition edit is running a stale settings graph. See
# .agents/protocols/process/background-script-execution.md for the full incident
# writeup. Two guards below close this:
#   1. Before running: if the registered server (project/target/active.json) predates
#      the newest build-definition file, kill it so sbt starts fresh and reloads.
#   2. After running: if the task list included a `clean` task (which invalidates ALL
#      cached compile state, so a subsequent compile can never legitimately be a
#      no-op) and sbt exited 0, verify something under target/ actually changed. If
#      not, this is a hollow success — exit 97 instead of 0, with a loud banner in
#      the log. This check is scoped to clean-including runs specifically so it
#      cannot false-positive on a legitimate "nothing changed" incremental compile.
#
# Exit code matches sbt's exit code (0 = all given tasks succeeded), EXCEPT that a
# detected hollow success (see guard 2 above) is reported as exit 97, never 0.

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

# Join multiple task args into a single "; task1; task2" command string — see the
# sbtn multi-arg-drop quirk note above. A single task arg passes through unchanged.
if [ "$#" -gt 1 ]; then
    SBT_CMD="$1"
    shift
    for task in "$@"; do
        SBT_CMD="${SBT_CMD}; ${task}"
    done
    set -- "$SBT_CMD"
fi

{
    printf '## sbt-run.sh started %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '## tasks: %s\n\n' "$*"
} > "$LOG_FILE"

# --- Guard 1: kill a detached server that predates the build definition ---------
ACTIVE_JSON="$REPO_ROOT/project/target/active.json"
if [ -f "$ACTIVE_JSON" ] && command -v lsof >/dev/null 2>&1; then
    SOCK_PATH="$(sed -n 's#.*"uri":"local://\(.*\)"}.*#\1#p' "$ACTIVE_JSON" 2>/dev/null)"
    if [ -n "$SOCK_PATH" ]; then
        SERVER_PID="$(lsof -U 2>/dev/null | awk -v s="$SOCK_PATH" '$0 ~ s && $0 ~ /LISTEN/ {print $2; exit}')"
        if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
            SERVER_START_EPOCH="$(date -d "$(ps -o lstart= -p "$SERVER_PID" 2>/dev/null)" +%s 2>/dev/null || true)"
            NEWEST_BUILD_DEF_EPOCH="$(find "$REPO_ROOT/build.sbt" "$REPO_ROOT/project" -maxdepth 1 \
                \( -name '*.scala' -o -name '*.sbt' -o -name 'build.properties' \) \
                -printf '%T@\n' 2>/dev/null | sort -rn | head -1 | cut -d. -f1)"
            if [ -n "$SERVER_START_EPOCH" ] && [ -n "$NEWEST_BUILD_DEF_EPOCH" ] \
                && [ "$SERVER_START_EPOCH" -lt "$NEWEST_BUILD_DEF_EPOCH" ] 2>/dev/null; then
                {
                    printf '## stale-server guard: server pid %s started %s (epoch), older than\n' \
                        "$SERVER_PID" "$SERVER_START_EPOCH"
                    printf '## the newest build-definition file (epoch %s) -- killing so sbt reloads\n\n' \
                        "$NEWEST_BUILD_DEF_EPOCH"
                } >> "$LOG_FILE"
                kill "$SERVER_PID" 2>/dev/null
                WAIT_N=0
                while kill -0 "$SERVER_PID" 2>/dev/null && [ "$WAIT_N" -lt 20 ]; do
                    sleep 0.2
                    WAIT_N=$((WAIT_N + 1))
                done
                kill -9 "$SERVER_PID" 2>/dev/null
                rm -f "$SOCK_PATH" "$ACTIVE_JSON"
            fi
        fi
    fi
fi

# --- Guard 2 setup: baseline target/ mtime, only when `clean` is requested ------
# `clean` (bare or project/config-scoped, e.g. evm/clean) always invalidates cached
# compile state, so a subsequent compile/Test:compile can never legitimately be a
# no-op — something under target/ MUST change. Runs without `clean` are not checked
# here: a genuine "nothing changed since last incremental compile" success is valid
# and must not be flagged.
HAS_CLEAN=0
if printf '%s' "$*" | grep -qE '(^|;)[[:space:]]*([A-Za-z0-9_.-]+/)*clean[[:space:]]*(;|$)'; then
    HAS_CLEAN=1
fi

TARGET_BASELINE_EPOCH=0
if [ "$HAS_CLEAN" -eq 1 ]; then
    TARGET_BASELINE_EPOCH="$(find "$REPO_ROOT/target" "$REPO_ROOT"/modules/*/target \
        -printf '%T@\n' 2>/dev/null | sort -rn | head -1 | cut -d. -f1)"
    TARGET_BASELINE_EPOCH="${TARGET_BASELINE_EPOCH:-0}"
fi

sbt -no-colors -Dsbt.supershell=false "$@" >> "$LOG_FILE" 2>&1
SBT_EXIT=$?

# --- Guard 2 verification -------------------------------------------------------
if [ "$HAS_CLEAN" -eq 1 ] && [ "$SBT_EXIT" -eq 0 ]; then
    TARGET_AFTER_EPOCH="$(find "$REPO_ROOT/target" "$REPO_ROOT"/modules/*/target \
        -printf '%T@\n' 2>/dev/null | sort -rn | head -1 | cut -d. -f1)"
    TARGET_AFTER_EPOCH="${TARGET_AFTER_EPOCH:-0}"
    if [ "$TARGET_AFTER_EPOCH" -le "$TARGET_BASELINE_EPOCH" ]; then
        {
            printf '\n## HOLLOW-SUCCESS DETECTED: task list included `clean`, sbt exited 0, but\n'
            printf '## nothing under target/ changed (baseline epoch=%s, after epoch=%s).\n' \
                "$TARGET_BASELINE_EPOCH" "$TARGET_AFTER_EPOCH"
            printf '## A clean followed by a real compile always writes something new under\n'
            printf '## target/ -- this is being treated as a stale/stuck sbt server false-green,\n'
            printf '## not a trustworthy PASS. Investigate project/target/active.json and the\n'
            printf '## sbt server process before relying on this result.\n'
        } >> "$LOG_FILE"
        SBT_EXIT=97
    fi
fi

{
    printf '\n## sbt-run.sh finished %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'EXIT CODE: %d\n' "$SBT_EXIT"
} >> "$LOG_FILE"

printf 'DONE log=%s exit=%d\n' "$LOG_FILE" "$SBT_EXIT"
exit "$SBT_EXIT"
