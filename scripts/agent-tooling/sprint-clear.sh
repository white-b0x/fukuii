#!/bin/bash
# sprint-clear.sh — move fully-closed batches out of sprints/QUEUE.md into sprints/completed/
# Usage: sprint-clear.sh [--apply]
# Dry-run by default (no --apply): reports what would move, changes nothing.
# Batch header convention: "### Batch <N> — <STATUS>" where STATUS is one of
# OPEN / GATED-ON-<x> / BLOCKED / CLOSED. A batch's section runs from its header to the
# next "### Batch" header (or EOF). Deliberately NOT bounded by the next level-2"## "
# header: batch bodies (IP-CL-* prompts) use "## CONTEXT"/"## INSTRUCTIONS"/etc. as
# internal sub-headers, so treating any "## " as a section end truncated a CLOSED batch
# to just its intro paragraph (caught during BATCH-1-CLOSE, 2026-07-04 — see QUEUE-PATH-01
# in QUEUE.md's Chase & Deferred Items for the sibling path-drift issue found same session).
# Caveat: if the LAST batch in the file is ever closed with nothing after it, this will
# also sweep trailing standalone sections (e.g. "## REPO-*", "## Chase & Deferred Items")
# into the extract — fine as long as a later batch or a trailing "### " marker exists
# after it; re-check this edge case before closing the final batch in the queue.
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
# everything else goes to KEEP_FILE. A batch section ends only at the next "### Batch"
# header (not at nested "## " sub-headers inside the batch body — see header comment).
awk '
  /^### Batch / { in_closed = ($0 ~ / — CLOSED[[:space:]]*$/) ? 1 : 0 }
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
