# go-ethereum — cl-engine
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

Scope: the **consensus-layer-integration / engine-driver** side of geth's PoS
support — how an external (or embedded) consensus layer's fork-choice is applied
to geth's execution chain. This is the DRIVER side, distinct from the `rpc-api`
slot which documents the Engine API *transport* (JSON-RPC method registration,
JWT auth, codecs). Here we document the *semantics*: how `forkchoiceUpdated`,
`newPayload`, and `getPayload` mutate the chain, plus geth's embedded
beacon-light-client (`blsync`) that can drive those same calls in-process from
CL-signed headers without a full consensus client.

## Architecture summary

Geth is a pure **execution layer (EL)**. Post-merge it does not choose the head
itself — a **consensus layer (CL)** does, and pushes decisions across the Engine
API as two verbs:

- **`engine_newPayload`** — "here is a block, execute and validate it" (does NOT
  make it canonical). Driver: `ConsensusAPI.newPayload`
  (`eth/catalyst/api.go:819`) → `BlockChain.InsertBlockWithoutSetHead`
  (`core/blockchain.go:2731`). Returns `VALID` / `INVALID` / `SYNCING` /
  `ACCEPTED`.
- **`engine_forkchoiceUpdated` (FCU)** — "make this head canonical; here are the
  safe/finalized checkpoints; optionally start building a payload". Driver:
  `ConsensusAPI.forkchoiceUpdated` (`eth/catalyst/api.go:240`) →
  `BlockChain.SetCanonical` (`core/blockchain.go:2746`) +
  `SetFinalized`/`SetSafe`. Optionally kicks off block production via
  `Miner().BuildPayload`, retrievable later with `engine_getPayload`.

The `ConsensusAPI` object (`eth/catalyst/api.go:90`) is the whole engine driver.
It holds no chain state of its own — it is a thin, heavily-locked translator
between Engine API calls and `core.BlockChain` mutations, plus ephemeral caches
for in-flight remote payloads and invalid-block tracking.

**The embedded-CL-lite pattern (`blsync`)**: geth ships a *beacon light client*
in `beacon/blsync/` that can follow the beacon chain from sync-committee-signed
light-client headers (no full CL, no validator duties, no state download) and
then drive the *same* engine driver. In blsync mode (`cmd/geth/config.go:311`)
geth registers `catalyst.NewConsensusAPI(eth)` on an **in-process** RPC server
and hands blsync `rpc.DialInProc(srv)` — so blsync speaks ordinary
`engine_newPayload`/`engine_forkchoiceUpdated` over an in-memory pipe. The engine
driver cannot tell an embedded blsync from an external Lighthouse/Prysm; the
Engine API is the single seam either way.

## Key types / interfaces / files

