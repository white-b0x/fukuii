#!/bin/bash
# sprint-clear.sh — move fully-closed batches out of sprints/QUEUE.md into sprints/completed/
# Usage: sprint-clear.sh [--apply]
# Dry-run by default (no --apply): reports what would move, changes nothing.
# Batch header convention: "### Batch <N> — <STATUS>" where STATUS is one of
# OPEN / GATED-ON-<x> / BLOCKED / CLOSED. A batch's section runs from its header
# to the next "### Batch" header or the next "## " (level-2) header, whichever comes first.
# Used by: fukuii-sprint-queue skill. Referenced by: sprint-lifecycle.md Rule 5.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
QUEUE="$REPO_ROOT/.claude/sprints/QUEUE.md"
COMPLETED_DIR="$REPO_ROOT/.claude/sprints/completed"
APPLY=0

for arg in "$@"; do
    case "$arg" in
        --apply) APPLY=1 ;;
        *) printf 'ERROR: unknown argument: %s\n' "$arg" >&2; exit 1 ;;
    esac
done

if [ ! -f "$QUEUE" ]; then
    printf 'ERROR: %s not found\n' "$QUEUE" >&2
    exit 1
fi

BRANCH=$(git -C "$REPO_ROOT" branch --show-current 2>/dev/null || echo "unknown-branch")
TARGET="$COMPLETED_DIR/${BRANCH}-CLEARED.md"

KEEP_FILE=$(mktemp)
EXTRACT_FILE=$(mktemp)
trap 'rm -f "$KEEP_FILE" "$EXTRACT_FILE"' EXIT

# Single pass: lines inside a "### Batch N — CLOSED" section go to EXTRACT_FILE,
# everything else goes to KEEP_FILE. A batch section ends at the next "### Batch"
# header or the next level-2 "## " header.
awk '
  /^### Batch / { in_closed = ($0 ~ / — CLOSED[[:space:]]*$/) ? 1 : 0 }
  /^## [^#]/ { in_closed = 0 }
  { if (in_closed) print > "'"$EXTRACT_FILE"'"; else print > "'"$KEEP_FILE"'" }
' "$QUEUE"

CLOSED_COUNT=$(grep -c '^### Batch .* — CLOSED[[:space:]]*$' "$QUEUE" || true)

if [ "$CLOSED_COUNT" -eq 0 ]; then
    echo "No CLOSED batches found in $QUEUE — nothing to clear."
    exit 0
fi

echo "Found $CLOSED_COUNT CLOSED batch(es):"
grep '^### Batch .* — CLOSED[[:space:]]*$' "$QUEUE" | sed 's/^### //'
echo
echo "Would append $(wc -l < "$EXTRACT_FILE") line(s) to: $TARGET"
echo "Would leave $(wc -l < "$KEEP_FILE") line(s) in: $QUEUE"

if [ "$APPLY" -eq 0 ]; then
    echo
    echo "Dry run only — re-run with --apply to perform the move."
    exit 0
fi

mkdir -p "$COMPLETED_DIR"
{
    echo
    echo "---"
    echo "<!-- Cleared from QUEUE.md on $(date -u '+%Y-%m-%d') (branch: $BRANCH) -->"
    echo
    cat "$EXTRACT_FILE"
} >> "$TARGET"

cp "$KEEP_FILE" "$QUEUE"
echo
echo "Done. $CLOSED_COUNT batch(es) moved to $TARGET."
