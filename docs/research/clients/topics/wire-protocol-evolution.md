# Topic — ETH wire-protocol version evolution (ETH68→ETH71)
_Anchor: go-ethereum upstream @ `59e89e81e`. Documented 2026-07-13. Git-archaeology topic doc._

go-ethereum **defines** the `eth` devp2p subprotocol, so its git history is the canonical
timeline for when each wire version was introduced, what message-set delta it carried, and
when it was dropped. Every claim below is anchored to a real commit hash or a
`protocol.go` line. Cite format: `<shorthash> <subject>` for commits,
`eth/protocols/eth/protocol.go:<line>` for source.

## Current advertised versions (geth HEAD)

At `59e89e81e`, `eth/protocols/eth/protocol.go`:

- `protocol.go:33-35` — version constants: `ETH69 = 69`, `ETH70 = 70`, `ETH71 = 71`.
  **No `ETH68` constant remains** — it was removed from source.
- `protocol.go:44` — `var ProtocolVersions = []uint{ETH71, ETH70, ETH69}` (first is primary /
  highest-preference). **geth advertises three versions simultaneously: 71, 70, 69.**
- `protocol.go:48` — `protocolLengths = map[uint]uint64{ETH71: 20, ETH69: 18, ETH70: 18}` —
  message-code count per version (ETH71 = 20 codes vs 18 for 69/70; the +2 is the BAL pair).
- `protocol.go:57-73` — message codes. Codes `0x00`–`0x10` are the classic set; the tail is
  version-gated:
  - `BlockRangeUpdateMsg = 0x11` (introduced with ETH69)
  - `GetBlockAccessListsMsg = 0x12`, `BlockAccessListsMsg = 0x13` (introduced with ETH71)

Confirms the prompt's expectation: **ETH71/70/69 advertised NOW; ETH68 removed.**

## Version-by-version evolution (commit log)

Source of the ordering: `git -C <repo> log --oneline -- eth/protocols/eth/protocol.go`.

### ETH68 — added `b0d44338b` 2022-10-31; removed `723aae2b4` 2026-02-28
- **Add:** `b0d44338b eth: implement eth/68 (#25980)` (2022-10-31). Implements **EIP-5793**:
  the `NewPooledTransactionHashes` announcement is widened from a bare hash list to carry, per
  announced tx, its **transaction type byte and size** alongside the hash (commit body:
  "added tx size to announcement", "check equal lengths on receiving announcement",
  "+1 to tx size because of the type byte"). Purpose: let a peer decide whether to fetch a
  large/blob tx by full request rather than blindly pulling every announced hash.
- **Remove:** `723aae2b4 eth/protocols/eth: drop protocol version eth/68 (#33511)` (2026-02-28).
  Commit body: "we are dropping support for protocol version eth/68. The only supported
  version is eth/69 now. The p2p receipt encoding logic can be simplified a lot… we now
  transform the network encoding into the database encoding directly, without decoding the
  receipts first." So at that moment geth advertised **only eth/69** — ETH70/71 did not exist
  yet.

#### ETH68 implementation detail (retained by fukuii — extracted from geth `cee751a1e`, pre-removal)

`cee751a1e` (2026-02-27) is the last commit where eth/68 still exists in geth; its child
`723aae2b4` removed it. All file:line references below are at `cee751a1e`,
`eth/protocols/eth/`.

**Advertised set at that time** (`protocol.go:43,47`): `ProtocolVersions = []uint{ETH69, ETH68}`
(ETH69 primary, ETH68 still supported); `protocolLengths = {ETH68: 17, ETH69: 18}` — eth/68 has
17 message codes, eth/69 has 18 (the extra is `BlockRangeUpdateMsg`, eth/69-only).

**eth/68 message-code table** (`protocol.go:56–69` — the shared constant block; eth/68 uses all
except `BlockRangeUpdateMsg`):

| Code | Name | In eth/68? |
|------|------|-----------|
| `0x00` | `StatusMsg` | yes |
| `0x01` | `NewBlockHashesMsg` | yes (block propagation — dropped in eth/69) |
| `0x02` | `TransactionsMsg` | yes |
| `0x03` | `GetBlockHeadersMsg` | yes |
| `0x04` | `BlockHeadersMsg` | yes |
| `0x05` | `GetBlockBodiesMsg` | yes |
| `0x06` | `BlockBodiesMsg` | yes |
| `0x07` | `NewBlockMsg` | yes (block propagation — dropped in eth/69) |
| `0x08` | `NewPooledTransactionHashesMsg` | yes |
| `0x09` | `GetPooledTransactionsMsg` | yes |
| `0x0a` | `PooledTransactionsMsg` | yes |
| `0x0f` | `GetReceiptsMsg` | yes |
| `0x10` | `ReceiptsMsg` | yes |
| `0x11` | `BlockRangeUpdateMsg` | **no** — eth/69-only |

