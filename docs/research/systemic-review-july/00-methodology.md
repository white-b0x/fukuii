# Systemic Review — July 2026 Cycle — Methodology Pointer

This cycle follows `.agents/protocols/process/systemic-review-protocol.md` in full —
verdict taxonomy, test-classification taxonomy, dead-code taxonomy, reference-client
authority model, citation convention, per-subsystem doc template, and the mandatory
parallel-lens/sequential-item execution rule all live there, not here. Read it first.

## This cycle's specifics

- **Scope:** all 12 `SR-NN` items + 2 `SR-EXT-NN` items tracked in
  `.claude/sprints/QUEUE.md`'s `### Systemic Review` persistent section — full coverage,
  no subsystem left as a skeleton placeholder.
- **Gating:** `SR-01` (blockchain/sync), `SR-02` (network), `SR-03` (jsonrpc), `SR-04`
  (consensus), and `SR-11`'s `transactions` sub-section are gated on Batch 2/3/4 (the
  in-flight, unrelated Class A/B TestProbe migration) closing first — their test-quality
  lens would otherwise audit a moving target. Everything else is ready to dispatch
  immediately. See the QUEUE.md section's own table for current gate status.
- **PP-00 supersession:** the Parity section's prior `PP-00` kickoff prompt (never run) was
  folded directly into `SR-04` and removed from QUEUE.md rather than kept as a stale
  "superseded" banner — its 10-phase design lives on inside `SR-04`'s prompt. The Parity
  section itself, and its existing `PARITY-01`/`PARITY-02` findings, remain active.
- **Recurring-cycle note:** a future cycle (next recommended: August 2026, or sooner if a
  large refactor is proposed for any subsystem this cycle covers) should diff against this
  cycle's `01-findings-index.md` rather than re-researching from zero — see the protocol's
  "Recurring-cycle efficiency rule."

## Output tree

Mirrors the src tree. Tier A (full 6-doc split): `blockchain-sync/`, `consensus/`,
`jsonrpc/`, `network/`, `db/`. Tier B (single `00-overview.md`): `vm/`, `domain/`,
`ledger/`, `mpt/`, `foundational-modules/`, `small-modules/`, `test-infrastructure/`.
Dedicated deep phase: `extensibility-architecture/`. Root: `01-findings-index.md` (living
rollup), `02-root-synthesis.md` (written last, once every item lands).
