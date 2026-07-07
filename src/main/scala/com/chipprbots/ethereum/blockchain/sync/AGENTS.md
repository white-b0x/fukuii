# blockchain/sync — Chain Synchronization

<!-- breadcrumb-currency: directory/file listing verified against source tree 2026-07-05 (a68dbec1f); re-verify when subpackages are added/removed/renamed, not on every code change inside existing files -->

Sync subsystem: how fukuii catches up to and then follows chain head, for both PoW (ETC/Mordor)
and PoS (ETH/Sepolia) networks. Three sync strategies coexist; `SyncController` is the
top-level dispatcher deciding which one runs.

## Directory Structure

| Path | Purpose |
|------|---------|
| `blockchain/sync/` (top-level) | Orchestration + peer plumbing shared by all strategies |
| `blockchain/sync/fast/` | Legacy fast-sync (state-trie download by node hash, pre-SNAP) |
| `blockchain/sync/regular/` | Steady-state chain following once caught up (fetch → import → broadcast) |
| `blockchain/sync/snap/` | SNAP protocol sync (range-based state sync) + post-sync healing |
| `blockchain/sync/snap/actors/` | SNAP's Pekko Typed coordinator/worker actor pairs |
| `blockchain/sync/codec/` | RLP wire codecs used during sync (MPT nodes, receipts) |

## Key Components

**Orchestration (top-level)**
- `SyncController.scala` — chooses/dispatches the active sync strategy
- `PeersClient.scala` / `PeerListSupportNg.scala` / `PeerListHelper.scala` / `PeerComparator.scala` /
  `PeerRateTracker.scala` / `Blacklist.scala` — peer selection, scoring, blacklisting shared
  across all strategies
- `BlockchainHostActor.scala` — serves headers/bodies/receipts to requesting peers (the server
  side of sync, not a client-side strategy)
- `PivotHeaderBootstrap.scala` — shared pivot-block-selection entry point

**`fast/` — legacy fast sync**
- `FastSync.scala` — top-level driver; `SyncStateSchedulerActor.scala`/`SyncStateScheduler.scala` —
  state-trie node scheduling; `PivotBlockSelector.scala`, `ReceiptsValidator.scala`,
  `SyncBlocksValidator.scala`
- `FastSyncBranchResolverActor.scala`/`FastSyncBranchResolver.scala`, `MerkleProofVerifier.scala` —
  **flagged candidate-dead-code post-capstone, not confirmed removed** (see
  `dead-code-review.md`) — verify current wiring before assuming these are on the live path

**`regular/` — steady-state chain following**
- `RegularSync.scala` — top-level driver; `BlockFetcher.scala` (+`HeadersFetcher`/`BodiesFetcher`/
  `BodiesSliceFetcher`/`StateNodeFetcher`) — fetch pipeline; `BlockImporter.scala` — validate +
  import; `BlockBroadcasterActor.scala`/`BlockBroadcast.scala` — propagate new blocks to peers

**`snap/` — SNAP protocol sync + healing**
- `SNAPSyncController.scala` — top-level driver, the most complex file in this subsystem
- `snap/actors/` coordinator/worker pairs, one pair per SNAP phase: `AccountRangeCoordinator`/
  `AccountRangeWorker`, `StorageRangeCoordinator`/`StorageRangeWorker`, `ByteCodeCoordinator`/
  `ByteCodeWorker`, `TrieNodeHealingCoordinator`/`TrieNodeHealingWorker` (the post-completion
  "heal" pass — see `site/architecture/SNAP_SYNC_*.md` and `site/runbooks/snap-sync-*.md` for
  its extensive operational history)
- `StateValidator.scala`, `SyncProgressMonitor.scala`, `SNAPRequestTracker.scala` — cross-cutting

**`codec/`**
- `MptNodeCodecs.scala`, `ReceiptCodecs.scala` — RLP encode/decode for wire-format sync payloads

## Actor migration status

The Pekko Classic→Typed migration of `blockchain/sync/` actor definitions is complete — no
`extends Actor` (Classic) actors remain in this package; all are `Behaviors.`-based (Typed).
Treat any new `extends Actor` here as a regression, not a mid-migration artifact.

## Cross-references

- P2P/wire-protocol slice: `.claude/agents/herald.md`
- Actor-structure/migration history: `.claude/agents/loom.md`
- Operational runbooks: `site/runbooks/snap-sync-*.md`, `site/architecture/SNAP_SYNC_*.md`
