# Observations — cl-engine
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/cl-engine.md + consensus-engines observation._

This is the Phase-2 cross-client comparison for the **cl-engine** subsystem (extended slot 17):
the **consensus-layer-integration / engine-driver** side of PoS support — how `newPayload` /
`forkchoiceUpdated` / `getPayload` mutate the execution chain, how the merge is composed into a
running node, and the two structural outliers (erigon's embedded Caplin CL, reth's in-memory
engine-tree). It is the **Engine-API-DRIVER + embedded-CL complement** to the
`consensus-engines.md` observation — that doc covers the merge/transition *model* at the
consensus-family-abstraction level (`beacon.New` decorator, `TransitionProtocolSchedule`,
`MergePlugin` co-activation); this doc covers what the driver actually *does to the chain* and
who can drive it. Where the two overlap (merge-transition model) this doc cites, not duplicates.
Every per-client claim is cited to that client's `cl-engine.md`.

**Authority model (per Phase-0, restated for this slot):** go-ethereum = canonical
engine-driver semantics (`eth/catalyst` `ConsensusAPI`: newPayload/fcU chain mutation, the
VALID/INVALID/SYNCING/ACCEPTED state machine, LatestValidHash, reorg-depth policy) **and** the
beacon-light-client (`blsync`) embedded-CL-lite pattern; besu + nethermind = the composable
transition references (JVM `TransitionUtils`/`MergeCoordinator`; `MergePlugin` decorator +
`IPoSSwitcher`); erigon/Caplin = THE embedded-CL / single-binary EL+CL authority (unique among
the six); reth = the Engine-API-native engine-tree + async-persistence reference; core-geth =
the negative datapoint (driver compiled-in but **inert** for ETC/PoW, and **no blsync**).

## Comparison table

| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | fukuii | Authoritative |
|---|---|---|---|---|---|---|---|---|
| **Engine-driver structure (newPayload/fcU → chain mutation)** | `ConsensusAPI` (`eth/catalyst/api.go:90`): thin, stateless, heavily-locked translator; `newPayload → InsertBlockWithoutSetHead`, `fcU → SetCanonical`+`SetFinalized/SetSafe` | inherits geth's `ConsensusAPI` unchanged; **never invoked on the ETC path** | `MergeCoordinator` (`MergeCoordinator.java:77`, `implements MergeMiningCoordinator`): `newPayload → rememberBlock/validateBlock`, `fcU → updateForkChoice → setNewHead` (forwardToBlock vs rewindToBlock) | `ExecutionEngine` Go iface (`interface.go:36`, ~15 methods "mimics engine API"); `NewPayload/ForkChoiceUpdate/GetAssembledBlock` bound to a direct in-proc EL or an Engine-API server | `NewPayloadHandler`/`ForkchoiceUpdatedHandler` (`Handlers/`): decode→validate→`SuggestBlockAsync(ForceDontSetAsMain)`+enqueue; fcU→`TryUpdateMainChain(forceUpdateHeadBlock)` | `EngineApiTreeHandler` (`tree/mod.rs:266`) on its own OS thread; `on_new_payload`/`on_forkchoice_updated` mutate an in-memory `TreeState`, not disk | fat single `IO` service `EngineApiService` (`EngineApiService.scala:151`/`:423`): `newPayload → storeBlock/storeBlockByHashOnly` (no head-set), `forkchoiceUpdated → ForkChoiceManager.applyForkChoiceState` (+ inline ~300-line proposer); thin `ForkChoiceManager` does the canonical mutation | **go-ethereum** (canonical driver semantics); besu/nethermind = JVM/.NET structural mirrors |
| **Head-selection discipline (only-fcU-moves-head)** | strict: `newPayload` explicitly does NOT set head (`InsertBlockWithoutSetHead`); only fcU calls `SetCanonical` (comment `:895`: payload exec must not trigger reorgs) | inherited but dormant | same contract: `newPayload` inserts+processes, canonical head only moves in `applyForkChoice`/`setNewHead` | same: EL is a passive validator/executor; Caplin's LMD-GHOST decides the head, EL only confirms the payload executes | same: `NewPayload` uses `ForceDontSetAsMain`; head moves only in `ForkchoiceUpdatedHandler` | same: `on_new_payload` inserts into tree; `on_forkchoice_updated → make_canonical` | same: `newPayload` stores without advancing `bestBlock` (comment `EngineApiService.scala:344`); the only best-block write is `ForkChoiceManager.saveBestKnownBlocks` (`ForkChoiceManager.scala:85`), reached only from the FCU path | **go-ethereum** — the load-bearing invariant all six honor; getting it wrong desyncs the pair |
| **Merge-transition model (wrapper/decorator/schedule)** | `beacon.New(ethone)` content-derived decorator, now **mandatory** (PoS-only boot) — see consensus-engines.md | same `beacon.New` wrap but **inert** (TTD unset) | `TransitionProtocolSchedule`/`TransitionCoordinator`/`TransitionContext` via one `TransitionUtils<T>` dispatch primitive, TTD-gated one-way latch — see consensus-engines.md | `merge.New(eng)` **conditional** decorator (only when `TTD != nil`) — the shape fukuii wants | `MergePlugin` **decorates DI regs** (`AddDecorator<IBase,MergeComponent>`), one `IPoSSwitcher` arbitrates per-header; `AuRaMergePlugin` proves it composes over any base | Engine-API-native: no transition wrapper — merge is a `ForkCondition::TTD` query, engine is the primary architecture | **content-derived**, not composable: `TransitionBlockHeaderValidator.scala:26` dispatches per-header on `difficulty==0` (PoS validator) vs PoW validator; `EngineApiEngine` = `sealer=None`, no `TransitionProtocolSchedule`/`IPoSSwitcher` analogue | **besu / nethermind** for the composable-transition *shape* (B7.0-c); go-ethereum for the canonical content-derived heuristic |
| **Embedded-CL (erigon Caplin)** | **`blsync`** beacon-light-client (follows sync-committee-signed heads, no beacon state, no validators) drives the same `ConsensusAPI` over `rpc.DialInProc` | **absent** — no `beacon/blsync/`, a 2025-01 fork that predates it | none (EL-only, needs external CL) | **Caplin** (`cl/`): a full beacon client (state-transition + LMD-GHOST + own libp2p sentinel) in-process; `--internalcl` → single-binary EL+CL, no separate beacon node | none (EL-only) | none (EL-only) | **absent** — external-CL-only; no Caplin, no `blsync`; embedded CL / validator software is a separately-gated future track (Phase-0 CL SR) | **erigon/Caplin** = full embedded CL; **go-ethereum/blsync** = embedded *light* CL — the only two with an in-binary driver |
| **Engine-tree / in-memory-unfinalized (reth)** | classic: mutates `core.BlockChain` on disk (`SetCanonical`) | inherited | mutates blockchain + persists world state per fcU | direct `chainRW.InsertBlock/ValidateChain` into the execution module | inserts+enqueues into `IBlockProcessingQueue` for EVM exec; disk-backed | **in-memory `TreeState` of executed-but-unfinalized blocks** + async `PersistenceService` on a 2nd OS thread; newPayload returns VALID without a disk write (persist-when-N-deep watermark, threshold 2) | disk-backed (`storeBlock`/`storeBlockByHashOnly` + `.commit()`); no in-memory tree, but an **optimistic-import** path (unknown parent → `ACCEPTED` by-hash-only + `acceptedChildrenByParent` for retroactive `markInvalidRecursive`, `EngineApiService.scala:112`) | **reth** — the sole engine-tree + async-persistence reference |
| **Backward-sync (CL head with missing ancestors)** | unknown head → `STATUS_SYNCING` + `Downloader().BeaconSync`; `delayPayloadImport` stashes in `remoteBlocks` | inherited/inert | `BackwardSyncContext.syncBackwardsUntil(headHash)` backward-fills from CL head; `TransitionBackwardSyncContext` picks the pre/post validator per block across TTD | Caplin's own range-sync methods (`InsertBlocks`/`GetBodiesByRange`) on the `ExecutionEngine` superset iface | `BeaconPivot`/`BeaconSync`/`BeaconHeadersSyncFeed`: reverse header sync from the CL-supplied pivot when head is unknown | `EngineHandler` services on-demand block download requests the tree emits for gap-filling | unknown/optimistic head → `SYNCING` + `ForkChoiceManager.applyForkChoiceState` still fires `BeaconHead` (`ForkChoiceManager.scala:67`) to trigger `SyncController` SNAP-pivot; no besu-style validator-per-block `BackwardSyncContext` | **besu / nethermind** for the merge-aware backward-fill (validator-per-block across the boundary) |
| **ETC-inertness** | N/A (geth is ETH-family baseline) | **the datapoint**: `catalyst.Register` wired unconditionally but ETC configs set no TTD and comment out `EIP4895FBlock` ⇒ merge never fires; Engine API is registered-yet-never-exercised dead weight | N/A | N/A | explicit: ETC/Mordor never instantiates MergePlugin/IPoSSwitcher/Engine-API driver | explicit: ETC has no CL, no `forkchoiceUpdated`; advances via P2P block import | aligns with core-geth "presence ≠ activation": Engine API config-gated (`network.engine-api.enabled=false` default, `NodeBuilder.scala:843`); ETC `terminalTotalDifficulty=None` ⇒ `ForkChoiceManager` listener never registered, PoS driver never touches `consensus/pow/` | **core-geth** — "presence ≠ activation"; the whole slot is ETH-family-only |

