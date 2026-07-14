# nethermind — block-production
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind unifies all block production — PoW mining, PoA sealing, PoS payload
building, and dev/NethDev single-node — behind **one `IBlockProducer.BuildBlock(...)`
abstraction** (`Nethermind.Consensus/IBlockProducer.cs`). A single async method takes an
optional parent header, a block tracer, `PayloadAttributes`, and a `Flags` enum
(`EmptyBlock`, `DontSeal`, `PrepareEmptyBlock`) and returns a `Block?`. Every consensus
family plugs into the same three-stage pipeline in `BlockProducerBase` — **prepare header
→ select transactions (via `ITxSource`) → process → seal (via `ISealer`)** — with the
consensus-specific differences isolated to the `ISealer`, the `IDifficultyCalculator`, and
the `ITxSource` injected into the base class.

Production is decoupled from *when* to produce: an `IBlockProducer` is driven by an
`IBlockProducerRunner` (`StandardBlockProducerRunner`) that subscribes to an
`IBlockProductionTrigger` (regular timer, per-pending-tx, on-request, loop, etc. — the
`BuildBlocks*` family in `Producers/`). PoW/PoA use the trigger→runner→producer flow
directly; **PoS bypasses triggers entirely** — the Engine API's `engine_forkchoiceUpdated`
drives `PayloadPreparationService`, which repeatedly re-invokes `BuildBlock` to *improve*
a cached payload until the consensus client fetches it with `engine_getPayload`.

Two capabilities set nethermind apart from core-geth/besu here: **`Nethermind.Flashbots`**
(the relay/builder-validation side of MEV-Boost — `flashbots_validateBuilderSubmissionV3`
plus `rbuilder_*` helper methods for external Rust block builders) and
**`Nethermind.Shutter`** (a threshold-encrypted mempool that decrypts a slot's transactions
only after a keyper set publishes the decryption key, then injects them at the *front* of
the block via a layered `ITxSource` — anti-frontrunning production).

## Key types / interfaces / files

- `Nethermind.Consensus/IBlockProducer.cs:13` — the one production interface:
  `Task<Block?> BuildBlock(parentHeader, blockTracer, payloadAttributes, Flags, ct)`;
  `Flags` = `None | EmptyBlock | DontSeal | PrepareEmptyBlock`.
- `Nethermind.Consensus/Producers/BlockProducerBase.cs:34` — abstract base implementing the
  prepare→process→seal pipeline; `_producingBlockLock` (a `SemaphoreSlim(1)`) serializes
  production; `PrepareBlockHeader` (line ~207) sets timestamp/author/difficulty/basefee,
  `PrepareBlock` (line ~237) pulls txs from `TxSource.GetTransactions(...)`, `SealBlock`
  delegates to `Sealer`. The class-header comment flags it as *wanting* to be split into a
  composable pipeline (prepare frame / select txs / seal).
- `Nethermind.Consensus/Transactions/ITxSource.cs:11` — `GetTransactions(parent, gasLimit,
  payloadAttributes, filterSource)` + `bool SupportsBlobs`; the pluggable tx-selection seam.
