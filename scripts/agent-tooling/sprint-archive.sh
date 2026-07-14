#!/bin/bash
# sprint-archive.sh — retire a fully-logged file from sprints/completed/ into sprints/archive/
# Usage: sprint-archive.sh <completed-file> [--apply] [--force]
# Refuses (exit 1) unless a reference to the file's basename is found somewhere under
# sprints/log/ — mechanically enforces "log it before you archive it" (sprint-lifecycle.md
# Rule 5). --force bypasses the check (use only when you've confirmed the log entry exists
# under a different name/wording).
# Dry-run by default: reports the decision, changes nothing unless --apply is given.
#
# archive/<basename> is the CUMULATIVE retirement archive for a sprint basename — a given
# basename (e.g. july-fourth-CLEARED.md) can be archived more than once across a sprint's
# life as later batches close out. If archive/<basename> does NOT already exist, this script
# moves the completed file there (unchanged from prior behavior). If archive/<basename>
# ALREADY exists, the completed content is APPENDED after the existing archive content (with
# a visible separator marker recording where it came from and when), then the completed
# source is removed. Never do a bare `mv`/overwrite onto an existing archive file — that
# silently destroys every batch archived there before this run.
# Used by: fukuii-sprint-queue skill.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SPRINTS_DIR="$REPO_ROOT/.claude/sprints"
LOG_DIR="$SPRINTS_DIR/log"
ARCHIVE_DIR="$SPRINTS_DIR/archive"

APPLY=0
FORCE=0
FILE=""

for arg in "$@"; do
    case "$arg" in
        --apply) APPLY=1 ;;
        --force) FORCE=1 ;;
        -*) printf 'ERROR: unknown flag: %s\n' "$arg" >&2; exit 1 ;;
        *) FILE="$arg" ;;
    esac
done

if [ -z "$FILE" ]; then
    printf 'ERROR: usage: sprint-archive.sh <completed-file> [--apply] [--force]\n' >&2
    exit 1
fi

# Accept either a bare filename (resolved under sprints/completed/) or a full path.
if [ -f "$FILE" ]; then
    SRC="$FILE"
elif [ -f "$SPRINTS_DIR/completed/$FILE" ]; then
    SRC="$SPRINTS_DIR/completed/$FILE"
else
    printf 'ERROR: file not found: %s (looked in cwd and sprints/completed/)\n' "$FILE" >&2
    exit 1
fi

BASENAME=$(basename "$SRC")

if [ "$FORCE" -eq 1 ]; then
    echo "--force given: skipping the log-reference check."
else
    HIT=$(grep -rl -- "$BASENAME" "$LOG_DIR" 2>/dev/null || true)
    if [ -z "$HIT" ]; then
        echo "REFUSED: no reference to '$BASENAME' found under $LOG_DIR."
        echo "Write the sprints/log/ entry for this batch first (sprint-lifecycle.md Rule 5"
        echo "step 1), or re-run with --force if you've confirmed it's logged under a"
        echo "different name."
        exit 1
    fi
    echo "Found log reference(s):"
    echo "$HIT" | sed "s|^$LOG_DIR/|  log/|"
fi

DEST="$ARCHIVE_DIR/$BASENAME"

echo
if [ -f "$DEST" ]; then
    MODE="append"
    EXISTING_LINES=$(wc -l < "$DEST")
    SRC_LINES=$(wc -l < "$SRC")
    echo "Archive destination already exists: $DEST ($EXISTING_LINES lines)"
    echo "Would APPEND: $SRC ($SRC_LINES lines) -> $DEST (cumulative archive, not overwritten)"
else
    MODE="move"
    echo "Would move: $SRC -> $DEST"
fi

if [ "$APPLY" -eq 0 ]; then
    echo
    echo "Dry run only — re-run with --apply to perform the $MODE."
    exit 0
fi

mkdir -p "$ARCHIVE_DIR"

if [ "$MODE" = "append" ]; then
    BEFORE_LINES=$(wc -l < "$DEST")
    SRC_LINES=$(wc -l < "$SRC")
    BATCHES=$(grep -h '^### Batch' "$SRC" | sed 's/^### //' | paste -sd ',' -)
    {
        echo ""
        printf '<!-- appended from completed/%s on archive (%s UTC); source batches: %s -->\n' \
            "$BASENAME" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$BATCHES"
        echo ""
        cat "$SRC"
    } >> "$DEST"
    rm "$SRC"
    AFTER_LINES=$(wc -l < "$DEST")
    echo
    echo "Appended to existing archive ($BEFORE_LINES + $SRC_LINES = $((BEFORE_LINES + SRC_LINES)) content lines,"
    echo "plus 3 separator lines -> $DEST now has $AFTER_LINES lines total)."
else
    mv "$SRC" "$DEST"
    echo
    echo "Done. Archived to $DEST."
fi
