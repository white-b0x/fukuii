# go-ethereum — sync

_Commit/branch documented: `59e89e81e57814a96c429c5cdcaa6ca2e0d6b143` (tag `v1.17.4-32-g59e89e81e`,
branch `upstream`). Vendored at `/media/dev/2tb/dev/reference-clients-evm/go-ethereum` (identical copy
at `.claude/repo-references/clients/go-ethereum`, same SHA). Documented 2026-07-13._

## Architecture summary

go-ethereum's sync is a two-layer system split across `eth/downloader/` (the orchestrator) and
`eth/protocols/snap/` (the state syncer + the wire protocol it serves). Post-merge, the client no
longer discovers or extends the head itself: the **Consensus Layer drives the head** via the Engine
API (`engine_forkchoiceUpdated` → `eth/catalyst/api.go:292` calls `Downloader.BeaconSync`). The
downloader's job is reduced to two things behind that head: (1) a **skeleton** header syncer that
grows the header chain *backwards* from the CL-announced head until it links to what the node already
has (`eth/downloader/skeleton.go`), and (2) a **backfiller** that, once linked, fills bodies/receipts
forward and — in snap mode — downloads the state of a **pivot** block (`beaconsync.go`,
`downloader.go`). Only two sync modes survive: `FullSync` and `SnapSync` (`eth/ethconfig/syncmode.go:26-27`
— `LightSync`/LES is gone). State download itself is **snap sync**, which geth *originated*: fetch flat
account/storage ranges with Merkle proofs against a recent pivot state root, then **heal** the small
inconsistencies that the moving pivot introduces. This HEAD ships **two** snap syncers side by side —
the classic **snap/1** (`sync.go`, range-download + trie-node healing) and a new **snap/2** (`syncv2.go`,
flat-state download + BAL-diff catch-up + local trie generation), selected at construction
(`downloader.go:255-259`).

## Key types / interfaces / files

### Orchestrator (`eth/downloader/`)
- `eth/downloader/downloader.go:99-166` — **`Downloader`** struct: holds the `queue` (download
  scheduler), `peers`, `skeleton`, `snapSyncer snap.Syncer` (`:145`), and the live `pivotHeader`
  (`:142-143`, guarded by `pivotLock`).
- `eth/downloader/downloader.go:239-259` — **`New(stateDb, mode, chain, dropPeer, success, snapV2)`**:
  wires the syncer — `snap.NewV2Syncer` when `snapV2` is set, else `snap.NewV1Syncer` (`:255-259`).
- `eth/downloader/syncmode.go:31-108` — **`syncModer`**: *adaptive* mode selection. It can silently
  switch full→snap when the head state is missing or the chain lagged behind the last pivot
  (`get()`, `:78-108`, keyed off `rawdb.ReadLastPivotNumber`), and snap→full once snap completes.
  `disableSnap()` (`:111-115`) flips to full after one successful snap cycle.
- `eth/downloader/downloader.go:361-435` — **`synchronise(beaconPing)`**: the single-flight entry
  (CAS on `synchronising`, `:377`); resolves the cycle mode, calls `blockchain.SnapSyncStart()` to
  make the downloader the sole chain mutator (`:398-401`), then `syncToHead()`.
- `eth/downloader/downloader.go:455-639` — **`syncToHead()`**: the core of a cycle. Picks the pivot
  (below), finds the beacon ancestor, sets the ancient-store limit, then `spawnSync` of the fetcher
  set: `fetchHeaders`, `fetchBodies`, `fetchReceipts`, `processHeaders`, plus either
  `processSnapSyncContent` or `processFullSyncContent` (`:623-637`).
- `eth/downloader/statesync.go:26-123` — **`stateSync`** scheduler: wraps one `snapSyncer.Sync(pivot,
  cancel)` run (`run()`, `:105-109`); replaced wholesale each time the pivot moves.

### Skeleton / beacon header sync (`skeleton.go`, `beaconsync.go`)
- `eth/downloader/skeleton.go:180-191` — **`skeleton`** doc: the post-merge header chain, grown
  *backwards* from the CL-dictated head to genesis/an existing header, kept as a separate entity from
  the sequential block chain until it links up.
