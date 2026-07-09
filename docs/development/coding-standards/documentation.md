# Documentation — scope stub

**Scope:** What makes a durable file durable — invariants + single-source pointers, never
live counts/status snapshots/dated grep results. Where live state actually belongs
(`QUEUE.md`, the one authoritative subsystem doc, `.local/docs/test-quality-log.md`) and
what does NOT count as the anti-pattern (historical incidents, `currency:` headers,
templates, provenance dates). This directory's own README and every doc in it is written
to this standard already — see `README.md`'s Governance section for the concrete rule
("never a live count or dated snapshot") in action.

**Owning specialist:** `warden`.

**Authority:** in-house convention (not upstream-authority-derived) — this is fukuii's own
documentation-hygiene rule, refined through incidents like the one `README.md`'s Governance
section describes (a stale claim surviving unquestioned in a protocol doc).

**Status:** not yet authored as a standalone file. `.agents/protocols/code-style/doc-
standards.md` currently carries this content as a protocol; migrating it here is a
follow-on pass (see `README.md`'s per-domain migration plan). Notably, this directory's
own governance content already had to satisfy `doc-standards.md`'s rule before being
written — the rule was applied to author this directory even before its own text has a
home here.
