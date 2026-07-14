# erigon — rpc-api
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon's defining RPC choice: **the JSON-RPC layer is a separable component, not
part of the core node.** The public RPC surface is served by a **RPCDaemon** —
`cmd/rpcdaemon` builds a standalone binary — that never touches the node's
internals directly. It reads chain/state data through a **remote-KV gRPC seam**
and reaches the node's non-DB services (txpool, mining, block bodies, filters)
through a sibling gRPC private API. The exact same `jsonrpc.APIList(...)` +
`StartRpcServer(...)` code path is reused verbatim by the embedded in-process
node, so "daemon" and "in-process RPC" are one implementation with two wirings.

There are effectively **three deployment shapes**, differing only in how the
`db`/`eth`/`txPool` handles are obtained:

1. **Standalone remote** (`cmd/rpcdaemon/main.go` → `cli.RemoteServices`,
   `config.go:354`): no `--datadir`. All DB reads go over the remote-KV `Tx`
   gRPC stream to erigon's `remotedbserver`. RPC can run on a *different host*
   and scale independently — the archival/data-serving use case.
2. **Standalone co-located** (`RemoteServices` with `--datadir`,
   `config.go:390`): the daemon opens erigon's DB files **read-only, in its own
   process, sharing the OS page cache** with the running node
   (`httpcfg/http_cfg.go:34`). DB reads are local mmap (zero-copy, no gRPC
   serialization); only txpool/mining/block-bodies/state-change-notifications
   still ride gRPC. Isolates RPC-load crashes from the node without a network
   hop.
3. **Embedded** (`node/eth/backend.go:735` → `cli.EmbeddedServices`,
   `config.go:319`): RPC lives inside the erigon process. gRPC clients are
   replaced by `direct.*ClientDirect` in-memory shims (`config.go:341`), and
   the coherent cache is replaced by a `SharedDomains` `LocalCache` overlay that
   is always current and needs no `StateChanges` stream (`config.go:327-338`).

The **Engine API is a fourth, deliberately separate server**
(`execution/engineapi/engine_server.go:156-176`): its own JWT-authenticated RPC
endpoint exposing **only** `eth` + `engine` namespaces, started via
`StartRpcServerWithJwtAuthentication` — never mixed into the public RPCDaemon's
namespace whitelist (`config.go:706` strips `engine`/`testing` from the public
list).

## Key types / interfaces / files

- `cmd/rpcdaemon/main.go:44` — `cli.RemoteServices(...)` returns the full handle
  bundle `(db, backend, txPool, mining, stateCache, blockReader, engine, ff, …)`
  that fully decouples the RPC impls from how those handles are sourced.
- `cmd/rpcdaemon/main.go:59` — `jsonrpc.APIList(...)` builds the namespace list;
  `:61` `cli.StartRpcServer(...)` serves it. The two-line body of the whole
  daemon.
- `rpc/jsonrpc/daemon.go:44` — `APIList(...)`: constructs every namespace impl
  (`NewEthAPI`, `NewErigonAPI`, `NewTxPoolAPI`, `NewPrivateDebugAPI`,
  `NewTraceAPI`, `NewOtterscanAPI`, `NewOverlayAPI`, `NewGraphQLAPI`, …) then
  `:92-200` registers them into `[]rpc.API` gated by the `cfg.API` whitelist.
- `cmd/rpcdaemon/cli/config.go:354` — `RemoteServices`: the remote/co-located
  wiring. `:365` gRPC `Connect(PrivateApiAddr)`; `:373` `NewKVClient`; `:374`
  `remotedb.NewRemote(...).Open()`; `:390` `WithDatadir` local-file branch;
  `:531-533` `if db == nil { db = remoteKv }` — remote-KV is the fallback DB.
- `cmd/rpcdaemon/cli/config.go:319` — `EmbeddedServices`: in-process wiring using
  `direct.NewEthBackendClientDirect` (`:341`) and `LocalCache`.
- `cmd/rpcdaemon/cli/config.go:123` — `--private.api.addr` flag (default
  `127.0.0.1:9090`): the single gRPC endpoint over which txpool/rpcdaemon/sentry/
  downloader all federate; its help text is the canonical statement of erigon's
  "components as independent processes" model.
- `cmd/rpcdaemon/cli/config.go:661` / `:669` — `StartRpcServer` /
  `StartRpcServerWithJwtAuthentication`: the public vs. auth server split.
