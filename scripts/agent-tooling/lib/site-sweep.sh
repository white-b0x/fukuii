#!/bin/bash
# site-sweep.sh — run N grep patterns concurrently against src/main/, merge + dedupe results
# Usage: site-sweep.sh [--exclude REGEX] -- PATTERN [PATTERN ...]
#
# Extracted from the "known-sites-first, unknowns-concurrent" pattern documented in
# .claude/sprints/patterns/PATTERNS.md — used identically for the pre-edit discovery pass
# (find sites a known-sites table might have missed) and the post-edit blind sanity check
# (re-run the same command after all edits, with no prior context assumed).
#
# Each PATTERN is run as its own `grep -rn` job in the background so N patterns cost roughly
# the wall-clock of the slowest one, not the sum of all of them — the point is to remove the
# investigation stall of running greps one after another before editing can start.
#
# Examples:
#   site-sweep.sh --exclude 'domain/GasPrice' -- \
#     'gasPrice|gas_price|maxFeePerGas|maxPriorityFeePerGas' \
#     'price:\s*BigInt'
#
#   site-sweep.sh --exclude 'domain/Timestamp|Ms:' -- \
#     'timestamp:\s*Long|unixTimestamp|blockTime|blockTimestamp' \
#     '\.timestamp\b'

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

EXCLUDE=""
PATTERNS=()
SEEN_SEPARATOR=0

for arg in "$@"; do
    if [ "$SEEN_SEPARATOR" -eq 0 ]; then
        case "$arg" in
            --exclude) NEED_EXCLUDE_VALUE=1; continue ;;
            --) SEEN_SEPARATOR=1; continue ;;
            *)
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
    printf 'ERROR: usage: site-sweep.sh [--exclude REGEX] -- PATTERN [PATTERN ...]\n' >&2
    exit 1
fi

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

i=0
for pattern in "${PATTERNS[@]}"; do
    i=$((i + 1))
    (
        grep -rn -E "$pattern" "$REPO_ROOT/src/main/" --include="*.scala" > "$TMP_DIR/$i.out" 2>/dev/null || true
    ) &
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
echo "## site-sweep: $TOTAL match(es) across ${#PATTERNS[@]} pattern(s)"
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
