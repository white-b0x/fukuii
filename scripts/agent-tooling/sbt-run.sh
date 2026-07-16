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
# Incident 1: a long-lived detached sbt server (left running from a prior session)
# answered clean/compile/testCompile requests with fast `[success]` while doing no
# real recompilation — the build's actual output tree never changed. Root cause:
# sbt's persistent server does not reload build.sbt/project/*.scala/build.properties
# changes on its own; a server that has been sitting since before the last
# build-definition edit is running a stale settings graph.
#
# Incident 2: an sbt `project <id>` selector followed by chained tasks in the same
# joined command string (e.g. `project evm; clean; compile; Test/compile`) silently
# runs ONLY the project switch — the chained tasks never execute — yet sbt still
# exits 0. This was not caught by guard 2 below in its first form because that
# guard scanned the WHOLE target/ tree for a freshness signal, and target/
# contains files (target/global-logging/sbt-global-log*.log, per-project
# streams/update/meta dirs) that get touched by ANY sbt invocation, including a
# bare `project X` switch that does nothing else — a false "something changed"
# reading that masked the real hollow run. Confirmed empirically: `project evm`
# alone touches target/global-logging but never touches the actual compile-output
# paths under target/out/*/scala-*/*/{classes,test-classes,zinc,test-zinc}.
#
# See .agents/protocols/process/background-script-execution.md for the full
# incident writeup. Three guards below close this:
#   1. Before running: if the registered server (project/target/active.json) predates
#      the newest build-definition file, kill it so sbt starts fresh and reloads.
#   2. Before running: reject outright (no sbt invocation at all) any task list
#      where an sbt `project <id>` command is chained with further tasks after it
#      in the same command string — this shape is unsafe by construction (see
#      incident 2). Callers must use module-scoped `<mod>/<task>` syntax instead
#      (e.g. `evm/clean`, `evm/compile`, `evm/Test/compile`), which never needs a
#      project switch and does not exhibit this failure mode.
#   3. After running: if the task list included a `clean` task (which invalidates ALL
#      cached compile state, so a subsequent compile can never legitimately be a
#      no-op) and sbt exited 0, verify the REAL compile-output paths
#      (target/out/*/scala-*/*/{classes,test-classes,zinc,test-zinc} — never the
#      whole target/ tree, which is too noisy — see incident 2) actually advanced.
#      If not, this is a hollow success — exit 97 instead of 0, with a loud banner
#      in the log. Scoped to clean-including runs specifically so it cannot
#      false-positive on a legitimate "nothing changed" incremental compile.
#
# Exit code matches sbt's exit code (0 = all given tasks succeeded), EXCEPT that:
#   - an unsafe `project <id>`-lead command list (guard 2) is rejected before sbt
#     ever runs, exit 3
#   - a detected hollow success (guard 3) is reported as exit 97, never 0

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