### Engine driver (`eth/catalyst/`)
- `eth/catalyst/api.go:90` — `ConsensusAPI` struct: the engine driver. Fields:
  `remoteBlocks *headerQueue` (payloads whose parent/state we don't have yet),
  `localBlocks *payloadQueue` (in-progress local builds keyed by payload ID),
  `invalidBlocksHits`/`invalidTipsets` (ephemeral bad-block cache, **never
  persisted** — a comment at `:102` explains a bug could otherwise permanently
  block a valid chain), and `forkchoiceLock`/`newPayloadLock` serializing the two
  verbs.
- `eth/catalyst/api.go:240` — `forkchoiceUpdated(...)`: the core fork-choice
  application. Handles unknown-head → `STATUS_SYNCING` + `Downloader().BeaconSync`
  (`:292`); known-but-non-canonical head → `SetCanonical` (`:320`); reorg-depth
  guard `maxReorgDepth = 32` (`:87`, `:333`); finalized/safe canonical-tree
  validation (`:350`, `:364`); optional payload build (`:380`).
- `eth/catalyst/api.go:819` — `newPayload(...)`: execute+validate a CL-supplied
  block *without* making it head. `ExecutableDataToBlock` → parent/timestamp/
  snap-sync checks → `InsertBlockWithoutSetHead` (`:927`). On failure records the
  block in the invalid caches (`:932`).
- `eth/catalyst/api.go:961` — `delayPayloadImport`: stash a block (missing
  parent, or snap-sync in progress) in `remoteBlocks` and try `BeaconExtend`;
  returns `SYNCING`/`ACCEPTED`.
- `eth/catalyst/api.go:999` / `:1009` — `setInvalidAncestor` / `checkInvalidAncestor`:
  propagate INVALID down descendants of a known-bad block, returning the
  `LatestValidHash`.
- `eth/catalyst/api.go:518` — `getPayload`: pull a built block out of
  `localBlocks` for the CL to propose.
- `eth/catalyst/simulated_beacon.go:1` — `SimulatedBeacon`: dev-mode embedded
  beacon that also drives the engine API (auto-seals on a timer); a second
  example of an in-process driver of the same `ConsensusAPI`.

### Fork-choice application (`core/`)
- `core/blockchain.go:2746` — `SetCanonical(head)`: recover missing head state
  via `recoverAncestors` (`:2754`), run `reorg` if `head.ParentHash != CurrentBlock`
  (`:2761`), `writeHeadBlock`, emit chain/head feeds. This is where the CL's
  chosen head becomes geth's canonical head.
- `core/blockchain.go:2731` — `InsertBlockWithoutSetHead`: validate+persist a
  block, leave the head pointer alone (newPayload's insertion primitive).
- `core/blockchain.go:807` / `:819` — `SetFinalized` / `SetSafe`: record the CL's
  finalized/safe checkpoints (enable freezer pruning / RPC `finalized`/`safe`
  block tags).

### Embedded beacon light client (`beacon/`)
- `beacon/blsync/client.go:34` — `Client`: wires a `request.Scheduler` with sync
  modules (`checkpointInit`, `forwardSync`, `headSync`, `beaconBlockSync`) plus a
  `HeadTracker`/`CommitteeChain` (backed by an in-memory `memorydb`, `:49`). It
  follows the chain from a **weak-subjectivity checkpoint** (`config.Checkpoint`,
  `:63`) forward via sync-committee signatures — never downloading beacon state.
- `beacon/blsync/block_sync.go:32` — `beaconBlockSync`: fetches the beacon block
  for each validated head, extracts the embedded execution payload
  (`headBlock.ExecutionPayload()`, `:153`), and emits a `types.ChainHeadEvent`
  (`:158`) carrying the EL `*Block`, exec requests, and finalized hash.
- `beacon/blsync/engineclient.go:35` — `engineClient`: consumes those
  `ChainHeadEvent`s in `updateLoop` (`:61`) and issues, per event, first
  `callNewPayload` (`:100`) then `callForkchoiceUpdated` (`:143`), versioning the
  method by fork (V1..V4). If `rpc == nil` it's a **dry run** — logs the head and
  drives nothing (`:71`), useful for following head without an EL.
- `beacon/types/light_sync.go:235` — `ChainHeadEvent{BeaconHead, Block,
  ExecRequests, Finalized}`: the authenticated-head hand-off type between the
  light client and the engine driver.
- `beacon/engine/types.go:85` — `ExecutableData`; `:260` `ExecutableDataToBlock`;
  `:355` `BlockToExecutableData`: the payload⇄block marshalling both the external
  Engine API and blsync's in-proc calls go through.
- `cmd/geth/config.go:311-317` — the blsync wiring: in-proc `rpc.NewServer()`,
  `RegisterName("engine", catalyst.NewConsensusAPI(eth))`, `DialInProc`.

## Design decisions & rationale

- **EL never picks the head.** The entire driver is reactive: `newPayload`
  validates but explicitly does *not* set head (`InsertBlockWithoutSetHead`),
  and only `forkchoiceUpdated` moves the canonical pointer. Rationale (comment
  at `:895`): payload execution must not trigger reorgs/sync, because siblings
  and semi-distant uncles are fed concurrently; head selection is the CL's job,
  applied atomically via one FCU.
- **Two locks, coarse-grained.** `forkchoiceLock` and `newPayloadLock` serialize
  each verb. The `newPayload` comment (`:820`) documents *why*: without the lock,
  a CL timeout + retry can pile up N concurrent inserts of the same payload
  behind a DB compaction, all of which pass the "do we already have it?" check.
- **Bad blocks are in-memory only.** `invalidBlocksHits`/`invalidTipsets` are
  ephemeral and forgotten after a hit threshold (`:106`) — a deliberate refusal
  to persist invalidity, because a geth bug could otherwise permanently reject a
  valid chain that a later release would accept.
- **Reorg-depth cap (32).** `maxReorgDepth` (`:87`) rejects FCU reorgs deeper
  than 32 blocks (below finality) with `TooDeepReorg`, unless syncing — a DoS /
  accidental-deep-reorg guard.
- **blsync trusts sync-committee signatures, not full validation.** The light
  client verifies light-client headers against the sync committee and a
  weak-subjectivity checkpoint (`beacon/light`), then lets the EL re-execute the
  payload. It downloads *no* beacon state and runs *no* validators — a minimal
  way to follow the canonical head. The `!config.NoFilter` head-tracker filter
  (`:50`) and threshold guard against a single lying server.
- **Same driver for external CL, blsync, and dev mode.** `catalyst.Register`
  (external), `blsync` (in-proc dial), and `SimulatedBeacon` (dev auto-seal) all
  target one `ConsensusAPI`. The Engine API is the only integration seam, so the
  three consumers share validation semantics for free.

## Notable patterns (the reusable idea)

**The Engine API as the single, transport-agnostic driver seam.** Geth's PoS
integration is one narrow interface (`newPayload` = validate, `forkchoiceUpdated`
= set-head + checkpoints + optional build) plus a strict rule that only FCU
touches the head. Everything upstream of that seam is interchangeable: a remote
CL over authenticated JSON-RPC, an embedded light client (`blsync`) over an
in-process pipe, or a dev-mode auto-sealer. Because the seam is defined purely by
`ExecutableData` and fork-choice hashes, geth embeds a *whole* alternative
"consensus" (blsync) just by dialing its own engine API in-process
(`rpc.DialInProc`) — no new code path in the EL. The reusable idea is: **define
head-selection as a small verb pair over a serializable payload type, forbid any
other code from moving the head, and every consumer — remote, embedded,
simulated — collapses onto the same validated path.**

Second reusable idea: **the invalid-block cache is ephemeral by contract.** Bad
blocks propagate INVALID to descendants for the CL's `LatestValidHash`, but are
never persisted and are forgotten after a threshold — invalidity is treated as a
possibly-buggy local opinion, not durable truth.

## Authority note

For fukuii's PoS family (ETH/Sepolia), **go-ethereum is the canonical reference**
for both the engine-driver semantics (what `newPayload`/`forkchoiceUpdated`
must do to the chain, the VALID/INVALID/SYNCING/ACCEPTED state machine,
`LatestValidHash` propagation, reorg-depth policy) and for the
beacon-light-sync (`blsync`) embedded-CL pattern. `beacon/blsync` +
`eth/catalyst` are the definitive expression of "EL is reactive; CL drives head".

**ETC/PoW (fukuii's `forge` family) does NOT use any of this.** ETC has no merge:
it selects its own head via PoW total-difficulty + block-number fork dispatch
(with MESS/ECIP-1100 as subjective fork-choice weighting, owned by `banksy`).
There is no Engine API, no `newPayload`/`forkchoiceUpdated`, no external CL, and
no blsync analogue on the PoW path. This entire slot is **ETH-family-only** for
fukuii. Do not let engine-driver or blsync concepts bleed into PoW consensus code
(`consensus/pow/`, Ethash, ECIP-1017 emission).

## Gotchas / anti-patterns / things they later changed

- **`newPayload` ≠ set head.** A common integration mistake is treating a VALID
  `newPayload` as "this is now the chain". It is not — the block is inserted
  without head-set; only a subsequent FCU makes it canonical. Fukuii's beacon
  path must keep these two mutations strictly separate.
- **`ACCEPTED` vs `SYNCING` vs `VALID` are load-bearing.** `newPayload` returns
  `ACCEPTED` when parent state is missing but the block looks structurally OK
  (`:923`), `SYNCING` when it stashes for later import (`:977`), `VALID` only
  after real insertion. CLs branch on these; getting the state machine subtly
  wrong desyncs the pair.
- **Do not persist bad-block state.** The ephemeral-only invalid cache is a
  deliberate scar from a past geth incident (import-vs-pending race, comment at
  `:109`) where persisted invalidity would have blocked a legitimate chain.
- **blsync is trust-minimized, not trustless-equivalent to a full CL.** It
  follows sync-committee-signed heads from a checkpoint; it does not attest,
  propose, or fully validate beacon state. It is a "follow the head" client, not
  a validator — appropriate for light/end-user use, not staking.
- **In-proc RPC for the embedded driver.** blsync drives the engine API via
  `rpc.DialInProc` over a real `rpc.Server`, not a direct method call
  (`cmd/geth/config.go:313-316`). This keeps blsync and external CLs on an
  identical code path but means the in-proc call still pays codec marshalling —
  an intentional trade (uniformity over a micro-optimization).
- **`forkchoiceUpdated` silently no-ops on the "head == current head" corner
  case** (`:323`) so a few missing slots don't disturb an in-progress payload
  build — easy to overlook when reimplementing FCU.
- **Fork-versioned method dispatch on the driver side too.** blsync's
  `engineClient` selects `newPayloadV1..V4`/`forkchoiceUpdatedV1..V3` by beacon
  fork name (`engineclient.go:107`, `:151`); an EL implementing the *server* side
  must accept whichever version the CL sends per fork.