- `db/kv/remotedbserver/remotedbserver.go:215` — `KvServer.Tx(stream)`: the
  **cursor-over-gRPC** heart of remote-KV. A single bidirectional stream carries
  an open read transaction; the server holds a map of live `kv.Cursor`s keyed by
  a client-assigned `CursorID` and services seek/next requests, so a remote
  client iterates the DB with MDBX-transaction semantics preserved.
- `db/kv/remotedbserver/remotedbserver.go:426` — `StateChanges(...)`: streaming
  subscription of state diffs that keeps the daemon's `kvcache` coherent
  (consumed by `subscribeToStateChangesLoop`, `config.go:242`).
- `db/kv/remotedbserver/remotedbserver.go:66` — `KvServiceAPIVersion` (7.0.0):
  the daemon negotiates this on connect (`remoteKv.EnsureVersionCompatibility`,
  `config.go:626`) so a mismatched RPCDaemon/erigon pair refuses to serve stale
  data.
- `db/kv/remotedbserver/remotedbserver.go:536-799` — the higher-level KV RPCs
  (`GetLatest`, `HasPrefix`, `HistorySeek`, `IndexRange`, `HistoryRange`,
  `RangeAsOf`, `Range`): erigon's temporal/history DB served remotely — the
  substrate that makes archival trace/otterscan queries answerable from a
  remote daemon.
- `execution/engineapi/engine_server.go:156` — the isolated `eth`+`engine`
  namespace list; `:171` its JWT-auth server start.
- `execution/engineapi/engine_api_methods.go:44-66` — full Engine method surface
  (`forkchoiceUpdatedV1..V4`, `newPayloadV1..V5`, `getPayloadV1..V6`,
  `getPayloadBodiesBy{Hash,Range}V1/V2`, `getClientVersionV1`, `getBlobsV1..V3`).
- `rpc/jsonrpc/otterscan_api.go:55` — `OtterscanAPI` interface: the Otterscan
  namespace (`ots_`) — `SearchTransactionsBefore/After`, `GetContractCreator`,
  `TraceTransaction`, `GetBlockDetails`, `GetTransactionBySenderAndNonce`, plus
  the `otterscan_search_*` / `otterscan_trace_*` files implementing paged
  address history scans over the temporal index.
- `rpc/jsonrpc/trace_adhoc.go`, `trace_api.go`, `trace_filtering.go` — Parity-
  style `trace_` namespace; `rpc/jsonrpc/overlay_api.go` — `overlay_` state-
  override `getLogs`; `rpc/jsonrpc/erigon_*.go` — the `erigon_` extension
  namespace.
- `node/eth/backend.go:1169` — the embedded node calling the identical
  `jsonrpc.APIList(...)`; `:1172` its `StartRpcServer`.

## Design decisions & rationale

- **RPC reads the DB, it does not call the node.** By routing all chain/state
  access through the KV abstraction (local mmap or remote-KV gRPC), erigon makes
  the read path a *pure function of the DB*, so the reader can be a separate
  process or even a separate machine. RPC CPU/allocation load can never stall
  block execution.
