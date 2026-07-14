# core-geth — state-trie
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary
Fork of go-ethereum. The state/trie slot — `trie/`, `triedb/`, `core/state/` —
**inherits geth's Merkle-Patricia Trie and state model unchanged**. ETC's
divergence from ETH is in **consensus, rewards, and fork schedule** (ECIP-1017
emission, ECIP-1099 ETChash, MESS), **not in state structure**. The account
model (nonce, balance, storageRoot, codeHash), the secure hexary MPT, the trie
node encoding, and the trie database (hash-mode + path-mode/pebble) are the same
bytes as geth. A grep for `etchash|ecip|classic|mordor` across `trie/`,
`triedb/`, and `core/state/` returns **zero hits**. This is a genuinely
non-diverging slot; the thin "inherits geth" verdict is the correct output.

## Key types / interfaces / files
- `trie/trie.go`, `trie/secure_trie.go` (StateTrie), `trie/node.go`,
  `trie/node_enc.go`, `trie/hasher.go`, `trie/committer.go`, `trie/encoding.go` —
  the hexary MPT: node types, RLP node encoding, hash/commit pipeline,
  hex↔compact key encoding. All geth-canonical.
- `trie/iterator.go` — trie iteration; inherited.
- `triedb/` — the trie-database abstraction over hash-based and path-based
  (pebble) backing stores; geth's, unchanged.
- `core/state/` — `StateDB`, state objects, account/storage state and journaling;
  inherited from geth. The account layout (`types.StateAccount`: nonce, balance,
  root, codehash) is ecosystem-shared and identical.
- No ETC-specific file exists anywhere in these three trees (verified by grep at
  `4185df450`).

## Design decisions & rationale
- **State structure is protocol-shared; leave it geth-canonical.** ETC and ETH
  agree on how accounts and storage are Merkleized — the state root algorithm is
  a compatibility invariant. Changing it would fork the state-root computation,
  which ETC explicitly does not do. ETC's economic/consensus differences
  (fixed-supply emission, PoW retention, ECIP fork points) all operate *on* the
  state via block processing and rewards, never *on* the trie/state encoding.
- **Where ETC actually diverges is upstream of this slot:** block reward
  crediting (ECIP-1017) is applied in the ledger/block-processor as `StateDB`
  mutations, and the fork-dispatch that selects gas/opcode rules is in
  `params/` + `core/vm/` — but the *resulting* state is stored through the same
  unmodified trie. So a state root produced by core-geth for ETC is computed by
  geth-identical trie code over ETC-specific account values.

## Notable patterns (the reusable idea)
For fukuii: **state/trie is an inherit-verbatim boundary — the ETC/ETH split is
consensus-and-rewards, not storage-structure.** This confirms fukuii's own
divergence map: its RocksDB/`DataSource` MPT and state layer should track
geth-canonical semantics, and network-specific behaviour belongs in the
block-processor/rewards/fork-config layers that *feed* the trie, not in the trie
itself. A change that appears to be "ETC state" is almost always an ECIP-1017
reward credit or a fork-selected VM rule applied before the state is committed
through the shared trie.

## Authority note
**Not authoritative — inherits geth.** core-geth is the ETC byte-authority for
consensus/rewards, but for the state-trie slot it is a pure geth passthrough. The
canonical reference for MPT/state-encoding behaviour is geth (and core-geth,
identically). The one thing core-geth *is* authoritative about here is negative
and useful: it establishes that **ETC introduces no state-structure divergence**,
so fukuii can treat this layer as geth-canonical with confidence.

## Gotchas / anti-patterns / things they later changed
- **Don't attribute ETC reward/emission logic to this slot.** ECIP-1017
  fixed-supply emission is applied as `StateDB` balance mutations in the block
  processor (consensus/ledger layer), *then* committed through this unmodified
  trie. The trie sees only the final values.
- **Vintage skew:** `triedb`'s path-mode/pebble backend here is 2025-01 vintage
  and predates later geth state-storage work (e.g. verkle/path-scheme
  refinements). Read it as "geth circa early 2025," not current geth state
  storage. fukuii's storage layer is independent (RocksDB, not
  pebble/leveldb) — see fukuii's `db/AGENTS.md`.
- The presence of verkle-adjacent deps (`go-verkle`, `go-ipa`) in `go.mod` is
  ETH-side experimental plumbing carried from geth; it does not indicate ETC
  state-structure change.
