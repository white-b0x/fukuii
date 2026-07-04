# Best Practices: P2P Patterns

Synthesized from go-ethereum, Besu, core-geth, and Fukuii.

---

## 1. ETC Chain Identity (Critical Constants)

| Field | Value | Source |
|-------|-------|--------|
| `networkId` | **1** (NOT 61) | core-geth `params/config_classic.go` |
| `chainId` | 61 | EIP-155 |
| `forkId` | `0xbe46d57c` (post-Spiral, block 19.25M, Next=0) | core-geth |
| ETH protocol version | **ETH68** | core-geth SupportedProtocolVersions = `[]uint{68}` |
| TD | Required (PoW chain selection) | Must be included in ETH68 Status |

**Why networkId=1 (not 61):** ETC split from ETH before EIP-155. The original chain
(which became ETC) retained networkId=1. 61 is chainId, not networkId. Distinguishing
ETC from ETH peers requires `forkId` — `networkId` alone is insufficient.

**Why ETH68 only (not ETH69):** ETH69 drops TD from Status. ETC needs TD for PoW chain
selection — TD is the canonical "heaviest chain" metric for Nakamoto consensus.
core-geth intentionally excludes ETH69.

---

## 2. ForkID as the Primary Peer Filter

The `forkId` field in Status is the correct mechanism for filtering ETC-vs-ETH peers:

```
ETC mainnet: 0xbe46d57c (post-Spiral)
ETH mainnet: 0x<different>
Mordor:      0x<testnet-specific>
```

**Filter order:**
1. `networkId` check (eliminates most non-Ethereum networks)
2. `genesisHash` check (eliminates different-genesis chains)
3. `forkId` check (distinguishes ETC from ETH, which share networkId=1)

---

## 3. SNAP Capability Detection

**During Status exchange:**
- Check if peer advertised `snap/1` in RLPx capability negotiation
- Set `remoteStatus.supportsSnap = true` if yes
- Never attempt snap requests to non-snap peers

**Global subscription for snap request serving:**
```
Subscribe(MessageClassifier(snap request codes, PeerSelector.AllPeers))
```
Reason: Hive devp2p test client sends snap requests before ETH-Status exchange completes.
Without the global subscription, early requests are silently dropped.

---

## 4. No Unsolicited GetBlockHeaders After Handshake

**Pattern (go-ethereum, Fukuii):**
After Status exchange, do NOT immediately send `GetBlockHeaders` to the peer.

**Why:**
- For ETH69 peers: `latestBlock`/`latestBlockHash` are already in Status (no fetch needed)
- For ETH64+: `forkId` in Status provides chain validation without headers
- Hive `TestStatus` test: sending a second Status after the first → disconnect.
  Unsolicited GetBlockHeaders violates expected message flow.

Block headers are fetched by the sync engine (`BlockFetcher`/`HeadersFetcher`) when needed.

---

## 5. Peer Lifecycle Event Handling

**Handshake success:**
1. Log at INFO level: capability, networkId, forkAccepted, supportsSnap
2. Subscribe to per-peer disconnect + message events
3. Add to `peersWithInfo`
4. Do NOT dispatch requests immediately (let sync engine decide when to use this peer)

**Disconnect:**
1. Unsubscribe per-peer events
2. Remove from `peersWithInfo`
3. Cancel all in-flight SNAP requests for this peer (critical — see BUG-W8)
4. Re-queue cancelled tasks

**Why cancel immediately on disconnect (not on timeout):**
- Peer is gone; its in-flight requests will never be answered
- Each timed-out request increments consecutive-timeout counter
- At threshold, sync engine concludes "all peers stalled" and shuts down → BUG-W8

---

## 6. Non-SNAP Peer Eviction

**Pattern (Fukuii: startSnapPeerEviction):**
Periodically evict non-SNAP peers to prevent them from filling all available peer slots.

**Why:** If all 25 slots are occupied by ETH-only peers, SNAP sync stalls even if SNAP-capable
peers are available on the network. Active eviction makes room for SNAP peers.

**Rate:** Don't evict aggressively — give newly connected peers time to complete handshake
and announce snap capability. Fukuii uses a grace period before marking a connected peer
as "non-snap" for eviction purposes.

---

## 7. Snapless vs. Stateless Peer Classification

Two distinct failure modes that require different handling:

| Type | Detection | Cleared | Cause |
|------|-----------|---------|-------|
| **Stateless** | Empty response for current root | On pivot refresh | Peer's snapshot doesn't include root |
| **Snapless** | Protocol-level failure, capability mismatch | Never (session) | Peer advertises snap/1 but can't serve (e.g., `--syncmode full`) |

**Common example of snapless peer:** core-geth nodes running `--syncmode full` include
`snap/1` in their capability list (because snap server is registered) but have no snapshot
data to serve. These peers will fail immediately on any snap request.

---

## 8. 60-Second Network Summary

Log a periodic summary to detect health issues:
```
Network [60s]: active=N peers (M snap-capable), +X tcp-failed, +Y auth-failed, 
               +Z auth-timeout, +W empty-headers
```

**Key metric:** if `snap-capable` drops to 0, SNAP sync will stall within minutes.
This is the primary early warning for SNAP sync degradation.

---

## Cross-References
- go-ethereum peer lifecycle: `../ref-clients/go-ethereum/p2p/peer-lifecycle.md`
- go-ethereum peer discovery: `../ref-clients/go-ethereum/p2p/peer-discovery.md`
- Besu peer management: `../ref-clients/besu/p2p/peer-lifecycle.md`
- core-geth ETC constants: `../ref-clients/core-geth/fork-id-etc.md`
- Fukuii implementation: `../fukuii-state/p2p/peer-management.md`
