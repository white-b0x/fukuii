# nethermind — cl-engine
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind implements the merge / consensus-layer integration as a **plugin that
decorates the base consensus engine**, not as a distinct schedule the node swaps
into. `MergePlugin` (`INethermindPlugin`) is enabled whenever the chainspec seal
engine is `BeaconChain`, `Clique`, or `Ethash` (`MergePlugin.cs:62`), and its
Autofac module (`MergePluginModule`) layers PoS-aware behaviour **on top of** the
already-wired PoW/PoA components by _decorating_ them:
`IHeaderValidator → MergeHeaderValidator`, `ISealValidator → MergeSealValidator`,
`ISealer → MergeSealer`, `IRewardCalculatorSource → MergeRewardCalculatorSource`,
`IBlockProducerFactory → MergeBlockProducerFactory`, etc.
(`MergePlugin.cs:198-215`). Each decorator internally delegates pre-merge blocks
to the wrapped base component and applies PoS rules post-merge. This is the
**plugin-co-activation** model: the base engine (Ethash for ETH mainnet, AuRa for
Gnosis) stays fully wired; the merge plugin composes over it and a single runtime
switch — `IPoSSwitcher` — decides, per block header, which regime governs.

The switch is `IPoSSwitcher` / `PoSSwitcher` (`Nethermind.Consensus/IPoSSwitcher.cs`,
`Nethermind.Merge.Plugin/PoSSwitcher.cs`). It owns the TTD transition state machine
(EIP-3675: reach TTD with PoW → first `forkchoiceUpdated` → first finalized PoS
block) and answers `GetBlockConsensusInfo(header) → (IsTerminal, IsPostMerge)`,
which every decorator consults. TTD/terminal-block parameters are resolved with a
priority chain: `MergeConfig` (CLI override) > chainspec > memory/DB
(`PoSSwitcher.cs:19-34`). The terminal block is discovered dynamically by
subscribing to `_blockTree.NewHeadBlock` until TTD is crossed, then persisted to
the metadata DB (`PoSSwitcher.cs:83-146`).

The **engine-driver** side — the handlers that actually mutate the chain in
response to the CL — lives in `Nethermind.Merge.Plugin/Handlers/`:
- `NewPayloadHandler` decodes an `ExecutionPayload` into a `Block`, validates the
  block hash, records parent/child in the `InvalidChainTracker`, then
  `SuggestBlockAsync(..., ForceDontSetAsMain)` + enqueues into
  `IBlockProcessingQueue` for full EVM execution, returning
  `VALID/INVALID/SYNCING/ACCEPTED`. It does **not** set head.
- `ForkchoiceUpdatedHandler` takes `(head, safe, finalized)`, validates
  consistency + ancestry, and on a processed head calls
  `_blockTree.TryUpdateMainChain(newHeadHeader, …, forceUpdateHeadBlock: true)` to
  perform the reorg, then `_poSSwitcher.ForkchoiceUpdated(...)` and
  `_blockTree.ForkChoiceUpdated(finalized, safe)`. If the head is unknown it drops
  into **beacon (backward) sync** instead of mutating.

## Key types / interfaces / files

- `src/Nethermind/Nethermind.Merge.Plugin/MergePlugin.cs:47` — `MergePlugin`, the
  `INethermindPlugin` DRIVER. `MergeEnabled` gates on seal-engine type
  (`:62`); `Init` resolves `IPoSSwitcher`/`IBlockCacheService`/`InvalidChainTracker`
  and installs `MergeGossipPolicy` + `MergeProcessingRecoveryStep` (`:83-99`).
- `MergePlugin.cs:191` — `MergePluginModule`: the Autofac `AddDecorator<…>` stack
  that co-activates PoS over the base engine (the reusable idea).
- `MergePlugin.cs:222` — `BaseMergePluginModule`: shared post-merge components
  (beacon sync, `IPoSSwitcher → PoSSwitcher`, `InvalidChainTracker`, beacon pivot)
  reused by Optimism, Taiko, and AuRa-merge — explicitly designed as a reuse seam.
- `src/Nethermind/Nethermind.Consensus/IPoSSwitcher.cs:11` — the transition
  interface: `GetBlockConsensusInfo`, `IsPostMerge`, `TryUpdateTerminalBlock`,
  `ForkchoiceUpdated`, `TransitionFinished`, `TerminalTotalDifficulty`.
- `src/Nethermind/Nethermind.Merge.Plugin/PoSSwitcher.cs:36` — `PoSSwitcher`,
  the TTD state machine. `GetBlockConsensusInfo` (`:161`) is the per-header
  classifier; `TryUpdateTerminalBlock` (`:120`) fires the `TerminalBlockReached`
  event and persists terminal number/hash.
- `src/Nethermind/Nethermind.Merge.Plugin/Handlers/NewPayloadHandler.cs:38` —
  the `engine_newPayload` driver: decode → validate hash → invalid-chain gate
  (`:140`) → `SuggestBlockAsync` + `_processingQueue.Enqueue` (`:383,:413`).
- `src/Nethermind/Nethermind.Merge.Plugin/Handlers/ForkchoiceUpdatedHandler.cs:35` —
  the `engine_forkchoiceUpdated` driver: `ApplyForkchoiceUpdate` →
  `TryUpdateMainChain` (`:255`) → `_poSSwitcher.ForkchoiceUpdated` (`:265`) →
  optional `payloadPreparationService.StartPreparingPayload` (block building).
- `src/Nethermind/Nethermind.Merge.AuRa/AuRaMergePlugin.cs:36` — `AuRaMergePlugin
  : MergePlugin`, `MergeEnabled` re-gated on `SealEngineType.AuRa`; must load
  **before** `MergePlugin` (comment `:35`). `AuRaMergeModule` re-uses
  `BaseMergePluginModule` and swaps in AuRa-specific withdrawal/block-processor
  components — the plugin-composability angle for Gnosis post-merge.