# --- Guard 2 (validated first, before touching sbt at all): reject an unsafe -----
# `project <id>` lead-form chained with further tasks. Split the joined command on
# `;` and check whether any token except the LAST is a `project <id>` selector —
# if so, further tasks were chained after a project switch in the same command
# string, which is the exact shape that silently no-ops (incident 2 above).
REJECT_PROJECT_LEAD=0
IFS=';' read -ra _SBT_TOKENS <<< "$*"
_SBT_TOKEN_COUNT=${#_SBT_TOKENS[@]}
for ((_i = 0; _i < _SBT_TOKEN_COUNT; _i++)); do
    _tok="$(printf '%s' "${_SBT_TOKENS[$_i]}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    if printf '%s' "$_tok" | grep -qE '^project[[:space:]]+[^[:space:]]+' \
        && [ "$_i" -lt "$((_SBT_TOKEN_COUNT - 1))" ]; then
        REJECT_PROJECT_LEAD=1
    fi
done

if [ "$REJECT_PROJECT_LEAD" -eq 1 ]; then
    {
        printf '\n## REJECTED: task list chains further tasks after an sbt `project <id>`\n'
        printf '## selector (e.g. "project evm; clean; compile"). This form silently runs\n'
        printf '## ONLY the project switch and discards everything chained after it, while\n'
        printf '## sbt still exits 0 -- a guaranteed hollow success, not a real gate result.\n'
        printf '## Use module-scoped task syntax instead: "<mod>/<task>" (e.g. evm/clean,\n'
        printf '## evm/compile, evm/Test/compile) never needs a project switch and does not\n'
        printf '## exhibit this failure mode.\n'
    } >> "$LOG_FILE"
    printf 'REJECTED log=%s exit=3 — unsafe `project <id>` lead-form; use <mod>/<task> syntax instead\n' "$LOG_FILE" >&2
    exit 3
fi

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

# --- Guard 3 setup: baseline the REAL compile-output paths, only when `clean` is
# requested. `clean` (bare or project/config-scoped, e.g. evm/clean) always
# invalidates cached compile state, so a subsequent compile/Test:compile can never
# legitimately be a no-op — the actual compile-output paths MUST advance. Runs
# without `clean` are not checked here: a genuine "nothing changed since last
# incremental compile" success is valid and must not be flagged.
#
# Deliberately scoped to target/out/*/scala-*/*/{classes,test-classes,zinc,test-zinc}
# (plus the legacy per-module modules/*/target/scala-*/... equivalents) — NEVER the
# whole target/ tree. target/global-logging and every project's own
# streams/update/meta dirs are touched by ANY sbt invocation, including one that did
# nothing but switch projects — scanning the whole tree produced exactly the false
# "something changed" reading that let incident 2 (above) slip through undetected.
compile_artifact_newest_epoch() {
    find "$REPO_ROOT"/target/out/*/scala-*/*/classes \
        "$REPO_ROOT"/target/out/*/scala-*/*/test-classes \
        "$REPO_ROOT"/target/out/*/scala-*/*/zinc \
        "$REPO_ROOT"/target/out/*/scala-*/*/test-zinc \
        "$REPO_ROOT"/modules/*/target/scala-*/classes \
        "$REPO_ROOT"/modules/*/target/scala-*/test-classes \
        "$REPO_ROOT"/modules/*/target/scala-*/zinc \
        "$REPO_ROOT"/modules/*/target/scala-*/test-zinc \
        -printf '%T@\n' 2>/dev/null | sort -rn | head -1 | cut -d. -f1
}

HAS_CLEAN=0
if printf '%s' "$*" | grep -qE '(^|;)[[:space:]]*([A-Za-z0-9_.-]+/)*clean[[:space:]]*(;|$)'; then
    HAS_CLEAN=1
fi

TARGET_BASELINE_EPOCH=0
if [ "$HAS_CLEAN" -eq 1 ]; then
    TARGET_BASELINE_EPOCH="$(compile_artifact_newest_epoch)"
    TARGET_BASELINE_EPOCH="${TARGET_BASELINE_EPOCH:-0}"
fi

sbt -no-colors -Dsbt.supershell=false "$@" >> "$LOG_FILE" 2>&1
SBT_EXIT=$?

# --- Guard 3 verification -------------------------------------------------------
if [ "$HAS_CLEAN" -eq 1 ] && [ "$SBT_EXIT" -eq 0 ]; then
    TARGET_AFTER_EPOCH="$(compile_artifact_newest_epoch)"
    TARGET_AFTER_EPOCH="${TARGET_AFTER_EPOCH:-0}"
    if [ "$TARGET_AFTER_EPOCH" -le "$TARGET_BASELINE_EPOCH" ]; then
        {
            printf '\n## HOLLOW-SUCCESS DETECTED: task list included `clean`, sbt exited 0, but\n'
            printf '## the real compile-output paths (target/out/*/scala-*/*/{classes,test-classes,\n'
            printf '## zinc,test-zinc}) never advanced (baseline epoch=%s, after epoch=%s).\n' \
                "$TARGET_BASELINE_EPOCH" "$TARGET_AFTER_EPOCH"
            printf '## A clean followed by a real compile always writes something new there -- this\n'
            printf '## is being treated as a stale/stuck sbt server or a swallowed-command false\n'
            printf '## green, not a trustworthy PASS. Investigate project/target/active.json, the\n'
            printf '## sbt server process, and whether the task list used an unsafe `project <id>`\n'
            printf '## lead-form before relying on this result.\n'
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