(Note the gap `0x0b`–`0x0e`: legacy `GetNodeData`/`NodeData`/older `GetReceipts`/`Receipts`
slots are unused; eth/68 places receipts at `0x0f`/`0x10`.)

**The defining EIP-5793 change — `NewPooledTransactionHashes` three parallel arrays**
(`protocol.go:285–289`). At `cee751a1e` the struct is named `NewPooledTransactionHashesPacket`
(not a `…Packet68` variant — it is shared by eth/68 and eth/69; the source comment reads
"represents a transaction announcement packet on eth/68 and newer"):

```go
// protocol.go:285–289
type NewPooledTransactionHashesPacket struct {
	Types  []byte         // per-tx transaction-type byte
	Sizes  []uint32       // per-tx encoded size
	Hashes []common.Hash  // per-tx hash
}
```

vs eth/67's hashes-only announcement. RLP encode/decode is struct-default (no custom
`EncodeRLP`/`DecodeRLP` on this type). The **equal-length sanity check** lives in the message
handler, `handlers.go:549–550`, inside `handleNewPooledTransactionHashes`:

```go
// handlers.go:549–550
if len(ann.Hashes) != len(ann.Types) || len(ann.Hashes) != len(ann.Sizes) {
	return fmt.Errorf("NewPooledTransactionHashes: invalid len of fields in %v %v %v",
		len(ann.Hashes), len(ann.Types), len(ann.Sizes))
}
```

A mismatched-length announcement tears down the peer connection (any non-nil handler error does).

**eth/68 handshake shape** — still the pre-range `StatusPacket68` (`protocol.go:91–98`), i.e. it
carries **total difficulty (`TD`) and `Head`, and has NONE of the range fields** eth/69 later
added:

```go
// protocol.go:91–98
type StatusPacket68 struct {
	ProtocolVersion uint32
	NetworkID       uint64
	TD              *big.Int
	Head            common.Hash
	Genesis         common.Hash
	ForkID          forkid.ID
}
```

The handshake is driven by `handshake68` (`handshake.go:49`) / `readStatus68`
(`handshake.go:75`); `Handshake` dispatches to `handshake68` for version < 69 and `handshake69`
otherwise (`handshake.go:41–43`). By contrast `StatusPacket69` (`protocol.go:100–108`) **drops
`TD`/`Head`** and **adds** `EarliestBlock`, `LatestBlock`, `LatestBlockHash` — those range fields
do **not** exist in the eth/68 handshake.

**Version dispatch (eth/68 alongside eth/69)** — `handler.go` keys a per-version handler map on
`peer.version`. Two maps exist: `eth68` (`handler.go:169–182`) and `eth69`
(`handler.go:184–197`). `handleMessage` selects between them (`handler.go:211–216`):

```go
// handler.go:211–216
var handlers map[uint64]msgHandler
if peer.version == ETH68 {
	handlers = eth68
} else if peer.version == ETH69 {
	handlers = eth69
} else {
	return fmt.Errorf("unknown eth protocol version: %v", peer.version)
}
```

then dispatches `handlers[msg.Code]` (`handler.go:232`). Differences visible in the two maps:
the `eth68` map still registers `NewBlockHashesMsg`/`NewBlockMsg` (block propagation) and binds
receipts to `handleGetReceipts68` / `handleReceipts[*ReceiptList68]`; the `eth69` map omits the
block-propagation handlers, binds receipts to the `…69` variants, and adds
`BlockRangeUpdateMsg: handleBlockRangeUpdate`.

### ETH69 — added `7e7925460` 2025-05-16 (no EIP number; protocol-level change)
- `7e7925460 eth/protocols/eth: implement eth/69 (#29158)` (2025-05-16). Two semantic deltas
  (commit body):
  1. **Drops the bloom filter from `Receipts` messages** — "reducing the amount of data needed
     for a sync by ~530GB (2.3B txs × 256 byte) uncompressed… compressed ~100GB." The bloom is
     recomputable locally, so it need not be sent on the wire.
  2. **Changes the `Status` handshake and introduces `BlockRangeUpdateMsg` (`0x11`)** — a new
     message relaying a peer's **available history range** (earliest/latest block). This is
     reflected today in `protocol.go`'s `StatusPacket` carrying `EarliestBlock`, `LatestBlock`,
     `LatestBlockHash` (protocol.go, StatusPacket struct) and the `errInvalidBlockRange`
     handshake error (`protocol.go:84`).
