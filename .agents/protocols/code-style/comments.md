# Comment Content Protocol

**Scope:** what makes a comment worth writing, and what belongs in a commit message or PR
body instead. This is a content-convention doc — like `logging-standards.md` — not a
Scala-3-language-idiom ratchet like `scala3-style.md`'s S1–S12. "Good why-comments" can't be
driven to a grep-verifiable `0 hits` the way `implicit val` can, so this doc has no
mechanical shortcut script and isn't in the S-series.

**Origin:** fukuii's own project docs (`CLAUDE.md`, `AGENTS.md`,
`.specify/memory/constitution.md`, `docs/development/contributing.md`) carry no comment
policy today — this is new project policy, not a transcription of an existing rule. It is
grounded directly in Erigon's vendored `.claude/rules/comments.md` and `CLAUDE.md` "Code
Style > Comments" section (`.claude/repo-references/clients/erigon/`), adapted for fukuii's
own observed practice — most importantly, fukuii's established PR/issue-citation habit,
which Erigon bans outright and which this doc deliberately does not (see below).

Used by: ALL agents writing or reviewing Scala code
Referenced by: scala3-style.md, wraith.md, warning-ratchet.md

---

## Default: no comment

Clear names and small, focused functions should carry the meaning. The vast majority of new
code — including code written by agents — should carry zero new comments. Before adding one,
ask whether renaming, extracting a helper, or restructuring would remove the need. Almost
always, it does.

## Write a comment only for

Erigon's four categories transfer to fukuii cleanly:

1. **Workarounds** for a bug in a dependency, the runtime, or other code (link the
   issue/commit).
2. **Non-obvious invariants or constraints** the types don't enforce.
3. **Surprising edge cases** a reader would otherwise miss.
4. **Performance-sensitive choices** where the obvious implementation would be wrong.

When a comment is genuinely required: one sentence, rarely two, never a paragraph. No
bulleted sub-lists inside `//`. If a reader could delete the comment without losing
information, delete it.

## Scaladoc is different — not a violation of "default: no comment"

The categories above govern **inline `//` comments explaining a design decision at a specific
line**. They do not govern **`/** ... */` scaladoc documenting a public class, object, or
method's contract** — that is legitimate, idiomatic Scala API documentation, a different genre
entirely, and this protocol does not discourage it.

Sampled fukuii examples confirming the pattern already in use:

- `blockchain/sync/HeadersFetcherQueue.scala` — class-level `/** ... */` describing the
  queue's batching behavior, a `Reference: go-ethereum/eth/downloader/queue.go` citation, and
  a method-level `/** ... @param ... */` doc.
- `blockchain/sync/codec/ReceiptCodecs.scala` — class doc stating where the code was moved
  from and why it lives in `sync/codec` rather than `domain` (avoiding a circular import), plus
  a cross-client reference (`Matches the Besu ethereum/core/encoding/ pattern`).
- `consensus/pos/ForkChoiceManager.scala` — class doc plus per-method docs using
  Scaladoc `[[...]]` cross-references to related types and actors.
- `consensus/ConsensusAdapter.scala` — a short class doc flagging the class itself as a
  temporary isolation layer.

Keep writing this kind of scaladoc. Judge it by scaladoc conventions (accurate contract,
useful `@param`/cross-references, states *why* a boundary/dependency choice was made where
non-obvious) — not by the four-category inline-comment test above, and not by the
"never a paragraph" length limit either (a class-level contract doc legitimately runs several
lines).

## PR/issue citations — explicit decision (diverges from Erigon)

Erigon's rule bans task references outright: *"Task references — PR numbers, issue numbers
(except a genuine workaround link) ... → goes in the commit message or PR body."*

**fukuii's decision: keep the citation, deliberately diverging from Erigon.** A terse
`#NNNN` / `PR #NNNN` marker naming the actual design constraint is a **sanctioned "why"
citation**, not narration, provided it reads like fukuii's own existing practice:

```scala
// PR #1378: PeersClient is a sync backbone actor — no `.withMaxRestarts` cap.
```

