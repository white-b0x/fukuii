#!/bin/bash
# pre-migration-checklist.sh — run LOOM's pre-flight checklist against one actor file
#
# Usage: pre-migration-checklist.sh <path/to/ActorName.scala>
#   path can be repo-root-relative or absolute.
#
# Example:
#   scripts/agent-tooling/lib/pre-migration-checklist.sh src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/BlockFetcher.scala
#
# Why this exists: .claude/agent-protocols/pre-migration-checklist.md lists 13 mechanical
# grep steps that must be run, by hand, against a single actor file before every LOOM
# migration session — then hand-transcribed into a "Pre-flight facts" block. This script
# runs all 13 checks in one call and prints the facts block directly in the protocol's own
# format, plus flags the mechanically-detectable red flags from the protocol's red-flag table.
#
# This is a fast, read-only, single-invocation collector script — no log file, no
# backgrounding needed (see background-script-execution.md for that separate pattern,
# which applies to long/noisy commands; this one finishes in well under a second).
#
# Exit code: 0 always (this is a report, not a pass/fail gate). Non-zero only on usage error
# or if the target file does not exist.

set -uo pipefail

if [ "$#" -ne 1 ]; then
    printf 'Usage: %s <path/to/ActorName.scala>\n' "$(basename "$0")" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"

TARGET="$1"
if [ ! -f "$TARGET" ]; then
    # allow a repo-root-relative path even when invoked from elsewhere
    if [ -f "$REPO_ROOT/$TARGET" ]; then
        TARGET="$REPO_ROOT/$TARGET"
    else
        printf 'ERROR: file not found: %s\n' "$TARGET" >&2
        exit 2
    fi
fi

ACTOR_NAME="$(basename "$TARGET" .scala)"

# ── 1. Line count and behavior count ────────────────────────────────────────
LOC=$(wc -l < "$TARGET" | tr -d ' ')
BEHAVIORS=$(grep -n "def receive\|def idle\|def working\|def finalizing\|def draining\|context\.become" "$TARGET" || true)
BEHAVIOR_COUNT=$(printf '%s\n' "$BEHAVIORS" | grep -c . || true)

# ── 2. sender() call sites ───────────────────────────────────────────────────
SENDER_SITES=$(grep -n "sender()" "$TARGET" || true)
SENDER_COUNT=$(printf '%s\n' "$SENDER_SITES" | grep -c . || true)

# ── 3. return statements ─────────────────────────────────────────────────────
RETURN_SITES=$(grep -n "\breturn\b" "$TARGET" | grep -v "//\|\".*return" || true)
RETURN_COUNT=$(printf '%s\n' "$RETURN_SITES" | grep -c . || true)

# ── 4. log.warning sites ─────────────────────────────────────────────────────
LOG_WARNING_SITES=$(grep -n "log\.warning" "$TARGET" || true)
LOG_WARNING_COUNT=$(printf '%s\n' "$LOG_WARNING_SITES" | grep -c . || true)

# ── 5. Timers and schedulers ─────────────────────────────────────────────────
TIMER_SITES=$(grep -n "scheduler\|schedule\|schedulerOnce\|scheduleAtFixedRate\|scheduleWithFixedDelay\|Cancellable" "$TARGET" || true)
TIMER_COUNT=$(printf '%s\n' "$TIMER_SITES" | grep -c . || true)

# ── 6. context.become / multiple behaviors ──────────────────────────────────
BECOME_SITES=$(grep -n "context\.become\|context\.unbecome\|become(" "$TARGET" || true)

# ── 7. Worker actors spawned ────────────────────────────────────────────────
WORKER_SITES=$(grep -n "context\.actorOf(.*Props(" "$TARGET" || true)
WORKER_COUNT=$(printf '%s\n' "$WORKER_SITES" | grep -c . || true)

# ── 8. @volatile fields and mutable state ───────────────────────────────────
VOLATILE_SITES=$(grep -n "@volatile" "$TARGET" || true)
VOLATILE_COUNT=$(printf '%s\n' "$VOLATILE_SITES" | grep -c . || true)
VAR_SITES=$(grep -n "var " "$TARGET" || true)
VAR_COUNT=$(printf '%s\n' "$VAR_SITES" | grep -c . || true)

# ── 9. ActorLogging / logging pattern ───────────────────────────────────────
ACTOR_LOGGING=$(grep -q "ActorLogging" "$TARGET" && echo yes || echo no)

# ── 10. preStart / postStop lifecycle hooks ─────────────────────────────────
PRESTART=$(grep -q "override def preStart" "$TARGET" && echo yes || echo no)
POSTSTOP=$(grep -q "override def postStop" "$TARGET" && echo yes || echo no)

# ── 11. context.watch / death watch ─────────────────────────────────────────
WATCH=$(grep -qE "context\.watch|context\.unwatch|Terminated" "$TARGET" && echo yes || echo no)

# ── 12. Classic callers not in session scope ────────────────────────────────
CALLER_FILES=$(grep -rl "$ACTOR_NAME\b" src/main/ --include="*.scala" 2>/dev/null | grep -v "^$TARGET$" | grep -v "${ACTOR_NAME}Spec" || true)

