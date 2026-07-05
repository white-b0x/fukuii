#!/bin/bash
# hive-run.sh — build/sync the fukuii hive adapter, run one or more ethereum/hive
# simulator suites against it, tabulate pass/fail, and always clean up — all output
# logged to a file, never streamed live to the calling terminal/agent session.
#
# Usage: hive-run.sh <log-name> <suite> [<suite2> ...]
#   log-name    basename (no extension) for the log file under .local/logs/
#   suite       one or more of: consensus engine rpc-compat sync devp2p
#               smoke-genesis smoke-network graphql pyspec consume-engine
#               consume-rlp osaka prague (see .agents/skills/fukuii-test-hive/SKILL.md
#               for the full suite reference table this script's case statement mirrors)
#
# Prerequisite: run `scripts/agent-tooling/sbt-run.sh <name> assembly` FIRST. This
# script does not build the fukuii assembly jar itself — it expects
# target/scala-3.*/fukuii-assembly-*.jar to already exist.
#
# Wraps Phases 2-5 of the fukuii-test-hive skill design (see
# docs/research/best-practices/evm-clients/repo-patterns/hive/fukuii-test-hive-skill-design.md):
#   Phase 2 — clone ethereum/hive fresh into a mktemp workdir (never ./hive, which
#             would shadow fukuii's own hive/ adapter source dir); tag the
#             chipprbots/fukuii:latest base image; sync hive/fukuii/* into the
#             ephemeral checkout fresh, every run (never trust a previously-copied
#             adapter in a long-lived hive checkout); build the hive binary
#             (repo root, not cmd/hive).
#   Phase 3 — run each requested suite's simulator sequentially (never concurrently
#             — one heavy Docker-fleet task at a time on this hardware).
#   Phase 4 — tabulate pass/fail from hive's workspace/logs/*.json (schema:
#             .testCases[].summaryResult.pass), falling back to a grep against
#             known verdict-string formats if zero JSON files parsed.
#   Phase 5 — always clean up (hive's own --cleanup + docker image prune -f),
#             via a trap so this fires on success, failure, or interrupt alike.
#
# Why this exists: this is a multi-minute-to-multi-hour, Docker-heavy operation —
# see .agents/protocols/process/background-script-execution.md. Never run this
# directly in the foreground; always via the calling tool's background-execution
# option.
#
# Exit code: 0 if every requested suite's hive invocation completed without a
# script-level error (missing jar, clone failure, build failure). This is NOT the
# same as "zero simulator test failures" — hive itself returns non-zero for many
# benign per-sim outcomes, so the tabulated passed/failed counts in the log are the
# real signal, not this exit code (see Phase 4 above and the design doc).

set -uo pipefail

if [ "$#" -lt 2 ]; then
    printf 'Usage: %s <log-name> <suite> [<suite2> ...]\n' "$(basename "$0")" >&2
    printf 'Suites: consensus engine rpc-compat sync devp2p smoke-genesis smoke-network graphql pyspec consume-engine consume-rlp osaka prague\n' >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$REPO_ROOT/.local/logs"
mkdir -p "$LOG_DIR"

LOG_NAME="$1"
shift
SUITES=("$@")
LOG_FILE="$LOG_DIR/${LOG_NAME}.log"

cd "$REPO_ROOT"

{
    printf '## hive-run.sh started %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '## suites: %s\n\n' "${SUITES[*]}"
} > "$LOG_FILE"