- `src/Nethermind/Nethermind.Merge.Plugin/Synchronization/BeaconPivot.cs:19`,
  `BeaconSync.cs`, `BeaconHeadersSyncFeed.cs` — backward beacon-header sync: when
  fcU/newPayload references an unknown head, the driver starts syncing headers
  backward from the CL-supplied pivot (`ForkchoiceUpdatedHandler` calls
  `StartNewBeaconHeaderSync`).
- `src/Nethermind/Nethermind.Serialization.Ssz/` — the SSZ codec library
  (`Ssz.Encode.cs`/`Ssz.Decode.cs`, `Merkleization/`, `SszSerializableAttribute`),
  consumed by the newer **SSZ-over-REST Engine API** transport in
  `Nethermind.Merge.Plugin/SszRest/` (`SszExecutionPayload.cs`, `SszCodec.cs`,
  `Handlers/NewPayloadSszHandler.cs`, `ForkchoiceUpdatedSszHandler.cs`) — the same
  driver handlers, fed by an SSZ wire format instead of JSON.

## Design decisions & rationale

- **Plugin decoration over schedule replacement.** The merge is not a fork entry
  in a `SpecProvider` timeline; it is a set of Autofac decorators that wrap the
  base engine's validators/sealer/reward source. Pre-merge blocks fall through to
  the wrapped PoW/PoA component; post-merge blocks get PoS rules. This keeps the
  base consensus engine intact and untouched and lets one runtime object
  (`IPoSSwitcher`) arbitrate — the reason the same `MergePlugin` works over
  Ethash, Clique, and (via subclass) AuRa.
- **Single per-header switch, three observation points.** `PoSSwitcher` deliberately
  centralises the "is this block PoS?" decision because it must be answered from
  three unsynchronised places — block processing (to swap producer classes at the
  transition), forkchoice (to handle terminal-block reorgs), and reverse header
  sync (to find the terminal block) — noted in the `IPoSSwitcher.cs:38-43` comment.
- **newPayload ≠ head mutation.** `newPayload` only inserts + processes the block
  (`ForceDontSetAsMain`); the canonical head only moves on `forkchoiceUpdated`.
  This mirrors the Engine API contract and cleanly separates "validate this block"
  from "this is now the chain."
- **Dynamic terminal-block discovery, then config-pinnable.** Because the terminal
  PoW block number isn't known before the merge, `PoSSwitcher` discovers it by
  watching new heads, then persists it; after the first post-merge release it can
  be pinned via `MergeConfig`/chainspec so the expensive TD checks collapse to a
  block-number comparison (`PoSSwitcher.cs:31-34, 195-206`).

## Notable patterns (the reusable idea)

**Merge-as-decorator co-activation.** The single most transferable pattern: rather
than a `TransitionProtocolSchedule` that picks PoW-schedule-vs-PoS-schedule (besu)
or a hard branch in the processor (geth), Nethermind expresses the transition as
`AddDecorator<IBaseComponent, MergeComponent>()` over the already-built base engine,
governed by one injectable `IPoSSwitcher`. Adding a merge to a new base engine is
"subclass the plugin, re-gate `MergeEnabled`, reuse `BaseMergePluginModule`" —
exactly what `AuRaMergePlugin` does in ~60 lines. The base engine never learns it
is being merged; the switch and the decorators carry all transition state.

## Authority note

nethermind = the MergePlugin-co-activation + IPoSSwitcher reference (merge layered
over any base engine, including AuRa/Gnosis). besu's `TransitionProtocolSchedule`
(schedule-swap) and go-ethereum's `engine/` beacon-consensus driver are the peer
references for the same slot — cross-check driver semantics (newPayload/fcU chain
mutation, terminal-block classification) against all three, but nethermind is the
authority for the _plugin-composability_ shape specifically.

## Gotchas / anti-patterns / things they later changed

- **ETC/PoW does not use any of this.** The whole `cl-engine` slot is PoS-only.
  For fukuii, ETC/Mordor never instantiates a MergePlugin, IPoSSwitcher, Engine
  API driver, or beacon sync — this slot informs the _plugin-composability_ design
  of fukuii's B7.0-c (private-network / multi-network stack), not the ETC path.
- **Plugin load order is load-bearing.** `AuRaMergePlugin` must be registered
  before `MergePlugin` (`AuRaMergePlugin.cs:35`); some components set by
  `MergePlugin` are later replaced by standard AuRa components (`AuRaMergeModule`
  comment). Co-activation via decoration is order-sensitive — a real footgun the
  comments flag explicitly.
- **Config-conflict guards.** `MergePlugin` throws on contradictory settings
  (`TerminalTotalDifficulty` set while `Enabled=false`, `SecondsPerSlot` mismatch
  between `BlocksConfig`/`MergeConfig`) rather than silently picking one
  (`MergePlugin.cs:103-136`) — fail-loud on transition misconfiguration.
- **Engine port is forced on.** If TTD is configured but JSON-RPC/engine module
  isn't, `MergePlugin` auto-enables JSON-RPC and throws if no engine port is
  configured (`MergePlugin.cs:138-181`) — a post-merge node is inoperable without
  the CL driver channel.
- **SSZ Engine API is the newer transport layer.** The `Nethermind.Serialization.Ssz`
  library + `SszRest/` handlers are an added SSZ-over-REST path feeding the _same_
  `NewPayloadHandler`/`ForkchoiceUpdatedHandler` driver logic — distinguish the
  transport (JSON vs SSZ, rpc-api slot) from the driver (this slot).
