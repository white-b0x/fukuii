#!/bin/bash
# pekko-typed-check.sh — run the grep-verifiable checks from
# .claude/agent-protocols/pekko-typed-api.md (P1-P25, TL1-TL2, CAPSTONE sweep) in one call.
#
# Usage: pekko-typed-check.sh
#
# Why this exists: the doc has ~20 independent grep checks scattered across 25 numbered
# preferences plus a CAPSTONE sweep section. Running them one at a time during a PRISM
# review or pre-CAPSTONE audit is 20+ tool calls; this runs all of them in one pass.
#
# IMPORTANT — migration state context (see doc's top section): the codebase is
# mid-migration from Classic to Typed. Classic adapters (.toClassic, PropsAdapter,
# Behavior[Any]) are INTENTIONAL SCAFFOLDING until CAPSTONE. The CAPSTONE section below
# is informational during migration — nonzero counts there are expected and are not
# regressions until the CAPSTONE close-out sweep.
#
# Checks needing a specific actor/file name as a parameter (P9's Terminated-caller grep,
# P10's per-file @volatile check, P21's ChildFailed candidate review) are NOT included —
# they're templated for one actor at a time, not a fixed repo-wide ratchet. Run those by
# hand per pre-migration-checklist.sh's actor-specific output instead. Pure code-pattern
# preferences with no grep (P4-P8, P13-P15) are also not included.
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
            printf '%-4s %-56s target=0   count=%-5d PASS\n' "$id" "$desc" "$count"
        else
            printf '%-4s %-56s target=0   count=%-5d FAIL\n' "$id" "$desc" "$count"
        fi
    else
        printf '%-4s %-56s %-11s count=%-5d\n' "$id" "$desc" "$target" "$count"
    fi
}

echo "### Pekko Typed API Ratchet Check — pekko-typed-api.md"
echo "### Section 1: Enforced now (target 0)"
echo

P1=$(grep -rn "context\.system\.scheduler\|system\.scheduler" src/main/ --include="*.scala" | grep -v "//\|test\|Classic\|\.toClassic" | wc -l | tr -d ' ')
report P1 "Raw scheduler instead of withTimers" 0 "$P1"

P3=$(grep -rn "sender()" src/main/ --include="*.scala" | grep -v "extends Actor\|//\|test" | wc -l | tr -d ' ')
report P3 "sender() in Typed actor" 0 "$P3"

P16=$(grep -rn "class.*(.*ActorRef\b" src/main/ --include="*.scala" | grep -v "typed\.ActorRef\|ActorRef\[" | grep -v "//.*ActorRef" | wc -l | tr -d ' ')
report P16 "Classic ActorRef constructor params" 0 "$P16"

P20=$(grep -rn "SupervisorStrategy\.restart\b" src/main/ --include="*.scala" | grep -v "withLimit\|restartWithBackoff\|WithLimit" | wc -l | tr -d ' ')
report P20 "Unbounded SupervisorStrategy.restart" 0 "$P20"

