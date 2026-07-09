# Scala 3 domain — scope stub

**Scope:** Scala 3 language idiom for fukuii's main/test sources — `return`, `null`
avoidance, `given`/`using`, extension methods, enums, `A & B` over `A with B`,
`asInstanceOf`/`isInstanceOf` discipline, `println` ban, braceless syntax, opaque-type
propagation, `Matchable`/E165 (see [`matchable-e165.md`](matchable-e165.md), already
migrated as this directory's exemplar), `@unchecked` annotation discipline across its
three grammars — pattern-binding irrefutability, match-scrutinee suppression, and
type-erasure in type patterns — including the consensus-tier amendments for
`vm`/`mpt`/`crypto`/`domain`/`ledger` sites (see
[`unchecked-annotations.md`](unchecked-annotations.md), full content), and warning-
suppression discipline (`@nowarn`, `@SuppressWarnings`, scalafix comment suppression) —
when a suppression is legitimate, its required form, and the ratchet relationship to
`warning-ratchet.md` (see [`warning-suppression.md`](warning-suppression.md), full
content).

**Owning specialist:** `mithril` (idiom modernization), `wraith` (compile-error triage —
consults this domain, does not house it). Consensus-path `@unchecked` sites additionally
require `forge` (PoW) or `beacon` (PoS) co-sign per `unchecked-annotations.md`'s
consensus-safety amendments.

**Authority:** `.claude/repo-references/scala3/` — primarily `docs/_docs/reference/` and
`docs/_docs/reference/error-codes/`; `changelogs/` for newly introduced migration patterns
not yet reflected here.

**Status:** three files with full content (`matchable-e165.md`, `unchecked-annotations.md`,
`warning-suppression.md`). The remaining S1–S12 (`scala3-style.md`) and G1–G3
(`scala3-given-migration.md`) rules
currently live in `.agents/protocols/code-style/` pending the per-domain migration pass
described in `../README.md`. Do not restate their content here ahead of that pass — link to
the protocol files in the meantime.
