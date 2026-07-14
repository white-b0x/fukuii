# core-geth — txpool
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

> Framed as **ETC-specific diffs from vanilla go-ethereum** (baseline `59e89e81e`). Short by
> design: core-geth's txpool is **structurally identical to geth's** — it inherits the pool
> wholesale. The only ETC-relevant behavior is which transaction *types* get admitted, and
> that flows entirely from chain config, not from any ETC-specific pool code.

## Architecture summary

core-geth carries geth's full `core/txpool` tree unchanged:

- `core/txpool/txpool.go` — the `TxPool` aggregator over sub-pools.
- `core/txpool/subpool.go` — the `SubPool` interface.
- `core/txpool/legacypool/` — the main pending/queued pool for legacy + access-list +
  dynamic-fee transactions.
- `core/txpool/blobpool/` — the EIP-4844 blob-transaction pool. **Present in the tree even
  though ETC never activates blobs** (multi-geth keeps the whole geth codebase; unused paths
  are gated off by config, not deleted).
- `core/txpool/validation.go` — shared `ValidateTransaction` admission checks.

No ETC-specific sub-pool, no ETC-specific eviction/ordering, no custom admission policy. ETC
behavior is an *emergent property of config*, not of code in this package.

## Key types / interfaces / files

- `core/txpool/validation.go:68` — `if !opts.Config.IsEnabled(opts.Config.GetEIP2565Transition,
  head.Number) && tx.Type() != types.LegacyTxType { … }`: before Magneto (EIP2565/2718), only
  legacy transactions are accepted.
- `core/txpool/validation.go:71` — `if !IsEnabled(GetEIP1559Transition, head.Number) &&
  tx.Type() == types.DynamicFeeTxType { reject }`: **ETC leaves `EIP1559FBlock` nil**, so
  type-2 dynamic-fee transactions are rejected at admission on ETC.
- `core/txpool/validation.go:74-77` — `eip4844Enabled := IsEnabledByTime(GetEIP4844TransitionTime)
  || IsEnabled(GetEIP4844Transition)`; if not enabled, `tx.Type() == types.BlobTxType` is
  rejected. **ETC sets neither**, so blob (type-3) transactions never enter the pool.
- `core/txpool/validation.go:108` — intrinsic-gas computation is itself parameterized by per-EIP
  transitions (`GetEIP2028Transition`, `GetEIP3860TransitionTime`), the same gating mechanism the
  EVM uses.

## Design decisions & rationale

- **Admission is config-gated, pool code is untouched.** ETC's "no 1559, no blobs" posture is
  expressed by leaving `EIP1559FBlock`, `EIP4844FBlock`, and `EIP4844TransitionTime` nil in
  `params/config_classic.go` — the same `IsEnabled(GetEIPxTransition, num)` mechanism used in
  block-execution and the EVM. The txpool then rejects dynamic-fee and blob transactions
  automatically; the blobpool simply stays empty.
- **No base-fee → legacy gas-price semantics in the pool.** Because ETC has no EIP-1559
  base-fee (see `evm.md`), the pool's ordering/pricing for ETC operates on absolute `gasPrice`
  of legacy (and Magneto access-list, type-1) transactions rather than tip-over-basefee. The
  1559-aware machinery exists in the shared code but is dormant on the ETC path.

## Notable patterns (the reusable idea)

- **Keep the upstream pool verbatim; let chain config decide what's admissible.** The reusable
  idea is that transaction-type support is *not* a fork of the pool — it is the same per-EIP
  transition gate applied at the admission boundary. A fukuii equivalent should likewise gate
  admissible tx types on the ETC fork config rather than maintaining an ETC-specific pool.

## Authority note

**txpool inherits go-ethereum** — it is **not** an independent ETC authority. For pool
structure, eviction, and pricing algorithms, vanilla geth is the reference. core-geth is
authoritative only for *which transaction types ETC admits*, and that is fully determined by
the `params/config_classic.go` fork schedule (documented in `evm.md`), not by anything in
`core/txpool`. Client-layer admission *policy* on the fukuii side (tip floors, ECIP-1122
`MIN_MINER_TIP`, gas-target enforcement) is a separate concern owned by the `banksy` layer —
not part of this upstream-parity pool.

## Gotchas / anti-patterns / things they later changed

- **The blobpool exists but is dead weight on ETC.** Do not infer blob support from the
  presence of `core/txpool/blobpool/`; check the config gate at `validation.go:74`.
- **Type-1 (access-list) is admitted from Magneto, type-2 (dynamic-fee) never.** Easy to
  conflate "EIP2718 typed envelope enabled" with "all typed transaction types enabled" —
  Magneto enables the envelope + access lists but ETC has no 1559, so dynamic-fee txs remain
  rejected (`validation.go:71`).
- **`IsEnabledByTime` vs `IsEnabled`.** Blob/initcode gates use timestamp transitions
  (`GetEIP4844TransitionTime`, `GetEIP3860TransitionTime`) while most ETC gates are block-number
  transitions — a reimplementation must honor both dispatch styles even though ETC's timestamp
  gates are all nil.
