# erigon — txpool
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

erigon's txpool is a **self-contained module that is also a standalone gRPC service**. The
same code (`txnprovider/txpool/`) links into the monolithic `erigon` binary as an in-process
`TxnProvider`, or compiles into `cmd/txpool` as an **independent OS process** that talks to
the rest of the node exclusively over gRPC. It is one of erigon's canonical
"separable component" binaries (alongside the Sentry P2P daemon, the `rpcdaemon`, and the
Core/`private.api` KV service).

The txpool consumes three feeds and exposes one:

- **Consumes gossip from Sentry** — one or more `sentryproto.SentryClient` connections. It
  never opens sockets to peers itself; the Sentry daemon owns devp2p/RLPx and hands the pool
  a decoded message stream (`Fetch.receiveMessage`) and an outbound send path
  (`Send.BroadcastPooledTxns` / `AnnouncePooledTxns`).
- **Consumes chain state from Core** — a remote KV database (`remotedb.NewRemote`) plus a
  `StateChanges` stream (`remoteproto.StateChangeRequest`) that pushes new-block
  nonce/balance deltas, and an `ETHBACKEND` client for extras (AA validation, mining notify).
  The pool holds **no local execution state**; it reads sender nonce/balance through a
  coherent cache (`kvcache.Cache`) backed by that remote DB.
- **Exposes a `txpool.Txpool` gRPC service** — `Add`, `Pending`, `All`, `Nonce`, `Status`,
  `Transactions`, `FindUnknown`, `GetBlobs`, and a streaming `OnAdd` subscription. This is
  the seam the `rpcdaemon` (for `eth_sendRawTransaction`, `txpool_content`, `eth_getTransactionByHash`
  fallback) and the block builder consume.

Internally the pool is the classic three-sub-pool structure (`pending`, `baseFee`, `queued`),
but sorted by a 5-bit **`SubPoolMarker`** validity bitset rather than ad-hoc rules, with
promotion/demotion recomputed on every new block.

## Key types / interfaces / files

- `node/interfaces/txpool/txpool.proto:96` — `service Txpool { … }`, the entire service
  boundary. Note `Add` is documented as adding *local* txns (line 101-103); remote txns enter
  only via P2P/Sentry. `AllReply.TxnType` (line 46-50) exposes the three sub-pools —
  `PENDING` / `QUEUED` / `BASE_FEE` — over the wire.
- `node/interfaces/txpool/mining.proto` — sibling `Mining`/`Txpool` service the block builder
  uses (`GetWork`, pending-block assembly); `cmd/txpool` starts this alongside the txpool
  service (`main.go:203-204`).
- `txnprovider/txpool/txpool_grpc_server.go:104` — `type GrpcServer struct`, the adapter that
  wraps the in-memory `txPool` interface (line 50-61, a *narrower* interface than `Pool`) as
  the proto service. `GrpcServer.Add` → `txPool.AddLocalTxns`; `Pending` → `PeekBest`.
- `txnprovider/txpool/txpool_grpc_server.go:66-102` — `GrpcDisabled`, a full no-op
  implementation returning `ErrPoolDisabled` for every method — the "pool off but seam still
  present" mode. Compile-time interface checks at line 63-64 keep both impls honest.
- `txnprovider/txpool/txpool_grpc_server.go:328` — `StartGrpc(...)`, binds the listener,
  installs TLS creds + a panic-recovery interceptor + a gRPC health service + reflection.
- `txnprovider/txpool/pool.go:74` — `type Pool interface`, the in-process contract. Comment
  (line 76): "Handle 3 main events — new remote txns from p2p, new local txns from RPC, new
  blocks from execution layer" → `AddRemoteTxns`, `AddLocalTxns`, `OnNewBlock`.
- `txnprovider/txpool/pool.go:108` — `type TxPool struct`. Holds `pending *PendingPool`,
  `baseFee *SubPool`, `queued *SubPool`, `all *BySenderAndNonce`, the `_stateCache`, and the
  `p2pFetcher *Fetch` / `p2pSender *Send`. Design comments (line 100-107): "txpool doesn't
  start any goroutines — leave concurrency to user", "no DB-TX fields — leave db transaction
  management to user".
- `txnprovider/txpool/assemble.go:36` — `Assemble(...)`, the one constructor that both the
  monolith and `cmd/txpool` call. Returns `(*TxPool, txpoolproto.TxpoolServer, error)` — the
  concrete pool *and* its gRPC front — from the same call. `defaultPoolDBInitializer`
  (line 89) opens the pool's own MDBX DB (persists across restarts so unmined txns survive).
