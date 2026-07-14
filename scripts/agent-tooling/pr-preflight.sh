#!/bin/bash
# pr-preflight.sh — one composite, background-safe pass over everything CI actually
# gates on a PR against chippr-robotics/fukuii: scalafmt, compile, Tier 1 tests, and
# (only when docs/** changed) the mkdocs --strict build + a doc-link check.
#
# Usage: pr-preflight.sh <log-name> [<base-ref>]
#   log-name    basename (no extension) for the log file under .local/logs/
#   base-ref    optional; if omitted, resolved automatically (see below) — never
#               guessed as main/develop, always resolved to the real chippr-robotics/
#               fukuii `staging` branch or a clear error asking for an explicit ref.
#
# Example:
#   scripts/agent-tooling/pr-preflight.sh july-fourth-preflight
#   scripts/agent-tooling/pr-preflight.sh july-fourth-preflight upstream/staging
#
# Why this exists: two of PR #1387's three failing checks (scalafmt, mkdocs --strict)
# were real, fixable-by-us regressions that this script catches before push — see
# .agents/protocols/tooling/pr-preflight-checklist.md for the full gate breakdown and
# the two known-upstream-bug categories this script deliberately does not try to fix
# (lychee CLI arg break, missing fork-guard on docs-link-check.yml's comment step).
#
# Portability: this script does NOT hardcode `upstream/staging`. It must resolve the
# same logical base ref (chippr-robotics/fukuii's `staging` branch) whether run from a
# fork clone (this one: origin=white-b0x/fukuii, upstream=chippr-robotics/fukuii) or
# from inside the canonical repo itself (no `upstream` remote — origin IS
# chippr-robotics/fukuii). See the resolution order below and in the protocol doc.
#
# Invoke with the caller's background-execution option (e.g. Bash tool
# `run_in_background: true`) — see background-script-execution.md. All sbt/mkdocs/
# lychee output goes to the log file, never streamed live.
#
# Exit code: 0 if every gate that actually ran came back PASS (WARN categories that
# could not be locally verified do not count as failure); non-zero if any gate FAILed.

set -uo pipefail

if [ "$#" -lt 1 ]; then
    printf 'Usage: %s <log-name> [<base-ref>]\n' "$(basename "$0")" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

LOG_NAME="$1"
EXPLICIT_BASE_REF="${2:-}"
LOG_DIR="$REPO_ROOT/.local/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/${LOG_NAME}.log"

log() { printf '%s\n' "$*" >>"$LOG_FILE"; }

