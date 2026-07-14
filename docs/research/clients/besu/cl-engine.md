# besu — cl-engine
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

Scope: the **consensus-layer-integration / engine-driver / merge-transition** side of
besu — how besu translates Consensus Layer (CL) directives into chain mutation and
fork-choice, and how it composes a pre-merge (PoW/Clique) protocol and a post-merge (PoS)
protocol into one running node during the transition. This is the **driver + transition
semantics**, distinct from the Engine-API JSON-RPC transport (`engine_newPayload`,
`engine_forkchoiceUpdated` handler classes) which is the rpc-api slot's concern. The driver
lives in `consensus/merge/src/main/java/org/hyperledger/besu/consensus/merge/`.

Use-case lens: the engine-driver serves the PoS/validator use case (an EL driven by a CL).
The composable `TransitionProtocolSchedule` / `TransitionCoordinator` / `TransitionContext`
family — the "run two consensus rulesets in one binary, dispatch by state" pattern — is the
multi-network reference that fukuii's **B7.0-c conditional-wrapper** validates. **ETC/PoW
does not use any of this**: there is no CL, no Engine API, no TTD merge; ETC stays on the
pre-merge (block-number-dispatched) schedule permanently. besu's value here is the *shape*
of the composition, not the PoS semantics themselves.

## Architecture summary

Besu models the merge as a **state-dependent switch between two fully-formed subsystems**
rather than as a fork inside one schedule. For every merge-aware concern there is a triplet:

- a **pre-merge object** (the existing PoW/Clique implementation),
- a **post-merge object** (the PoS implementation), and
- a **Transition* wrapper** that holds both and, on each call, dispatches to one of them
  according to the single authoritative boolean `MergeContext.isPostMerge()`.

The generic dispatch mechanism is `TransitionUtils<SwitchingObject>` — it stores
`preMergeObject` + `postMergeObject` + `mergeContext` and exposes
`dispatchFunctionAccordingToMergeState(fn)` = `fn.apply(isPostMerge ? post : pre)`. Every
Transition wrapper is either a `TransitionUtils` subclass (`TransitionCoordinator`) or holds
one (`TransitionProtocolSchedule`), so the *same* pre/post/flag pattern is reused for the
protocol schedule, the mining coordinator, the consensus context, and the backward-sync
context. This is the single most transferable idea in the slot.

The **engine-driver** proper is `MergeCoordinator` (implements `MergeMiningCoordinator`). It
is the object that actually mutates the chain in response to CL directives. Two directive
families:

1. **Block building** (`engine_forkchoiceUpdated` with payload attributes →
   `preparePayload`): builds an *empty* block first, stores it, then asynchronously and
   repeatedly rebuilds a *better* (higher-value) block until the CL calls `getPayload`. Best
   proposal wins by `PayloadWrapper.compareTo` (block value).
2. **Block insertion + fork-choice** (`engine_newPayload` → `rememberBlock`/`validateBlock`;
   `engine_forkchoiceUpdated` → `updateForkChoice`): validate-and-process a CL-supplied
   block, then move the canonical head (forward, or rewind on reorg) and persist the world
   state, finalized, and safe-block pointers.

`MergeContext` (impl `PostMergeContext`) is the **shared mutable merge state** and the event
bus: it holds `isPostMerge`, terminalTotalDifficulty, finalized/safe/terminal-PoW block
pointers, the in-progress payload cache, and three subscriber lists (merge-state changed,
unverified-forkchoice, new-payload). The transition from PoW→PoS is a one-way latch: once
`setIsPostMerge` sees total difficulty ≥ TTD it flips to `true` and **never flips back**.

`BackwardSyncContext` is how the driver copes with a CL handing it a head hash whose ancestors
it does not have: `getOrSyncHeadByHash` triggers `syncBackwardsUntil(headHash)`, which
backward-fills from the CL-given head toward the local chain, then sets finalized.

## Key types / interfaces / files

- `consensus/merge/…/blockcreation/MergeCoordinator.java:77` — **the engine-driver**.
  `implements MergeMiningCoordinator, BadChainListener`. Owns `preparePayload` (async
  best-block building loop), `updateForkChoice`/`applyForkChoice`/`setNewHead` (canonical
  head mutation + reorg rewind), `rememberBlock`/`validateBlock`, `computeReorgDepth`,
  `getLatestValidAncestor`, `onBadChain`. `isMining()` hardcoded `true`; the legacy
  `createBlock(...)` overloads throw `UnsupportedOperationException("random is required")` —
  PoS blocks require prevRandao and cannot be built the old way.