P22=$(grep -rn "org\.slf4j\.MDC\|MDC\.put\|MDC\.clear" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report P22 "Manual MDC (should be Behaviors.withMdc)" 0 "$P22"

TL1=$(grep -rn "IORuntime\.global" src/main/ --include="*.scala" | grep -v "NodeApp\|DiscoveryService" | wc -l | tr -d ' ')
report TL1 "IORuntime.global outside composition root" 0 "$TL1"

echo
echo "### Section 2: Informational — review each hit, no hard target"
echo

P2=$(grep -rn "override def preStop\|override def postStop" src/main/ --include="*.scala" | grep -v "//\|extends Actor" | wc -l | tr -d ' ')
report P2 "preStop/postStop outside Classic actors" "info-only" "$P2"

P11a=$(grep -rn "IO\s*{" src/main/ --include="*.scala" -A30 | grep -c "ctx\.log\|context\.log" || true)
report P11a "ctx.log inside CE IO blocks (asyncLog violation)" "info-only" "$P11a"

P11b=$(grep -rn "ctx\.log\|context\.log" src/main/ --include="*.scala" -B5 | grep -c "Future\|onComplete\|\.map\|\.flatMap\|\.recover" || true)
report P11b "ctx.log inside Future/callback closures" "info-only" "$P11b"

P11c=$(grep -rn "private def" src/main/ --include="*.scala" -A20 | grep -c "ctx\.log\|context\.log" || true)
report P11c "private defs using ctx.log (indirect risk)" "info-only" "$P11c"

P12=$(grep -rn "preMaterialize\|fromMaterializer" src/ --include="*.scala" | wc -l | tr -d ' ')
report P12 "preMaterialize/fromMaterializer (review legit vs. eager-subscribe)" "info-only" "$P12"

P17=$(grep -rn "messageAdapter\[" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report P17 "messageAdapter sites (verify: setup only, not in receive)" "info-only" "$P17"

P18=$(grep -rn "spawnAnonymous" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report P18 "spawnAnonymous sites (verify: no watchWith needed)" "info-only" "$P18"

P25A=$(grep -rn "messageAdapter\[" src/main/ --include="*.scala" | wc -l | tr -d ' ')
P25B=$(grep -rn "\.narrow\[" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report P25 "messageAdapter=$P25A vs .narrow[]=$P25B (prefer narrow for subtype replies)" "info-only" "$P25B"

TL2=$(grep -rn "unsafeRunSync" src/main/ --include="*.scala" | grep -v "//\|Benchmark\|NodeApp\|Main" | wc -l | tr -d ' ')
report TL2 "unsafeRunSync outside composition root/tests" "info-only" "$TL2"

echo
echo "### Section 3: Cross-reference checks"
echo

# P19 — files with Behaviors.supervise/onFailure that lack a PreRestart handler
SUPERVISED_FILES=$(grep -rl "Behaviors\.supervise\|\.onFailure\[" src/main/ --include="*.scala" 2>/dev/null || true)
P19_MISSING=0
P19_LIST=""
if [ -n "$SUPERVISED_FILES" ]; then
    while IFS= read -r f; do
        [ -z "$f" ] && continue
        if ! grep -q "PreRestart" "$f"; then
            P19_MISSING=$((P19_MISSING + 1))
            P19_LIST="$P19_LIST    $f\n"
        fi
    done <<< "$SUPERVISED_FILES"
fi
report P19 "Supervised actors missing PreRestart handler" 0 "$P19_MISSING"
[ "$P19_MISSING" -gt 0 ] && printf "$P19_LIST"

# P23 — test files using timers without ManualTime
P23_FILES=$(grep -rl "withTimers\|startTimerWithFixedDelay\|startSingleTimer" src/test/ --include="*.scala" 2>/dev/null | xargs -r grep -L "ManualTime" 2>/dev/null || true)
P23_COUNT=$(printf '%s\n' "$P23_FILES" | grep -c . || true)
report P23 "Timer specs without ManualTime" 0 "$P23_COUNT"
[ -n "$P23_FILES" ] && printf '%s\n' "$P23_FILES" | sed 's/^/    /'

# P24 — supervised actor specs missing a LoggingTestKit assertion
P24_MISSING=0
P24_LIST=""
if [ -n "$SUPERVISED_FILES" ]; then
    while IFS= read -r f; do
        [ -z "$f" ] && continue
        base=$(basename "$f" .scala)
        if ! grep -rl "LoggingTestKit" src/test/ --include="*${base}Spec*" >/dev/null 2>&1; then
            P24_MISSING=$((P24_MISSING + 1))
            P24_LIST="$P24_LIST    MISSING: $base\n"
        fi
    done <<< "$SUPERVISED_FILES"
fi
report P24 "Supervised actor specs missing LoggingTestKit" 0 "$P24_MISSING"
[ "$P24_MISSING" -gt 0 ] && printf "$P24_LIST"

echo
echo "### Section 4: CAPSTONE-only — informational during migration, not a current regression"
echo

C1=$(grep -rn "\.toClassic\b" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report C1 "Classic adapters (.toClassic)" "post-CAPSTONE=0" "$C1"

C2=$(grep -rn "PropsAdapter\b" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report C2 "PropsAdapter usages" "post-CAPSTONE=0" "$C2"

C3=$(grep -rn "Behavior\[Any\]" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report C3 "Behavior[Any] (should narrow to real type)" "post-CAPSTONE=0" "$C3"

C4=$(grep -rn "ActorSystem\b" src/main/ --include="*.scala" | wc -l | tr -d ' ')
report C4 "ActorSystem (should be ActorSystem[Nothing])" "post-CAPSTONE=0" "$C4"

C5=$(grep -rn "@volatile" src/main/ --include="*.scala" | grep -v "extends Actor\b" | wc -l | tr -d ' ')
report C5 "@volatile in Typed actors (P10)" "post-CAPSTONE=0" "$C5"

echo
echo "Sections 2 and 4 are read-and-decide, not pass/fail — see pekko-typed-api.md for"
echo "the corresponding preference before treating any nonzero count as an action item."
