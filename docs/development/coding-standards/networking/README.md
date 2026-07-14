# Networking domain — scope stub

**Scope:** Code-shape conventions for P2P/RLPx/ETH-wire-protocol code — handshake
structure, Snappy framing, message-codec conventions, multi-client interop shape
(distinguishing "our client is wrong" from "the wire format is genuinely ambiguous"), and
peer-lifecycle policy (disconnect handling, blacklist/ban tiers, peer reputation).

**Owning specialist:** `herald`.

**Authority:** ECIPs/EIPs network-layer specs under `.claude/repo-references/`; reference
clients under `.claude/repo-references/clients/{go-ethereum,core-geth,nethermind,besu,
erigon,reth}` for cross-client wire-format and peer-policy comparison.

## Content in this directory

| File | Status |
|---|---|
| `peer-disconnect-blacklist.md` | **DRAFT — pending operator ratification.** Peer disconnect/blacklist/ban tier policy across all wire `Disconnect.Reasons`, the self-detected-vs-received trust-direction axis, and NETWORK-03's self-detected `BreachOfProtocol` wiring. Evidence base: `docs/research/best-practices/evm-clients/peer-disconnect-blacklist-policy.md` |

**Status (remaining scope):** handshake/Snappy/message-codec conventions not yet
authored — net new. First content for those expected via `../README.md`'s "commit-log
mining" / "new synthesis" intake paths, owned by `herald`.