## Approach catalog (use-case-aware)

Verdicts: **DEFAULT** = fukuii's baseline best practice (ETH-family / `beacon` path only) ·
**OPTIONAL(role)** = offer for a named use-case (single-binary/enterprise · validator-perf ·
light) · **OBSOLETE** = understood-but-discarded. **ETC/PoW (`forge`) uses NONE of these** — no
merge, no Engine API, no CL — so every row below is scoped to the PoS family.

| Approach | Clients using it | Good for (use-case / node-role) | Verdict | Why |
|---|---|---|---|---|
| **Thin engine-driver, only-fcU-moves-head** | go-ethereum (`ConsensusAPI`), core-geth (inert) | every PoS node role — the base contract | **DEFAULT** | The canonical driver: `newPayload` = validate + insert-without-head, `fcU` = set-head + safe/finalized + optional build, and *nothing else* touches the head. A small verb-pair over a serializable payload type; every upstream consumer (remote CL, embedded light-CL, dev sealer) collapses onto one validated path. go-ethereum is byte-authority for the VALID/INVALID/SYNCING/ACCEPTED state machine + LatestValidHash propagation. |
| **Composable transition wrapper / decorator** | besu (`TransitionUtils`/`TransitionProtocolSchedule`/`MergeCoordinator`), nethermind (`MergePlugin` merge-as-decorator + `IPoSSwitcher`) | single-binary multi-network where merge must layer over Ethash *or* Clique *or* AuRa without touching the base engine | **DEFAULT (composition principle — B7.0-c)** | Route by **one** authoritative predicate (`isPostMerge`/`IPoSSwitcher`), make it a one-way latch, reuse one dispatch primitive across every pluggable seam, keep the wrapper a pure router. besu = JVM `TransitionUtils<T>(pre,post,flag)` mirror; nethermind = `AddDecorator` co-activation proven over 3+ base engines (`AuRaMergePlugin` in ~60 lines). **Validates fukuii's B7.0-c conditional wrapper.** |
| **Embedded CL / single-binary EL+CL** | erigon (Caplin, `--internalcl`) | enterprise single-binary multi-network (JPMC/E*TRADE/Fireblocks); light/end-user; no separate beacon node / JWT / Engine-API port to deploy | **OPTIONAL(single-binary/enterprise)** | The one client of six that ships its own beacon chain in-process. Reusable *design* (cheap): model EL↔CL as one narrow `ExecutionEngine` interface (~15 methods, "mimics engine API"), bind it to a **direct in-proc** EL by default (skip JSON-RPC+JWT when co-located), keep the Engine-API binding only for foreign-EL interop. Reusable *implementation* (expensive): a full home-grown beacon client is thousands of files with a permanent spec-conformance burden (Phase0→Gloas). = **CL-RESEARCH-EMBED-01 reference**. |
| **Beacon-light-client (blsync)** | go-ethereum (`beacon/blsync/`) | light / end-user "follow the head" without a full CL or validator duties | **OPTIONAL(light)** | Trust-minimized (sync-committee sigs + weak-subjectivity checkpoint, no beacon-state download, no attesting/proposing). Drives the *same* `ConsensusAPI` over an in-proc pipe — the EL can't tell it from an external Lighthouse. A lighter embedded-CL point than Caplin: follow-the-head, not stake. **absent in core-geth** — not reproducible on the ETC fork. |
| **In-memory engine-tree + async persistence** | reth (`EngineApiTreeHandler` + `PersistenceService`) | validator perf — fast newPayload→fcU turnaround; cheap reorgs | **OPTIONAL(validator perf)** | Answer `newPayload`/`fcU` entirely from an in-memory tree of executed-but-unfinalized blocks, return VALID without waiting on disk, lazily flush the settled tail on a 2nd OS thread with a "persist when N deep" watermark. Keeps RocksDB off the CL's latency critical path; competing branches already in memory make reorgs cheap. The transferable idea (in-memory-tree + async-persistence) applies to **any** block-import path, PoW included — not the Engine-API driver itself. |
| **ETC = inert, uses none** | core-geth (driver compiled-in, never activated) | nothing — ETC has no merge | **OBSOLETE (for the ETC path)** | ETC deliberately kept PoW/Ethash + ECIP-1017 emission; the Merge (EIP-3675/TTD) is ETH-only. core-geth neutralizes the whole slot by *config* (no TTD, `EIP4895FBlock` commented out), not code deletion. For fukuii this is a **warning**: the `forge`/PoW path must treat Engine-API/beacon concepts as strictly ETH-family-only and never let them bleed into `consensus/pow/`. |

## Best-practice synthesis

**ETH-FAMILY ONLY.** Everything here scopes to fukuii's PoS family (currently ETH/Sepolia, owned
by `beacon`). ETC/PoW (`forge`) has no merge, no Engine API, no CL, no blsync analogue — the
entire slot does not apply to it, and core-geth is the reference for keeping it inert.