- **Co-located default, remote optional.** The recommended layout is `--datadir`
  co-located (log line `config.go`: "if you run RPCDaemon on same machine with
  Erigon add --datadir option") — you get process isolation without a network
  hop, sharing the OS page cache. Pure-remote is the opt-in for horizontal read
  scaling.
- **Accede / read-only open discipline** (`config.go:390` comment block): the
  daemon opens DBs in Accede mode so it (0) refuses to start on an empty dir,
  (1) survives erigon being down without cascading, and (2) never *creates* a DB
  (it can't know erigon's pagesize/flags). RPC availability is decoupled from
  core availability.
- **Cache coherency by streaming diffs, not polling.** Remote mode feeds a
  `kvcache` coherent cache from the `StateChanges` gRPC stream; embedded mode
  skips the cache entirely for a zero-overhead always-current `SharedDomains`
  overlay. Same handler code, cache strategy chosen at the seam.
- **Engine API quarantined.** Keeping `engine_*` on a dedicated JWT server means
  the trusted CL channel and the public data-serving channel share zero
  namespace-registration or exposure surface.
- **Namespace whitelist** (`cfg.API`): every namespace is opt-in per deployment;
  archival nodes light up `ots`/`trace`/`erigon`/`overlay`, a plain endpoint
  runs just `eth`/`net`/`web3`.

## Notable patterns (the reusable idea)

- **The read seam as a process boundary.** The single most transferable idea:
  express *all* RPC data access through one narrow, serializable DB-read
  interface (KV cursors + a state-change subscription), and the RPC server
  becomes relocatable — in-process, sibling-process-on-a-shared-datadir, or
  remote — *with the same handler code*. The deployment topology becomes a
  wiring choice at one function (`RemoteServices` vs `EmbeddedServices`), not a
  code fork.
- **Cursor-over-gRPC** (`Tx` stream, `remotedbserver.go:215`): transactional
  iteration semantics survive a network boundary by keeping server-side cursor
  state keyed by an ID inside one long-lived bidi stream — including
  transaction-renewal that snapshots and restores cursor positions
  (`:255-290`).
- **Same handler, swapped clients**: gRPC clients vs `direct.*ClientDirect`
  in-memory shims let embedded mode pay zero serialization cost while reusing
  the daemon's exact impls.
- **Version-gated seam**: `KvServiceAPIVersion` negotiation refuses incompatible
  daemon/core pairings instead of silently serving wrong-shaped data.

## Authority note

erigon = **the RPC-as-separable-process + remote-KV authority**: the reference
for decoupling the JSON-RPC/data-serving tier from the core node over a
DB-read seam, and for the extended archival method surface (`ots_` Otterscan,
Parity `trace_`, `overlay_`, `erigon_`) served from history/temporal indexes.
go-ethereum remains the canonical authority for the *behavior* of standard
`eth_*`/`net_*`/`web3_*` methods and the Engine API wire spec — erigon tracks
that behavior, its innovation is the transport/topology and the extra
namespaces, not the base method semantics.

## Gotchas / anti-patterns / things they later changed

- **`--datadir` co-located is read-only-shared, not a second writer.** The
  daemon opens erigon's live DB read-only and depends on erigon for writes,
  snapshot production, and DB creation. It cannot bootstrap from an empty
  directory — erigon must run first (`config.go:390` comment items 0/2).
- **Coherent cache only exists in remote mode.** In embedded mode the `kvcache`
  is bypassed for the `SharedDomains` overlay (`config.go:327`); code assuming a
  `kvcache` in all modes is wrong.
- **KV API-version skew breaks the daemon.** A daemon built against a different
  `KvServiceAPIVersion` than the running erigon is rejected at connect
  (`EnsureVersionCompatibility`) — the remote/core pair must be upgraded in
  lockstep.
- **`engine`/`testing` are not public namespaces.** `startRegularRpcServer`
  explicitly filters `engine` and `testing` out of the public whitelist
  (`config.go:697,706`); `testing_` is only admitted when the embedded caller
  supplies an impl, and standalone rpcdaemon warns and ignores it.
- **`db_` namespace is deprecated** (`daemon.go:137`, `db_api_deprecated.go`);
  don't model it as a live capability.
- **Long-lived remote `Tx` streams pin server resources.** Each open remote
  transaction holds MDBX read-tx state and cursors server-side with a
  `MaxTxTTL` renewal ticker (`remotedbserver.go:240`); careless long scans from
  many remote clients pressure the core's read-tx budget (`DBReadConcurrency`).

---

### Use-case lens (fukuii)

- **Archival / data-serving**: run the RPCDaemon as N separate read replicas
  against one erigon DB (remote-KV) to scale read throughput independently of
  execution; light up `ots`/`trace`/`overlay`/`erigon` for block-explorer-grade
  history. fukuii's JSON-RPC lives in `jsonrpc/` (conduit's territory) coupled
  to the node; the transferable move is to define a DB-read seam so RPC can be
  peeled into a separate scaling unit.
- **Enterprise**: the remote-KV seam is the enterprise deployment story —
  RPC/data-serving hosts distinct from consensus/execution hosts, with a
  version-negotiated, TLS-capable (`grpcutil.TLS`, `config.go:361`) gRPC
  boundary between them.
- **Archival trace**: `ots_`/`trace_`/`overlay_` answered from erigon's
  temporal/history DB (`RangeAsOf`/`HistoryRange`/`HistorySeek`) is the pattern
  for serving deep historical queries without an archive-of-full-states.