- `txnprovider/txpool/fetch.go:63` — `type Fetch struct` + `NewFetch`. `ConnectSentries`
  (line 252) starts a `receiveMessageLoop` per Sentry client; `ConnectCore` (line 266) starts
  `handleStateChanges` against the Core KV `StateChanges` stream (line 769-772). This is the
  gossip-in + state-in wiring.
- `txnprovider/txpool/send.go:34` — `type Send struct` + `BroadcastPooledTxns` (line 64) /
  `AnnouncePooledTxns` (line 108): gossip-out, again through Sentry, honoring
  `cfg.NoGossip`.
- `txnprovider/txpool/sub_pool.go:24-64` — `SubPoolType` (`Pending`/`BaseFee`/`Queued`) and
  the `SubPoolMarker` bitset: `NoNonceGaps`, `EnoughBalance`, `NotTooMuchGas`,
  `EnoughFeeCapBlock`, `IsLocal`, with `BaseFeePoolBits = NoNonceGaps + EnoughBalance +
  NotTooMuchGas`. A txn's sub-pool is a pure function of its marker bits.
- `txnprovider/txpool/pending_pool.go` / `queues.go` — the heaps: `PendingPool` keeps a
  `BestQueue`/`WorstQueue` pair (min/max by mining priority) so `Best`/`PopWorst` are O(log n).
- `txnprovider/txpool/pool.go:959` — `validateTx(...)`, admission gate.
- `txnprovider/txpool/pool.go:1697` — `addLocked(...)`, insertion + same-(sender,nonce)
  replacement.
- `txnprovider/txpool/pool.go:2211` — `promote(...)`, the sub-pool re-shuffle + eviction.
- `txnprovider/txpool/pool.go:2276` — `Run(ctx)`, the single owning goroutine's event loop.
- `cmd/txpool/main.go:126` — `doTxpool(...)`, the standalone-process entrypoint (see below).
- `txnprovider/txpool/txpoolcfg/` — `Config` (limits, price floors, gossip toggle) and the
  `DiscardReason` enum used both internally and mapped to proto `ImportResult`.

## Design decisions & rationale

**One `Assemble` → concrete pool + gRPC server, always.** Whether embedded or standalone, the
node builds the pool identically and gets back a `txpoolproto.TxpoolServer`. The monolith
serves that server over a loopback/direct channel; `cmd/txpool` serves it over a real socket.
There is exactly one code path — the seam is a deployment choice, not a fork.

**The standalone process is pure client wiring** (`cmd/txpool/main.go:126-216`): connect to
`--private.api.addr` (Core: `ETHBACKEND` + KV state), connect to each `--sentry.api.addr`
(gossip), `txpool.Assemble(...)`, `txpool.StartGrpc(...)` on `--txpool.api.addr`, then
`txPool.Run(ctx)`. The pool needs *nothing* from the execution engine except the state DB and
the state-change stream — proof the dependency surface is genuinely narrow.

**No local execution state; read chain state remotely through a coherent cache.** The pool
never re-executes; it validates against sender nonce/balance fetched from Core's KV via
`kvcache.Cache`, invalidated by the `StateChanges` stream on each new block. This is what lets
the pool live in a different process from the chain.

**Sub-pool membership is a bitset, not procedural rules.** `SubPoolMarker`
(`sub_pool.go:44-64`) encodes the five independent validity conditions; a txn is Pending iff
it has all of `BaseFeePoolBits` *and* clears the current base fee, BaseFee if it has the base
bits but not enough fee cap, Queued otherwise. `promote()` (`pool.go:2211`) re-derives
membership by walking Best/Worst of each heap after every block — one deterministic function
replaces scattered "should this move?" checks.

**Replacement uses a configurable price bump with an anti-DoS twist** (`addLocked`,
`pool.go:1704-1748`): a same-(sender,nonce) replacement must beat both tip and fee-cap by
`cfg.PriceBump` (`BlobPriceBump` for type-3). If the replacement *raises the value* it
multiplies the tip threshold by the sender's pooled-txn count to blunt "latent overdraft"
attacks (line 1730-1733). Blob txns can only be replaced by blob txns (`BlobTxReplace`).

**Persistent pool DB.** `Assemble` opens a dedicated MDBX DB (`assemble.go:89`, datadir
`txpool/`) so pending/queued txns survive a restart — matters more for a separable service
that may restart independently of the node.

## Notable patterns (the reusable idea)

