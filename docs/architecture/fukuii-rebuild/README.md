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

| Doc | Covers | Commit |
|---|---|---|
| [`00-repo-setup.md`](00-repo-setup.md) | Clean-slate: layering, build config, dep floor, sbt-2, keep/clear | `7d2d2ae72` |
| [`01-L0-primitives.md`](01-L0-primitives.md) | `bytes`, `common` — value types + byte utils | `b8c064ef6` |
| [`02-L0-rlp.md`](02-L0-rlp.md) | `rlp` — working `derives` codecs + byte engine | `fd29ca578` |
| [`03-L0-crypto.md`](03-L0-crypto.md) | `crypto` — keccak, secp256k1 ECDSA, alt-bn128, ECIES | — |
| `04-L1-domain.md` | `domain` — (pending) | — |
| … | L2→L10 as built | — |

Each doc records: **scope**, **design decisions** with **empirical logic** (which observation
DEFAULT / which reference client), **improvements over old fukuii**, and a **Layer boundaries**
section — durable placement decisions (what lives at a *different* layer and why).

**Build status lives ONLY in the index table above (the commit-sha column).** Per-layer docs must
never carry "what's built / not-yet-built / deferred" status — that goes stale within a commit (a
sibling module lands and the note is instantly wrong). Design and layer-boundaries are durable;
build-status is single-sourced. (See the `docs-future-proof` rule.)
