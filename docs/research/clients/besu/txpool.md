# besu — txpool
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

Package root: `ethereum/eth/src/main/java/org/hyperledger/besu/ethereum/eth/transactions/`
(hereafter `…/transactions/`). Plugin seams live in `plugin-api/…/plugin/services/`.

## Architecture summary

besu runs **two selectable txpool implementations** behind one `PendingTransactions`
interface, chosen by config (`--tx-pool=LAYERED|SEQUENCED`, default `LAYERED`):

- **Layered pool** (`layered/`) — the modern default. A chain of priority-ordered
  *layers*, each with its own admission rule, capacity limit, ordering, and eviction
  policy. A transaction lives in exactly one layer at a time; layers are wired
  head→tail as **Prioritized → Ready → Sparse → End**. Adds are tried at the head
  layer and cascade toward the tail (`TRY_NEXT_LAYER`); freed space triggers promotion
  back toward the head. Designed specifically to tolerate **nonce gaps** and hostile
  spam without penalizing legitimately-out-of-order senders.
- **Legacy sorter pool** (`sorter/`) — the older `AbstractPendingTransactionsSorter`
  (base-fee / gas-price variants). A single flat sorted structure. Retained for
  compatibility; layered supersedes it.

`TransactionPool` (the `BlockAddedObserver`) is the front door for *admission*: it runs
the multi-stage validation pipeline, then hands accepted txs to the active
`PendingTransactions`. `LayeredPendingTransactions` is the synchronization owner and
delegates all storage to the head `AbstractPrioritizedTransactions` layer. Block
*building* pulls candidates back out via `selectTransactions(selector)`, where the
selector is driven by `BlockTransactionSelector` and can wrap plugin logic.

The two **plugin seams** — a `PluginTransactionPoolValidator` (gate admission) and a
`PluginTransactionSelector` (gate block inclusion) — are the enterprise
permissioning/custom-policy hooks, and are the direct structural analog of fukuii's
`banksy` admission + transaction-selection tier.

## Key types / interfaces / files

- `…/transactions/PendingTransactions.java` — the pool SPI: `addTransaction`,
  `selectTransactions(PendingTransactionsSelector)`, `manageBlockAdded`, nonce/query
  accessors, listener subscription. Both implementations satisfy it; also
  `DisabledPendingTransactions.java` (no-op when pool disabled).
- `…/transactions/TransactionPool.java:229` `addTransaction(...)` — admission entry;
  `:409` `validateTransaction(...)` — the ordered admission pipeline (see below).
- `…/transactions/TransactionPoolFactory.java:253` `createPendingTransactions` — picks
  LAYERED vs SEQUENCED; `:308` `createLayeredPendingTransactions` wires the layer chain
  `EndLayer → SparseTransactions → ReadyTransactions → (BaseFee|GasPrice)Prioritized`.
- `…/transactions/layered/package-info.java` — the canonical prose description of the
  layered design (read this first; it is the design doc).
- `…/transactions/layered/TransactionsLayer.java` — the per-layer interface: `add(tx,
  gap, AddReason)`, `remove`, `promote(...)`, `penalize`, `getByScore`, `blockAdded`.
- `…/transactions/layered/AbstractTransactionsLayer.java` — shared layer machinery:
  `add` (`:200`) → `canAdd` → `processAdded` → `maybeFull`/`evict`; overflow cascades
  via `addToNextLayer` (`:342`); freed space drives `promoteTransactions` (`:506`).
- `…/transactions/layered/AbstractPrioritizedTransactions.java` — head layer; keeps a
  `TreeSet<PendingTransaction> orderByFee`, count-limited (`getMaxPrioritizedTransactions`,
  default 2000), no nonce gaps, `getByScore()` produces the block-building candidate list.
- `…/transactions/layered/BaseFeePrioritizedTransactions.java` — orders by
  `getEffectivePriorityFeePerGas(nextBlockBaseFee)` (EIP-1559 chains).
- `…/transactions/layered/GasPricePrioritizedTransactions.java` — orders by flat gas
  price (pre-1559 / non-base-fee chains — the ETC-relevant variant).
