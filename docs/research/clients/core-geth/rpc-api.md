# core-geth — rpc-api
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary
core-geth inherits go-ethereum's JSON-RPC stack essentially unchanged: the `rpc/` package
(HTTP/WS/IPC transports, method dispatch, subscription plumbing), the namespaced API objects
(`eth_*`, `net_*`, `web3_*`, `txpool_*`, `admin_*`, `debug_*`, `personal_*`), and the
`eth/tracers/` tracing engine (`debug_traceTransaction` etc., including the parity-style
call tracer). There is **no ETC-specific RPC surface** worth cataloguing — the semantics of
the inherited methods differ only where the underlying consensus/config differs (e.g.
`eth_chainId` returns 61/63; `eth_getBalance` reflects ECIP-1017 emission), which is a
consequence of the config, not of RPC-layer code. This is a **thin, inherited slot**.

## Key types / interfaces / files
- `eth/backend.go:330` — `stack.RegisterAPIs(eth.APIs())` — standard geth API registration;
  the `eth`/`debug`/`net` namespaces are the vanilla set.
- `cmd/utils/flags.go:2308` — `stack.RegisterAPIs(tracers.APIs(backend.APIBackend))` —
  inherited tracer/`debug_trace*` registration.
- `eth/catalyst/api.go:43` — `func Register(stack, backend)` — the **Engine API** (`engine_*`,
  the CL↔EL interface) is *present* in the tree, inherited from geth.
- `eth/catalyst/simulated_beacon.go` / `simulated_beacon_api.go` — the dev-mode simulated
  beacon; `cmd/utils/flags.go:2341` `catalyst.RegisterFullSyncTester(stack, eth, target)`.
- `eth/tracers/internal/tracetest/calltrace_parity_test.go` — parity-format call tracer
  (inherited from geth; not an ETC addition).

## Design decisions & rationale
- **Engine API is present but inert for ETC.** ETC is Proof-of-Work — it has no Consensus
  Layer / beacon client driving `engine_forkchoiceUpdated` / `engine_newPayload`. The
  `eth/catalyst` package is carried because it is part of the go-ethereum base and is used
  by the `--dev` simulated-beacon and full-sync-tester paths, **not** because ETC needs a
  live Engine API. For a PoW ETC node the Engine API endpoint is effectively unused. (This
  is the fukuii-relevant datapoint: an ETC-only client does not need to expose authrpc/Engine
  API in production; it exists only as inherited dev tooling.)
- **No custom debug/trace additions.** The tracing surface is stock geth. ETC's divergent
  behaviour (rewards, opcode/gas schedule) is expressed through the config the tracer reads,
  so no ETC-specific tracer code was needed.

## Notable patterns (the reusable idea)
RPC correctness for a forked chain is achieved **without touching the RPC layer** — the same
handlers produce ETC-correct answers because they read an ETC config/consensus underneath.
The RPC surface is a stable seam; chain identity lives below it.

## Authority note
core-geth is **not** an RPC authority — the `rpc/` engine, namespaces, and tracers are
inherited from go-ethereum and any current geth is the better structural reference. core-geth
authority is confined to network-config/bootnodes/ForkID (see `networking-p2p.md`).

## Gotchas / anti-patterns / things they later changed
- **Engine API / authrpc looks live but is dead weight on ETC.** Don't assume the presence of
  `eth/catalyst` implies ETC drives a CL — it does not. A fukuii ETC deployment can omit the
  authrpc port entirely; only the PoS family (ETH/Sepolia) needs it.
- Being an older geth base, the RPC method set is a **2025-vintage snapshot** — newer geth
  RPC methods/fields (later `eth_`/`debug_` additions, blob/4844 RPC surface) are absent.
  Cross-check against current geth when porting RPC features rather than treating this tree
  as complete.