# ── 13. Constructor params — spawn-site .toClassic audit ───────────────────
CTOR_ACTORREF=$(grep -n "ActorRef\b" "$TARGET" | grep -v "typed\.ActorRef\|ActorRef\[" || true)
SPAWN_SITES=$(grep -rn "${ACTOR_NAME}\b\|${ACTOR_NAME}\.apply\|Props(.*${ACTOR_NAME}" src/main/ --include="*.scala" 2>/dev/null | grep -v "//\|Spec\|test" || true)

# ── Mechanically-detectable red flags ───────────────────────────────────────
RED_FLAGS=()
[ "$WORKER_COUNT" -ge 10 ] && RED_FLAGS+=("Actor spawns $WORKER_COUNT workers (>=10) — map all workers first, may need separate sprint")
[ "$LOC" -gt 2000 ] && [ "$BEHAVIOR_COUNT" -ge 4 ] && RED_FLAGS+=("File is $LOC LOC with $BEHAVIOR_COUNT behaviors (>2000 LOC, 4+ behaviors) — split into subsession plan")
grep -q "context\.system\.eventStream" "$TARGET" && RED_FLAGS+=("Uses context.system.eventStream — route to FORGE/HERALD, PSH serialization pre-flight required")
grep -qE "akka\.remote|pekko\.remote" "$TARGET" && RED_FLAGS+=("Uses akka.remote/pekko.remote — serialization review (PSH) required, route to FORGE")
grep -qE "@SerializationProxy|readResolve" "$TARGET" && RED_FLAGS+=("Has @SerializationProxy or readResolve — serialization review required")
case "$TARGET" in
    */consensus/*|*/vm/*) RED_FLAGS+=("File is in consensus/ or vm/ — FORGE (ETC) or BEACON (ETH) review before touching workers/spawn sites") ;;
esac

# ── Output: the protocol's own "Pre-flight facts block" format ─────────────
echo "### Pre-flight facts — $ACTOR_NAME"
echo
echo "- File: ${TARGET#$REPO_ROOT/}"
echo "- LOC: $LOC"
echo "- Behaviors: $BEHAVIOR_COUNT"
[ -n "$BEHAVIORS" ] && printf '%s\n' "$BEHAVIORS" | sed 's/^/    /'
echo "- sender() sites: $SENDER_COUNT"
[ -n "$SENDER_SITES" ] && printf '%s\n' "$SENDER_SITES" | sed 's/^/    /'
echo "- return statements: $RETURN_COUNT"
[ -n "$RETURN_SITES" ] && printf '%s\n' "$RETURN_SITES" | sed 's/^/    /'
echo "- log.warning sites: $LOG_WARNING_COUNT"
[ -n "$LOG_WARNING_SITES" ] && printf '%s\n' "$LOG_WARNING_SITES" | sed 's/^/    /'
echo "- Schedulers: $TIMER_COUNT"
[ -n "$TIMER_SITES" ] && printf '%s\n' "$TIMER_SITES" | sed 's/^/    /'
echo "- context.become sites: $(printf '%s\n' "$BECOME_SITES" | grep -c . || true)"
[ -n "$BECOME_SITES" ] && printf '%s\n' "$BECOME_SITES" | sed 's/^/    /'
echo "- Workers spawned (context.actorOf(Props(...))): $WORKER_COUNT"
[ -n "$WORKER_SITES" ] && printf '%s\n' "$WORKER_SITES" | sed 's/^/    /'
echo "- @volatile fields: $VOLATILE_COUNT"
[ -n "$VOLATILE_SITES" ] && printf '%s\n' "$VOLATILE_SITES" | sed 's/^/    /'
echo "- var fields (state, informational): $VAR_COUNT"
echo "- extends ActorLogging: $ACTOR_LOGGING"
echo "- preStart hook: $PRESTART"
echo "- postStop hook: $POSTSTOP"
echo "- context.watch / Terminated used: $WATCH"
echo "- Constructor Classic ActorRef params:"
[ -n "$CTOR_ACTORREF" ] && printf '%s\n' "$CTOR_ACTORREF" | sed 's/^/    /' || echo "    (none)"
echo "- Callers referencing $ACTOR_NAME in src/main/ (excluding self/spec):"
[ -n "$CALLER_FILES" ] && printf '%s\n' "$CALLER_FILES" | sed "s|^|    |" || echo "    (none found)"
echo "- Spawn sites (Props(...$ACTOR_NAME...)) in src/main/:"
[ -n "$SPAWN_SITES" ] && printf '%s\n' "$SPAWN_SITES" | sed 's/^/    /' || echo "    (none found)"
echo
if [ "${#RED_FLAGS[@]}" -gt 0 ]; then
    echo "### Red flags — stop and consult"
    echo
    for flag in "${RED_FLAGS[@]}"; do
        echo "- $flag"
    done
else
    echo "### Red flags: none mechanically detected"
    echo "(Still manually check: Java interop access — not greppable from this file alone.)"
fi
