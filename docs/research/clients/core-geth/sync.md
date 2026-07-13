# core-geth — sync

_Commit/branch documented: `b28aa0a0bbb1e3ba72ce11afb9310d9dc38c1832` (branch `main`, 2026-06-26).
Vendored at `.claude/repo-references/clients/core-geth`. Documented 2026-07-13._

_core-geth is a **go-ethereum fork** (multi-geth lineage), pinned ~4 minor versions behind geth HEAD
(the sibling `go-ethereum/sync.md` documents `v1.17.4-32-g59e89e81e`). This doc documents core-geth's
sync **as a diff against that baseline** — read `go-ethereum/sync.md` first for the range-proof snap
protocol, the skeleton/backfill shape, pivot machinery, and the DoS-bounded serving handlers, all of
which core-geth inherits. The one high-value divergence is the headline: **core-geth RETAINS the entire
pre-merge, peer-driven PoW sync orchestrator that geth HEAD deleted.** That is how an ETC/PoW node syncs
from genesis with no consensus-layer client, and it is the reference for fukuii's ETC sync path._

## Architecture summary

core-geth's sync is the **pre-merge geth downloader with a beacon retrofit bolted on**, not geth HEAD's
beacon-only rewrite. The `Downloader` (`eth/downloader/downloader.go`) carries **two coexisting head-sync
strategies selected per cycle by a `beaconMode bool`**: (1) the **legacy peer-driven path** —
`LegacySync` → `fetchHead` (master peer advertises head+TD) → `findAncestor` (span + binary search) →
`fetchHeaders` (header-first *skeleton* filled concurrently from all peers) → full/snap content; and
(2) the **beacon path** — `BeaconSync`/`BeaconExtend` (Engine-API-driven) → reverse `skeleton.go` header
sync → `findBeaconAncestor` → backfill. Which one runs is decided in `eth/sync.go`'s `chainSyncer`, which
picks **the peer with the highest total difficulty** and drives `LegacySync` until TTD is passed — and on
an ETC config TTD is unset, so the legacy path is the *only* path that ever runs. State download is
**snap/1 only** (`eth/protocols/snap/`, `SNAP1` the sole version) — the range-proof-then-heal protocol
geth invented; there is **no experimental snap/2 / BAL syncer** here. Three sync modes survive —
`FullSync`, `SnapSync`, **and `LightSync`** — though the LES light-*client* protocol package is gone, so
`LightSync` is a header-only-download vestige, not a served light protocol. ETC's subjective reorg
protection (**MESS / ECBP-1100 "artificial finality"**) is wired directly into this sync loop, an
ETC-specific divergence with no geth analogue.

## Key types / interfaces / files

### The retained legacy (pre-merge, PoW) orchestrator — the crucial divergence
- `eth/downloader/downloader.go:343-368` — **`LegacySync(id, head, td, ttd, mode)`**: the peer-driven
  entry point **geth HEAD removed entirely**. Wraps `synchronise(..., beaconMode=false, ...)`, drops the
  master peer on `errInvalidChain`/`errBadPeer`/`errTimeout`/etc., and treats `ErrMergeTransition` as an
  expected quiet fault.
- `eth/downloader/downloader.go:373-465` — **`synchronise(id, hash, td, ttd, mode, beaconMode, beaconPing)`**:
  the single-flight cycle entry (CAS on `synchronising`, `:394`). `beaconMode=false` requires a real
  master peer (`d.peers.Peer(id)`, `:456`); `beaconMode=true` needs none. This dual signature is the
  structural marker of the retrofit — geth HEAD's `synchronise` is beacon-only.
- `eth/downloader/downloader.go:473-668` — **`syncWithPeer`**: the fork point. `:497-502` legacy →
  `fetchHead(p)`; `:503-533` beacon → `skeleton.Bounds()`. `:544-556` legacy → `findAncestor(p, latest)`;
  beacon → `findBeaconAncestor()`. `:642-649` legacy header fetcher = `fetchHeaders(p, ...)`; beacon =
  `fetchBeaconHeaders(...)`. Both feed the same `fetchBodies`/`fetchReceipts`/`processHeaders` +
  `processSnapSyncContent`/`processFullSyncContent` fetcher set.
- `eth/downloader/downloader.go:746-786` — **`fetchHead(p)`**: **master-peer head + TD selection**. Reads
  the peer's advertised `latest, peerTd` via `p.peer.Head()` (`:751`), requests head (+pivot at HEAD−64
  for snap) directly from that one peer, validates against `d.checkpoint`. This is the "master peer +
  TD-based head" mechanism the task asked to confirm — **present and intact**.