**The DEFAULT + OPTIONAL menu that falls out of the six clients:**

1. **Engine-driver — DEFAULT: a thin driver where only `forkchoiceUpdated` moves the head.**
   go-ethereum's `ConsensusAPI` is the canonical shape and byte-authority: `newPayload` =
   validate + `InsertBlockWithoutSetHead` (explicitly NOT head-set), `fcU` = `SetCanonical` +
   safe/finalized + optional payload build, and a strict rule that no other code touches the
   head. Keep the VALID/INVALID/SYNCING/ACCEPTED state machine and LatestValidHash propagation
   byte-for-byte per geth. All six clients honor only-fcU-moves-head; it is the load-bearing
   invariant fukuii's `beacon` path must preserve.

2. **Merge — DEFAULT: a composable *conditional* transition wrapper (B7.0-c).** besu's
   `TransitionUtils`/`MergeCoordinator` (JVM structural mirror) and nethermind's merge-as-decorator
   + `IPoSSwitcher` (co-activates over any base engine) are the two references. Route by one
   authoritative TTD-derived latch, reuse one dispatch primitive across every seam, and make the
   wrapper **conditional** (skipped for permanently-PoW ETC — erigon's `merge.New` shape, not
   geth's now-mandatory wrap). This *validates* fukuii's B7.0-c. See consensus-engines.md for the
   family-abstraction-level treatment of the same transition model.

3. **OPTIONAL(single-binary/enterprise): embedded CL (erigon Caplin).** The EL↔CL
   narrow-interface + direct-in-proc-transport pattern is fukuii's **single-binary-EL+CL /
   CL-RESEARCH-EMBED-01 reference**. Adopt the *design* first — one ~15-method `ExecutionEngine`
   contract, a direct in-process binding as default (skip Engine-API/JWT when co-located), the
   Engine-API binding kept only for foreign-EL interop. A full home-grown beacon client is a
   large, permanently spec-tracked surface; the interface + direct binding are cheap, the CL
   itself is not.

4. **OPTIONAL(validator perf): in-memory engine-tree + async persistence (reth).** Answer the CL
   from memory, flush lazily on a dedicated thread with a persist-when-N-deep watermark. Off the
   Engine-API latency critical path; cheap reorgs. Transferable to any import path.

5. **OPTIONAL(light): beacon-light-client (blsync).** A trust-minimized follow-the-head embedded
   driver over the same `ConsensusAPI` seam — the light-node point on the same spectrum as Caplin.

6. **Family-neutrality guard.** As in consensus-engines.md: keep Engine-API/merge concepts out of
   neutral and PoW code. core-geth's inert-catalyst is the warning — the Engine API existing in
   the binary must never imply the PoW path uses it.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)

These are **seeds**, not verdicts to implement in this doc.

- **fukuii's `beacon` agent owns the PoS engine-driver.** The canonical target is go-ethereum's
  only-fcU-moves-head `ConsensusAPI` semantics for the ETH/Sepolia path — keep `newPayload`
  (validate + insert, no head-set) and `forkchoiceUpdated` (set-head + safe/finalized + optional
  build) as two strictly separate mutations, with the VALID/INVALID/SYNCING/ACCEPTED state machine
  and LatestValidHash per geth.

- **B7.0-c (composable transition wrapper) is the merge model.** besu's `TransitionUtils`/
  `MergeCoordinator` (JVM mirror) + nethermind's merge-as-decorator/`IPoSSwitcher` (co-activation
  over any base) validate the conditional-wrapper approach — relocate fukuii's existing
  content-derived routing (`TransitionBlockHeaderValidator`) into a reusable conditional
  `EngineSchedule` that stays permanently unwrapped for ETC. Cross-referenced from
  consensus-engines.md item 5.

- **The embedded-CL (Caplin) pattern is the CL-RESEARCH-EMBED-01 reference**, gated on the EL
  reaching Phase 4. The transferable seam is the narrow `ExecutionEngine` interface + direct
  in-proc binding (single-binary EL+CL, skip Engine-API/JWT when co-located); it maps directly
  onto fukuii's enterprise single-binary multi-network thesis. Not a near-term build — a full
  beacon client is a large, permanently spec-tracked surface.

- **reth's in-memory engine-tree + async persistence** is an OPTIONAL(validator-perf) seed,
  applicable to any import path (PoW included), independent of the Engine-API driver.

- **ETC/`forge` uses none of this.** No merge, no Engine API, no CL, no blsync — core-geth is the
  reference for keeping the driver inert (config-neutralized, never code-deleted). Do not let
  engine-driver/merge/embedded-CL concepts bleed into `consensus/pow/`, Ethash, or ECIP-1017
  emission.
