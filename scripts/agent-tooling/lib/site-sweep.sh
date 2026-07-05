#!/bin/bash
# site-sweep.sh — run N grep patterns concurrently against one or more source trees,
# merge + dedupe results
# Usage: site-sweep.sh [--scope main|test|it|all] [--exclude REGEX] -- PATTERN [PATTERN ...]
#
# Extracted from the "known-sites-first, unknowns-concurrent" pattern documented in
# .claude/sprints/patterns/PATTERNS.md — used identically for the pre-edit discovery pass
# (find sites a known-sites table might have missed) and the post-edit blind sanity check
# (re-run the same command after all edits, with no prior context assumed).
#
# --scope selects which source root(s) to search (default: all three). This defaults to
# `all` on purpose: Batch 1's opaque-type sweep originally hardcoded src/main/ only and
# missed ~248 test-source leakage sites, which became its single biggest unplanned follow-up
# item (IP-14, 105 files) — see .claude/agent-protocols/batch-research-protocol.md rule (a).
#   main → src/main/
#   test → src/test/
#   it   → src/it/
#   all  → all three (default)
#
# Each PATTERN is run as its own `grep -rn` job per scoped root, all in the background, so
# N patterns across M roots cost roughly the wall-clock of the slowest one, not the sum of
# all of them — the point is to remove the investigation stall of running greps one after
# another before editing can start.
#
# Examples:
#   site-sweep.sh --exclude 'domain/GasPrice' -- \
#     'gasPrice|gas_price|maxFeePerGas|maxPriorityFeePerGas' \
#     'price:\s*BigInt'
#
#   site-sweep.sh --scope main --exclude 'domain/Timestamp|Ms:' -- \
#     'timestamp:\s*Long|unixTimestamp|blockTime|blockTimestamp' \
#     '\.timestamp\b'

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

SCOPE="all"
EXCLUDE=""
PATTERNS=()
SEEN_SEPARATOR=0

for arg in "$@"; do
    if [ "$SEEN_SEPARATOR" -eq 0 ]; then
        case "$arg" in
            --scope) NEED_SCOPE_VALUE=1; continue ;;
            --exclude) NEED_EXCLUDE_VALUE=1; continue ;;
            --) SEEN_SEPARATOR=1; continue ;;
            *)
                if [ "${NEED_SCOPE_VALUE:-0}" -eq 1 ]; then
                    SCOPE="$arg"
                    NEED_SCOPE_VALUE=0
                    continue
                fi
                if [ "${NEED_EXCLUDE_VALUE:-0}" -eq 1 ]; then
                    EXCLUDE="$arg"
                    NEED_EXCLUDE_VALUE=0
                    continue
                fi
                printf 'ERROR: unexpected argument before --: %s\n' "$arg" >&2
                exit 1
                ;;
        esac
    else
        PATTERNS+=("$arg")
    fi
done

if [ "${#PATTERNS[@]}" -eq 0 ]; then
    printf 'ERROR: usage: site-sweep.sh [--scope main|test|it|all] [--exclude REGEX] -- PATTERN [PATTERN ...]\n' >&2
    exit 1
fi

case "$SCOPE" in
    main) ROOTS=("$REPO_ROOT/src/main/") ;;
    test) ROOTS=("$REPO_ROOT/src/test/") ;;
    it)   ROOTS=("$REPO_ROOT/src/it/") ;;
    all)  ROOTS=("$REPO_ROOT/src/main/" "$REPO_ROOT/src/test/" "$REPO_ROOT/src/it/") ;;
    *)
        printf 'ERROR: --scope must be one of main|test|it|all (got: %s)\n' "$SCOPE" >&2
        exit 1
        ;;
esac

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

i=0
for pattern in "${PATTERNS[@]}"; do
    for root in "${ROOTS[@]}"; do
        i=$((i + 1))
        (
            grep -rn -E "$pattern" "$root" --include="*.scala" > "$TMP_DIR/$i.out" 2>/dev/null || true
        ) &
    done
done
wait

MERGED="$TMP_DIR/merged.out"
cat "$TMP_DIR"/*.out 2>/dev/null | sort -u > "$MERGED" || true

if [ -n "$EXCLUDE" ]; then
    FILTERED="$TMP_DIR/filtered.out"
    grep -Ev "$EXCLUDE" "$MERGED" > "$FILTERED" || true
    MERGED="$FILTERED"
fi

TOTAL=$(wc -l < "$MERGED" | tr -d ' ')
echo "## site-sweep: $TOTAL match(es) across ${#PATTERNS[@]} pattern(s), scope=$SCOPE (${ROOTS[*]#$REPO_ROOT/})"
echo

if [ "$TOTAL" -eq 0 ]; then
    echo "(no matches)"
    exit 0
fi

echo "### Per-file counts"
echo
cut -d: -f1 "$MERGED" | sed "s|^$REPO_ROOT/||" | sort | uniq -c | sort -rn
echo
echo "### Matches"
echo
sed "s|^$REPO_ROOT/||" "$MERGED"
