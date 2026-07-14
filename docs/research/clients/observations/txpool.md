# Observations — txpool
_Phase-2 synthesis 2026-07-13. Sources: 6 `{client}/txpool.md`._

Scope: how each reference client structures its mempool — pool architecture, the
admission gate, ordering, sub-pool/promotion machinery, replacement/eviction, blobs,
gossip, and the service boundary — and where fukuii's **banksy** client-layer policy
tier should hang its admission/selection seams. **Verdicts are use-case-aware** — a
shape that is OBSOLETE for one node role is DEFAULT for another. Node-role taxonomy:
enterprise/full, custody, validator, light, mining-pool, multi-network/product-family.

Authority split (per source docs): **go-ethereum** is the canonical reference for
txpool *behavior* — the `SubPool` seam, shared `validation.go` gates, two-heap priced
eviction, the 10% price-bump replacement rule, and the entire `blobpool`. Every other
client's doc explicitly defers "what the rules are" to geth. What the others contribute
is *how to structure the rule engine*: **nethermind** = composable admission
filter-chain (`IIncomingTxFilter`); **reth** = compile-time trait validator/ordering
(`TransactionValidator`/`TransactionOrdering`); **besu** = runtime plugin
validator/selector + layered pool; **erigon** = txpool-as-gRPC-service. **core-geth** is
the ETC authority only for *which tx types are admitted* (config-gated, no pool fork).

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | fukuii | Authoritative |
|---|---|---|---|---|---|---|---|---|
| **Pool architecture** | `TxPool` aggregator over pluggable `SubPool`s (legacypool + blobpool); fan-out/merge on every op | inherits geth's `SubPool` aggregator **verbatim** (no ETC-specific pool code) | **two selectable impls** behind `PendingTransactions` SPI: **layered** (default) + legacy sorter; `--tx-pool=LAYERED\|SEQUENCED` | self-contained module that is **also a standalone gRPC service**; same code embeds or runs as `cmd/txpool` OS process | `Nethermind.TxPool/`: orthogonal split — filter-chain admission + bucketed `SortedPool` storage | single generic `PoolInner<V,T,S>` behind `TransactionPool` trait; **generic over validator/ordering/blobstore** | **flat Guava `Cache[txHash, PendingTransaction]` in one Pekko actor** (`PendingTransactionsManager.scala:139`); no SubPool/layered/bucket/partition; ordering computed outside the pool | geth (`SubPool` seam) · besu (layered) · reth (trait-generic) · erigon (gRPC service) |
| **Admission-gate model** | centralized `validation.go`: stateless `ValidateTransaction` + stateful `…WithState`, parameterized by `ValidationOptions` bitmap/callbacks per subpool | same shared `validation.go`; ETC posture = config-gated tx-type acceptance (no 1559, no blobs — `EIP1559FBlock`/`EIP4844*` nil) | ordered pipeline in `validateTransaction` (cheap→expensive) + **`PluginTransactionPoolValidator`** seam (accept/reject-with-reason, `VALIDATE_ALL` default) | `validateTx` gate; local (`AddLocalTxns`) vs remote (`AddRemoteTxns`) differ on fee floor | **two-phase array of `IIncomingTxFilter`s** (`_preHashFilters`/`_postHashFilters`), first-reject wins; **injectable extra filter** seam | `TransactionValidator` trait (`validate_transaction → Valid/Invalid/Error`); off-thread task pool; `EthTransactionValidatorBuilder` | single `validateAgainstState` method, 3 sequential `Set.filter` stages (pending-nonce → **ECIP-1122 tip floor** → MPT nonce-window+balance) (`PendingTransactionsManager.scala:205`); ECDSA recovery pushed to child STFA actor; degrades to nonce-only when state absent | **nethermind** (filter-chain) · **reth** (trait) · **besu** (plugin) |
| **Ordering** | two-heap `pricedList` (`urgent` by effective tip, `floating` by gasFeeCap, 4:1); `LazyTransaction` mining view | inherited; on ETC degenerates to raw `gasPrice` (no base fee) | per-layer: `TreeSet` `orderByFee`; `BaseFeePrioritized` (effective priority fee) vs **`GasPricePrioritized` (flat — ETC-relevant)**; score-based | `PendingPool` Best/Worst heap pair by mining priority (O(log n)) | `SortedPool` per-sender nonce buckets + global-worst set; `GasBottleneck` = effective price capped by bucket worst | **`TransactionOrdering` trait**; default `CoinbaseTipOrdering` = `effective_tip_per_gas(base_fee)`; `BestTransactions` iterator | **production-time sort, not in-pool** (`BlockGeneratorSkeleton.prepareTransactions:151-172`): flat `gasPrice` desc per sender, nonce-ordered, greedy gasLimit fill; no base-fee reheap (ETC) | geth (dual-heap) · reth (`TransactionOrdering` seam) |
| **Sub-pool structure & promotion** | legacypool `pending`/`queue` (executable vs nonce-gapped); async `scheduleReorgLoop`/`runReorg` promote/demote off the add path | inherited | **layer chain** Prioritized→Ready→Sparse→End; adds cascade `TRY_NEXT_LAYER`, freed space promotes headward; only Sparse tolerates gaps | 3 sub-pools (`pending`/`baseFee`/`queued`) derived from a **5-bit `SubPoolMarker` bitset**; `promote()` re-derives per block | per-sender buckets by nonce; separate blob pool; capacity evicts global-worst | **4 sub-pools** (Pending/BaseFee/Queued/Blob) derived from **8-bit `TxState` → `From<TxState> for SubPool`** (pure fn, models erigon) | **none** — no executable/future split; gapped txs (nonce window `[acct.nonce, +1024)`, `:270`) share the flat cache; non-executables filtered at block-execution time | geth (pending/queued) · besu (layered promotion) · **reth/erigon (state-bitflag → pure-fn sub-pool)** |
| **Replacement / eviction** | 10% price-bump (must beat feeCap **and** tip); overflow discards globally cheapest, churn capped 25%/reorg | inherited | `TransactionReplacementByFeeMarket/GasPrice` (`priceBump` %, higher `blobPriceBump`); layer eviction (Prioritized count / Ready-Sparse bytes); score-penalize > hard drop | configurable price-bump; raises-value multiplies tip threshold by pooled-count (anti-latent-overdraft); blob-only replaces blob | replacement = pluggable comparer (`CompareReplacedTxByFee`, 2x for blob); `SortedPool` global-worst eviction O(log n) | `is_underpriced(replacement, &PriceBumpConfig)` self-contained predicate; `discard_worst` drops global-worst across sub-pools via parked orderings | **no gossip-path price-bump** (same-nonce txs coexist, resolved at selection keep-highest-gasPrice); local RPC `AddOrOverrideTransaction` evicts same-nonce unconditionally (`:338`); eviction = Guava size(1000)/TTL(2min), **not fee-aware** | geth (10% price-bump) |
| **Blob pool** | **`blobpool/`** — disk-backed (`billy` shelf), fee-jump (`log1.125`) eviction, pulled-not-pushed; PoS/ETH-only (canonical) | present in tree but **dead weight** (ETC never activates blobs; config gate at `validation.go:74`) | separate `BlobCache` (Caffeine); `restoreBlob`; sidecar own eviction lifecycle | `GetBlobs` RPC; blob-only replacement; type-3 handling | separate `BlobTxDistinctSortedPool` (+ persistent variant, disk); `MinBlobTxPriorityFee` filter | `BlobStore` trait seam (`DiskFileBlobStore` deferred-delete, mem, noop); `BlobTransactions` sub-pool | **no separate blob pool** — sidecar bytes in a side map `blobTxNetworkBytes` (`:136`) for `PooledTransactions` replay; ETH/Sepolia-path only (ETC no blobs) | **go-ethereum** (blobpool) |
| **Gossip / announce** | eth/68: small txs direct to ~√peers, blobs/large **announced hash-only** (pull); `tx_fetcher` retrieval state machine | inherited | (via eth subprotocol; not the doc's focus) | **gossip owned by Sentry daemon** — pool consumes decoded stream, sends via `BroadcastPooledTxns`/`AnnouncePooledTxns` (never opens peer sockets) | `TxBroadcaster`: persistent local txs re-broadcast on timer + batched peer txs; `ITxGossipPolicy` | admission decides propagation (`Valid.propagate`); local/private may be admitted-not-broadcast | **announce-then-pull for every tx** — hash-only `NewPooledTransactionHashes` to peers not in `knownTransactions` (`:170-192`), pull via `GetPooledTransactions`; no direct small-tx push; announcement type/size mismatch disconnects peer (`:424-429`) | **go-ethereum** (eth/68 announce) |
| **Service boundary (in-proc vs gRPC)** | in-process struct; subpools composed in one binary | in-process (inherited) | in-process; plugin seams are the extension surface | **in-proc `TxnProvider` OR separate OS process over gRPC** — one `Assemble` returns pool + `TxpoolServer`; `GrpcDisabled` no-op stub; pool holds **no execution state**, reads chain via remote KV cache + `StateChanges` stream | in-process; injectable filter is the extension surface | in-process (`Arc<PoolInner>`); seams are compile-time type params | **in-process Pekko actor** addressed by `ActorRef`; no transport-neutral service interface, no embed-or-standalone split | **erigon** (txpool-as-gRPC-service) |

## Approach catalog (use-case-aware)

| Approach | Clients using it | Good for (use-case / node-role) | Verdict | Why |
|---|---|---|---|---|
| **`SubPool` aggregator seam (route by tx type)** | go-ethereum (origin), core-geth (inherited) | any client that must admit **structurally different tx classes** (legacy vs blob) without special-casing a monolith | **DEFAULT (structural baseline)** | A narrow interface + aggregator that routes by `Filter`, coordinates lifecycle in lockstep, merges queries. Turns "add a tx class" from monolith surgery into "write a SubPool." geth is byte-authority. fukuii (ETC, no blobs) needs only the legacy path, but the seam is the right decomposition. |
| **Layered pool (Prioritized→Ready→Sparse)** | besu | **enterprise/full nodes under spam** — confine expensive full sort to a small (2000-tx) candidate layer, quarantine nonce-gap spam | **OPTIONAL(enterprise/full)** | Explicit cost/scale tradeoff: full ordering only on the block-candidate set, cheap buffering below, gap-tolerant "purgatory" (Sparse, drop-oldest-first) that can never crowd executable txs. Directly relevant to efficient tip-of-branch mining selection. besu = JVM structural mirror. |
| **Sub-pools derived from a state bitflag (pure fn)** | reth (8-bit `TxState`), erigon (5-bit `SubPoolMarker`) | any pool needing a **self-consistent pending/parked partition under state churn** (base-fee moves, nonce/balance deltas) | **OPTIONAL(idea-level for fukuii) · DEFAULT(reth/erigon native)** | Sub-pool is never set imperatively — it's *derived* from independent validity bits, recomputed per block. Keeps the partition correct by construction. If fukuii ever grows a first-class parked/pending split, this is the model. BaseFee-vs-Queued split is 1559-specific (ETC has no base-fee reheap). |
| **Composable `IIncomingTxFilter` admission chain** | nethermind (banksy model) | **operator-tunable admission** — tip/fee floors, allowlists, per-tenant rate limits added without forking the pool core | **DEFAULT (banksy admission shape)** | Admission = ordered pipeline of tiny accept/reject-with-reason filters; add/remove/reorder = one-line change; enterprise adds a rule via one injected filter. Policy floors (`FeeTooLowFilter`, `PriorityFeeTooLowFilter`) are just config-parameterized filters. **The clearest existing admission-as-filter-chain.** |
| **`TransactionValidator` + `TransactionOrdering` traits (compile-time banksy)** | reth | **statically-composed deployments** (L2, enterprise, network-specific) wanting zero-dispatch monomorphized pools | **DEFAULT (banksy seam shape, compile-time variant)** | Admission (`minimum_priority_fee` gate) and ordering (`priority(tx, base_fee)`) are the two places a network-specific economic policy plugs in — exactly where a `MIN_MINER_TIP` lives (admission floor **and** selection ordering). Trades runtime swappability for compile-time guarantees. Maps 1:1 onto banksy. |
| **`PluginTransactionValidator`/`Selector` (enterprise custom admission)** | besu | **enterprise/permissioned** — runtime-registered custom admission + two block-inclusion veto points (pre- and post-EVM) | **OPTIONAL(enterprise/permissioned)** | Cleanly separates consensus-mandatory validation (baked into `TransactionValidator`) from operator-tunable policy (plugin gates, both no-op by default). Sits at exactly the two points banksy owns — admission and selection/ordering. Runtime-registry counterpart to reth's compile-time traits. |
| **txpool-as-gRPC-service (embed-or-standalone)** | erigon (product-family seam) | **product-family / mining-pool** — run the pool in-process for a lean node OR hoist it into a separate scalable/replaceable binary | **OPTIONAL(product-family)** | One `Assemble` → pool + transport-neutral `TxpoolServer`; deployment is a choice, not a fork. Requires discipline: pool holds **no execution state**, pulls chain state through cache+stream. An external mining-pool optimizer or enterprise admission module plugs into the *same* seam the built-in pool uses. Ties to DRPC-GATEWAY-01. |

## Best-practice synthesis

**DEFAULT (what fukuii's banksy tier should adopt):**

- **Express admission as a composable filter/validator chain over a consensus-invariant
  core.** The three independent implementations agree on the shape: nethermind's
  `IIncomingTxFilter` pipeline (runtime list), reth's `TransactionValidator` trait
  (compile-time), and besu's `PluginTransactionPoolValidator` (runtime plugin) all
  separate *operator-tunable policy* from *consensus-mandatory validation*, all return
  accept/reject-with-reason, and all sit at the mempool-admission boundary banksy owns.
  **ECIP-1122 `MIN_MINER_TIP` is a `TipTooLowFilter`, NOT an edit to the pool core** —
  structurally identical to nethermind's `FeeTooLowFilter`/`PriorityFeeTooLowFilter` and
  reth's `minimum_priority_fee` gate. The floor is config, not a hard fork — precisely
  banksy's "does not change the state root" test.
- **Express ordering as a pluggable strategy, separate from the storage data structure.**
  reth's `TransactionOrdering::priority(tx, base_fee)` is the one place block-production
  ordering plugs in; nethermind's takeaway is identical — keep the *admission policy*
  (banksy filters) separate from the *ordering data structure* (`SortedPool`). A
  `MIN_MINER_TIP` that must be enforced at selection (not just admission) hangs here.
- **Keep the two-phase cheap→expensive gate ordering.** Run sender-independent rejects
  before ECDSA recovery (nethermind pre/post-hash split; geth stateless/stateful split;
  besu cheap-price-floor-before-world-state) so junk is dropped before paying recovery
  cost — an admission-DoS mitigation every client converges on.

**OPTIONAL menu (role-dependent, not baseline):**

- **OPTIONAL(product-family): txpool as a separable gRPC service** (erigon) — the
  embed-or-standalone seam. Define the mempool boundary as a transport-neutral service
  interface (admit txn, stream pending, query status/nonce), consume P2P and chain-state
  through equally narrow interfaces, and let the same implementation embed in the lean
  node or hoist into a separate binary an operator scales or replaces. The dRPC-adjacent
  seam for the product-family thesis; requires the pool to hold no execution state.
- **OPTIONAL(enterprise/full): layered promotion pipeline** (besu) — confine full
  ordering to a small candidate set, buffer the rest cheaply, quarantine nonce-gap/spam
  in a drop-first purgatory layer. Relevant to efficient tip-of-branch mining selection.
- **OPTIONAL(enterprise/permissioned): two-veto-point block selection** (besu plugin
  selector, pre- and post-EVM) for result-dependent inclusion policy.

**Note — ETC has no blob / 1559-effective-tip parts.** The `blobpool` (geth authority)
serves the PoS validator role only; core-geth carries it as dead weight gated off by
config. EIP-1559 effective-tip ordering, the `BaseFee` sub-pool split, and base-fee
reheap degenerate on ETC to raw `gasPrice` ordering (core-geth: no base fee → absolute
gas-price semantics). fukuii's ETC path uses the flat-gas-price variant (besu's
`GasPricePrioritizedTransactions` is the analog), not the effective-tip machinery.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

Seeds, not verdicts — these do not authorize implementation.

- **fukuii's banksy tier = the admission/selection seam.** The composable-filter-chain is
  banksy's reference shape: ECIP-1122 `MIN_MINER_TIP` tip floor implemented as a *filter*
  (`TipTooLowFilter`, parameterized by the configured floor), dropped into the chain over
  a consensus-invariant core — never as edits to the pool/validator internals. Admission
  policy stays separate from the ordering data structure.
- **Enterprise custom admission** (allowlists, per-tenant rate limits, compliance gates)
  is the injected-filter / plugin-validator seam — a customer adds admission rules
  without forking the pool.
- **SR-11 basket / transactions sub-section is BLOCKED-ON-BATCH-4** — this synthesis
  feeds that section but does not unblock it.
- **The gRPC-service txpool ties to DRPC-GATEWAY-01** — the product-family / mining-pool
  separable-mempool seam. Evaluate the embed-or-standalone boundary there, with erigon's
  no-execution-state discipline as the precondition for making the seam real.
- **Consensus boundary stays intact.** Tip floors / gas-target / selection ordering are
  banksy-owned (state-root-invariant, operator-tunable). ECIP-1017 emission and
  ECIP-1111 base-fee-floor economics that banksy sizes against remain forge-owned; route
  accordingly.
