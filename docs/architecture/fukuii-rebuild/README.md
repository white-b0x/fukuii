# fukuii rebuild — architecture record

The layer-by-layer design record of fukuii's from-scratch rebuild. **Each layer is documented at
completion, before its commit** — capturing the design decisions, the *empirical logic* behind
them (grounded in the reference-client Systemic Review, not preference), and the concrete
improvements over the old (pre-rebuild) fukuii.

## Why the rebuild

fukuii's pre-rebuild codebase was a fork of IOHK **Mantis** (Akka Classic), carried forward
piecemeal. A full reference-client Systemic Review (see [`docs/research/clients/`](../../research/clients/))
documented fukuii as a 7th client against go-ethereum, core-geth, besu, erigon, nethermind, and
reth, then measured it against the cross-client best-practice synthesis. The verdict:

- **Incomplete, not deliberate.** The dominant pattern was *built-but-unshipped* — the modern
  mechanism scaffolded next to a legacy one, cutover never finished (e.g. a `derives`-based RLP
  codec that existed but was unwired and didn't compile; a consensus engine-marker layer built but
  carried in zero shipped configs).
- **A 13-package dependency cycle.** The core packages formed one strongly-connected component —
  `domain` imported *up* into `db`/`mpt`/`ledger`/`jsonrpc`/`network`; `db ↔ mpt`, `ledger ↔
  consensus`, `network ↔ blockchain` all cyclic. You could not build or reason about one piece
  without dragging in everything.
- **Stale idioms on a modern base.** Already on Scala 3, but with Scala-2-era idioms (`implicit
  class`/`implicit def`) and Pekko-Classic remnants; the build still on sbt 1.x.

fukuii is pre-1.0 with **no users and no production deployments** — the one window to build it
correctly. So this is a **clean write**, not a migration: the old code is a *reference* (read to
get behavior and byte-values right), then left behind. It is preserved on branch `july-fourth`.

## Method

Authority is **per-network and per-concern, not per-client** — fukuii is a **multi-network framework**
(many networks across consensus families PoW/PoS/PoA…; ETC/Mordor and ETH/Sepolia are today's
instances, not the ceiling — "not if/else"), so each design decision draws on the source that owns
*that concern for that network*. Canonical statement: the `reference-client-authority` memory +
`systemic-review-protocol.md`'s authority model.

| Source | Answers | Where |
|---|---|---|
| **besu** — the **ETC/ETH JVM implementation guide** (maintained JVM client, 9 yrs of ETC + current ETH) | *"how do I build this correctly on the JVM?"* — besu's **Java transfers to fukuii's Scala/JVM constraints** (BouncyCastle, JNI native libs, big-int towers, no native `uint256`) where go-ethereum's Go idioms often don't. **Read besu's Java alongside geth's Go, from line one.** Also the structural mirror (ProtocolSpec, ServiceManager DI, Lifecycle FSM) and a second *maintained* behavioral cross-check | `.claude/repo-references/clients/besu/`, `docs/research/clients/besu/` |
| **go-ethereum + besu** (shared behavior) · **core-geth** (ETC-frozen only) | *"what is the correct byte-value?"* — **shared EVM/RLP/crypto → go-ethereum + besu together** (both maintained, must agree); **ETC-specific frozen values + fork level → core-geth** (FROZEN/deprecated Sept-2024, only ECIP-1017/1099/1100 & the fork schedule); **ETH-family → go-ethereum** | authority model in the SR / memory `reference-client-authority` |
| **`observations/{slot}.md`** | *"which approach is DEFAULT vs OPTIONAL(role) vs OBSOLETE?"* | `docs/research/clients/observations/` |

The **idiom** is native to us: **Scala 3** (opaque types, `given`/`using`, `extension`, enums —
never `implicit class`/`implicit def`) and **Pekko Typed** (sealed `Command` ADT + `replyTo`, one
actor owns its state, behavior-as-state-machine). No reference client uses actors, so the JVM
clients inform the *target structure* while the actor *idiom* is ours (see
`observations/cross-cutting-themes.md`).

## The layering

Modules form a **directed acyclic dependency graph** — every `.dependsOn` edge points *down* a
layer; an upward edge is a **compile error** (enforced by sbt module boundaries). This is the
structural fix for the old SCC: the cycle cannot re-form because it would not compile.