**The txpool is defined by a proto service, and the in-process object is just one
implementation behind it.** Everything that wants transactions — rpcdaemon, block builder,
`eth_*` RPC — talks to the `txpool.Txpool` gRPC contract, never to the struct. That single
indirection buys erigon: (a) run the pool in-process or as its own process with zero code
change; (b) a `GrpcDisabled` stub so the seam exists even when the feature is off; (c)
independent scaling/restart of the pool; (d) trivial mocking in tests. The pool's *own*
upstream dependencies — gossip and chain state — are **also** gRPC seams (Sentry, Core KV),
so the component is sandwiched between clean interfaces on both sides.

For fukuii this is the reference pattern for the product-family thesis: **define the mempool
boundary as a transport-neutral service interface (admit txn, stream pending, query
status/nonce), consume P2P and chain-state through equally narrow interfaces, and let the same
implementation be embedded in the lean node or hoisted into a separate binary an operator can
scale or replace.** An external mining-pool optimizer or an enterprise "custom admission
policy" module then plugs into the *same* seam the built-in pool uses. Concretely for
fukuii's Pekko world: the `banksy`-owned admission/ordering policy is exactly the surface that
should sit behind such an interface, so ECIP-1122 tip-floor / MESS-style policy can be swapped
without touching consensus or P2P code. erigon also shows the discipline required to make the
seam real: the pool must hold no execution state and pull chain state through a cache+stream,
or it cannot be separated.

## Authority note

erigon is the **service-decomposition authority** here — the reference for "txpool as a
separable gRPC component" and for the three-way Sentry/Core/rpcdaemon seam layout. It is
**not** the authority for canonical mempool *behavior*: erigon's own `README.md` frames its
pool as an implementation of go-ethereum's semantics, and **go-ethereum remains the canonical
reference for txpool admission/ordering/replacement rules**. Use erigon for *how to draw the
boundaries*, go-ethereum for *what the rules inside the boundary should be*. (For ETC/PoW
specifics, core-geth is the PoW authority per fukuii's reference-client map.)

## Gotchas / anti-patterns / things they later changed

- **`Add` (gRPC) is local-only.** The proto explicitly says add-via-RPC marks txns local and
  "use P2P to add remote txns" (`txpool.proto:101-103`). A caller expecting the gRPC `Add` to
  behave like remote-peer ingestion (with its looser/different validation and gossip path)
  will be surprised — local and remote are two different entry functions (`AddLocalTxns` vs
  `AddRemoteTxns`, `pool.go:1424` / `927`) with different fee-floor treatment (local txns skip
  the `MinFeeCap` underpriced gate, `validateTx` line 1000-1001).
- **The pool owns no goroutines except `Run`.** `TxPool` comment (line 100): "leave
  concurrency to user". A single loop in `Run` (`pool.go:2276`) drives commits, remote-txn
  batch processing, gossip-to-new-peers, and the dormancy sweep on tickers. Calling into pool
  methods from other goroutines relies on the internal `*sync.Mutex`, not on message passing —
  a footgun if you assume actor-style single-threaded ownership.
- **`GrpcDisabled` must be kept in lockstep with the proto.** Every service method needs a
  no-op twin (`txpool_grpc_server.go:66-102`); the compile-time `var _ TxpoolServer =
  (*GrpcDisabled)(nil)` check (line 64) is the only thing stopping the disabled stub from
  drifting when a new RPC is added. A hand-maintained parallel impl is a maintenance cost of
  the "seam even when off" pattern.
- **Remote state is a hard dependency, not optional.** If the Core `StateChanges` stream or KV
  DB is unreachable, the pool cannot validate (no nonce/balance) — `Run` sleeps and retries on
  `IsRetryLater`/`IsEndOfStream` (`pool.go:2320-2323`) rather than degrading. The separable
  process trades in-process reliability for a network dependency on Core.
- **A queued-pool discard step was removed** — `promote()` still carries the comment
  `// Discard worst transactions from the queued sub pool if they do not qualify` followed by
  `// <FUNCTIONALITY REMOVED>` (`pool.go:2245-2246`); queued eviction now happens only via the
  capacity-limit loop and the newer dormancy sweep (`senderLastActivity` /
  `QueuedDormancyDuration`, struct comment at `pool.go:~155`), a deliberate later change in how
  stale queued txns are reclaimed.
- **Module moved.** Historically `erigon-lib/txpool/`; now `txnprovider/txpool/` with protos
  under `node/interfaces/txpool/` and generated code in `node/gointerfaces/txpoolproto`. Old
  path references are stale.