- `eth/downloader/downloader.go:847-1034` — **`findAncestor` / `findAncestorSpanSearch` /
  `findAncestorBinarySearch`**: the classic peer-driven common-ancestor search (top-N span check, then
  binary search), with explicit `FullSync`/`SnapSync`/**`LightSync`** switch arms (`:855-864`, `:950-959`,
  `:1004-1013`). geth HEAD replaced this with `findBeaconAncestor` over the skeleton only.
- `eth/downloader/downloader.go:1041-1092` — **`fetchTotalDifficulty(p, latest)`**: the **TD-based
  head-quality enforcement** ("Snapping Snap Sync" mitigation) — periodically compares the master peer's
  TD against all peers and drops it (`errUnsyncedPeer`) if it lags `maxTotalDifficultyDistance=10`
  difficulty-units behind a heavier chain (`:62`, `:1054-1066`). Purely a PoW/TD construct; absent from a
  beacon-driven node.
- `eth/downloader/downloader.go:1094-1314` — **legacy `fetchHeaders`**: header-first **skeleton assembly**
  (`MaxSkeletonSize=128`, `MaxHeaderFetch=192`, `:43-44`) built from the master peer and filled
  concurrently from all peers via `fillHeaderSkeleton` (`:1325-1338`), with in-band pivot-staleness moves
  (`:1124-1200`). This is the *legacy* skeleton (forward, genesis-relative), distinct from geth HEAD's
  *beacon* `skeleton.go` (reverse, CL-head-relative) — **both exist in this tree**.
- `eth/downloader/downloader.go:1365-1590` — **`processHeaders`** carries the **legacy-vs-merge guard**:
  in `!beaconMode` it accumulates TD and refuses to import any header at/after `ttd`, returning
  `ErrMergeTransition` (`:1499-1550`). The same guard is in `processFullSyncContent` (`:1602-1639`). On an
  ETC config `ttd == nil`, so the guard is inert and every PoW header imports — the mirror of core-geth's
  inert beacon consensus wrap (see `consensus-engines.md`).

### The legacy sync *driver* (ETC-specific), `eth/sync.go`
- `eth/sync.go:93-245` — **`chainSyncer`** + **`nextSyncOp`**: the loop that *decides* to legacy-sync.
  `:217` `peerWithHighestTD()` — **TD-based head selection at the driver level**. `:195` the *only*
  disable condition is `GetEthashTerminalTotalDifficultyPassed() || merger.TDDReached()` — never true for
  ETC, so the legacy path runs indefinitely. geth HEAD deleted this whole file's legacy machinery.
- `eth/sync.go:252-281` — **`modeAndLocalHead`**: core-geth's **adaptive snap↔full selection lives
  HERE**, not in a `downloader/syncmode.go` component (that file is **absent** — see Gotchas). Re-enables
  snap when `ReadLastPivotNumber` is ahead of head (`:262-268`) or head state is missing (`:272-277`) —
  the same adaptive intent as geth HEAD's `syncModer`, but sited in the handler and keyed off DB pivot +
  `HasState`.
- `eth/sync.go:290-318` — **`doSync`** calls `h.downloader.LegacySync(op.peer.ID(), op.head, op.td,
  ttd, op.mode)` (`:292`). This is the concrete wiring that geth HEAD has no equivalent of.
- `eth/sync.go:56-77, 205-241` — **MESS / ECBP-1100 "artificial finality" toggling inside the sync loop**:
  `artificialFinalitySafetyLoop` disables AF on a stale head (eclipse-attack escape), and `nextSyncOp`
  enables AF only in `FullSync` with `≥ minArtificialFinalityPeers` and a fresh head (`:233-241`). This
  ETC subjective-reorg-protection plumbing has **no geth counterpart** and is the sync-side of the
  MESS behaviour `consensus-engines.md` documents in `core/blockchain_af.go`.

### The beacon path — present but dormant on ETC
- `eth/downloader/skeleton.go`, `beaconsync.go` — the reverse-skeleton + backfiller, **structurally the
  same as geth HEAD's** (documented in `go-ethereum/sync.md`). Constructed unconditionally in
  `New(...)` (`downloader.go:250`).
- `eth/catalyst/api.go:309` `BeaconSync` / `:687` `BeaconExtend` — the **only** callers, both from the
  Engine API (`engine_forkchoiceUpdated`). With no CL driving the Engine API, ETC never enters beacon
  mode. `eth/downloader/beacondevsync.go:36` `BeaconDevSync` is a test-only helper.

### snap state sync + serving — inherited snap/1, one version behind
- `eth/protocols/snap/protocol.go:29-43` — **`SNAP1 = 1` is the sole version**; `ProtocolVersions =
  {SNAP1}`, message set `GetAccountRange…GetTrieNodes` (0x00–0x07) only. **No `SNAP2`, no
  `GetAccessLists/AccessLists`, no BAL messages** — confirmed by zero `AccessList`/`FrozenPivot`/`syncv2`/
  `BAL` hits across the whole `snap/` package.
- `eth/protocols/snap/sync.go:446-516` — a **single `Syncer` + `NewSyncer(db, scheme)`** constructor;
  **no `snap.Syncer` interface, no `NewV1Syncer`/`NewV2Syncer` adapters** (geth HEAD's uniform seam).
  The downloader holds it as a concrete `*snap.Syncer` (`downloader.go:144`). This is the classic
  range-download + trie-node **healing** syncer; `gentrie.go` (2024) is present, so it is a *reasonably
  recent* snap/1, just pre-snap/2.
- `eth/downloader/downloader.go:1904-1928` — **`DeliverSnapPacket`** handles exactly four packet types
  (Account/Storage/ByteCodes/TrieNodes) — no `AccessListsPacket` case, the wire-level confirmation of
  snap/1-only.
- `eth/protocols/snap/handler.go:342-664` — **the peer-serving side**, same exported
  `ServiceGetAccountRangeQuery`/`…StorageRanges`/`…ByteCodes`/`…TrieNodes` `(chain, req, start) →
  response` shape as geth (see `go-ethereum/sync.md`), with **identical** account-range proof placement
  (`tr.Prove(Origin)` + `tr.Prove(last)` for first & last account, `:392-405`) and identical size caps
  `softResponseLimit = 2 MiB`, `maxCodeLookups = maxTrieNodeLookups = 1024`, `stateLookupSlack = 0.1`
  (`:39-53`). **One divergence: the per-request serving-time cap is `maxSnapServingTime = 2s`
  (`:55-60`), applied uniformly across all four serving loops — geth HEAD uses `maxTrieNodeTimeSpent = 5s`
  scoped to trie-node serving.** No `maxAccessListLookups` (snap/2-only in geth).

## Design decisions & rationale

- **Retain the pre-merge orchestrator because ETC never merges.** ETC has no consensus layer, so head
  selection cannot be delegated over the Engine API — the node must discover the heaviest chain itself.
  core-geth therefore keeps the full `LegacySync` machinery (master peer + TD head + span/binary ancestor
  + forward header-skeleton) that geth HEAD deleted, and merely *adds* the beacon path beside it for the
  ETH-family configs it can also run. On a block-number-keyed PoW config the TTD guards
  (`processHeaders:1499`, `processFullSyncContent:1609`, `nextSyncOp:195`) are all inert, so the beacon
  path is dead code at runtime and the legacy path is authoritative. This is the **sync-layer twin** of
  the consensus-layer decision documented in `consensus-engines.md` (real `ethash.New`, inert `beacon`
  wrap): keep both shapes, let "ETC never merges" make the PoS half inert rather than deleting the PoW
  half.
- **TD-based head + peer-drop as Sybil/eclipse defense.** Because there is no trusted CL head, the driver
  ranks peers by advertised TD (`nextSyncOp:217`) and the downloader actively drops a master peer that
  falls behind a heavier chain (`fetchTotalDifficulty`). This TD hygiene is meaningless post-merge (geth
  HEAD removed it) but essential for a live PoW network.
- **MESS folded into the sync loop, self-disabling.** ECBP-1100 artificial finality is toggled by peer
  count and head-staleness right where sync decisions are made (`eth/sync.go`), so a node under eclipse
  or stalled on a dead chain releases the subjective-finality lock automatically. Consistent with MESS
  being a *node-local fork-choice preference*, never a state-root rule (hence banksy-owned, forge-cosigned
  in fukuii).
- **snap/1 inherited verbatim, snap/2 not adopted.** core-geth tracks geth's stable snap/1 (range proofs
  + healing, plus the 2024 `gentrie` completed-range trie generation) but is pinned before geth's
  experimental, feature-flag-gated snap/2 (BAL / EIP-7928). For an ETC node this is the right authority
  anyway — snap/2's BAL catch-up depends on a network-wide access-list retention assumption ETC does not
  make. See Authority note.
- **LightSync retained in the enum, LES protocol dropped.** `SyncMode` still lists `LightSync`
  (`modes.go:29`) and `findAncestor`/`processHeaders` still branch on it, but the `les` package is gone,
  so there is no served light-client protocol — `LightSync` degrades to a header-only download with no
  companion CHT/LES serving. Effectively vestigial.

## Notable patterns (the reusable idea)

1. **Dual-path downloader gated by one `beaconMode` bool** — a single orchestrator that supports both
   peer-driven (PoW, self-selected head) and CL-driven (PoS, skeleton) sync, with the TTD guard making
   the wrong half inert per network. The reusable idea for a client that must serve a permanently-PoW
   chain *and* a post-merge chain from one codebase (exactly fukuii's dual-family situation).
2. **TD-ranked master-peer selection + lag-based peer drop** as the PoW head-discovery primitive
   (`peerWithHighestTD` + `fetchTotalDifficulty`) — the canonical "no CL, trust the heaviest chain"
   mechanism.
3. **Subjective fork-choice wired into the sync scheduler, not the validator** (MESS toggled in
   `chainSyncer` by peers/staleness) — reorg resistance that self-disables under eclipse, kept out of
   block-validity so it never touches determinism.
4. **Adaptive snap↔full re-enable keyed off a persisted pivot number + `HasState`** (`modeAndLocalHead`)
   — a rollback below `LastPivotNumber`, or a missing head state, transparently re-enters snap sync.

## Authority note

**For PoW / pre-merge sync-from-genesis, core-geth is the ETC-relevant authority** — go-ethereum HEAD
*deleted* the legacy peer-driven orchestrator (`LegacySync`, `fetchHead` master peer, `findAncestor`,
`fetchTotalDifficulty`, forward header-skeleton), so aligning fukuii's ETC sync to geth HEAD would be a
regression that removes the only path by which a CL-less PoW node can find and follow the heaviest chain.
When fukuii asks "how does an ETC node sync from genesis without a beacon client," **this file
(`downloader.go` legacy path + `eth/sync.go` TD-driven `chainSyncer`) is the reference to match**, not
geth's `beaconsync.go`. core-geth's legacy path is itself an inherited-and-frozen copy of pre-merge geth,
so it is authoritative by *retention*, not by independent design.

**For snap-sync protocol semantics, go-ethereum remains the authority.** core-geth's snap/1 (range
proofs, healing, `GetAccountRange`/`…StorageRanges`/`…ByteCodes`/`…TrieNodes`, the serving caps and
first/last-account proof placement) is byte-for-byte geth's — cite `go-ethereum/sync.md` for the wire
semantics fukuii must match. core-geth simply lags: **snap/1 only (no snap/2 / BAL)**, **eth/68 wire only**
(geth HEAD advertises eth/69+), and a **2s** rather than 5s serving-time cap. Where the question is "what
does the snap protocol *do*," follow geth; where it is "does an ETC node need snap/2," the answer from
core-geth's retention is *no* — snap/1 is sufficient and snap/2's BAL retention assumption does not apply
to ETC.

## Gotchas / anti-patterns / things they later changed

- **Two skeletons, two ancestor searches, in one tree.** The *legacy* forward skeleton
  (`downloader.go:fetchHeaders` + `findAncestor`) and the *beacon* reverse skeleton (`skeleton.go` +
  `findBeaconAncestor`) both exist. Do not conflate them: only the legacy pair is exercised on ETC. A
  reader porting "geth's skeleton sync" could grab the wrong one.
- **`eth/downloader/syncmode.go` does NOT exist here.** geth HEAD's adaptive `syncModer` component was
  never in this fork's snapshot; the equivalent snap↔full decision lives in `eth/sync.go:modeAndLocalHead`.
  Search the handler, not a downloader sub-file.
- **Legacy checkpoint / CHT fields survive** (`Downloader.checkpoint`, `.genesis`, `downloader.go:107-108`;
  used in `fetchHead`/ancient-limit logic) — hardcoded-checkpoint machinery geth HEAD dropped. Load-bearing
  only for the legacy path.
- **`LightSync` is a trap value.** It is still a valid `SyncMode` and still branched on, but with the `les`
  package gone there is no light protocol behind it — selecting it yields header-only download with no
  serving peer support. Treat "core-geth has LightSync" as *enum retained, protocol removed*.
- **`ErrMergeTransition` is expected, not an error, on ETH-family configs** — the legacy path deliberately
  bails at TTD and waits for a beacon client (`eth/sync.go:161-164`). On ETC (TTD nil) it never fires; do
  not "fix" the guard.
- **Serving-time cap differs from current geth** (`maxSnapServingTime = 2s` vs `maxTrieNodeTimeSpent = 5s`).
  If matching serving behaviour byte-for-byte against a modern geth peer, this per-request budget is a real
  divergence — smaller responses under load, not a correctness issue but a wire-timing one.
