# core-geth — exec-extensions
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary

core-geth's execution-notification seam **diverges substantially from the documented
go-ethereum baseline — because it predates geth's `core/tracing.Hooks` refactor.**
At commit `4185df450` (2025-01) `core/tracing/` **does not exist**; there is no fat
`Hooks` struct of reason-annotated function pointers, no `--vmtrace` live-tracer
registry, no typed `BalanceChangeReason`/`GasChangeReason` enums. Instead core-geth
uses the **older `core/vm.EVMLogger` interface** — the `CaptureStart`/`CaptureState`/
`CaptureEnter`/`CaptureExit`/`CaptureEnd`/`CaptureFault` opcode-logger contract that
geth used before the tracing rewrite.

On top of that inherited older base, core-geth adds **two ETC-attributable
extensions**:

1. **`EVMLogger_StateCapturer`** (`core/vm/logger_coregeth.go`) — a core-geth
   interface that *extends* `EVMLogger` with a `CapturePreEVM(env *EVM)` hook, so a
   native state-diff tracer can snapshot state directly before EVM execution.
2. **Parity/OpenEthereum `trace_*` RPC namespace** (`eth/tracers/api_parity.go`) — a
   whole `TraceAPI` implementing `trace_block`, `trace_transaction`, `trace_filter`,
   `trace_call`, `trace_callMany`, plus **block- and uncle-reward traces** that are
   ECIP-1017-emission aware. This is the OpenEthereum-compatible trace surface that
   upstream geth never provided; it is what ETC block explorers / indexers expect.

Additionally, core-geth drives **OpenRPC** service-description generation for its
JSON-RPC (ETC Labs `go-openrpc-reflect` / `openrpc-go-document`) — an ETC-specific
tooling addition; see `build-deps.md` (the `-trimpath`-vs-OpenRPC regression note)
and `rpc-api.md`. The coarse **event-feed / filter-index** layer (`event.Feed`,
`core/events.go`, `eth/filters/`, tx/log indexers) is inherited from geth of this era
essentially unchanged.

## Key types / interfaces / files

- `core/vm/logger.go:30` — `type EVMLogger interface` — the **old** opcode-logger
  seam (`CaptureStart`/`CaptureState`/`CaptureEnter`/`CaptureExit`/`CaptureFault`/
  `CaptureEnd`), installed as `vmConfig.Tracer`. This is core-geth's exec-extension
  interface — the pre-`core/tracing.Hooks` design.
- `core/vm/interpreter.go:28` — `Tracer EVMLogger` in `Config` — the interpreter's
  single tracer field the logger is installed into. Contrast geth's
  `vmConfig.Tracer *tracing.Hooks`.
- `core/vm/logger_coregeth.go:24` — `type EVMLogger_StateCapturer interface {
  EVMLogger; CapturePreEVM(env *EVM) }` — **core-geth-specific**. Lets the native
  state-diff tracer (used by `trace_*`) manage state directly; comment points at
  `api_parity.go`.
- `eth/tracers/api_parity.go:190` `Block` / `:241` `Transaction` / `:249` `Filter` /
  `:262` `Call` / `:275` `CallMany` — the **`trace_*` RPC methods** (Parity/OpenEthereum
  ad-hoc + block trace). **core-geth addition, not in upstream geth.**
- `eth/tracers/api_parity.go:139` `traceBlockReward` / `:161` `traceBlockUncleRewards`
  — reward pseudo-traces that surface ECIP-1017 block/uncle emission as trace entries
  (`params/mutations` import) — ETC-emission-aware.
- `internal/web3ext/web3ext.go:802-826` — the JS console bindings registering
  `trace_block`, `trace_transaction`, `trace_filter`, `trace_call`, `trace_callMany`.
- `eth/tracers/` — `js/ native/ logger/` (old-style tracer packages), `api.go`,
  `api_parity.go` (core-geth), `tracker.go`. **No `eth/tracers/live/`** (geth's
  live-tracer directory) — it doesn't exist in this era.
- `go.mod` — `github.com/etclabscore/go-openrpc-reflect` (+ indirect
  `open-rpc/meta-schema`) — the OpenRPC doc-generation deps; **absent from geth's
  go.mod** (see `build-deps.md`).
- `event/feed.go`, `core/events.go`, `eth/filters/`, `core/txindexer.go` — the coarse
  event-feed / filter-index layer, inherited from geth of this era.

## Design decisions & rationale

- **OpenEthereum-compatible `trace_*` for ETC ecosystem parity.** ETC block explorers,
  indexers, and analytics were built against OpenEthereum/Parity `trace_*` semantics;
  when Parity was deprecated, core-geth implemented that surface on top of go-ethereum
  so the ETC tooling ecosystem kept working. This is the single largest exec-extension
  divergence and it is ETC-motivated.
