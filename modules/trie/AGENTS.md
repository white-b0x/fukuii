# `modules/trie` — L2 subsystem breadcrumb

_The Merkle-Patricia trie + state-root core. Depends **down-only** on `domain`, `crypto`, `storage` — an
upward `.dependsOn` is a compile error. Full record:
[`docs/architecture/fukuii-rebuild/implementation-reports/03-L2-storage-trie.md`](../../docs/architecture/fukuii-rebuild/implementation-reports/03-L2-storage-trie.md);
plan: [`plan/L2.md`](../../docs/architecture/fukuii-rebuild/plan/L2.md); byte-cited RX evidence:
[`plan/rx/L2.md`](../../docs/architecture/fukuii-rebuild/plan/rx/L2.md). Read the record before structural
changes here._

## What lives here

`enum MptNode` (Leaf/Extension/Branch/Hash/Null) + `MerklePatriciaTrie`, `HexPrefix`, the
`ByteArraySerializable` node serializers, the `MptStorage` persistence seam (+ `PersistedMptStorage` over
L2 `storage`), root-neutral path threading, and the `TrieLog` `{prior, updated}` leaf-diff journal.

## Invariants (do not break without forge/beacon — this is the state root)

- **Consensus-critical.** Node encoding + the empty-trie root feed every state / storage / tx / receipt root
  and the block hash. Byte-exact to go-ethereum + besu (shared) — route structural changes through the
  Consensus-Critical Change Protocol.
- **`MptNode.Null` is the bare byte `0x80`** (RLP empty string), **not** a 1-item list; the empty-trie root
  is `56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421` and is never persisted.
- **`< 32`-byte inline threshold.** A child is stored separately (by hash) **iff** its RLP encoding is
  `≥ 32` bytes (`isStoredSeparately`); a shorter node is embedded in its parent.
- **`HexPrefix`**: bit-5 = leaf/extension flag, bit-4 = odd-length flag — the nibble-path encoding is
  consensus-fixed.
- **Secure-trie is serializer composition, not a wrapper class** — key-hashing is a `ByteArraySerializable`
  layer over the same trie, not a subclass.
- **Path threading is root-neutral.** Threading `(owner, path)` for a commit must not change the root a
  hash-rooted re-commit produces (a Hash-rooted re-commit short-circuits on hash alone).
- **`TrieLog` is root-neutral + byte-pure**, keyed by **leaf** (not node hash): `{prior, updated}`
  leaf-diffs (besu Bonsai) support `rollForward`/`rollBack` to a shared ancestor.

## Boundaries

Physical persistence + the `NodeLocation` key scheme → L2 `storage` (trie holds the *nodes*, storage holds
the *bytes*). Trie-node **owner** composition (the account hash for storage sub-tries) is threaded by L4
`WorldStateProxy`. StackTrie / HashBuilder streaming root (SNAP pivot rebuild, parallel state-root) → L7
(T3, deferred entire — see the record's Deferrals).