- ETH69 has no assigned EIP number in the commit — it is a protocol-maintenance version. (Note:
  fukuii's `herald` charter labels ETH70 "EIP-7706"; geth attributes ETH70 to **EIP-7975** — see
  fukuii-relevance section for this mismatch.)

### ETH70 — added `965bd6b6a` 2026-03-30 (EIP-7975)
- `965bd6b6a eth: implement EIP-7975 (eth/70 - partial block receipt lists) (#33153)`
  (2026-03-30). Delta: **partial block receipt lists.** A receipts response may now be
  *incomplete* for its last block — the peer buffers partial receipts (commit body: "buffered
  in the peer's receipt buffer when the `lastBlockIncomplete` field is true"; a continued
  request reuses the original request id via `RequestPartialReceipts`; partial responses
  verified in `validateLastBlockReceipt`). This lets a peer bound a single receipts response's
  size without failing the whole request.
- Message-code count unchanged from ETH69: `protocolLengths[ETH70] = 18` (snapshot at
  `965bd6b6a:eth/protocols/eth/protocol.go` — `{ETH69: 18, ETH70: 18}`). ETH70 reuses the
  ETH69 message set; the change is in the receipt-serving/response semantics, not new codes.

### ETH71 — added `a484a8506` 2026-05-19 (Block Access Lists exchange)
- `a484a8506 eth/protocols/eth: implement eth71 bal response (#34879)` (2026-05-19). Delta:
  the **Block Access List (BAL) exchange** message pair — `GetBlockAccessListsMsg = 0x12`,
  `BlockAccessListsMsg = 0x13` (`protocol.go:72-73`), raising ETH71's code count to **20**
  (`protocolLengths[ETH71] = 20`). Imports `core/types/bal` (`protocol.go`, import block).
