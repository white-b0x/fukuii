#!/usr/bin/env python3
"""Advisory PreToolUse hook: nudge ad hoc compound Bash commands toward
`.local/scratch/` per `.agents/protocols/process/compound-command-scratch.md`
(symlinked at `.claude/agent-protocols/compound-command-scratch.md`).

Never blocks. Fires on PreToolUse (not PostToolUse) specifically because a
denied permission means the tool never runs — a PostToolUse hook would never
see exactly the cases this hook exists to catch. Detects commands that would
decompose (on `&&`/`;`/newline) into a bare shell-keyword fragment
(`for`/`while`/`until`/`if`/`then`/`case`/`esac`) or an ad hoc variable
assignment feeding a conditional/loop — both of which the Bash permission
system's prefix-matching can never fully allow-list, regardless of how large
`.claude/settings.local.json`'s allow-list grows.

Structurally mirrors `.claude/hooks/comment-policy.py`'s non-blocking shape:
parse stdin JSON inside a try/except that exits 0 on any failure, never sets
`permissionDecision`, only ever emits `additionalContext`, always exits 0.
"""
import json
import re
import sys

# Commands already targeting the scratch convention shouldn't be nagged about
# the fix they're already applying.
SCRATCH_RE = re.compile(r"\.local/scratch/")

# Strip quoted spans (both '...' and "...") before matching, so a plain-English
# word like "for"/"if" inside a quoted grep pattern or string argument doesn't
# false-positive. Naive (doesn't handle escaped quotes) — deliberately
# conservative: a missed strip only means an occasional extra nudge, never a
# blocked command.
QUOTED_RE = re.compile(r"'[^']*'|\"[^\"]*\"")

CHECKS = [
    ("for/while/until loop with a body",
     re.compile(r"\b(for|while|until)\b[\s\S]{0,300}?\bdo\b")),
    ("if/then conditional with a body",
     re.compile(r"\bif\b[\s\S]{0,300}?\bthen\b")),
    ("case/esac construct",
     re.compile(r"\bcase\b[\s\S]{0,300}?\besac\b")),
    ("ad hoc variable assignment feeding a conditional/loop",
     re.compile(r"\b\w+=\S*\s*[;\n]\s*(if|for|while)\b")),
]


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        sys.exit(0)  # never interfere

    if data.get("tool_name") != "Bash":
        sys.exit(0)

    command = (data.get("tool_input") or {}).get("command", "")
    if not command:
        sys.exit(0)

    if SCRATCH_RE.search(command):
        sys.exit(0)  # already using the target pattern — don't nag about the fix itself

    stripped = QUOTED_RE.sub("", command)

    matched = None
    for label, rx in CHECKS:
        if rx.search(stripped):
            matched = label
            break

    if not matched:
        sys.exit(0)

    msg = (
        "Compound-command check (.claude/agent-protocols/compound-command-scratch.md) — "
        f"this command looks like a {matched}. Shell keywords and ad hoc variable "
        "assignments can't be reliably pre-allow-listed by the Bash permission system, "
        "so this may need approval even with a large allow-list. Consider writing it "
        "once to .local/scratch/<slug>.sh and running `bash .local/scratch/<slug>.sh` "
        "instead."
    )

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "additionalContext": msg,
        }
    }))
    sys.exit(0)


if __name__ == "__main__":
    main()