WORKDIR=""
cleanup() {
    {
        printf '\n## cleanup started %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } >> "$LOG_FILE"
    if [ -n "$WORKDIR" ] && [ -x "$WORKDIR/hive/hive" ]; then
        (cd "$WORKDIR/hive" && ./hive --cleanup --cleanup.older-than 0s) >> "$LOG_FILE" 2>&1 || true
    fi
    docker image prune -f >> "$LOG_FILE" 2>&1 || true
    if [ -n "$WORKDIR" ] && [ -d "$WORKDIR" ]; then
        rm -rf "$WORKDIR"
    fi
    {
        printf '## cleanup finished %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } >> "$LOG_FILE"
}
trap cleanup EXIT INT TERM

RUN_EXIT=0

printf '## Phase 2 -- ephemeral hive checkout\n' >> "$LOG_FILE"
WORKDIR=$(mktemp -d /tmp/fukuii-hive-test-XXXXXX)

CLONE_OK=0
for attempt in 1 2 3 4 5; do
    if git -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=30 \
        clone --depth=1 --filter=blob:none \
        https://github.com/ethereum/hive.git "$WORKDIR/hive" >> "$LOG_FILE" 2>&1; then
        CLONE_OK=1
        break
    fi
    printf 'clone attempt %d failed, retrying...\n' "$attempt" >> "$LOG_FILE"
    rm -rf "$WORKDIR/hive"
    sleep $((attempt * 2))
done

if [ "$CLONE_OK" -ne 1 ]; then
    printf 'ERROR: failed to clone ethereum/hive after 5 attempts\n' >> "$LOG_FILE"
    RUN_EXIT=1
else
    JAR="$(ls target/scala-3.*/fukuii-assembly-*.jar 2>/dev/null | head -1)"
    if [ -z "$JAR" ]; then
        printf 'ERROR: no fukuii-assembly-*.jar found under target/scala-3.*/ -- run scripts/agent-tooling/sbt-run.sh <name> assembly first\n' >> "$LOG_FILE"
        RUN_EXIT=1
    else
        printf '## Phase 2 -- tag chipprbots/fukuii:latest base image\n' >> "$LOG_FILE"
        mkdir -p target/quick-docker
        cp "$JAR" target/quick-docker/
        cat > target/quick-docker/Dockerfile <<'DOCKERFILE'
FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends jq curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir -p /app/fukuii/lib /app/data /app/hive-conf
COPY fukuii-assembly-*.jar /app/fukuii/lib/fukuii-assembly.jar
ENTRYPOINT ["java", "-jar", "/app/fukuii/lib/fukuii-assembly.jar"]
DOCKERFILE
        docker build -t chipprbots/fukuii:latest target/quick-docker/ >> "$LOG_FILE" 2>&1
        BUILD_IMG_EXIT=$?

        printf '## Phase 2 -- sync hive/fukuii/* adapter (fresh, every run)\n' >> "$LOG_FILE"
        mkdir -p "$WORKDIR/hive/clients/fukuii"
        cp hive/fukuii/Dockerfile "$WORKDIR/hive/clients/fukuii/"
        cp hive/fukuii/fukuii.sh  "$WORKDIR/hive/clients/fukuii/"
        cp hive/fukuii/mapper.jq  "$WORKDIR/hive/clients/fukuii/"
        cp hive/fukuii/enode.sh   "$WORKDIR/hive/clients/fukuii/"
        cp hive/fukuii/hive.yaml  "$WORKDIR/hive/clients/fukuii/"
        cp "$JAR" "$WORKDIR/hive/clients/fukuii/"

        printf '## Phase 2 -- build hive binary (repo root, not cmd/hive)\n' >> "$LOG_FILE"
        (cd "$WORKDIR/hive" && go build -o hive .) >> "$LOG_FILE" 2>&1
        BUILD_HIVE_EXIT=$?

        if [ "$BUILD_IMG_EXIT" -ne 0 ] || [ "$BUILD_HIVE_EXIT" -ne 0 ]; then
            printf 'ERROR: docker image build (exit %d) or hive binary build (exit %d) failed\n' \
                "$BUILD_IMG_EXIT" "$BUILD_HIVE_EXIT" >> "$LOG_FILE"
            RUN_EXIT=1
        else
            for SUITE in "${SUITES[@]}"; do
                printf '\n## Phase 3 -- running suite: %s\n' "$SUITE" >> "$LOG_FILE"

                PARALLELISM=4
                EXTRA=()
                case "$SUITE" in
                    consensus)      SIM="ethereum/consensus";           EXTRA=(--sim.timelimit 60m) ;;
                    engine)         SIM="ethereum/engine";              EXTRA=(--sim.timelimit 60m) ;;
                    rpc-compat)     SIM="ethereum/rpc-compat";          EXTRA=(--sim.timelimit 30m) ;;
                    sync)           SIM="ethereum/sync";                PARALLELISM=1; EXTRA=(--client fukuii,go-ethereum,nethermind --sim.timelimit 40m) ;;
                    devp2p)         SIM="devp2p";                       EXTRA=(--sim.timelimit 40m) ;;
                    smoke-genesis)  SIM="smoke/genesis";                EXTRA=(--sim.timelimit 20m) ;;
                    smoke-network)  SIM="smoke/network";                EXTRA=(--sim.timelimit 20m) ;;
                    graphql)        SIM="ethereum/graphql";             PARALLELISM=1; EXTRA=(--sim.timelimit 20m) ;;
                    pyspec)         SIM="ethereum/pyspec";              EXTRA=(--sim.timelimit 60m) ;;
                    consume-engine) SIM="ethereum/eels/consume-engine"; EXTRA=(--sim.buildarg disable_strict_exception_matching=nimbus-el,fukuii --sim.timelimit 60m) ;;
                    consume-rlp)    SIM="ethereum/eels/consume-rlp";    EXTRA=(--sim.buildarg disable_strict_exception_matching=nimbus-el,fukuii --sim.timelimit 60m) ;;
                    osaka)          SIM="ethereum/eels/consume-engine"; EXTRA=(--sim.limit '.*fork_Osaka.*' --sim.timelimit 40m) ;;
                    prague)         SIM="ethereum/eels/consume-engine"; EXTRA=(--sim.limit '.*fork_Prague.*' --sim.timelimit 40m) ;;
                    *)
                        printf 'ERROR: unknown suite "%s" -- see .agents/skills/fukuii-test-hive/SKILL.md suite table\n' "$SUITE" >> "$LOG_FILE"
                        RUN_EXIT=1
                        continue
                        ;;
                esac

                (cd "$WORKDIR/hive" && ./hive \
                    --sim "$SIM" \
                    --client fukuii \
                    --sim.parallelism "$PARALLELISM" \
                    --client.checktimelimit=120s \
                    --loglevel 3 \
                    "${EXTRA[@]}") >> "$LOG_FILE" 2>&1
                # hive's own exit code is not authoritative here -- see header comment.

                printf '## Phase 4 -- tabulating suite: %s\n' "$SUITE" >> "$LOG_FILE"
                JSON_COUNT=$(find "$WORKDIR/hive/workspace/logs" -name '*.json' 2>/dev/null | wc -l | tr -d ' ')
                if [ "$JSON_COUNT" -gt 0 ]; then
                    PASSED=0
                    FAILED=0
                    for jf in "$WORKDIR"/hive/workspace/logs/*.json; do
                        [ -f "$jf" ] || continue
                        P=$(jq '[.testCases[]?.summaryResult.pass | select(. == true)] | length' "$jf" 2>/dev/null || echo 0)
                        F=$(jq '[.testCases[]?.summaryResult.pass | select(. == false)] | length' "$jf" 2>/dev/null || echo 0)
                        PASSED=$((PASSED + P))
                        FAILED=$((FAILED + F))
                    done
                    TOTAL=$((PASSED + FAILED))
                    printf 'Hive · %s (%s)\npassed=%d failed=%d total=%d source=%d JSON result(s)\n' \
                        "$SUITE" "$SIM" "$PASSED" "$FAILED" "$TOTAL" "$JSON_COUNT" >> "$LOG_FILE"
                else
                    PASSED=$(grep -Ec '(\] PASSED |PASSED tests/|--- PASS:)' "$WORKDIR/hive/hive-run.log" 2>/dev/null || echo 0)
                    FAILED=$(grep -Ec '(\] FAILED |FAILED tests/|--- FAIL:)' "$WORKDIR/hive/hive-run.log" 2>/dev/null || echo 0)
                    TOTAL=$((PASSED + FAILED))
                    printf 'Hive · %s (%s)\npassed=%d failed=%d total=%d source=grep fallback\n' \
                        "$SUITE" "$SIM" "$PASSED" "$FAILED" "$TOTAL" >> "$LOG_FILE"
                fi
            done
        fi
    fi
fi

{
    printf '\n## hive-run.sh finished %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'EXIT CODE: %d\n' "$RUN_EXIT"
} >> "$LOG_FILE"

printf 'DONE log=%s exit=%d\n' "$LOG_FILE" "$RUN_EXIT"
exit "$RUN_EXIT"