- `eth/downloader/skeleton.go:98-120` — **`subchain`** + **`skeletonProgress`**: disjoint,
  DB-backed header segments that merge as the reverse download closes gaps; persisted so a restart
  can resume without re-downloading.
- `eth/downloader/skeleton.go:35-49` — `scratchHeaders = 131072` (≈64 MB scratch space for
  concurrent header download), `requestHeaders = 512` per packet.
- `eth/downloader/beaconsync.go:136-159` — **`BeaconSync(head, final)`** / **`BeaconExtend(head)`**:
  the two CL entry points. `BeaconSync` forces a new target; `BeaconExtend` optimistically extends and
  drops the new head on mismatch rather than reorging (`:140-148`). Both just call `skeleton.Sync`.
- `eth/downloader/beaconsync.go:35-121` — **`beaconBackfiller`**: `suspend()`/`resume()` driven purely
  by skeleton head/tail events; `resume()` spawns `synchronise` on its own goroutine (`:100-119`).
- `eth/downloader/beaconsync.go:166-253` — **`findBeaconAncestor()`**: top-N link check, then a
  **binary search** over skeleton headers for the common ancestor (`:224-250`), requiring the
  *canonical* mapping (not mere presence) so side-chains/orphans get re-synced.

### Pivot selection & movement
- `eth/downloader/downloader.go:57` — `fsMinFullBlocks = 64`: the pivot sits at **HEAD − 64**; the
  last 64 blocks are always imported as full blocks even in snap sync.
- `eth/downloader/downloader.go:479-521` — pivot pick: read `skeleton.Header(HEAD-64)`, fall back to
  the local chain, resume a **frozen pivot** from a prior cycle if still canonical (`:512-521`).
- `eth/downloader/beaconsync.go:306-341` — **pivot staleness / movement**: inside `fetchHeaders`, if
  the head runs `> pivot + 2*64 − 8` ahead, the pivot is moved forward to HEAD−64 and
  `rawdb.WriteLastPivotNumber` records it (so a rollback re-enables snap). Skipped when the syncer
  froze its pivot (`snapSyncer.FrozenPivot() != nil`, snap/2 trie-gen phase).
