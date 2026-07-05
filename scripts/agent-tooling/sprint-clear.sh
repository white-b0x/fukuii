#!/bin/bash
# sprint-clear.sh — move fully-closed batches out of sprints/QUEUE.md into sprints/completed/
# Usage: sprint-clear.sh [--apply]
# Dry-run by default (no --apply): reports what would move, changes nothing.
#
# QUEUE.md structure (as of the 2026-07-05 restructuring): "## Persistent Sections"
# (REPO, Security, Parity, Modernization, Performance — ongoing, never CLOSED as a whole)
# comes before "## Batches" (finite, numbered units that DO close). This script only ever
# operates between the "## Batches" header and the next "## " header (currently
# "## Chase & Deferred Items") — Persistent Sections are structurally out of reach, not just
# pattern-excluded, so a Persistent Section can never be mistaken for a closeable batch no
# matter what its heading text says.
#
# Batch header convention within that region: "### Batch <N> — <STATUS>" where STATUS is one
# of OPEN / GATED-ON-<x> / BLOCKED / CLOSED. A batch's section runs from its header to the
# next level-3 "### " header (or the end of the "## Batches" region) — deliberately NOT
# bounded by a level-2 "## " header, since batch bodies (IP-CL-* prompts) use
# "## CONTEXT"/"## INSTRUCTIONS"/etc. as internal sub-headers; treating any "## " as a section
# end truncated a CLOSED batch to just its intro paragraph (caught during BATCH-1-CLOSE,
# 2026-07-04 — see QUEUE-PATH-01 in QUEUE.md's Chase & Deferred Items for the sibling
# path-drift issue found same session).
#
# Real incident (Batch 1.5 close, 2026-07-05, pre-restructuring): back when Persistent
# Sections (then just "REPO-*") lived at the same "### " level *inside* "## Batches", the
# boundary regex required the next header to literally start with "### Batch " — since
# "### REPO-*" didn't match that, clearing a CLOSED "### Batch 1.5" swept the entire following
# "### REPO-*" section into the extract too. Caught and manually repaired same session
# (both files are local/untracked, nothing lost). Fixed two ways: (1) the boundary regex now
# matches any "^### " line, not just ones starting with "Batch"; (2) Persistent Sections were
# moved structurally out of "## Batches" entirely, which is the real fix — (1) alone would
# still be one heading-text accident away from the same class of bug. Re-verify with a dry run
# and a manual read of what got extracted before trusting a fresh --apply run blindly.
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

# Single pass, gated on being inside "## Batches": lines inside a "### ... — CLOSED"
# section go to EXTRACT_FILE, everything else goes to KEEP_FILE. `in_batches` turns on at
# the literal "## Batches" header (printed to KEEP_FILE itself, then `next`s so it isn't
# immediately matched by the very next rule) and turns back off at the following "## "
# header (any text) — Persistent Sections before "## Batches" are never eligible for
# extraction no matter what their own heading text looks like. Within "## Batches", a batch
# section ends at the next level-3 "### " header — not at nested "## " sub-headers inside
# the batch body (see header comment for why).
awk -v extract="$EXTRACT_FILE" -v keep="$KEEP_FILE" '
  {
    if ($0 ~ /^## Batches[[:space:]]*$/) {
      in_batches = 1; in_closed = 0
      print > keep
      next
    }
    if (in_batches && $0 ~ /^## /) { in_batches = 0; in_closed = 0 }
    if (in_batches && $0 ~ /^### /) {
      in_closed = ($0 ~ / — CLOSED[[:space:]]*$/) ? 1 : 0
    }
    if (in_closed) print > extract; else print > keep
  }
' "$QUEUE"

CLOSED_COUNT=$(grep -c '^### .* — CLOSED[[:space:]]*$' "$EXTRACT_FILE" || true)

if [ "$CLOSED_COUNT" -eq 0 ]; then
    echo "No CLOSED batches found in $QUEUE — nothing to clear."
    exit 0
fi

echo "Found $CLOSED_COUNT CLOSED batch(es):"
grep '^### .* — CLOSED[[:space:]]*$' "$EXTRACT_FILE" | sed 's/^### //'
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