- `…/transactions/layered/ReadyTransactions.java` — byte-space-limited buffer feeding
  Prioritized; no nonce gaps; only the sender's first tx is fully ordered
  (score → priority → maxGasPrice → sequence).
- `…/transactions/layered/SparseTransactions.java` — the only gap-tolerant layer;
  "purgatory" for out-of-order/future txs; evicts oldest-first by `sequence`, tracks
  `gapBySender` so gap-free txs can be promoted to Ready.
- `…/transactions/layered/EndLayer.java` — the tail sink; everything reaching it is
  dropped (the overflow terminator).
- `…/transactions/layered/AbstractSequentialTransactionsLayer.java` — shared base for
  the two no-gap layers (Prioritized, Ready).
- `…/transactions/TransactionPoolReplacementHandler.java` +
  `TransactionReplacementByFeeMarketRule.java` (`:40` `shouldReplace`) /
  `TransactionReplacementByGasPriceRule.java` — same-nonce replacement policy, requires
  a `priceBump` %; separate, higher `blobPriceBump` for blob txs.
- **Admission plugin seam:** `plugin-api/…/txvalidator/PluginTransactionPoolValidator.java`
  — `validateTransaction(tx, isLocal, hasPriority) → Optional<String>` (empty = accept);
  factory `PluginTransactionPoolValidatorFactory`; wired via
  `plugin-api/…/services/TransactionPoolValidatorService.java`, impl
  `app/…/services/TransactionPoolValidatorServiceImpl.java`. `VALIDATE_ALL` is the
  default no-op.
- **Selection plugin seam:** `plugin-api/…/txselection/PluginTransactionSelector.java` —
  `evaluateTransactionPreProcessing` + `evaluateTransactionPostProcessing` (pre- and
  post-EVM-execution veto points) returning a `TransactionSelectionResult`; plus
  `onTransactionSelected` / `onTransactionNotSelected` callbacks. `ACCEPT_ALL` default;
  factory `PluginTransactionSelectorFactory`; service
  `plugin-api/…/services/TransactionSelectionService.java`.
- `ethereum/blockcreation/…/txselection/BlockTransactionSelector.java:210`
  `buildTransactionListForBlock` → `selectTransactions(this::timeLimitedSelection)` —
  drives selection, runs the plugin selector (`:233` `pluginTimeLimitedSelection`) under
  a time budget, then the internal EVM-execution selection loop.
- Blob handling: `…/transactions/BlobCache.java` (Caffeine `Cache<VersionedHash,
  BlobProofBundle>`), `restoreBlob` on the pool interface; `PendingTransaction` carries
  the blob sidecar; blob-tx admission checks in `validateTransaction` (`:460`).

## Design decisions & rationale

- **Layers as a promotion/demotion pipeline, not a single sorted set.** Each layer is a
  narrow contract (`canAdd` gate + `getEvictable` + ordering). Admission tries the head;
  rejection falls through to the next layer; a freed slot promotes from the next layer.
  This keeps the *expensive full sort* confined to the small (2000-tx) Prioritized layer
  while the large Ready/Sparse buffers use cheaper partial ordering — an explicit
  cost/scale tradeoff called out in `package-info.java`.
- **Nonce-gap containment.** Only Sparse tolerates gaps; Prioritized/Ready require each
  sender's head tx to be the next expected nonce. Spam of non-executable future txs is
  quarantined in Sparse (evicted oldest-first) and can never crowd out executable txs in
  the block-candidate layer. `maxFutureBySender` bounds per-sender future depth at the
  `TransactionPool`/`LayeredPendingTransactions` boundary (`nonceChecks`).
- **Score-based demotion instead of hard drop.** A tx penalized during block selection
  (`penalize`) has its score decremented and is re-sorted lower rather than evicted, so
  a transiently-failing tx gets another chance. `getByScore()` is what block building
  consumes.
- **Nonce reconciliation on disparity.** `LayeredPendingTransactions.reconcileSender`
  rebuilds a sender's txs when the world-state nonce and the pool's tracked nonce
  diverge (block-import race or reorg) — the pool trusts world state and rebuilds rather
  than silently corrupting.