This states *what must remain true* (no restart cap on this actor) and *where the constraint
was decided* (`#1378`) in one line — a reader hitting this actor doesn't need archaeology to
know the restart-cap omission is deliberate, not an oversight. A repo-wide sample
(`grep -rEn '^\s*//.*#[0-9]{3,6}\b' src/main/`) found a non-trivial, already-established number
of such sites — most concentrated in `SyncController.scala`'s `PR #1378` no-restart-cap
markers — and they read as disciplined design-constraint citations, not incident storytelling.
Re-run the grep yourself for the current count rather than trusting a cached number here.

**Why not adopt Erigon's stricter ban:** rewriting or stripping these established sites to chase
Go's convention would erase real information (which actors are deliberately supervision-
exempt, and why) for no correctness gain — the citations already meet the "states what must
remain true" bar the rest of this doc sets, they're just short enough to keep the reference
inline instead of only in the PR body. These sites are **house style, not grandfathered
debt** — this is a deliberate, disclosed divergence from Erigon's Go convention, ratified
here for future reviewers and for `REPO-08b`'s regex design to implement against.

**What still isn't allowed**, even with a citation attached — these remain banned regardless
of whether a `#NNNN` is present:

- "as requested in review" / "per review comment" — that's process narration, not a design
  constraint.
- Incident/reproduction narration attached to a citation ("broke in prod, see #1234, here's
  what happened on 2026-05-21...") — the citation is fine, the surrounding story is not.
- A citation used in place of stating the actual constraint ("see #1234" with nothing else) —
  the one-line constraint must still be present; the number is a pointer, not a substitute.

## Three sanctioned exception genres already mandated elsewhere in this repo

These are pre-existing, protocol-mandated comment forms. A future enforcement mechanism
(`REPO-08b`) must not flag any of these as violations:

1. **`// MIGRATION:`** (`wraith.md`) — required for non-obvious compile-error fixes made
   during a Scala 3 migration pass, so a reviewer can tell an intentional semantic-preserving
   rewrite from an accidental behavior change.
2. **`@nowarn("cat=...") // <reason> — see .claude/sprints/QUEUE.md §<ref>`**
   (`warning-ratchet.md`) — the narrow site-level suppression format for deferred warnings.
   Note this already carries a backlog reference inline — it is itself an instance of the
   (b)-style PR/issue citation ratified above, not narration.
3. **`// implicit val (not given): overridden in subclasses — Scala 3 given is final`**
   (`scala3-style.md` S3 / G3) — the override-chain annotation marking an intentional
   `implicit val` holdout from the `given`/`using` migration.

## Never in code — goes in the commit message or PR body

Erigon's categories, largely portable as-is:

- **Scope/limitation/"honest" narration** — "forward-only", "safety net", "cannot repair X",
  "NOTE: this only…". PR-description material, not source.
- **Incident/reproduction narration** — dates, devnet/branch names, "deployed via X, called N
  blocks later at M", post-mortem storytelling.
- **Rebuild-provenance / "the old code" narration** — the rebuild's own development history is
  NOT code documentation and goes stale the moment the rebuild lands. Never write **"AS-IS"**,
  **"fukuii/july-fourth"**, "the pre-rebuild code", "we pivoted from Mantis", "eliminating tech
  debt", "modernization sprint", or any "how we got here" comparison. A comment must stand on its
  own describing *this* code, never "what the old code did / this replaces X". If — and only if — a
  comment genuinely needs to cite the pre-rebuild reference implementation as a source, name its
  **version** (`v0.8.1-series`, the reference tip `42959353b`), not a branch name or the "AS-IS"
  shorthand. (These are internal dev vocabulary; they must not ship in fukuii.)
- **Restating the code** — `// increment counter`, narrating what the next line obviously
  does.
- **The same rationale repeated at multiple sites** — state the *why* once at the canonical
  place (the field/type/function it belongs to); use terse pointers elsewhere, not the full
  paragraph again.

## Test docstrings

Slightly more latitude — explaining a non-obvious scenario the test pins is fine — but the
same rules apply: no incident/scope/task narration beyond a sanctioned citation.
