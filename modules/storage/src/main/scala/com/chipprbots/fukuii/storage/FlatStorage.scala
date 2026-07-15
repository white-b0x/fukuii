package com.chipprbots.fukuii.storage

/** The flat-state accelerator's O(1) `key -> value` mirror in front of the trie (besu Bonsai
  * `BonsaiFullFlatDbStrategy`/`BonsaiFlatDbStrategy`; go-ethereum's snapshot layer) — the trie remains authoritative,
  * this is a read-side accelerator only, never a second source of truth. `storage` delivers the column-family primitive
  * and the range-serve seam ([[seekFrom]], the RX-L2-24 storage-side SNAP-serving primitive); the flat-first,
  * MPT-fallback READ WIRING into the live account path is an L4 concern (`WorldStateProxy.getAccount`, `plan/L2.md`
  * RX-L2-09's plan-edit note) that consumes this primitive — this class builds the CF + the seam only, not the
  * account-read dispatch.
  *
  * Values are opaque pre-encoded bytes (byte-pure, DoD grep — no `Account` domain type here): the caller (L4)
  * encodes/decodes.
  */
final class FlatAccountStorage(dataSource: DataSource):

  def get(accountKey: IndexedSeq[Byte]): Option[IndexedSeq[Byte]] = dataSource.get(Namespace.FlatAccount, accountKey)

  def put(accountKey: IndexedSeq[Byte], encodedAccount: IndexedSeq[Byte]): Unit =
    dataSource.update(Seq(DataSourceUpdate(Namespace.FlatAccount, Nil, Seq(accountKey -> encodedAccount))))

  def remove(accountKey: IndexedSeq[Byte]): Unit =
    dataSource.update(Seq(DataSourceUpdate(Namespace.FlatAccount, Seq(accountKey), Nil)))

  /** SNAP `GetAccountRange`-shaped serving primitive (RX-L2-24) — a bounded forward scan over `[fromKey,
    * toKeyExclusive)` in the flat account keyspace, ascending unsigned-lexicographic order ([[DataSource.scanRange]]'s
    * contract). `sync` (L7) drives DoS budgeting / proof bounding on top of this; `storage` only guarantees the scan
    * itself is bounded and abort-safe.
    */
  def seekFrom(fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Iterator[(Array[Byte], Array[Byte])] =
    dataSource.scanRange(Namespace.FlatAccount, fromKey, toKeyExclusive)

/** The per-account storage-slot analogue of [[FlatAccountStorage]] (besu `BonsaiFlatDbStrategy`
  * `getFlatStorageValueByStorageSlotKey`/`storageToPairStream`). Keys are ACCOUNT-SCOPED — `owner ++ slotKey` —
  * mirroring [[NodeLocation]]'s and [[PathKeyedNodeStorage]]'s account-scoping rationale: a bare slot key would collide
  * across accounts sharing one [[Namespace.FlatSlot]] column family.
  */
final class FlatSlotStorage(dataSource: DataSource):

  private def key(owner: IndexedSeq[Byte], slotKey: IndexedSeq[Byte]): IndexedSeq[Byte] = owner ++ slotKey

  def get(owner: IndexedSeq[Byte], slotKey: IndexedSeq[Byte]): Option[IndexedSeq[Byte]] =
    dataSource.get(Namespace.FlatSlot, key(owner, slotKey))

  def put(owner: IndexedSeq[Byte], slotKey: IndexedSeq[Byte], value: IndexedSeq[Byte]): Unit =
    dataSource.update(Seq(DataSourceUpdate(Namespace.FlatSlot, Nil, Seq(key(owner, slotKey) -> value))))

  def remove(owner: IndexedSeq[Byte], slotKey: IndexedSeq[Byte]): Unit =
    dataSource.update(Seq(DataSourceUpdate(Namespace.FlatSlot, Seq(key(owner, slotKey)), Nil)))

  /** Account-scoped range-serve (besu `storageToPairStream` analogue, RX-L2-24) — bounds the scan to one account's
    * storage slots via the caller-supplied `[fromSlotKey, toSlotKeyExclusive)` window, concatenated behind the `owner`
    * prefix. Returned keys have the `owner` prefix stripped back off — the caller sees only the bare slot key, the same
    * shape [[get]]/[[put]] accept.
    */
  def seekStorageRange(
      owner: IndexedSeq[Byte],
      fromSlotKey: Array[Byte],
      toSlotKeyExclusive: Array[Byte]
  ): Iterator[(Array[Byte], Array[Byte])] =
    val ownerArr = owner.toArray
    dataSource
      .scanRange(Namespace.FlatSlot, ownerArr ++ fromSlotKey, ownerArr ++ toSlotKeyExclusive)
      .map { case (k, v) => (k.drop(ownerArr.length), v) }