- `Nethermind.Consensus/Transactions/TxSourceExtensions.cs:8` — `.Then(second)` composes tx
  sources into a `CompositeTxSource` (first source's txs take priority) — the mechanism
  Shutter uses to prepend decrypted txs.
- `Nethermind.Consensus/StandardBlockProducerRunner.cs:15` — wires an
  `IBlockProductionTrigger` to `IBlockProducer.BuildBlock`, tracks `IsProducingBlocks`,
  raises `BlockProduced`.
- `Nethermind.Consensus/Producers/MultipleBlockProducer.cs:16` — runs N producers in
  parallel and picks the best via an `IBestBlockPicker` (the local-vs-external / MEV
  competition primitive).
- **PoW (cross-ref consensus-pow):**
  `Nethermind.Consensus.Ethash/MinedBlockProducer.cs:15` — a ~10-line `BlockProducerBase`
  subclass that just injects `EthashSealer` + `EthashDifficultyCalculator`.
  `Nethermind.Consensus.Ethash/EthashSealer.cs:16` — `ISealer.SealBlock` runs `_ethash.Mine`
  on a background `Task` (CPU miner, opt-in), sets `Nonce`/`MixHash`, recomputes the hash;
  `CanSeal` always true. `EthashPlugin.cs` / `NethDevPlugin.cs` register the producers.
- **PoS / Merge:**
  `Nethermind.Merge.Plugin/BlockProduction/PayloadPreparationService.cs:30` — caches
  in-progress payloads by `payloadId`; `StartPreparingPayload` builds an empty block first
  (`PrepareEmptyBlock`) then kicks a continuous `ImproveBlock` loop; a timer cleans up stale
  payloads. `PostMergeBlockProducer.cs`, `MergeBlockProducer.cs`,
  `IBlockImprovementContext(Factory).cs` are the improvement-loop seams.
- **Flashbots (MEV/builder):**
  `Nethermind.Flashbots/Flashbots.cs:15` — plugin, enabled by `IFlashbotsConfig`; registers
  the two RPC modules.
  `Nethermind.Flashbots/Modules/Flashbots/IFlashbotsRpcModule.cs:15` —
  `flashbots_validateBuilderSubmissionV3(BuilderBlockValidationRequest)`: the relay-side
  check that a builder's submitted block is valid and pays the proposer.
  `Nethermind.Flashbots/Handlers/ValidateSubmissionHandler.cs:26` — re-executes the
  submitted payload on an overridable read-only env (`ProcessingOptions.ReadOnlyChain |
  ForceProcessing | ...`), validates header/block and the `BidTrace` value.
  `Nethermind.Flashbots/Modules/Rbuilder/IRbuilderRpcModule.cs:16` — `rbuilder_getCodeByHash
  / calculateStateRoot / getAccount / getBlockHash`: state-access helpers an external Rust
  builder needs to assemble blocks off-client.
- **Shutter (encrypted mempool / anti-MEV):**
  `Nethermind.Shutter/ShutterTxSource.cs:17` — an `ITxSource` that returns a slot's
  *decrypted* txs from an LRU cache, keyed by building slot; `GetTransactions` returns empty
  unless `shutterConfig.Validator`; `WaitForTransactions`/`LoadTransactions` gate on key
  arrival.
  `Nethermind.Shutter/ShutterAdditionalBlockProductionTxSource.cs:9` — composes
  `shutterApi.TxSource.Then(baseFactory.Create())` so decrypted txs go *first*.
  `Nethermind.Shutter/ShutterBlockImprovementContext.cs:44` — overrides the Merge improvement
  loop to wait for the decryption key before producing (reports `BlockFees => 0`).
  `ShutterP2P.cs`, `ShutterEon.cs`, `ShutterKeyValidator.cs`, `ShutterCrypto.cs`, and the
  `Contracts/` (keyper-set / sequencer / validator-registry) implement the keyper gossip,
  eon key management, and on-chain registry.

## Design decisions & rationale

- **One `BuildBlock` for every consensus family.** PoW, PoA, PoS, and dev all reduce to the
  same interface; the differences are injected (`ISealer`, `IDifficultyCalculator`,
  `ITxSource`), not branched. Adding a new production mode = new `BlockProducerBase` subclass
  + factory + plugin, no changes to callers.
- **Trigger/runner separation.** *What* to build (`IBlockProducer`) is orthogonal to *when*
  (`IBlockProductionTrigger` + `IBlockProducerRunner`). PoW loops/timers, PoS is driven by
  the Engine API — same producer, different driver.
- **PoS "improve the payload" model.** Because a proposer has a whole slot, nethermind builds
  an empty block immediately (so `getPayload` always has *something*) then continuously
  rebuilds a better one in the background, storing the best in a per-`payloadId` cache. This
  is the transferable structure MEV local-vs-external competition (`MultipleBlockProducer`)
  and Shutter both hook into.
- **Tx-selection as a composable `ITxSource`.** All "what goes in the block" policy lives
  behind `ITxSource` and composes with `.Then(...)`. Shutter needs only a *new tx source*
  layered ahead of the pool source — no edits to `BlockProducerBase`.
- **Flashbots = validate, don't build.** Nethermind's Flashbots module is primarily the
  *relay/proposer validation* side (re-execute a builder's block, check it pays out) plus
  RPC helpers (`rbuilder_*`) that let an external specialized builder do the heavy assembly.
  The client stays a validator/relay participant rather than a monolithic MEV builder.
- **Shutter = fairness via threshold encryption.** Transactions are encrypted to an eon key
  held by a distributed keyper set; the proposer can only include a slot's txs *after* the
  keypers publish the decryption key, eliminating proposer/builder frontrunning. Opt-in,
  validator-only, contract-driven.

## Notable patterns (the reusable idea)

**The single most transferable idea: block production as an injected 3-stage pipeline
(prepare-header → `ITxSource` select → `ISealer` seal) behind one `IBlockProducer`
interface, with a separate trigger/runner deciding *when*.** Consensus-specific behavior is
confined to three injected collaborators, and *policy extensions* (MEV competition,
encrypted mempool) attach without touching the base producer:

- New consensus family → subclass `BlockProducerBase`, inject a sealer + difficulty calc
  (`MinedBlockProducer` is the ~10-line proof of how thin this is).
- New tx-inclusion policy → a new `ITxSource` composed with `.Then(...)` (Shutter).
- Competing block sources → `MultipleBlockProducer` + `IBestBlockPicker` (local vs
  MEV-relay).
- Long-lived, improvable production (PoS) → `PayloadPreparationService`'s
  empty-first-then-improve cache with pluggable `IBlockImprovementContextFactory` (Shutter
  overrides exactly this to wait for decryption keys).

## Authority note

nethermind = the **Flashbots-builder / MEV-relay-validation + Shutter-encrypted-mempool
block-production reference**, and the best reference for a *unified* `IBlockProducer`
abstraction spanning PoW/PoA/PoS. For ETC PoW *sealing* semantics (Ethash mine, ECIP-1017
reward, difficulty), **core-geth is the authority** — nethermind's `EthashSealer` is an
opt-in CPU miner, not the ETC production path. For BFT/round-robin **proposer** selection,
besu (QBFT/IBFT) is the reference. Treat nethermind here as the source for *structure*
(the pluggable pipeline) and for the *advanced optional features* (MEV builder-validation,
Shutter), not for ETC consensus values.

## Gotchas / anti-patterns / things they later changed

- **`BlockProducerBase` self-documents as not-yet-refactored.** Its class comment says the
  class "can be significantly simplified" by splitting into a real prepare/select/seal
  pipeline of separately testable components — production still bundles the stages in one
  class. Don't mistake the *conceptual* pipeline for a fully componentized one.
- **`EthashSealer` is opt-in mining, not the ETC path.** `CanSeal` returns `true`
  unconditionally and mining runs on a raw `Task.Factory.StartNew`; it is a dev/testnet CPU
  miner. Do not port it as an authoritative ETC sealer — go to core-geth.
- **PoS payload building never touches the trigger/runner path.** `PayloadPreparationService`
  calls `BuildBlock` directly and manages its own cache + cleanup timer. The empty-block-first
  step exists specifically so `engine_getPayload` never returns nothing; a `GetPayload...
  Delay` (50 ms) waits briefly for a non-empty improvement.
- **Shutter production silently no-ops off the happy path.** `ShutterTxSource.GetTransactions`
  returns `[]` when not in validator mode, when the slot can't be computed, or when keys
  haven't arrived — encrypted txs are simply omitted rather than the block failing. Fail-open
  by design, but easy to misread as "Shutter didn't work."
- **`MultipleBlockProducer` swallows per-producer cancellation.** On
  `OperationCanceledException` it keeps only the producers whose tasks
  `IsCompletedSuccessfully`; a cancelled/faulted MEV source just drops out of the race rather
  than aborting the slot.
- **Flashbots is validation-first.** It is not a full internal MEV builder — heavy block
  assembly is expected to happen in an external `rbuilder` that queries nethermind through
  the `rbuilder_*` state-access methods. Treat "nethermind as MEV builder" as *relay/builder
  support*, not a self-contained builder.
