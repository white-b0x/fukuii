# Comments — scope stub

**Scope:** Default-to-no-comment policy; when a comment is actually warranted; sanctioned
exceptions (`// MIGRATION:`, `@nowarn` rationale lines, `not given` annotations); the
scaladoc-vs-inline-comment distinction.

**Owning specialist:** cross-cutting — no single owning specialist; `prism` reviews;
enforced live today via `.claude/hooks/comment-policy.py` (advisory `PostToolUse` hook on
`Write`/`Edit`/`MultiEdit` to `*.scala` files).

**Authority:** in-house convention (not upstream-authority-derived).

**Status:** not yet authored. `.agents/protocols/code-style/comments.md` currently carries
this content as a protocol, and is also what `.claude/hooks/comment-policy.py` enforces
against; migrating the content here (without breaking the hook's reference) is a follow-on
pass (see `README.md`'s per-domain migration plan).
