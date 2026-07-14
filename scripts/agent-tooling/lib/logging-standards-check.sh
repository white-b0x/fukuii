#!/bin/bash
# logging-standards-check.sh — run the "Grep-verifiable ratchet targets" checks from
# .claude/agent-protocols/logging-standards.md in one call.
#
# Usage: logging-standards-check.sh
#
# Why this exists: the doc's closing section lists grep checks meant to be run
# together as a logging-quality ratchet. Running them one at a time is many tool
# calls; this runs all of them and reports count vs. stated target per check.
#
# Some checks (silent catch blocks, off-thread ctx.log, private-def ctx.log) are the
# doc's own "approximate — verify manually" checks: a nonzero count is a candidate list
# to read, not an automatic violation. Marked accordingly below.
#
# Read-only, fast — no logging, no backgrounding needed.
# Exit code: 0 always (this is a report, not a CI gate).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"

report() {
    local id="$1" desc="$2" target="$3" count="$4"
    if [ "$target" = "0" ]; then
        if [ "$count" -eq 0 ]; then
            printf '%-3s %-56s target=0   count=%-5d PASS\n' "$id" "$desc" "$count"
        else
            printf '%-3s %-56s target=0   count=%-5d FAIL\n' "$id" "$desc" "$count"
        fi
    else
        printf '%-3s %-56s %-11s count=%-5d\n' "$id" "$desc" "$target" "$count"
    fi
}

echo "### Logging Standards Ratchet Check — logging-standards.md"
echo

L1=$(grep -rn "log\.warning" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report L1 "log.warning (should be log.warn)" 0 "$L1"

L2=$(grep -rn "^\s*println\|System\.out\.print\|System\.err\.print\|printStackTrace" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report L2 "println / System.err|out.print / printStackTrace" 0 "$L2"

L3=$(grep -rn 'log\.\(debug\|info\|warn\|error\)(s"' src/main/ --include="*.scala" | wc -l | tr -d ' ')
report L3 "String interpolation in log calls" 0 "$L3"

L4=$(grep -rn 'log\.\(debug\|info\|warn\|error\)(.*" +' src/main/ --include="*.scala" | wc -l | tr -d ' ')
report L4 "String concatenation in log calls" 0 "$L4"

L5=$(grep -rn "} catch {" src/main/ --include="*.scala" -A5 | grep -v "log\.\|logger\." | grep -c "case.*=>" || true)
report L5 "Silent catch blocks (approximate)" "approx-0" "$L5"

L6=$(grep -rn "Behaviors\.unhandled" src/main/ --include="*.scala" -B3 | grep -v "log\." | grep -c "Behaviors\.unhandled" || true)
report L6 "Unhandled message handlers with no log" "approx-0" "$L6"

L7=$(grep -rn "IO\s*{" src/main/ --include="*.scala" -A30 | grep -c "ctx\.log\|context\.log" || true)
report L7 "context.log inside CE IO blocks" 0 "$L7"

L8=$(grep -rn "ctx\.log\|context\.log" src/main/ --include="*.scala" -B5 | grep -c "Future\|onComplete\|\.map\|\.flatMap\|\.recover" || true)
report L8 "context.log inside Future/callback closures" 0 "$L8"

L9=$(grep -rn "private def" src/main/ --include="*.scala" -A20 | grep -c "ctx\.log\|context\.log" || true)
report L9 "private defs using ctx.log (manual review)" "info-only" "$L9"

L10=$(grep -rn 'log\.\(info\|warn\|error\|debug\)("[A-Za-z][^=]*{}' src/main/ --include="*.scala" | wc -l | tr -d ' ')
report L10 "Positional log messages (no key= prefix)" 0 "$L10"

L11=$(grep -rn "MITHRIL-DEBUG\|TEMP DEBUG" src/main/ src/test/resources/ 2>/dev/null | wc -l | tr -d ' ')
report L11 "Leftover debug-instrumentation markers" 0 "$L11"

echo
echo "L5/L6 are the doc's own 'approximate' checks — a nonzero count is a candidate"
echo "list to read, not an automatic violation. L9 is informational: each hit needs"
echo "manual review for whether the private def is ever called off the actor thread"
echo "(see logging-standards.md's asyncLog section)."
