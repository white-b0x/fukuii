# Observations — cross-cutting themes (Phase-2 capstone)

_Phase-2 synthesis 2026-07-13. Two themes that cut ACROSS the 20 per-subsystem observations rather than living
in any one. These are the strategic payoff of the reference-client review — they inform HOW fukuii should be
built, not just what each subsystem does. Forward-refs to Phase 3–4; do not act here._

---

## Theme 1 — CSP / JVM structure → the Pekko-Typed migration target

**The finding: no reference client uses an actor model, so there is NO Classic→Typed migration framework to
import.** geth/core-geth/erigon use Go goroutines + channels (CSP); nethermind uses C# `async`/DI; reth uses
Rust `tokio`; besu uses Vert.x + plain service objects. The migration *mechanics* are fukuii's own — encoded in
`loom` + `.agents/protocols/pekko-typed-api.md` + `scala3-given-migration.md`. fukuii's only actor-based EVM
ancestor is **Mantis** (Akka Classic), which is precisely what it is migrating *away* from. **Do not expect the
SR to hand over a migration guide — you already have it.**

**What the clients DO give: the target architecture the Typed migration should adopt.** The migration is
fukuii's one chance to re-draw actor boundaries onto a production-grade shape rather than preserving Mantis's
Akka-Classic structure. The mapping (each row traces to a per-subsystem observation):

| Reference pattern (source observation) | What it teaches the Typed migration |
|---|---|
| **geth/erigon channel-ownership** — a single goroutine exclusively owns state, no locks (`networking-p2p`, `sync`) | The litmus for **actor granularity**: "what one goroutine exclusively owns" = what one Typed actor's private state should be. Pekko Typed's one-actor-owns-state *is* CSP channel-ownership. |
| **besu `ServiceManager` + constructor-injected services** (`node-lifecycle`) | Migrate dependencies to **explicit typed `ActorRef[Command]` handles passed at spawn** (constructor injection), not late `context.actorSelection`/global lookup. The counter to nethermind's retiring `INethermindApi` god-context. |
| **besu Lifecycle FSM** — register→start→stop, `checkState`-guarded (`node-lifecycle`) | Encode lifecycle as **distinct Behaviors** (behavior-as-state-machine, return the next `Behavior` per state) — Pekko Typed makes besu's guarded FSM idiomatic instead of a runtime enum check. |
| **besu `ProtocolSpec` immutable per-fork bundle** actors *reference* (`block-execution`, `consensus-engines`) | Keep consensus behavior in **immutable value objects** the actor references; hold no mutable fork state in the actor. |
| **besu/geth typed message DTOs** (JsonRpcMethod, typed wire packets) (`rpc-api`, `networking-p2p`) | The core of the migration: every interaction a **sealed Command ADT + explicit `replyTo`**, replacing `Any` + `sender()`. |

**Adjacent modernization the SR pulls in (per the SR↔dev integration directive in QUEUE.md):**
- **sbt / build**: besu's versionless-submodule + central BOM (`build-deps`) → `project/Dependencies.scala` as the
  single version source; besu's `verification-metadata.xml` sha256 gate ≈ the supply-chain `resolution-age` rule.
- **Effect systems (Cats Effect)**: NOT in any reference client (Go/C#/Rust/Java are imperative/async), so CE3/fs2
  patterns come from the Typelevel ecosystem (`docs/research/best-practices/typelevel/` + TL1/TL2 in
  `pekko-typed-api.md`), not the EVM clients.

**The risk to avoid:** treating the Typed migration as a 1:1 port of Classic actors. The SR says re-draw actor
boundaries onto the **besu-service / geth-channel-ownership** model. **Phase-4 sequencing:** the Classic→Typed
remainder (Batch 8 Theme C, MOD-13) + the Scala 3 dep bumps (Theme D + MOD-19 waves) are the **build/idiom floor**
each subsystem's green-light folds in — done as a PREREQ where a subsystem needs a newer base.

---

## Theme 2 — the gRPC service seam = product-family decomposition AND the dRPC bridge

**The finding: one seam serves two strategic goals at once.** The gRPC service boundary that lets erigon run
components as separate processes is the *same* boundary that lets a node be a Provider in a decentralized-RPC
network. Two Phase-4 themes that looked separate collapse into one: **"expose a clean gRPC service seam."**

**The evidence chain (each traces to a per-subsystem observation):**
- **erigon internal decomposition** (`networking-p2p` Sentry, `rpc-api` RPCDaemon+remote-KV, `txpool`
  txpool-as-gRPC-service, `exec-extensions` StateChanges, `node-lifecycle` embedded-or-remote wiring) — every
  heavy component is a gRPC service with **"one interface, two impls: in-proc `direct.*` shim / remote gRPC
  client."** Same binary runs monolith OR distributed; topology is a wiring choice at one function.
- **reth ExEx** (`exec-extensions`) — the flagship data-product framework: reorg-aware notifications +
  `FinishedHeight` **distributed prune barrier** (node gates irreversible cleanup on `min(consumer heights)`) +
  WAL for restart reorg-safety. This is the **backpressure + reorg-safety** besu's synchronous `BesuEvents`
  callbacks lack — the design fukuii's data-seam should adopt.
- **drpc.org architecture** (`User —JSON-RPC→ Dproxy —gRPC→ Main Dshackle —gRPC→ Providers`) — the data plane
  after the edge is gRPC end-to-end; a "Provider" is a node exposing a gRPC service. **erigon's internal gRPC
  decomposition IS the drpc.org Provider architecture.**

**The synthesis for fukuii:** if the node grows a **first-class gRPC data-seam** alongside conduit's JSON-RPC
(`rpc-api`), it becomes drpc-Provider-native AND internally decomposable in one move. The seam should carry
reth's ExEx design (reorg-aware + `FinishedHeight` backpressure + WAL) and be realized cross-process the way
erigon does `StateChanges` (push diffs + remote-KV pull on one service → an indexer/Provider gets push+pull).

**The two concrete deliverables this unlocks (now schedulable in `queue/chase-deferred.md`):**
- **`DRPC-GATEWAY-01`** — the dRPC gateway = the 5th product-family component. fukuii is an **UPSTREAM to
  Dshackle, NOT a host of it** — implement Dshackle's `Blockchain.proto` (`NativeCall` = generic JSON-RPC
  forward + status subscriptions) as a **thin Provider adapter** over conduit's existing JSON-RPC + health/status
  signals. Gated on this exec-extensions gRPC-seam Phase-4 work.
- The internal product-family seams (pool-software / validator-software / GUI consume the node's clean seams)
  — the same gRPC boundary.

**The nuance to keep straight:** same *pattern* (gRPC service boundary), NOT same *interface*. erigon's protos
(remote-KV, Sentry) are internal-component IPC; Dshackle's `Blockchain.proto` is a chain-agnostic
RPC-forwarding + status contract. fukuii implements Dshackle's proto as the Provider adapter and may *separately*
use erigon-style gRPC for internal component separation. Unifying principle: **expose gRPC seams**, realized as
two concrete protos.

---

## Where these land in Phase 3–4
- Theme 1 (Typed target) governs the **structure** of every subsystem's Phase-4 green-light — actor boundaries,
  DI, immutability, lifecycle — folding in the Classic→Typed + dep-bump floor per the SR↔dev directive.
- Theme 2 (gRPC seam) is a **Phase-4 architectural seed** concentrated in `exec-extensions` / `rpc-api` /
  `networking-p2p` / `node-lifecycle`, and the precondition for `DRPC-GATEWAY-01` and the product-family split.
