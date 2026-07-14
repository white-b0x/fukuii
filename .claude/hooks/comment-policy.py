#!/usr/bin/env python3
"""Advisory PostToolUse hook: flag comment-policy violations in Scala edits.

Never blocks. On a Write/Edit/MultiEdit to a *.scala file it scans the ADDED
text's comment lines for the narration `.agents/protocols/code-style/comments.md`
(symlinked at `.claude/agent-protocols/comments.md`) says belongs in the commit
message or PR body, not source — and, if any are found, returns
`additionalContext` so the model can self-correct in a follow-up edit.

Structurally ported from Erigon's own hook
(`.claude/repo-references/clients/erigon/.claude/hooks/comment-policy.py`), but
the regex set is NOT a verbatim copy: fukuii's comments.md deliberately keeps a
terse `#NNNN` / `PR #NNNN` "why" citation (e.g. `// PR #1378: ...`) that Erigon's
Go convention bans outright — 113 sites across 23 files in `src/main` already
use this shape as ratified house style. This hook only ever sees NEWLY ADDED
lines (Write gets full `content`; Edit/MultiEdit get only `new_string`/
`edits[].new_string`) — it is forward-looking only and never a retroactive scan
of existing comments (that's a separate, already-logged audit item, MOD-05).

Three exception genres are mandated elsewhere in this repo and must never be
flagged here (see comments.md's "Three sanctioned exception genres" section):
  1. `// MIGRATION:` (wraith.md)
  2. `@nowarn("cat=...") // <reason> — see .claude/sprints/QUEUE.md §<ref>`
     (warning-ratchet.md) — naturally excluded: the line starts with `@nowarn(`,
     not `//`/`/*`/`*`, so added_comment_lines() never captures it as a comment
     line in the first place.
  3. `// implicit val (not given): overridden in subclasses ...` (scala3-style.md
     S3 / G3, literal text in scala3-given-migration.md:108)

Only two of comments.md's four "Never in code" categories get a mechanical
regex here (scope/limitation narration, incident/reproduction narration) —
matching Erigon's own precedent of not attempting to mechanically detect
"restating the code" or "the same rationale repeated at multiple sites", which
are context-dependent judgment calls, not grep-verifiable patterns. A third
category here is fukuii-specific: process narration ("as requested in review")
and bare "see #NNNN with nothing else" citations, both of which comments.md's
PR/issue-citation section explicitly keeps banned even though the citation
convention itself is sanctioned.
"""
import json
import re
import sys

# (label, regex) — case-insensitive. Targeted at the recurring offenders listed
# in comments.md's "Never in code" section, kept conservative to avoid
# false-positive noise on legitimate why-comments and the three sanctioned
# exception genres (checked separately, before these patterns run).
PATTERNS = [
    ("scope/limitation narration (→ commit message / PR body)",
     re.compile(r"forward[- ](only|prevention)|safety[- ]?net|cannot repair|\bNOTE:\s", re.I)),
    ("incident/reproduction narration (→ commit message / PR body)",
     re.compile(r"\bmainnet\b|\bdevnet\b|\bblock[s]?\s+\d{6,9}\b|\b20[0-9]{6}\b|"
                r"\bdeployed via\b|\bcalled\b.*\bblocks?\s+later\b", re.I)),
    ("process narration (→ commit message / PR body; not a sanctioned citation)",
     re.compile(r"\bas requested\b|\bper (?:review|code review)\b|\bin review\b", re.I)),
]

# comments.md: "A citation used in place of stating the actual constraint
# ('see #1234' with nothing else) — the one-line constraint must still be
# present; the number is a pointer, not a substitute." Full-line match (not
# search) against the comment body with its leading marker(s) stripped — a
# citation *alongside* a stated constraint (e.g. "PR #1378: PeersClient is a
# sync backbone actor — no `.withMaxRestarts` cap.") has real text after the
# citation and will not match this.
BARE_CITATION_RE = re.compile(r"^(?:see\s+)?(?:pr\s*)?#\d{3,6}\.?$", re.I)

# Sanctioned exception genres (comments.md "Three sanctioned exception
# genres..."). Lines matching these are never flagged, full stop.
SANCTIONED_PREFIXES = (
    "// migration:",
    "// implicit val (not given):",
)


def strip_comment_marker(line):
    s = line
    for marker in ("///", "//", "/*", "*/", "*"):
        if s.startswith(marker):
            s = s[len(marker):]
            break
    return s.strip()


def added_comment_lines(tool_name, tool_input):
    if tool_name == "Write":
        text = tool_input.get("content", "")
    elif tool_name in ("Edit", "MultiEdit"):
        # new_string for Edit; concatenate edits for MultiEdit
        if "new_string" in tool_input:
            text = tool_input.get("new_string", "")
        else:
            text = "\n".join(e.get("new_string", "") for e in tool_input.get("edits", []))
    else:
        return []
    out = []
    for ln in text.splitlines():
        s = ln.strip()
        if s.startswith("//") or s.startswith("/*") or s.startswith("*"):
            out.append(s)
    return out


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

    comments = added_comment_lines(tool_name, tool_input)
    if not comments:
        sys.exit(0)

    flagged = []
    for line in comments:
        lowered = line.lower()
        if lowered.startswith(SANCTIONED_PREFIXES):
            continue

        body = strip_comment_marker(line)
        if BARE_CITATION_RE.match(body):
            flagged.append(("bare citation, no stated constraint (comments.md: "
                             "the number is a pointer, not a substitute)", line))
            continue

        for label, rx in PATTERNS:
            if rx.search(line):
                flagged.append((label, line))
                break

    if not flagged:
        sys.exit(0)

    msg = ["Comment-policy check (.claude/agent-protocols/comments.md) — review these added "
           "comment lines; scope/incident/process narration and bare citations belong in the "
           "commit message or PR body, not the code:"]
    for label, line in flagged[:12]:
        snippet = line if len(line) <= 100 else line[:97] + "..."
        msg.append(f"  - [{label}] {snippet}")
    msg.append("A terse `#NNNN` / `PR #NNNN` citation IS sanctioned when it names the actual "
                "constraint alongside it (e.g. `// PR #1378: <constraint>`) — keep those.")

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": "\n".join(msg),
        }
    }))
    sys.exit(0)


if __name__ == "__main__":
    main()