```
L0  bytes · common · crypto→bytes · rlp→bytes           (foundation)
L1  domain → bytes,crypto,rlp,common                    (pure value types)
L2  storage → domain,common ·  trie → domain,crypto,storage
L3  evm → domain,crypto,rlp
L4  execution → evm,trie,storage,domain
L5  consensus → execution,evm,domain
L6  network → domain,crypto,rlp,common
L7  sync → network,consensus,execution,storage,trie
L9  rpc → domain,execution,consensus,sync,network,storage
L10 node → aggregates + dependsOn all                   (composition root)
```

Namespace `com.chipprbots.fukuii.*`; modules under `modules/<name>/`. Build on **Scala 3.3.8 LTS /
JDK 25**. The full target with rationale and the per-subsystem port/rewrite/write-new dispositions
lives in `.local/docs/phase4/target-architecture.md` and `_phase3-findings-rollup.md`.

## Documents (one per layer, in build order)

The tree has three parts: **`plan/`** — forward-looking layer designs (`L0.md`–`L10.md`), their per-item
RX verification (`plan/rx/`), the cross-layer coherence passes (`plan/coherence-pass-0N.md`), and the
`requirements`/`optimizations`/`feature-ledger` registries; **`implementation-reports/`** — the **as-built
records** (`NN-L{n}-*.md`) landed *after* a layer is built, plus `00-repo-setup.md` and the `L0-review.md`
gate; and **`research-index.md`** — the research-asset→layer map. Lifecycle: **plan → build → gate →
record** (`plan/L{n}.md` is intent; `implementation-reports/NN-L{n}-*.md` is what was actually built).

| Doc (in `implementation-reports/`) | Covers | Commit |
|---|---|---|
| [`00-repo-setup.md`](implementation-reports/00-repo-setup.md) | Clean-slate: layering, build config, dep floor, sbt-2, keep/clear | `dc7e32c61`…`39e8fd6ed` |
| [`01-L0-foundation.md`](implementation-reports/01-L0-foundation.md) | `bytes`, `common`, `crypto`, `rlp` — value types + byte utils, zero-cast `derives` RLP engine, keccak / secp256k1 ECDSA / alt-bn128 / ECIES / KZG / BLS, `CryptoBackend` seam, `constantTimeEquals` | `77da1da62`, `6f922f0aa`, `16502d72d` |
| [`02-L1-domain.md`](implementation-reports/02-L1-domain.md) | `domain` — value types, `enum Transaction` (5 EIP-2718 variants + dispatch), sender recovery (EIP-155 + H-1 homestead + N-1 gate + 7702 authority), fork-variant `BlockHeader` (open tail) + Block/Body + Receipt | `5972aed58`…`494c42333` |
| [`03-L2-storage-trie.md`](implementation-reports/03-L2-storage-trie.md) | `storage`, `trie` — MPT (`enum MptNode`, state-root core), `DataSource`/RocksDB + `enum Namespace`, `INodeStorage` scheme-indirection `(owner,path)`, `StorageProfile`, `ChainWeight` + BUG-W7 atomic write, composable pruning, `TrieLog`, `ColdStore`/era1 | `1cfb8e09f`…`54b51a4b7` |
| … | L3→L10 as built | — |

**Note — commit SHAs are the `fukuii-rebuild` curated foundation series.** L0 was first built on the
`july-mod-sprint` planning branch, then the clean cut off `upstream/staging` collapsed that granular
history into an ordered foundation series (see [`plan/migration-runbook.md`](plan/migration-runbook.md)),
so the per-doc SHAs above are coarser than the original per-file commits (all L0 modules land in the
foundation `77da1da62`). L0 close-out also carries `735b0607a` (sbt-2 test-gate fix) and `b8c4040a8`
(scalafmt reflow to current config). The pre-cut SHAs are preserved on `july-mod-sprint` /
`backup/july-mod-sprint-pre-rebuild`.

Each doc records: **scope**, **design decisions** with **empirical logic** (which observation
DEFAULT / which reference client), **improvements over old fukuii**, and a **Layer boundaries**
section — durable placement decisions (what lives at a *different* layer and why).

**Build status lives ONLY in the index table above (the commit-sha column).** Per-layer docs must
never carry "what's built / not-yet-built / deferred" status — that goes stale within a commit (a
sibling module lands and the note is instantly wrong). Design and layer-boundaries are durable;
build-status is single-sourced. (See the `docs-future-proof` rule.)
