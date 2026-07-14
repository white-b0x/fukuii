#!/bin/bash
# scala3-style-check.sh — run the Scala 3 style ratchet checks (S1-S9) from
# .claude/agent-protocols/scala3-style.md in one call.
#
# Usage: scala3-style-check.sh
#
# Why this exists: scala3-style.md documents 9 independent grep-verifiable ratchet
# checks (S1-S9), each meant to be run and compared against a stated target (usually
# "0 hits"). Running them one at a time is 9 separate tool calls; this runs all of them
# and reports count vs. target per check.
#
# S10 has no grep (it's a policy for new code only, not a regression check). S11 is a
# path-specific sweep tool, not a fixed-target ratchet (the doc says "adjust path") —
# use site-sweep.sh for that instead.
#
# Read-only, fast (well under a second) — no logging, no backgrounding needed.
# Exit code: 0 always (this is a report, not a CI gate).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"

report() {
    local id="$1" desc="$2" target="$3" count="$4"
    if [ "$target" = "0" ]; then
        if [ "$count" -eq 0 ]; then
            printf '%-4s %-52s target=0   count=%-5d PASS\n' "$id" "$desc" "$count"
        else
            printf '%-4s %-52s target=0   count=%-5d FAIL\n' "$id" "$desc" "$count"
        fi
    else
        printf '%-4s %-52s %-11s count=%-5d\n' "$id" "$desc" "$target" "$count"
    fi
}

echo "### Scala 3 Style Ratchet Check (S1-S9, S12) — scala3-style.md"
echo

S1=$(grep -rn "\breturn\b" src/main/ --include="*.scala" | grep -v "//\|\"" | wc -l | tr -d ' ')
report S1 "No return statements" 0 "$S1"

S2=$(grep -rn "\bnull\b" src/main/ --include="*.scala" | grep -v "//\|test\|@null" | wc -l | tr -d ' ')
report S2 "No null" 0 "$S2"

S3=$(grep -rn "implicit val\|implicit def\|implicit lazy val" src/main/ --include="*.scala" \
  | grep -v "consensus/\|vm/\|crypto/\|//\|@nowarn\|not given.*override" | wc -l | tr -d ' ')
report S3 "implicit val/def/lazy val (given/using)" "info-only" "$S3"

S4=$(grep -rn "implicit class\b" src/main/ --include="*.scala" | grep -v "//\|consensus/\|vm/" | wc -l | tr -d ' ')
report S4 "implicit class (extension methods)" 0 "$S4"

S5=$(grep -rn "sealed trait\|sealed abstract class" src/main/ --include="*.scala" | grep -v "consensus/\|vm/" | wc -l | tr -d ' ')
report S5 "sealed trait/abstract class (enum candidates)" "info-only" "$S5"

S6=$(grep -rn "self:.*with\b" src/main/ --include="*.scala" | grep "=>" | wc -l | tr -d ' ')
report S6 "self: A with B (intersection syntax)" 0 "$S6"

S7=$(grep -rn "asInstanceOf\[" src/main/ --include="*.scala" | grep -v "consensus/\|vm/\|crypto/\|//" | wc -l | tr -d ' ')
report S7 "asInstanceOf outside consensus/vm/crypto" 0 "$S7"

S8=$(grep -rn "isInstanceOf\[" src/main/ --include="*.scala" | grep -v "//\|consensus/\|vm/" | wc -l | tr -d ' ')
report S8 "isInstanceOf outside consensus/vm" 0 "$S8"

S9=$(grep -rn "println\|System\.out\|System\.err" src/main/ --include="*.scala" | grep -v "//\|Benchmark" | wc -l | tr -d ' ')
report S9 "println/System.out/System.err in main" 0 "$S9"

S12=$(grep -rEn "given (Ordering|Numeric|Fractional|Integral)\[.*\] = .*\.by\(_\." src/main/ --include="*.scala" | wc -l | tr -d ' ')
report S12 "bare/unpinned opaque-type given (self-ref deadlock risk)" 0 "$S12"

echo
echo "S10 (braceless syntax) has no fixed-target grep — policy for new code only."
echo "S11 (opaque type full-layer) is a path-specific sweep, not a fixed ratchet —"
echo "use site-sweep.sh with the patterns in scala3-style.md's S11 section instead."
echo
echo "'info-only' rows have no hard target — read scala3-style.md's corresponding"
echo "section before treating any count as an action item; consensus-critical"
echo "changes (S3-S11) require FORGE/BEACON review regardless of count."
