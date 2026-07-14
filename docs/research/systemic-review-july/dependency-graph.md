# Systemic Review — fukuii Cross-Subsystem Dependency Graph (the map)

This is **research, not a prompt** — a map of how fukuii's code builds on itself, derived from
`build.sbt` `.dependsOn` edges + cross-package `import` sampling. It exists to **drive the
`Dispatch #` ordering** of the `SR-NN`/`SR-EXT-NN` items in `.claude/sprints/QUEUE.md`'s
"Systemic Review" section: review a subsystem only after the subsystems it depends on, so a
higher-layer review never cites unverified assumptions about a lower layer it hasn't reached.

The graph changes only when the code's module/import structure changes — **update this file
(not the queue prompts) as the code evolves**, then re-derive the `Dispatch #` column from it.
Methodology for *how* dependency order and cycles are handled lives in
`.agents/protocols/process/systemic-review-protocol.md` ("Dependency-ordered dispatch"); this
file is the concrete July-2026-cycle instance of that map.

Produced by `SR-00`. Assessed at the `wt/sr-july` base (branch `july-fourth`).

## How it was built (evidence, not intuition)

- **Sibling sbt modules** — ground truth from `build.sbt` `.dependsOn` (`build.sbt:188-289`):
  `bytes` (leaf) ← `crypto`, `rlp`; `scalanet` (leaf) ← `scalanetDiscovery`; the main module
  `node` `.dependsOn(bytes, crypto, rlp, scalanet, scalanetDiscovery)`.
- **Main-module packages** — directed edge counts from cross-package `import` sampling
  (`.local/scratch/sr00-depgraph.sh`; edge A→B = "N files in package A import package B").

## Dominant edges (low → high layer)

| Edge (A depends on B) | Files | Note |
|---|---|---|
| `vm → domain` | 21 | vm depends on core types only; otherwise clean |
| `domain → rlp` | 23 | domain → primitive (expected) |
| `mpt → db` | 9 | trie needs its storage backend |
| `db → mpt` | 7 | storage needs trie node structure — **cycle** (see below) |
| `mpt → db` heavier than `db → mpt` | — | so `db` is the more-depended-upon of the pair → review `db` first |
| `ledger → domain` / `ledger → vm` | 14 / 6 | block execution over types + EVM |
| `consensus → domain` / `consensus → ledger` | 48 / 17 | consensus over ledger + types |
| `network → domain` | 10 | P2P over types + primitives |
| `blockchain → domain / network / db / mpt / consensus` | 44 / 43 / 24 / 19 / 10 | sync depends on nearly everything |
| `jsonrpc → (almost everything)` | domain 39, consensus 17, blockchain 12 | top layer |

## Cycles — the graph is NOT a clean DAG (each is a coupling finding)

Verified with file:line. Per the protocol's cycle rule: dispatch by dominant edge weight, log
the back-edge as a `01-structural-comparison.md` coupling finding routed to the owning item +
`SR-EXT-01`; the later item cites the earlier one rather than blocking.

| Cycle / back-edge | Evidence | Route to |
|---|---|---|
| **`domain` is not a clean bottom layer** — imports up into `db`/`mpt`/`ledger`/`vm`/`jsonrpc`/`network` | `domain/Blockchain.scala:5-17`, `BlockchainReader.scala:5-16`, `BlockchainWriter.scala:3-5` (facade types); `domain/Block.scala:34`, `SignedTransaction.scala:18`, `Receipt.scala:27` (wire codecs) | `SR-07` (headline structural finding) + `SR-EXT-01` |
| **`db ↔ mpt`** 2-cycle | `db/storage/MptStorage.scala:6-9` imports `mpt`; `mpt/MerklePatriciaTrie.scala:10-12` imports `db.storage` | `SR-05` + `SR-09` (cite jointly) |
| **`db → blockchain`** | `db/storage/FastSyncStateStorage.scala:12`, `StateStorage.scala:7` reach into `blockchain.sync` | confirms `SR-05`↔`SR-01` FastSync-storage cross-ref |
| **`network → blockchain`** | `network/{Peer,PeerManagerActor,ServerActor}.scala` import `blockchain.sync.Blacklist` | `Blacklist` possibly misplaced → `SR-01`/`SR-02` |

## Derived layering (low → high)

primitives (`bytes` → `crypto`/`rlp`, + `utils` leaf) → **domain** → **db + mpt** (mutually
cyclic) → **vm** / **network** (parallel; both over domain+primitives) → **ledger** →
**consensus** → **blockchain/sync** → **jsonrpc**.

## Derived dispatch order (the `Dispatch #` column projects this)

Two bands, because the Batch 2/3/4 gate *overlays* the order (it does not reorder it). One item
at a time; parallel lens sub-agents *within* each item.

**Ready-now band:** `SR-EXT-01` (early — see placement decision) → `SR-10` (primitives) →
`SR-07` (domain) → `SR-05` (db) → `SR-09` (mpt) → `SR-06` (vm) → `SR-08` (ledger) → `SR-11`
(basket, minus `transactions`) → `SR-12` (test infra — independent, last in band).

**Batch-gated band (after Batch 2/3/4 closes):** `SR-02` (network) → `SR-04` (consensus) →
`SR-01` (blockchain/sync) → `SR-03` (jsonrpc) → `SR-11`'s `transactions` sub-section →
`SR-EXT-02` (target design — last).

## Placement decisions (SR-00 open questions)

- **`SR-EXT-01` → early** (dispatch #2, before the subsystem reviews). Its inputs are
  `pluggable-consensus-vision.md` + this coupling/cycle inventory, both available immediately —
  it needs no completed subsystem docs. Its "where does this subsystem hard-code exactly-2-
  networks" output is a *watch-list*, more useful before the reviews than after. The late-
  synthesis need is met separately by `SR-EXT-02` running last over the completed subsystem docs.
- **`SR-12` → independent**, last in the ready-now band. It audits the `src/it`/`src/evmTest`/
  `src/rpcTest`/`ets/tests` harness layer, consumed mainly by its own E2E specs — subsystem
  unit tests (`src/test`) use ScalaTest + ActorTestKit directly and don't route through it, so
  its findings don't gate their `03-test-quality.md` lenses. Slotted before the Batch-gated
  E2E-heavy items (`SR-01`/`SR-02`/`SR-03`/`SR-04`) so they can cite its harness-health verdict
  (e.g. the "6 E2E/sync specs silently never ran in CI" finding, `REPO-06-ITSUITE`).