- **Caveat from the commit body itself:** this PR implements only the **serving side** ("the
  serving side of the eth71 BAL exchange messages. Until commit `4cd7092` also contained the
  requesting side, but since that part still needs more work, I'm splitting it out"). So ETH71
  at HEAD is a **partial implementation** — BAL responses can be served but the requester side
  was deferred. The test "injects BALs directly into rawdb… removed once BAL generation is
  integrated into the chain maker." Treat ETH71 as in-progress, not finalized.

## Range advertising & negotiation mechanism

geth does **not** run a single-version handshake. It advertises the full
`ProtocolVersions` set and negotiates per connection:

- **Advertise the whole set.** `eth/protocols/eth/handler.go:108-134` — `MakeProtocols`
  iterates `for _, version := range ProtocolVersions` and appends one `p2p.Protocol{Name:
  "eth", Version: version, Length: protocolLengths[version], …}` per version. Each becomes a
  devp2p capability (`eth/71`, `eth/70`, `eth/69`) in the RLPx Hello.
- **Negotiate highest-common.** `p2p/peer.go:437-459` `matchProtocols` sorts the remote's
  advertised caps ascending (`slices.SortFunc(caps, Cap.Cmp)`) then walks them, so the
  **last (highest) version present in both local and remote cap sets wins** — effectively
  `min(max_local, max_remote)` over the shared name. The winning protocol's `Length` sets the
  per-name message-code offset window; an old match is reverted (`offset -= old.Length`) if a
  higher one is found later in the sort. This is geth's equivalent of a range advertisement:
  local range × remote range → single agreed version, chosen highest.
- One `Cap{Name, Version}` per version (`p2p/protocol.go:69`), so a peer speaking only eth/69
  and a peer speaking eth/69/70/71 will settle on eth/69 automatically — no version can be
  "skipped": the message-code layout for each version is disjoint by construction.

## Deprecation cadence

From the add/drop commit pairs (all on `protocol.go`):

| Version | Added | Removed | Lifespan | Drop commit |
|---------|-------|---------|----------|-------------|
| eth/66 | (pre-window) | 2023-10-03 | — | `bc6d18487 … drop eth/66 (#28239)` |
| eth/67 | 2022-06-15 (`30602163d`) | 2024-02-08 | ~1.7 yr | `8a76a814a … drop support for eth/67 (#28956)` |
| eth/68 | 2022-10-31 (`b0d44338b`) | 2026-02-28 | ~3.3 yr | `723aae2b4 … drop protocol version eth/68 (#33511)` |
| eth/69 | 2025-05-16 (`7e7925460`) | — (current) | live | — |
| eth/70 | 2026-03-30 (`965bd6b6a`) | — (current) | live | — |
| eth/71 | 2026-05-19 (`a484a8506`) | — (current, partial) | live | — |

Pattern: geth keeps a **small rolling window of ~2–3 concurrently advertised versions** and
removes the oldest only after the network has broadly migrated. The window is not a fixed
number of releases; eth/68 lived ~3.3 years while eth/67 lived ~1.7 — removal is driven by
"almost nobody speaks the old version and dropping it simplifies the code" (the explicit
justification in `723aae2b4`), not a timer. Note the **momentary collapse to a single
advertised version** after `723aae2b4` (only eth/69) before eth/70 was added a month later —
so the window occasionally shrinks to one.

## Other clients — adoption timeline (to be appended per networking-p2p slot)

Per-client wire-version adoption will be filled in as each client's networking-p2p SR slot is
documented. Stub roster:

- **besu** — **documented.** Advertises **ETH68/69/70/71 simultaneously** via a generic
  `CapabilityMultiplexer` (name → disjoint message-code RangeMap): adding a version = 1 constant
  + 1 message list + 1 switch arm. Per-connection, per-protocol-name highest-version pinning —
  the version range lives in the `SubProtocol`, not the transport. See
  `docs/research/clients/besu/networking-p2p.md` (esp. `CapabilityMultiplexer.java:32-49,118-145`)
  and `initial-assessment.md:52-53` (§1f).
- **core-geth** — TBD (PoW/ETC authority; ETC wire may lag geth's PoS-era versions — ETC does
  not need blob/BAL messaging).
- **erigon** — TBD.
- **nethermind** — TBD.
- **reth** — TBD.

## fukuii relevance / Phase-4 seed

- **fukuii's `herald` charter targets ETH68 / ETH69 / ETH70** ("ETH63–67 are removed"). geth
  HEAD has **already removed ETH68 and added ETH71** — so fukuii's advertised set (68/69/70) and
  geth's (69/70/71) now **differ at both ends**: fukuii still carries a version geth dropped
  (68) and lacks the newest (71). This is an explicit **parity delta to flag as a Phase-4 seed.**
- **fukuii DELIBERATELY retains ETH68** for broader peer-compatibility — geth's `723aae2b4`
  removal is a divergence fukuii does **not** follow. geth dropped 68 to simplify its receipt
  encoding path (it now transforms network→database receipt encoding without an intermediate
  decode), which is a geth-internal cleanup, not a wire-compat requirement; peers that still speak
  only eth/68 (older/other clients that have not upgraded to eth/69) remain reachable by fukuii.
  The **"ETH68 implementation detail"** block under `### ETH68` above preserves the geth
  `cee751a1e` pre-removal datapoints (message-code table, the `NewPooledTransactionHashesPacket`
  Types/Sizes/Hashes struct + equal-length check, the `StatusPacket68` handshake shape, and the
  `peer.version == ETH68` dispatch) needed to maintain fukuii's ETH68 support after geth deleted
  the reference source.
- **Attribution mismatch to resolve:** the herald charter labels **ETH70 as "EIP-7706"**, but
  geth attributes ETH70 to **EIP-7975 (partial block receipt lists)** (`965bd6b6a`). Whichever
  fukuii intends, the herald doc's EIP tag should be reconciled against geth's canonical mapping
  before implementing ETH70 wire support.
- **Structural seed (matches initial-assessment §1f / §2.2).** fukuii currently collapses wire
  negotiation to a single `Option[Capability]` via `best()` + a SNAP bolt-on boolean, whereas
  both geth (`ProtocolVersions` list + `MakeProtocols` loop + `matchProtocols` highest-common)
  and besu (`CapabilityMultiplexer`) advertise a *range* and negotiate per connection. The
  Phase-4 target is a **CapabilityMultiplexer-style range-advertising layer** so fukuii can carry
  eth/69, eth/70, eth/71 (and ETC-side versions) concurrently and negotiate highest-common per
  peer, instead of pinning one version. Reference: `initial-assessment.md:136` ("CapabilityMultiplexer-style
  wire-version multiplexer — replace `best()`-collapse + SNAP-bolt-on").
- **ETC-specific note:** ETH70 (partial receipts) and ETH71 (Block Access Lists) are ETH/PoS-era
  bandwidth/statelessness optimizations. Whether ETC/Mordor needs them is a separate question —
  core-geth's actual advertised set (TBD above) is the authority for the PoW side, not geth HEAD.
