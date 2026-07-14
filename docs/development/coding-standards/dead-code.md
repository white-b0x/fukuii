# Dead code — scope stub

**Scope:** What code *shapes* count as genuinely dead vs. never-wired-but-valuable (the
classification content, if and when it's separable from the decision procedure below) —
**not** the three-verdict adjudication procedure itself (Wire it / Delete it / Defer),
which is operational and stays a protocol permanently, per `README.md`'s per-domain
migration plan.

**Owning specialist:** `mithril`, `prism` for classification; the three-verdict procedure
is used by `wraith`, `prism`, `mithril`, and any agent performing dead-code sweeps.

**Authority:** in-house convention; git history and test coverage of the code in question
are the actual evidence source per-instance, not a vendored authority repo.

**Status:** not yet authored, and may end up thin — most of this ground is process
(`.agents/protocols/process/dead-code-review.md`'s three-verdict procedure), which is
explicitly excluded from migration into this directory. This file exists as the named
destination in case a genuine style-classification component (independent of the
adjudication procedure) is identified later; do not force content here prematurely.