- **`CapturePreEVM` because a state-diff tracer needs pre-execution state.** The
  Parity `stateDiff` output requires a snapshot of account/storage state *before* the
  call runs; the base `EVMLogger` fires only at/after `CaptureStart`, so core-geth
  widened the interface rather than reconstruct pre-state after the fact.
- **Reward traces model ECIP-1017 emission as first-class trace entries.** Parity's
  trace format includes `reward` action types; core-geth's `traceBlockReward` /
  `traceBlockUncleRewards` compute them from ETC's fixed-supply schedule (via
  `params/mutations`), so explorers see correct ETC issuance/uncle rewards.
- **OpenRPC for machine-readable JSON-RPC discovery.** ETC Labs invested in OpenRPC
  service descriptions; core-geth wires `go-openrpc-reflect` to emit them — at the
  documented cost of dropping `-trimpath` from the build (a concrete geth divergence,
  `build-deps.md`).

## Notable patterns (the reusable idea)

**The reusable idea here is ecosystem-compatibility as an execution extension: a
`trace_*` RPC facade over the EVM tracer, plus a widened tracer interface
(`CapturePreEVM`) to satisfy state-diff output.** For fukuii the transferable lessons
are two-directional:

- **Do carry the OpenEthereum `trace_*` surface** (with ECIP-1017-aware reward
  traces) — it is what ETC indexers/explorers depend on, and it is core-geth's, not
  geth's. fukuii's `conduit`/RPC layer should treat `trace_*` as an ETC-family
  expectation, not an optional geth extra.
- **Do NOT model fukuii's tracing seam on core-geth's old `EVMLogger`.** core-geth is
  frozen *before* geth's typed-reason `core/tracing.Hooks` design — the strictly
  better interface shape (per the go-ethereum `exec-extensions` doc: reason-annotated
  mutations, name→constructor live-tracer registry, dispatch-cost avoidance). For the
  *interface design*, follow geth's `Hooks`; for the *ETC RPC compatibility*, follow
  core-geth's `trace_*`.

## Authority note

**Authority splits by concern.** For the **exec-extension *interface* design**
(typed-reason hooks, live-tracing registry, event-feed altitudes), **go-ethereum is
authoritative and strictly ahead of core-geth** — core-geth's `EVMLogger` here is the
older, superseded shape. For **ETC-specific RPC/trace behavior** — the
OpenEthereum-compatible `trace_*` namespace, the `stateDiff`/`CapturePreEVM` seam, and
**ECIP-1017-aware block/uncle reward traces** — **core-geth is the authority**, since
that surface does not exist upstream. OpenRPC generation is likewise core-geth
(ETC Labs) territory (cross-ref `build-deps.md`, `rpc-api.md`).

## Gotchas / anti-patterns / things they later changed

- **`core/tracing/` does not exist here.** Do not look for geth's `Hooks` struct,
  `BalanceChangeReason` enums, `--vmtrace` live tracers, or `eth/tracers/live/` in
  core-geth at this commit — they postdate this fork. core-geth uses the old
  `core/vm.EVMLogger` interface. This is the biggest trap when diffing against the
  documented geth baseline.
- **`trace_*` is core-geth, `debug_trace*` is inherited geth.** Two different trace
  surfaces coexist: the OpenEthereum `trace_*` namespace (`api_parity.go`, core-geth)
  and geth's `debug_traceTransaction`/`debug_traceBlock*` (`api.go`, inherited). Don't
  conflate them; ETC tooling generally wants the former.
- **`EVMLogger_StateCapturer` is an extension, not the base.** A tracer that only
  implements `EVMLogger` won't get `CapturePreEVM`; state-diff output needs the
  extended interface. Naive tracers miss pre-state.
- **OpenRPC forces dropping `-trimpath`.** The OpenRPC reflect deps break `-trimpath`
  discovery, so core-geth's build omits it — a downstream consumer wanting trimmed
  paths must choose between `-trimpath` and OpenRPC (see `build-deps.md`).
- **Live tracers run in the consensus hot path** — this geth caveat still applies to
  the `EVMLogger` installed as `vmConfig.Tracer`: a slow/panicking capture stalls
  block import. The old interface has no `HasGasHook`-style dispatch-cost avoidance,
  so per-opcode logging is comparatively costlier than geth's later nil-guarded hooks.
- **2025-01 frozen snapshot.** Everything geth added to tracing after Jan 2025 (the
  entire `core/tracing.Hooks` era) is absent. Treat this as the deprecated ETC
  byte-authority, and take the modern interface design from the go-ethereum doc.