- **Admission pipeline order (cheap → expensive), `validateTransaction`:** chain-head
  present → price floor (`validatePrice`) → stateless `TransactionValidator.validate`
  → strict-replay-protection → block-gas-limit → tx-type/fee-market legality → blob
  presence → **plugin validator** → world-state `validateForSender` (nonce/balance).
  State access is last so cheap rejects never touch the trie.
- **Local vs priority senders.** Local (API-submitted) txs are prioritized by default
  (`--tx-pool-no-local-priority` to disable); explicit `--tx-pool-priority-senders` list
  also grants priority. Priority is a first-class sort key inside layers.
- **Selection under a time budget with two plugin veto points.** Block building caps
  total and per-plugin selection time; the plugin selector can veto a candidate *before*
  execution (cheap permissioning) and *after* execution (result-dependent policy).

## Notable patterns (the reusable idea)

**The single most transferable pattern for fukuii/banksy: the two plugin seams as the
policy boundary.** besu cleanly separates *consensus-mandatory* validation (baked into
`TransactionValidator`) from *operator-tunable policy* (the `PluginTransactionPoolValidator`
admission gate and the `PluginTransactionSelector` block-inclusion gate). Both default to
no-op (`VALIDATE_ALL` / `ACCEPT_ALL`), both return a simple accept/reject-with-reason, and
both sit at exactly the two points banksy owns — mempool admission and transaction
selection/ordering. This is the reference shape for banksy's ECIP-1122 tip floor,
permissioning, and selection ordering: express them as pluggable gates layered over the
consensus-invariant core, not as edits to the validator/selector themselves.

Secondary reusable idea: **the layered promotion pipeline** — confine full ordering to a
small block-candidate set, buffer the rest cheaply, and quarantine nonce-gap/spam txs in a
drop-first purgatory layer. Directly relevant to efficient tip-of-branch selection for
mining.

## Authority note

besu = JVM txpool structural reference and the source for the **plugin admission/selection
seam** shape (besu is fukuii's JVM structural mirror; its layered pool + `TransactionsLayer`
contract + `PluginTransactionPoolValidator`/`PluginTransactionSelector` map onto banksy's
admission and selection/ordering tier). **go-ethereum is the canonical `SubPool`/`blobpool`
authority** for on-the-wire pool semantics and blob-pool behavior; consult it for
byte/behavior-exact questions. Neither is the ETC *consensus* authority — that remains
core-geth (tip floors / emission economics that banksy sizes against are ECIP-driven).

## Gotchas / anti-patterns / things they later changed

- **Layers are deliberately NOT thread-safe.** All synchronization is centralized in
  `LayeredPendingTransactions` (`synchronized` methods + `EthScheduler` service tasks for
  penalize/discard). Do not call a layer directly, and do not assume per-layer locking —
  replicating the pool means replicating the single-owner sync model.
- **Two pools, one interface — the legacy `sorter/` pool still exists.** New behavior
  must be added to the layered path; the sorter is compatibility-only. Don't mistake the
  sorter for the current design.
- **Selection is not the whole pool.** `selectTransactions` snapshots only the head
  (Prioritized) layer's `getByScore()` candidates under lock, then releases the lock for
  the (long) evaluation — so block building sees a *point-in-time* candidate set, and
  discard/penalize results are applied back asynchronously via `EthScheduler`.
- **`maxSize()` returns -1 for the layered pool** — capacity is per-layer (count for
  Prioritized, bytes for Ready/Sparse via `getPendingTransactionsLayerMaxCapacityBytes`),
  not a single global count. Sizing/monitoring must reason per-layer.
- **Blob sidecars are cached separately** (`BlobCache`, Caffeine) and can be restored
  onto a tx via `restoreBlob`; the pool stores the tx but the heavy blob data has its own
  eviction lifecycle — don't assume tx eviction frees blob memory or vice versa.
- **Plugin interfaces are `@Unstable`.** The seam shape is the right model to copy, but
  besu itself does not guarantee its signature across releases.
