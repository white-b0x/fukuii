package com.chipprbots.fukuii

/** The `trie` module (L2): the Merkle Patricia Trie and the state-root consensus core.
  *
  * Owns the typed node contract — [[com.chipprbots.fukuii.trie.MptNode]] (RLP node encoding, hex-prefix nibble
  * compaction, `< 32`-byte inlining), [[com.chipprbots.fukuii.trie.MerklePatriciaTrie]] (the immutable functional MPT +
  * bottom-up state root), and the [[com.chipprbots.fukuii.trie.MptStorage]] load/store seam over the byte-pure
  * `storage` layer. Consensus-load-bearing: node bytes and the state root must stay geth/core-geth byte-identical.
  * `trie → domain, crypto, storage` is down-only; `storage` never imports node types.
  */
package object trie