{
    printf '## pr-preflight.sh started %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >"$LOG_FILE"

# --- Resolve base ref -------------------------------------------------------
# Order: explicit arg > upstream/staging (fork clone) > origin/staging (canonical
# repo clone, verified by origin's URL) > local `staging` branch > fail loudly.
BASE_REF=""
BASE_REF_SOURCE=""

if [ -n "$EXPLICIT_BASE_REF" ]; then
    BASE_REF="$EXPLICIT_BASE_REF"
    BASE_REF_SOURCE="explicit arg"
elif git remote get-url upstream >/dev/null 2>&1 && git rev-parse --verify --quiet upstream/staging >/dev/null; then
    BASE_REF="upstream/staging"
    BASE_REF_SOURCE="remote 'upstream' has a staging branch"
elif git rev-parse --verify --quiet origin/staging >/dev/null \
    && grep -qi 'chippr-robotics/fukuii' <<<"$(git remote get-url origin 2>/dev/null)"; then
    BASE_REF="origin/staging"
    BASE_REF_SOURCE="origin IS chippr-robotics/fukuii and has a staging branch"
elif git rev-parse --verify --quiet refs/heads/staging >/dev/null; then
    BASE_REF="staging"
    BASE_REF_SOURCE="local branch 'staging'"
else
    log "ERROR: could not resolve a base ref. Checked:"
    log "  - no <base-ref> arg given"
    log "  - no remote named 'upstream' with a 'staging' branch"
    log "  - origin is not chippr-robotics/fukuii (or has no 'staging' branch)"
    log "  - no local branch named 'staging'"
    log "Pass an explicit <base-ref> instead: $(basename "$0") <log-name> <base-ref>"
    printf 'FAIL: could not resolve base ref — see %s\n' "$LOG_FILE" >&2
    exit 2
fi

log "## base ref resolved: $BASE_REF ($BASE_REF_SOURCE)"

if ! git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
    log "ERROR: resolved base ref '$BASE_REF' does not exist in this clone."
    printf 'FAIL: base ref %s not found — see %s\n' "$BASE_REF" "$LOG_FILE" >&2
    exit 2
fi

# --- Detect whether docs changed (mirrors docs-preview.yml's own paths: filter) ---
CHANGED_FILES="$(git diff --name-only "${BASE_REF}...HEAD" 2>>"$LOG_FILE")"
log ""
log "## changed files vs ${BASE_REF}:"
log "$CHANGED_FILES"

DOCS_PATTERN='^(docs/|mkdocs\.yml$|requirements-docs\.txt$|scripts/convert_insomnia_to_openapi\.py$|scripts/validate_openapi\.py$|insomnia_workspace\.json$|\.github/workflows/docs-preview\.yml$)'

DOCS_CHANGED=0
# Use a here-string, not `printf ... | grep -q`: grep -q exits as soon as it finds a
# match, which can SIGPIPE the writer on the other end of a real pipe — combined with
# `pipefail` that makes the whole `if` see a false failure even though grep matched.
if grep -qE "$DOCS_PATTERN" <<<"$CHANGED_FILES"; then
    DOCS_CHANGED=1
fi

NON_DOCS_CHANGED=0
if grep -qvE "$DOCS_PATTERN" <<<"$CHANGED_FILES"; then
    NON_DOCS_CHANGED=1
fi

log ""
log "## docs changed: $DOCS_CHANGED / non-docs source changed: $NON_DOCS_CHANGED"

# --- Result tracking ---------------------------------------------------------
declare -A RESULT
RESULT[format]="SKIP"
RESULT[compile]="SKIP"
RESULT[tests]="SKIP"
RESULT[docs-build]="SKIP"
RESULT[doc-links]="SKIP"

OVERALL_FAIL=0

run_sbt_task() {
    local key="$1" task="$2"
    log ""
    log "## sbt $task started $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    if sbt -no-colors -Dsbt.supershell=false "$task" >>"$LOG_FILE" 2>&1; then
        RESULT[$key]="PASS"
    else
        RESULT[$key]="FAIL"
        OVERALL_FAIL=1
    fi
    log "## sbt $task finished $(date -u +%Y-%m-%dT%H:%M:%SZ) — ${RESULT[$key]}"
}

# --- sbt gate: scalafmt -> compile -> testEssential, fail-fast like ci.yml's steps ---
if [ "$NON_DOCS_CHANGED" -eq 1 ]; then
    run_sbt_task format scalafmtCheckAll
    if [ "${RESULT[format]}" = "PASS" ]; then
        run_sbt_task compile compile-all
    fi
    if [ "${RESULT[compile]}" = "PASS" ]; then
        run_sbt_task tests testEssential
    fi
else
    log ""
    log "## no non-docs source changed vs ${BASE_REF} — skipping sbt gate"
fi

# --- docs gate: mkdocs build --strict, only if docs/** changed ---
if [ "$DOCS_CHANGED" -eq 1 ]; then
    MKDOCS_BIN=""
    for candidate in mkdocs .venv/bin/mkdocs venv/bin/mkdocs .local/venv-docs/bin/mkdocs; do
        if command -v "$candidate" >/dev/null 2>&1; then
            MKDOCS_BIN="$candidate"
            break
        elif [ -x "$REPO_ROOT/$candidate" ]; then
            MKDOCS_BIN="$REPO_ROOT/$candidate"
            break
        fi
    done

    if [ -n "$MKDOCS_BIN" ]; then
        log ""
        log "## $MKDOCS_BIN build --strict started $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        if "$MKDOCS_BIN" build --strict >>"$LOG_FILE" 2>&1; then
            RESULT[docs-build]="PASS"
        else
            RESULT[docs-build]="FAIL"
            OVERALL_FAIL=1
        fi
        log "## mkdocs build --strict finished $(date -u +%Y-%m-%dT%H:%M:%SZ) — ${RESULT[docs-build]}"
    else
        RESULT[docs-build]="WARN"
        log ""
        log "## WARN: docs build not locally verifiable, mkdocs not installed (pip install -r requirements-docs.txt into a venv to enable this check)"
    fi

    # --- doc-links gate: lychee against the freshly-built site/, only if lychee is on PATH ---
    if command -v lychee >/dev/null 2>&1; then
        if [ "${RESULT[docs-build]}" = "PASS" ]; then
            log ""
            log "## lychee started $(date -u +%Y-%m-%dT%H:%M:%SZ)"
            if lychee --verbose --no-progress --accept 200,204 \
                --exclude 'localhost' --exclude '127.0.0.1' \
                --exclude 'github.com/.*/edit/' --exclude 'github.com/.*/issues/new' \
                './site/**/*.html' >>"$LOG_FILE" 2>&1; then
                RESULT[doc-links]="PASS"
            else
                RESULT[doc-links]="FAIL"
                OVERALL_FAIL=1
            fi
            log "## lychee finished $(date -u +%Y-%m-%dT%H:%M:%SZ) — ${RESULT[doc-links]}"
        else
            RESULT[doc-links]="WARN"
            log ""
            log "## WARN: doc-link check skipped, ./site/ was not built (docs-build did not PASS)"
        fi
    else
        RESULT[doc-links]="WARN"
        log ""
        log "## WARN: doc-link check not locally verifiable, lychee not installed"
    fi
else
    log ""
    log "## docs/** unchanged vs ${BASE_REF} — skipping docs-build and doc-links gates"
fi

# --- Final report -------------------------------------------------------------
{
    printf '\n## pr-preflight.sh finished %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '\n%-12s %s\n' "CATEGORY" "RESULT"
    printf '%-12s %s\n' "format" "${RESULT[format]}"
    printf '%-12s %s\n' "compile" "${RESULT[compile]}"
    printf '%-12s %s\n' "tests" "${RESULT[tests]}"
    printf '%-12s %s\n' "docs-build" "${RESULT[docs-build]}"
    printf '%-12s %s\n' "doc-links" "${RESULT[doc-links]}"
    printf '\nWARN categories are not locally verifiable (missing tool) or known upstream\n'
    printf 'bugs, not a regression from your branch — see\n'
    printf '.agents/protocols/tooling/pr-preflight-checklist.md before chasing one.\n'
} >>"$LOG_FILE"

printf 'DONE log=%s exit=%d — format=%s compile=%s tests=%s docs-build=%s doc-links=%s\n' \
    "$LOG_FILE" "$OVERALL_FAIL" \
    "${RESULT[format]}" "${RESULT[compile]}" "${RESULT[tests]}" "${RESULT[docs-build]}" "${RESULT[doc-links]}"
exit "$OVERALL_FAIL"
