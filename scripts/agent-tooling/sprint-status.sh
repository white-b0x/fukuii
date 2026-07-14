#!/bin/bash
# sprint-status.sh — report the current state of .claude/sprints/
# Usage: sprint-status.sh
# Read-only. Prints structured markdown. Used by: fukuii-sprint-queue skill.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SPRINTS_DIR="$REPO_ROOT/.claude/sprints"
QUEUE="$SPRINTS_DIR/QUEUE.md"
LEGACY_DIR="$REPO_ROOT/.claude/progress-tracking"

if [ ! -f "$QUEUE" ]; then
    printf 'ERROR: %s not found\n' "$QUEUE" >&2
    exit 1
fi

echo "## Sprint Status"
echo

# --- Persistent Sections ---
echo "### Persistent Sections"
echo
for section in REPO Security Parity Modernization Performance; do
    ITEM_COUNT=$(awk -v s="### $section" '
      $0 ~ "^"s"( |$)" { flag=1; past_sep=0; next }
      /^### / { flag=0 }
      /^## / { flag=0 }
      flag && /^\|-+\|/ { past_sep=1; next }
      flag && past_sep && /^\| / && !/_\(none yet/ { count++ }
      END { print count + 0 }
    ' "$QUEUE")
    echo "- **$section**: $ITEM_COUNT item(s)"
done
echo
echo "See \`QUEUE.md\`'s \`## Persistent Sections\` for detail — these never fully close, so"
echo "there's no OPEN/CLOSED count the way Batches have."
echo

# --- Batches ---
BATCH_LINES=$(grep -n '^### Batch ' "$QUEUE" || true)
if [ -z "$BATCH_LINES" ]; then
    echo "### Batches"
    echo
    echo "No active batches yet. \`sprints/QUEUE.md\` starts at Batch 2 — see its header."
    echo
else
    echo "### Batches"
    echo
    echo '```'
    echo "$BATCH_LINES" | sed -E 's/^[0-9]+:### //'
    echo '```'
    echo

    OPEN_COUNT=$(echo "$BATCH_LINES" | grep -c ' — OPEN' || true)
    GATED_COUNT=$(echo "$BATCH_LINES" | grep -c ' — GATED' || true)
    BLOCKED_COUNT=$(echo "$BATCH_LINES" | grep -c ' — BLOCKED' || true)
    CLOSED_COUNT=$(echo "$BATCH_LINES" | grep -c ' — CLOSED' || true)
    echo "Open: $OPEN_COUNT · Gated: $GATED_COUNT · Blocked: $BLOCKED_COUNT · Closed: $CLOSED_COUNT"
    echo

    if [ "$CLOSED_COUNT" -gt 0 ]; then
        echo "**$CLOSED_COUNT batch(es) marked CLOSED — candidates for \`sprint-clear.sh\`.**"
        echo
    fi
fi

# --- Chase & Deferred Items ---
CHASE_ROWS=$(awk '/^## Chase & Deferred Items/{flag=1; next} /^## /{flag=0} flag && /^\| [^-|]/' "$QUEUE" | grep -v '_(none yet)_\| ID | Type ' || true)
echo "### Chase & Deferred Items"
echo
if [ -z "$CHASE_ROWS" ]; then
    echo "None open."
else
    echo "$CHASE_ROWS" | wc -l | xargs -I{} echo "{} open item(s) — see QUEUE.md for detail."
fi
echo

# --- completed/ and archive/ ---
for tier in completed archive; do
    DIR="$SPRINTS_DIR/$tier"
    echo "### sprints/$tier/"
    echo
    if [ -d "$DIR" ] && [ -n "$(ls -A "$DIR" 2>/dev/null)" ]; then
        echo '```'
        ls -la "$DIR" | tail -n +2
        echo '```'
    else
        echo "(empty)"
    fi
    echo
done

# --- Legacy tracker (informational, transition period only) ---
if [ -d "$LEGACY_DIR" ]; then
    echo "### Legacy tracker still present: .claude/progress-tracking/"
    echo
    echo "Not managed by this tool. Content fully migrated into .claude/sprints/* (Phase A"
    echo "complete) — this directory is pending Phase B deletion, gated on the operator's own"
    echo "per-file sign-off. See \`sprint-lifecycle.md\` → \"Retiring the legacy tracker.\""
    echo
    if [ -d "$LEGACY_DIR/working-docs" ]; then
        echo '```'
        ls -la "$LEGACY_DIR/working-docs" | tail -n +2
        echo '```'
    fi
    echo
fi