- `eth/downloader/downloader.go:916-1062` — **`processSnapSyncContent()`**: consumes downloaded
  results, `splitAroundPivot` (`:1041-1062`) partitions them into before/at/after the pivot, restarts
  the `stateSync` whenever the pivot root changes (`:990-1016`), and on pivot completion calls
  `commitPivotBlock` (`:1097-1112`) → `InsertReceiptChain` + `blockchain.SnapSyncComplete(...)` and
  sets `committed` (from here on it's a normal full sync).

### snap wire protocol + peer-serving side (`eth/protocols/snap/`)
- `eth/protocols/snap/protocol.go:29-61` — versions `SNAP1=1`, `SNAP2=2`; message codes
  `GetAccountRange/AccountRange` (0x00/0x01), `GetStorageRanges/StorageRanges` (0x02/0x03),
  `GetByteCodes/ByteCodes` (0x04/0x05), `GetTrieNodes/TrieNodes` (0x06/0x07), and snap/2's
  `GetAccessLists/AccessLists` (0x08/0x09). `ProtocolVersions = {SNAP2, SNAP1}` but snap/2 is
  **gated behind a feature flag** on the wire (`handler.go:82-91`, `MakeProtocols(backend, snapV2)`).
- `eth/protocols/snap/protocol.go:76-235` — the request/response packet structs (`GetAccountRangePacket`
  with `Root/Origin/Limit/Bytes`, slim-format `AccountData`, `TrieNodePathSet` for compact account-then-
  storage path addressing, `:199-209`).
- `eth/protocols/snap/handlers.go` — **the peer-serving side** (directly relevant to fukuii's SNAP
  serving). Each `handleGetX` decodes and delegates to an exported `ServiceGetXQuery(chain, req)`:
  - `ServiceGetAccountRangeQuery` (`:56-119`) — iterates the account snapshot/trie from `Origin`,
    caps at `req.Bytes`/`Limit`, and appends **Merkle proofs for the first and last account**
    (`tr.Prove`, `:107-117`).
  - `ServiceGetStorageRangesQuery` (`:172-291`) — per-account storage iteration; a `hardLimit =
    Bytes * (1 + stateLookupSlack)` (`:181`, slack 0.1) avoids splitting a contract mid-trie; proofs
    added **only when the range was capped or started mid-trie** (`:256-288`).
  - `ServiceGetByteCodesQuery` (`:347-373`) — code by hash, empty-code short-circuited, capped by
    `maxCodeLookups`.
  - `ServiceGetTrieNodesQuery` (`:425-536`) — resolves account/storage nodes by path via
    `chain.TrieDB()`, guarded by `maxTrieNodeLookups`, `maxTrieNodeTimeSpent = 5s`, and `req.Bytes`
    (`:525-533`) — a DoS-bounded loop.
  - `ServiceGetAccessListsQuery` (`:569-599`) — snap/2 only; serves BALs by block hash from
    `chain.GetAccessListRLP`, empty entries for unknown/missing.
- `eth/protocols/snap/handler.go:30-54` — serving limits: `softResponseLimit = 2 MiB`,
  `maxCodeLookups/maxTrieNodeLookups/maxAccessListLookups = 1024`, `stateLookupSlack = 0.1`,
  `maxTrieNodeTimeSpent = 5s`. The snapshot/trie iteration switches on `TrieDB().Scheme()` — hash
  scheme serves from the flat **snapshot** iterator, path scheme serves directly from
  `TrieDB().AccountIterator/StorageIterator` (`handlers.go:68-74, 211-217`).

### snap/1 syncer + state healing (`sync.go`)
- `eth/protocols/snap/sync.go:444-512` — **`syncer`** struct: `tasks []*accountTask` (the snap phase),
  `healer *healTask` (the heal phase), per-kind idler/request maps, and trienode-heal throttle state.
- `eth/protocols/snap/sync.go:291-368` — **`accountTask`** / **`storageTask`**: the account keyspace is
  split into `accountConcurrency = 16` chunks (`:102-108`); large contracts split into
  `storageConcurrency = 16` storage subtasks. `needHeal[]` flags accounts whose storage was chunked
  and therefore needs healing.
- `eth/protocols/snap/sync.go:370-376` — **`healTask`**: wraps a `trie.Sync` scheduler
  (`state.NewStateSync(root, db, onHealState, scheme)`, `:612-613`) plus queued trie-node and code
  tasks.
- `eth/protocols/snap/sync.go:603-846` — **`Sync(root, cancel)`** run loop: repeatedly
  `assignAccount/Bytecode/StorageTasks`, and once `len(tasks)==0` (snap phase done) switches to
  `assignTrienode/BytecodeHealTasks` (`:700-719`). Terminates when both `tasks` and the healer's
  `scheduler.Pending()` are zero (`:689-698`). Progress is journaled (`syncProgress`, `:378-397`) so a
  restart resumes.
- `eth/protocols/snap/sync.go:74-95, 1377-1490` — **heal throttling**: `assignTrienodeHealTasks`
  divides peer capacity by an adaptive `trienodeHealThrottle` (EWMA of process-vs-arrive rate,
  increase 1.33 / decrease 1.25) to keep from expanding the trie breadth-first faster than it can be
  persisted.

### snap/2 syncer — flat state + BAL catch-up (`syncv2.go`)
- `eth/protocols/snap/syncv2.go:359-415` — **`syncerV2`**: "downloads all accounts, storage slots, and
  bytecodes … as **flat state**, applies **BAL** diffs on pivot moves, and triggers a final **trie
  generation** once flat state is consistent." Phases (`syncPhase`, `:286-298`): `phaseDownload` →
  `phaseGenerate`.
- `eth/protocols/snap/syncv2.go:49-72` — BAL tuning: `maxAccessListRequestCount = 28` blocks/request
  (BALs ≈72 KiB compressed, EIP-7928/EIP-8189), `maxCatchUpBlocks = FullImmutabilityThreshold`,
  `catchUpWindow = 512` blocks applied at a time to bound memory.
- `eth/protocols/snap/syncv2.go:597-650` — **pivot-move catch-up**: instead of healing chunk
  boundaries, snap/2 rolls flat state forward by fetching the **block access lists** between the old
  and new pivot and applying their diffs; if the gap exceeds the BAL retention window it discards
  progress and restarts from scratch (`:604-608`). This is why snap/2 **freezes** its pivot during
  trie generation (`syncer.go` `FrozenPivot`) while snap/1 never does.
- `eth/protocols/snap/syncer.go:26-164` — the **`snap.Syncer` interface** and the two adapters
  (`NewV1Syncer`/`NewV2Syncer`) that normalize both syncers to a uniform `Progress`, `OnAccounts/
  OnStorage/OnByteCodes/OnTrieNodes/OnAccessLists`, `FrozenPivot`, `Version` surface for the
  downloader. snap/1's `OnAccessLists` is a no-op; snap/2's `OnTrieNodes` is a no-op.

## Design decisions & rationale

- **CL-driven head, backwards skeleton, forwards backfill.** Post-merge, geth cannot pick a head from
  peers (there's no PoW "heaviest chain"), so head selection is delegated entirely to the CL over the
  Engine API. The skeleton grows the header chain *backwards* from that trusted head and the backfiller
  fills content *forwards* once linked — decoupling "what is the head" (CL) from "download the body of
  the chain" (EL). The old master-peer, header-first, genesis-forward fast-sync is gone
  (`skeleton.go:180-191`, `beaconsync.go:130-135`).
- **Snap sync = flat leaves + proofs, then heal.** Rather than walk the trie top-down fetching nodes
  (old fast-sync), snap fetches contiguous **leaf ranges** (accounts, storage) with Merkle **range
  proofs**, which lets a serving peer stream sequentially off its flat snapshot and lets the syncer
  reconstruct trie nodes locally. Because the pivot state root moves during the download, the chunk
  boundaries end up slightly inconsistent; a **healing** pass (`trie.Sync` scheduler over
  `GetTrieNodes`) fixes exactly those gaps (`sync.go:603-607`). geth *invented* this protocol.
- **Moving pivot with a 64-block full tail.** The pivot tracks HEAD−64 and is moved forward when it
  falls >~128 blocks stale, so the synced state stays close to a state peers still have available
  (`beaconsync.go:306-317`); the last 64 blocks are always executed as full blocks (`fsMinFullBlocks`).
- **snap/2: replace healing with BAL catch-up + trie generation.** The newer syncer downloads pure
  flat state (no proofs-driven trie reconstruction during download), rolls the flat state forward on
  pivot moves by applying **block access list** diffs (EIP-7928) rather than re-healing, and only at
  the end generates the trie locally and commits the root. This trades network round-trips (trie-node
  heal requests) for local CPU (trie generation) and depends on peers retaining recent BALs
  (`syncv2.go:359-363, 597-608`).
- **Uniform `snap.Syncer` seam.** Both syncers are hidden behind one interface with adapters
  (`syncer.go:53-164`), so the downloader's pivot/commit machinery is written once regardless of which
  state-sync algorithm is active — the version only surfaces at `SnapSyncComplete(...,
  version==SNAP2)` (`downloader.go:1105`).
- **Serving is DoS-bounded and proof-minimal.** Every `ServiceGetXQuery` clamps to `softResponseLimit`
  and per-kind lookup/time caps, and adds Merkle proofs *only* at range boundaries that were actually
  capped — a fully-contained range needs no proof (`handlers.go:253-288`).

## Notable patterns (the reusable idea)

1. **Skeleton (reverse) header sync + forward backfill**, decoupled from head selection — the
   canonical post-merge EL sync shape. The reverse-download-into-disjoint-subchains-that-merge model
   (`subchain`) is the reusable structure.
2. **Range-proof state sync ("snap")**: fetch flat leaf ranges + boundary Merkle proofs, reconstruct
   nodes locally, then heal boundary inconsistencies against a moving pivot. This is the pattern every
   other client (including fukuii's SNAP path) implements.
3. **Adaptive heal throttling** (EWMA process-vs-arrive rate → request divisor) to avoid breadth-first
   trie explosion outrunning disk (`sync.go:74-95`).
4. **Exported `ServiceGetXQuery` functions** decoupled from the wire handler — the serving logic is a
   pure `(chain, req) → response` function, testable and reusable, with the p2p send left to the thin
   handler (`handlers.go:38-52`).
5. **Uniform syncer interface with per-version adapters** so the orchestrator is algorithm-agnostic
   across snap/1 and snap/2.

## Authority note

**go-ethereum is THE authority for snap sync — it designed the protocol.** The range-proof account/
storage download, the `GetAccountRange`/`GetStorageRanges`/`GetByteCodes`/`GetTrieNodes` message set,
the healing model, and now the snap/2 BAL-based variant all originate here. For the **serving side**
specifically — which fukuii must implement to be a good network citizen — geth's
`ServiceGetAccountRangeQuery`/`…StorageRanges`/`…ByteCodes`/`…TrieNodes` (`handlers.go`) plus the
`softResponseLimit`/`maxTrieNodeTimeSpent`/`stateLookupSlack` limits (`handler.go:30-54`) are the
reference to match byte-for-byte on wire semantics and proof placement.

The **post-merge orchestration** (skeleton + Engine-API-driven head) is also geth-canonical for the
**ETH/PoS** baseline. It is **not** directly the authority for a PoW chain that still discovers its own
head: an ETC node without a CL must retain a peer-driven head-selection/header-sync path (the pre-merge
model geth deleted). fukuii's ETC (PoW) sync therefore diverges deliberately here — it needs the
snap *state* protocol (geth-authoritative) without the *beacon-only* head plumbing.

**erigon is the structurally different alternative authority for the pipeline**: its **staged sync**
(headers → bodies → senders → execution → hashstate → …, each stage a full pass over MDBX) replaces
geth's concurrent-fetcher + heal model, and pairs with erigon's flat-state Domains (no trie-node store
to heal). Where the question is "staged, restartable, flat-state pipeline," erigon leads; where it is
"snap protocol wire behavior + range proofs," geth leads.

## Gotchas / anti-patterns / things they later changed

- **`LightSync`/LES removed.** Only `FullSync` and `SnapSync` remain (`ethconfig/syncmode.go:26-31`).
  Any client mirroring geth's sync modes should not expect a light path here.
- **Pre-merge fast-sync (header-first from genesis, master peer) is gone.** The whole downloader is now
  skeleton/beacon-shaped; a PoW-only network cannot lift geth HEAD's orchestrator wholesale — the head
  must come from somewhere, and geth assumes a CL (`beaconsync.go:130-135`, this is the sync-side
  analogue of geth dropping standalone PoW in consensus).
- **Two snap syncers coexist; snap/2 is feature-flag-gated on the wire.** `MakeProtocols` only
  advertises snap/2 when `snapV2` is set (`handler.go:82-91`) because it "is not safe to advertise
  unconditionally yet." Treat snap/2 (BAL sync, `syncv2.go`) as experimental/bleeding-edge when
  comparing; snap/1 (`sync.go`) is the stable, widely-deployed reference.
- **snap/2 depends on peers retaining recent BALs (~2 weeks / ~100k blocks).** If a pivot moves past
  the retention window the syncer *cannot* catch up and restarts from scratch
  (`syncv2.go:59-66, 604-608`) — a hard dependency on a network-wide retention assumption that snap/1's
  trie-node healing does not have.
- **Serving hash-scheme vs path-scheme diverges.** The serving handlers still route hash-scheme nodes
  through the legacy flat **snapshot** iterator and path-scheme through `TrieDB` directly
  (`handlers.go:65-74, 209-217`), with a TODO to remove the snapshot path once hash scheme is
  deprecated — i.e. the serving code carries dual-scheme complexity tied to the storage layer's
  hash→path migration (see `storage-persistence.md`).
- **Ephemeral per-cycle channels are mandatory.** The syncer recreates all delivery channels each
  `Sync` cycle because a persistent channel once delivered a *stale* response across cycles
  ("yup, this happened", `sync.go:670-684`) — a subtle concurrency footgun for anyone porting the
  request/response bookkeeping.
- **Pivot commit is a one-way door into full sync.** After `commitPivotBlock` sets `committed`, the
  node full-syncs from the pivot forward; a rollback below the recorded `LastPivotNumber` is what
  re-enables snap (`syncmode.go:92-98`) — the pivot number in the DB is load-bearing sync state.
