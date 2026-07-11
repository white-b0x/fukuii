#!/bin/bash
# finding-placement-check.sh — verify every "-> Batch N" / "-> B<n>" routed finding in
# .claude/sprints/queue/chase-deferred.md and .claude/sprints/QUEUE.md's own close-out
# rows is actually PLACED as a line-item inside that Batch N's "### Batch N" section
# body in QUEUE.md — not just logged as an intent to route it there.
#
# Usage: finding-placement-check.sh
#
# Why this exists: finding-resolution.md's PLACEMENT rule distinguishes the INDEX
# (chase-deferred.md's one-line cross-reference) from the SCHEDULE (a concrete
# line-item inside the target batch's own section body) — "logged != scheduled."
# A finding routed "-> Batch N" with no matching line-item inside Batch N's own
# section body is an unscheduled finding wearing a scheduled costume; this script
# is the mechanical ratchet that catches that drift before a batch closes.
#
# Detects two routing shapes actually in use in this repo's queue docs:
#   1. Prose bullet blocks: "- **-> Batch N** ...: `ID1` (...); `ID2` (...)."
#      — including nested CLOSE-OUT-GATES sub-bullets: "  - **`ID`** -- ...".
#   2. Inline shorthand: "`ID` -> Batch N" / "`ID`->BatchN" / "`ID` -> **Batch N**".
# A routing whose target isn't literally "Batch N" / "B<n>" (e.g. "-> pre-Olympia",
# "-> own tracked item", "-> standing") is a standing/pre-event item by convention,
# not a batch — exempt by construction, since neither pattern above matches it.
# See finding-resolution.md's PLACEMENT rule for the full disposition/exemption text.
#
# Read-only. Exit 0 = every routed finding is placed (PASS). Exit 1 = at least one
# orphan found (FAIL). Unlike the other lib/ ratchet scripts in this directory
# (informational, exit 0 always), this one gates: sprint-lifecycle.md's Rule 5
# close-out step runs this before a batch may be declared closed.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SPRINTS_DIR="$REPO_ROOT/.claude/sprints"
QUEUE="$SPRINTS_DIR/QUEUE.md"
CHASE="$SPRINTS_DIR/queue/chase-deferred.md"

if [ ! -f "$QUEUE" ]; then
    printf 'ERROR: %s not found\n' "$QUEUE" >&2
    exit 1
fi
if [ ! -f "$CHASE" ]; then
    printf 'ERROR: %s not found\n' "$CHASE" >&2
    exit 1
fi

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

PAIRS_FILE="$TMPDIR/pairs.txt" # "ID<TAB>BATCH<TAB>source" lines, deduped below
: >"$PAIRS_FILE"

# --- Pass 1: prose bullet-block routing in chase-deferred.md ---
# A top-level "- **-> Batch N" bullet (no leading whitespace) starts a block;
# indented continuation lines (incl. nested "  - **`ID`**" sub-bullets) extend it;
# any other top-level bullet or non-bullet line ends it. Non-"Batch N" bullets
# (own-item / verify-now / resolved / etc.) are never written to a block file —
# that's the exemption mechanism, not a separate allowlist to maintain.
awk -v tmp="$TMPDIR" '
BEGIN { active = 0; n = 0 }
/^- \*\*→ Batch [0-9]+/ {
    line = $0
    sub(/^- \*\*→ Batch /, "", line)
    match(line, /^[0-9]+/)
    batch = substr(line, RSTART, RLENGTH)
    n++
    active = 1
    outfile = tmp "/block-" batch "-" n ".txt"
    print $0 > outfile
    next
}
/^- \*\*→/ { active = 0; next }
active && /^[[:space:]]/ { print $0 > outfile; next }
{ active = 0 }
' "$CHASE"

for f in "$TMPDIR"/block-*.txt; do
    [ -e "$f" ] || continue
    base=$(basename "$f")
    batch=$(echo "$base" | sed -E 's/^block-([0-9]+)-.*/\1/')
    grep -oP '`[0-9A-Z][0-9A-Z._-]*`' "$f" | tr -d '`' | while read -r id; do
        printf '%s\t%s\tchase-bullet\n' "$id" "$batch" >>"$PAIRS_FILE"
    done
done

# --- Pass 2: inline "`ID` -> Batch N" / "`ID`->BatchN" / "`ID` -> B<n>" shorthand ---
# Applies to both files — QUEUE.md's own close-out rows use this shape too, when a
# finding resolved in one batch forwards a follow-up to a later batch.
for src in "$CHASE" "$QUEUE"; do
    label=$(basename "$src")
    grep -noP '`[0-9A-Z][0-9A-Z._-]*`[^`\n]{0,8}→\s*\*{0,2}B(atch)?\s*[0-9]+' "$src" 2>/dev/null |
        while IFS=: read -r lineno match; do
            id=$(echo "$match" | grep -oP '(?<=`)[0-9A-Z][0-9A-Z._-]*(?=`)')
            batch=$(echo "$match" | grep -oP '[0-9]+$')
            printf '%s\t%s\t%s:%s\n' "$id" "$batch" "$label" "$lineno" >>"$PAIRS_FILE"
        done
done

# --- Dedupe (ID, batch) pairs — keep first source seen for reporting ---
TAB="$(printf '\t')"
sort -u -t"$TAB" -k1,1 -k2,2 "$PAIRS_FILE" >"$TMPDIR/pairs-sorted.txt"
awk -F'\t' '!seen[$1"\t"$2]++' "$TMPDIR/pairs-sorted.txt" >"$TMPDIR/pairs-deduped.txt"

echo "## Finding Placement Check"
echo
echo "Scans $CHASE (routing bullets/shorthand) and $QUEUE (inline close-out"
echo "shorthand) for every finding carrying a \`-> Batch N\` routing, and confirms"
echo "each ID actually appears inside that batch's own \`### Batch N\` section body"
echo "in QUEUE.md — not just mentioned as an intent to route it there."
echo

TOTAL=0
ORPHAN_COUNT=0
ORPHAN_ROWS=()

while IFS=$'\t' read -r id batch source; do
    [ -z "$id" ] && continue
    TOTAL=$((TOTAL + 1))
    body=$(awk -v n="$batch" '
      $0 ~ ("^### Batch " n "([^.0-9]|$)") { flag = 1; next }
      flag && /^### / { exit }
      flag && /^## / { exit }
      flag { print }
    ' "$QUEUE")
    if grep -qF -- "$id" <<<"$body"; then
        status="PLACED"
    else
        status="ORPHAN"
        ORPHAN_COUNT=$((ORPHAN_COUNT + 1))
        ORPHAN_ROWS+=("$id | Batch $batch | first seen: $source")
    fi
    printf '%-40s -> Batch %-3s %-7s (%s)\n' "$id" "$batch" "$status" "$source"
done <"$TMPDIR/pairs-deduped.txt"

echo
echo "$TOTAL routed finding(s) checked, $ORPHAN_COUNT orphan(s)."
echo

if [ "$ORPHAN_COUNT" -gt 0 ]; then
    echo "FAIL — routed but not placed (logged != scheduled, per finding-resolution.md's"
    echo "PLACEMENT rule). Add a concrete line-item for each ID below inside its target"
    echo "batch's own section body before that batch can close:"
    echo
    for row in "${ORPHAN_ROWS[@]}"; do
        echo "  - $row"
    done
    exit 1
else
    echo "PASS — every routed finding has a matching line-item in its target batch."
    exit 0
fi
