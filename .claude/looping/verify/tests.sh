#!/bin/sh
# tests.sh — gate: run test tier or targeted suite; fail on any failure or count regression
# Controlled by LOOP_TEST_TARGET env var:
#   essential          -> sbt-run.sh <log> testEssential (Tier 1, ~24 min)
#   standard           -> sbt-run.sh <log> testStandard (Tier 2, ~30 min)
#   only <Spec>...     -> sbt-run.sh <log> "testOnly <Spec> ..."
# Defaults to essential if not set.
#
# Prints GATE:tests RESULT:PASS or GATE:tests RESULT:FAIL detail=<reason>
#
# Runs sbt via sbt-run.sh (log-to-file, no live-streamed/captured output) instead of
# capturing full sbt output into a shell variable — see background-script-execution.md.
# fukuii-test (formerly used here) is retired; sbt-run.sh supersedes it for gate use.

set -eu

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || printf '/media/dev/2tb/dev/fukuii')"
SBT_RUN="$REPO_ROOT/scripts/agent-tooling/sbt-run.sh"

if [ ! -x "$SBT_RUN" ]; then
    printf 'GATE:tests RESULT:FAIL detail=sbt-run-script-not-found:%s\n' "$SBT_RUN"
    exit 1
fi

TARGET="${LOOP_TEST_TARGET:-essential}"

# SyncTest exclusion guard: reject if caller tries to run a known SyncTest spec
for blocked in RegularSyncSpec FastSyncSpec SyncControllerSpec BlockchainHostActorSpec SyncStateDownloaderStateSpec; do
    if printf '%s' "$TARGET" | grep -q "$blocked"; then
        printf 'GATE:tests RESULT:FAIL detail=SyncTest-excluded:%s-stalls-under-CI-load\n' "$blocked"
        exit 1
    fi
done

LOG_NAME="looping-gate-tests-$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$REPO_ROOT/.local/logs/${LOG_NAME}.log"

case "$TARGET" in
    essential)
        "$SBT_RUN" "$LOG_NAME" testEssential || {
            FAILURES=$(grep -E 'FAILED|failures' "$LOG_FILE" | tail -3)
            printf 'GATE:tests RESULT:FAIL detail=test-failures:see-output\n'
            printf '%s\n' "$FAILURES"
            exit 1
        }
        ;;
    standard)
        "$SBT_RUN" "$LOG_NAME" testStandard || {
            FAILURES=$(grep -E 'FAILED|failures' "$LOG_FILE" | tail -3)
            printf 'GATE:tests RESULT:FAIL detail=test-failures:see-output\n'
            printf '%s\n' "$FAILURES"
            exit 1
        }
        ;;
    only\ *)
        SPECS=$(printf '%s' "$TARGET" | sed 's/^only //')
        "$SBT_RUN" "$LOG_NAME" "testOnly $SPECS" || {
            printf 'GATE:tests RESULT:FAIL detail=targeted-test-failure:suite=%s\n' "$SPECS"
            exit 1
        }
        ;;
    *)
        printf 'GATE:tests RESULT:FAIL detail=unknown-target:%s\n' "$TARGET"
        exit 1
        ;;
esac

# Test count regression check for essential tier
if [ "$TARGET" = "essential" ]; then
    COUNT=$(grep -oE '[0-9]+ test' "$LOG_FILE" | tail -1 | awk '{print $1}' || printf '0')
    BASELINE=3595
    if [ -n "$COUNT" ] && [ "$COUNT" -lt "$BASELINE" ]; then
        printf 'GATE:tests RESULT:FAIL detail=test-count-regression:expected>=%d:got=%s\n' "$BASELINE" "$COUNT"
        exit 1
    fi
fi

printf 'GATE:tests RESULT:PASS\n'
