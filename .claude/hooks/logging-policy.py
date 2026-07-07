#!/usr/bin/env python3
"""Advisory PostToolUse hook: flag debug-print instrumentation in Scala edits.

Never blocks. On a Write/Edit/MultiEdit to a *.scala file it scans the ADDED
text for the debug-print calls `.agents/protocols/code-style/logging-standards.md`
(symlinked at `.claude/agent-protocols/logging-standards.md`) bans in source —
`System.err.println` / `System.out.println` (and their `print` variants),
`printStackTrace()`, and leftover `MITHRIL-DEBUG` / `TEMP DEBUG` markers — and,
if any are found, returns `additionalContext` so the model can self-correct in a
follow-up edit (use SLF4J at the appropriate level; instrument the test, not the
production code; remove any temporary debug before the task is done).

Companion to `comment-policy.py`, same structure and contract. This hook only
ever sees NEWLY ADDED lines (Write gets full `content`; Edit/MultiEdit get only
`new_string`/`edits[].new_string`) — forward-looking only, never a retroactive
scan (the one known pre-existing offender, `crypto/SignatureValidator.scala`, is
a separately-logged finding). Motivated by a real incident (2026-07-07): a
test-only agent task instrumented two production files with
`System.err.println("MITHRIL-DEBUG ...")` traces to chase a failing test and
left them in the tree. See `logging-standards.md` §"Debug instrumentation in
production code" and `testing-protocol.md` §"Test-only task scope boundary".

Bare `println(` is deliberately NOT flagged: legitimate CLI/`fukuii cli`
subcommand output uses it, so a mechanical flag would be false-positive noise.
The zero-tolerance grep done-gate in logging-standards.md still covers it at
review time.
"""
import json
import re
import sys

# (label, regex). Unambiguous debug-print cruft only — chosen to avoid
# false positives on legitimate CLI output (bare `println(` is intentionally
# excluded; see module docstring).
PATTERNS = [
    ("System.err debug print (→ use SLF4J; instrument the test, not production)",
     re.compile(r"System\.err\.print(?:ln)?\s*\(")),
    ("System.out debug print (→ use SLF4J; CLI output has its own path)",
     re.compile(r"System\.out\.print(?:ln)?\s*\(")),
    ("printStackTrace (→ log via SLF4J with the throwable, e.g. log.error(msg, ex))",
     re.compile(r"\.printStackTrace\s*\(")),
    ("leftover debug marker (→ remove before the task is done)",
     re.compile(r"MITHRIL-DEBUG|TEMP DEBUG|TEMP-DEBUG")),
]


def added_lines(tool_name, tool_input):
    if tool_name == "Write":
        text = tool_input.get("content", "")
    elif tool_name in ("Edit", "MultiEdit"):
        if "new_string" in tool_input:
            text = tool_input.get("new_string", "")
        else:
            text = "\n".join(e.get("new_string", "") for e in tool_input.get("edits", []))
    else:
        return []
    return text.splitlines()


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        sys.exit(0)  # never interfere

    tool_name = data.get("tool_name", "")
    tool_input = data.get("tool_input", {}) or {}
    path = tool_input.get("file_path", "")
    if not path.endswith(".scala"):
        sys.exit(0)

    flagged = []
    for ln in added_lines(tool_name, tool_input):
        for label, rx in PATTERNS:
            if rx.search(ln):
                flagged.append((label, ln.strip()))
                break

    if not flagged:
        sys.exit(0)

    is_prod = "/src/main/" in path or path.startswith("src/main/")
    scope = ("PRODUCTION code (zero tolerance) — " if is_prod else "")
    msg = [f"Logging-policy check (.claude/agent-protocols/logging-standards.md) — {scope}"
           "these added lines are debug-print instrumentation, not sanctioned logging. "
           "Use the SLF4J logger at the appropriate level; instrument the test, not the "
           "production code; and remove any temporary debug before the task is considered done:"]
    for label, line in flagged[:12]:
        snippet = line if len(line) <= 100 else line[:97] + "..."
        msg.append(f"  - [{label}] {snippet}")

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": "\n".join(msg),
        }
    }))
    sys.exit(0)


if __name__ == "__main__":
    main()