- `consensus/merge/…/blockcreation/MergeCoordinator.java:730` — `applyForkChoice`: rejects
  head whose timestamp ≤ parent's, calls `setNewHead`, then persists finalized + safe block
  into both blockchain and `mergeContext`.
- `consensus/merge/…/blockcreation/MergeCoordinator.java:768` — `setNewHead`: moves world
  state, then `forwardToBlock` (extension) vs `rewindToBlock` (reorg) — the actual chain
  mutation.
- `consensus/merge/…/blockcreation/MergeCoordinator.java:855` — `findValidAncestor`: walks
  back; **returns `Hash.ZERO` when it hits a PoW block** (difficulty > 0), i.e. the
  latest-valid-hash of an invalid post-merge chain that roots in PoW is zero (Engine-API
  spec behaviour).
- `consensus/merge/…/blockcreation/MergeMiningCoordinator.java:39` — **the driver interface**.
  Defines `MAX_REORG_DEPTH = 90_000`, the `PreparePayloadArgs` record (parentHeader,
  timestamp, prevRandao, feeRecipient, withdrawals, parentBeaconBlockRoot, slotNumber), the
  `ForkchoiceResult` value type with `Status {VALID, INVALID, INVALID_PAYLOAD_ATTRIBUTES,
  IGNORE_UPDATE_TO_OLD_HEAD}`, and `updateForkChoiceWithoutLegacySkip` (newer
  execution-apis #786 path that moves the ancestor-of-finalized skip out to the caller).
- `consensus/merge/…/blockcreation/TransitionCoordinator.java:35` — the **outer** mining
  coordinator the rest of besu sees. `extends TransitionUtils<MiningCoordinator> implements
  MergeMiningCoordinator`. Lifecycle/mining methods (`start`, `enable`, `isMining`,
  `createBlock`, gas-price) dispatch pre/post by merge state; **all engine-driver methods
  (`preparePayload`, `updateForkChoice`, `rememberBlock`, `getOrSyncHeadByHash`, …) delegate
  unconditionally to the `mergeCoordinator`** — the driver is always the PoS one, only the
  *mining* half switches.
- `consensus/merge/…/TransitionProtocolSchedule.java:37` — **the composable PoW→PoS
  transition schedule**; `implements ProtocolSchedule`, wraps `preMergeProtocolSchedule` +
  `postMergeProtocolSchedule` + `mergeContext` via a `TransitionUtils<ProtocolSchedule>`.
  `getByBlockHeader` dispatches by merge-state **and** by the header's own difficulty==0
  (see TransitionUtils below). `getByBlockHeaderWithTransitionReorgHandling` (`:100`) is the
  careful path used before finalization: it consults TTD and `isTerminalProofOfWorkBlock` to
  decide pre- vs post-merge schedule for a header that may be a re-org of the TTD block.
  `putBlockNumberMilestone`/`putTimestampMilestone` throw — the wrapper is a router, not a
  registry.
- `consensus/merge/…/TransitionUtils.java:34` — the **generic dispatch primitive**.
  `dispatchFunctionAccordingToMergeState(fn)` = `fn(isPostMerge ? post : pre)`. The
  header-aware overload (`:87`) additionally requires `blockHeader.difficulty == 0` before
  choosing post-merge — so a PoW header always routes to the pre-merge object even after the
  node is post-merge. `isTerminalProofOfWorkBlock` (`:122`) is the static TTD-crossing test
  (parentTD < TTD ≤ parentTD + headerDifficulty), plus the merge-at-genesis special case.
- `consensus/merge/…/MergeContext.java:27` — **the merge-state interface** (`extends
  ConsensusContext`). isPostMerge, TTD get/set, finalized/safe/terminal-PoW pointers,
  payload cache put/retrieve, and the three subscriber-registration methods. `fireNewPayloadEvent`
  is fired per `engine_newPayload`; `fireNewUnverifiedForkchoiceEvent` per FCU.
- `consensus/merge/…/PostMergeContext.java:36` — **the concrete merge state**. The
  one-way-latch `setIsPostMerge` (`:94`); `isSyncing` (`:127`) with the `--p2p-enabled=false`
  short-circuit (TTD marked reached at startup so the engine API can serve immediately — the
  documented commit `3fd233a4f9` is exactly `setReachedTerminalDifficulty when p2p
  disabled`); the `EvictingQueue<PayloadWrapper>` best-block cache of size
  `MAX_BLOCKS_IN_PROGRESS = 12`; `putPayloadById` keeping only the highest-value proposal.
- `consensus/merge/…/TransitionContext.java:27` — **the composable consensus context**;
  `implements MergeContext`, holds `preMergeContext` (a plain `ConsensusContext`, e.g.
  Clique/PoW) + `postMergeContext`. `as(klass)` returns whichever context matches the
  requested type — lets Clique-specific and merge-specific code each fetch its own context
  from one object. All merge operations delegate to `postMergeContext`.
- `consensus/merge/…/TransitionBackwardSyncContext.java:31` — **the merge-aware
  backward-fill**. `extends BackwardSyncContext`, overrides only
  `getBlockValidatorForBlock` to pick the validator via
  `getByBlockHeaderWithTransitionReorgHandling` — so blocks backward-filled across the TTD
  boundary are validated under the correct (pre- or post-merge) ruleset even before the chain
  finalizes.
- `ethereum/eth/…/backwardsync/BackwardSyncContext.java:45` — the base backward-sync engine.
  `syncBackwardsUntil(Hash)` (`:138`) appends the CL-given head hash to the `BackwardChain`
  and starts/joins a sync session; `syncBackwardsUntil(Block)` (`:159`) appends a trusted
  pivot block. `BATCH_SIZE = 200`, halving on failure. This is how a CL head with missing
  ancestors gets filled.
- `consensus/merge/…/TransitionBestPeerComparator.java:29` — peer selection during
  transition: ranks peers by `|estimatedTotalDifficulty − TTD|` (closest to the merge point
  first), then chain height; updates TTD from the merge-state-changed event. Steers sync
  toward peers near the terminal block.
- `consensus/merge/…/MergeProtocolSchedule.java:44` — builds the post-merge `ProtocolSpec`s,
  installing the no-difficulty / no-nonce / constant-ommers-hash header rules that define a
  PoS block.
- `ethereum/eth/…/consensus/merge/{MergeStateHandler,NewPayloadListener,UnverifiedForkchoiceListener,ForkchoiceEvent}.java`
  — the listener SAM interfaces + event value type wired to the `PostMergeContext` subscriber
  lists (note these four live in the `eth` module, not `consensus/merge`, to avoid a dependency
  cycle).

## Design decisions & rationale

- **State-switch, not fork-in-schedule.** The merge changes the *entire* consensus engine
  (block validation, mining, fork-choice authority), not just a fee schedule, so besu keeps
  two complete `ProtocolSchedule`s and dispatches at runtime rather than trying to express
  PoS as another milestone inside the PoW schedule. The `TransitionUtils` pre/post/flag
  triple is applied uniformly to every affected subsystem.
- **Single authoritative flag with a one-way latch.** `PostMergeContext.isPostMerge` is the
  one source of truth, computed from TTD, and once true it never reverts (`setIsPostMerge`
  early-returns). This prevents a deep PoW re-org from silently un-merging a node that has
  already finalized post-merge blocks.
- **Difficulty==0 as the intrinsic PoS marker.** Header-aware dispatch also checks
  `header.difficulty == 0`, so even post-merge a stray PoW header validates under PoW rules —
  belt-and-suspenders against mis-routing during the transition/re-org window.
- **Empty block first, then improve.** `preparePayload` immediately stores a valid empty
  block so a `getPayload` that arrives early always has something to return, then spins an
  async loop (`retryBlockCreationUntilUseful`) to produce higher-value blocks until deadline
  or cancellation. Decouples the CL's timing from block-building latency.
- **Driver is always PoS; only mining switches.** `TransitionCoordinator` delegates all
  engine-API driver methods straight to the merge coordinator (the Engine API only exists
  post-merge), while `start`/`isMining`/`createBlock` dispatch by state. Pre-merge the node
  mines; post-merge the node is driven.
- **Validator chosen per block during un-finalized transition.**
  `getByBlockHeaderWithTransitionReorgHandling` + `TransitionBackwardSyncContext` ensure a
  block near the TTD boundary is validated under whichever ruleset actually applies to it,
  even before a finalized block pins the merge point.

## Notable patterns (the reusable idea)

**The composable dual-consensus wrapper.** One generic combinator —
`TransitionUtils<T>(preT, postT, flag)` with `dispatch(fn) = fn(flag ? postT : preT)` — is
instantiated once per pluggable subsystem to produce a drop-in `T` that the rest of the
system treats as a single implementation:

| Subsystem interface | pre-merge impl | post-merge impl | composed wrapper |
|---|---|---|---|
| `ProtocolSchedule` | PoW/Clique schedule | PoS schedule | `TransitionProtocolSchedule` |
| `MiningCoordinator` | `PoWMiningCoordinator`/… | `MergeCoordinator` | `TransitionCoordinator` |
| `ConsensusContext` | Clique/PoW context | `PostMergeContext` | `TransitionContext` |
| `BackwardSyncContext` | (base) | (merge validator) | `TransitionBackwardSyncContext` |

The whole node is wired with the composed wrappers; nothing downstream knows there are two
engines. Adding/removing a consensus family is "supply another (pre, post, flag)" rather than
threading conditionals through call sites. **This is the exact shape B7.0-c's conditional
wrapper validates** for fukuii's multi-network goal: a family-neutral outer type that holds
N family implementations and routes by a single authoritative predicate, keeping
PoW-vs-PoS (or per-network) branching out of the call sites. The transferable rules:

1. Route by **one** authoritative predicate (here `isPostMerge`), not scattered `if`s.
2. Make the predicate a **latch** where correctness demands monotonicity.
3. Reuse **one** generic dispatch primitive across every pluggable seam.
4. Let the wrapper be a **pure router** — it throws on registry-mutation calls
   (`putBlockNumberMilestone`) it has no business owning.

## Authority note

For this slot besu is the **JVM structural reference** for the composable-merge shape
(`TransitionProtocolSchedule` / `TransitionCoordinator` / `TransitionContext` /
`TransitionBackwardSyncContext` + the `TransitionUtils` dispatch primitive) and for the
`MergeCoordinator` engine-driver on the JVM — the closest same-language mirror of the pattern
fukuii would build. **go-ethereum remains the canonical engine-driver / merge semantics
authority** (TTD, fork-choice, Engine-API behaviour byte-for-byte); besu is consulted for
*how to structure it in Scala/JVM*, not for consensus ground truth. None of this applies to
ETC/PoW, which has no CL, no Engine API, and no TTD merge — the pattern's value to fukuii is
purely the multi-network composition mechanism.

## Gotchas / anti-patterns / things they later changed

- **`isMining()` is hardcoded `true` and `createBlock(...)` throws** on `MergeCoordinator` —
  a PoS "miner" is a misnomer; it builds via `preparePayload` with a required prevRandao, not
  via the legacy miner API. Reading the driver as if it were a PoW miner will mislead.
- **The `isSyncing` / `--p2p-enabled=false` interaction is subtle** and was actively being
  fixed at the documented commit: without the "mark TTD reached at startup" short-circuit a
  p2p-disabled node would report syncing forever and refuse to serve the Engine API. Post-TTD
  besu relies *solely* on peer sync state (comment at `PostMergeContext:133`) — an earlier
  version always returned `false` post-merge and thus never noticed it was still downloading.
- **Two fork-choice entry points exist** (`updateForkChoice` vs
  `updateForkChoiceWithoutLegacySkip`) because the "ignore update to an old ancestor head"
  optimization had to be narrowed (to ancestor-of-*finalized*) and moved to the caller for
  the execution-apis #786 FCU flow. Using the wrong one reintroduces the legacy skip.
- **`computeReorgDepth` needs an explicit fast-path** (`MergeCoordinator:949`) even though the
  loop below looks like it subsumes it: without short-circuiting on extension/no-op FCUs, a
  benign update would still walk back to a common ancestor and could trip `MAX_REORG_DEPTH`.
  A comment flags this as a real bug that the redundant-looking check prevents.
- **Latest-valid-hash is `Hash.ZERO` when the chain roots in PoW** — not the PoW block's own
  hash. Easy to get wrong; it's Engine-API-mandated so a CL doesn't try to build on a
  pre-merge block.
- **The four listener interfaces live in `ethereum/eth`, not `consensus/merge`**, purely to
  break a module dependency cycle — don't expect all merge types under one package.
- **`TransitionBestPeerComparator` uses a `static` `AtomicReference<Difficulty>` for TTD** —
  shared class-level state; fine because a process has one TTD, but a surprising place to
  find global mutable state and a hazard if besu ever ran two chains in one JVM (the
  multi-network scenario fukuii cares about — a per-instance field would be the fix).
