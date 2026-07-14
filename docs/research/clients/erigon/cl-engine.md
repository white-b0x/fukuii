# erigon — cl-engine
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon is the only reference client of the six that **ships its own consensus-layer
(beacon-chain) client**, called **Caplin**, living entirely under `cl/`. Every other
client (geth, besu, nethermind, reth) is execution-layer-only and requires a *separate*
external CL process (Lighthouse, Prysm, Teku, …) wired over the standard HTTP Engine API.
Erigon can run the same way — but with `--internalcl` (`config.InternalCL`) it instead
spins up Caplin **in-process, in the same binary**, producing a single-process EL+CL node
with no separate beacon node to deploy, port-map, JWT-authenticate, or monitor.

Caplin is a full beacon client, not a light stub. It contains the three pillars of a
CL:

1. **Beacon state-transition** (`cl/transition/`) — the spec state transition
   (`process_slots` / `process_block` / epoch processing), Phase0 → Gloas fork-gated.
2. **LMD-GHOST fork choice** (`cl/phase1/forkchoice/`) — `on_block`, `on_attestation`,
   `on_tick`, proposer-boost, and a weight-store head walk. This is the **driver**: it
   decides the canonical head and finalized/justified checkpoints and pushes them to the
   EL.
3. **Sentinel** (`cl/sentinel/`) — the CL's *own* libp2p/discv5 gossip + req-resp
   networking stack (beacon blocks, attestations, blob sidecars), completely separate
   from the EL's devp2p/RLPx stack.

The interesting seam for fukuii is the **EL↔CL boundary**, abstracted behind one Go
interface (`cl/phase1/execution_client/interface.go`) with three implementations that
differ only in *how* the CL reaches the EL — same interface, in-process or over the wire.

## Key types / interfaces / files

- `cl/phase1/execution_client/interface.go:36` — `ExecutionEngine` interface: the entire
  EL↔CL contract, deliberately shaped to "pretty much mimic engine API" (comment at
  `:32`). Core methods: `NewPayload`, `ForkChoiceUpdate`, `GetAssembledBlock` (block
  production), `InsertBlocks`/`GetBodiesByRange` (sync), `GetBlobs`. This ONE interface is
  the pluggable boundary.
- `cl/phase1/execution_client/execution_client_direct.go:44` — `ExecutionClientDirect`:
  the **true single-binary path**. Holds a `chainRW chainreader.ChainReaderWriterEth1` —
  a direct in-process handle to erigon's execution module. `NewPayload` calls
  `cc.chainRW.InsertBlock(...)` + `cc.chainRW.ValidateChain(...)` directly
  (`:86`, `:107`); `ForkChoiceUpdate` calls `cc.chainRW.UpdateForkChoice(...)` (`:125`);
  block production calls `cc.chainRW.AssembleBlock(...)` (`:144`). **No HTTP, no JSON-RPC,
  no JWT** — the CL and EL are function calls apart.
- `cl/phase1/execution_client/execution_client_engine.go:66` — `ExecutionClientEngine`:
  the Engine-API implementation, itself dual-mode (comment `:63`): *Local* (an in-process
  `*EngineServer` + `chainRW`) or *Remote* (an `*rpc.Client` over HTTP to a foreign EL).
  This is the path that lets Caplin also drive a *non-erigon* EL, and lets an external CL
  drive erigon.
- `execution/engineapi/engine_server.go` — the standard **external-CL Engine-API server**
  (`engine_newPayloadVX` / `engine_forkchoiceUpdatedVX`), used when a separate beacon node
  drives erigon. Same server object (`backend.engineBackendRPC`) is reused internally by
  the `EngineLocal` mode — so the two worlds share one implementation.
- `cl/phase1/forkchoice/forkchoice.go` + `cl/phase1/forkchoice/get_head.go:247` —
  `ForkChoiceStore.getHead`: the LMD-GHOST head walk (weight-sorted children, lexical
  tiebreak at `:298`). `cl/phase1/forkchoice/interface.go:31` — `ForkChoiceStorage`
  read/writer, whose `Engine() execution_client.ExecutionEngine` accessor (`:41`) is how
  fork choice reaches back into the EL.
- `cl/transition/machine/machine.go:27` — the state-transition `Interface` (composed of
  `BlockProcessor`/`SlotProcessor`/`BlockOperationProcessor`, `:33`–`:61`);
  `cl/transition/machine/transition.go:25` — `TransitionState(impl, state, block)` runs it
  against a pluggable `impl` (the real one is `cl/transition/impl/eth2`).
- `cl/sentinel/sentinel.go:55` — `Sentinel` struct: its own `discover.UDPv5` listener,
  `GossipManager`, `HandShaker`, peer pool — a second, independent P2P stack for the CL.
- `cmd/caplin/caplin1/run.go:183` — `RunCaplinService(ctx, engine ExecutionEngine, …)`:
  the embedded-CL bootstrap. Builds the fork-choice store (`:362`), starts the sentinel
  service (`:412`), wires all gossip services, and ticks fork choice
  (`forkChoice.OnTick`, `:515`). Takes the `ExecutionEngine` as a parameter — it does not
  care whether it's Direct or Engine.
- `node/eth/backend.go:998` — where erigon **assembles the embedded node**:
  `NewExecutionClientDirect(...)` is the default. `:1028` gates on
  `config.InternalCL && clparams.EmbeddedSupported(config.NetworkID)`; if
  `EnableEngineAPI` it swaps to `NewExecutionClientEngineLocal(engineBackendRPC, …)`
  (`:1032`); then `go caplin1.RunCaplinService(...)` (`:1045`) launches Caplin in the same
  process.
- `cl/clparams/config.go:1666` — `EmbeddedSupported(networkId)`: the allow-list of
  networks for which the embedded CL is offered.

## Design decisions & rationale

- **One interface, three transports.** The EL↔CL contract is a single Go interface
  (`ExecutionEngine`) modelled on the Engine API. Because the CL depends only on the
  interface, erigon can bind it to (a) a direct in-process EL, (b) an in-process Engine
  server, or (c) a remote HTTP EL — without any change to fork choice, sync, or gossip.
  The transport is a wiring decision made once in `backend.go`, not baked into the CL.
- **Direct beats Engine-API for co-located EL/CL.** When both halves are in one binary,
  going through JSON-RPC + JWT to talk to your own execution module is pure overhead.
  `ExecutionClientDirect` skips it and calls `chainRW` methods directly. The Engine-API
  path is retained for interop (foreign EL/CL), not as the default for the embedded case.
- **The CL keeps its own P2P.** Sentinel is a wholly separate libp2p/discv5 stack from the
  EL's devp2p. Beacon-chain gossip topics, encodings (SSZ + snappy), and peer scoring are
  fundamentally different from the ETH wire protocol, so they are not shoehorned into the
  EL networking layer. Single *process*, two *networks*.
- **Fork choice is the driver, not the EL.** The EL is a passive validator/executor
  (`NewPayload` → valid/invalid; `ForkChoiceUpdate` → set head). Caplin's LMD-GHOST decides
  *what* the head is; the EL only confirms the payload executes. This inversion of control
  is the defining CL↔EL relationship and is worth internalizing before embedding a CL.

## Notable patterns (the reusable idea)

**Model the EL↔CL boundary as a single narrow interface, then pick the transport at
assembly time.** Erigon's `ExecutionEngine` interface (~15 methods) is the *entire* surface
the consensus layer needs from the execution layer. Everything else — whether the EL is a
function call away or an HTTP hop away — is a constructor choice in one file (`backend.go`).
This is exactly the seam fukuii would want if it ever hosts a CL for its PoS family: define
the execution-side contract narrowly (payload insert/validate, forkchoice-update, assemble,
range-sync, blobs), implement a *direct* in-process binding first (single binary, no
Engine-API round-trip), and keep the Engine-API implementation as an optional interop shim
rather than the primary path.

## Authority note

**erigon/Caplin is THE embedded-CL / single-binary-EL+CL authority — unique among the six
reference clients.** geth, besu, nethermind, and reth are execution-only and *require* a
separate external CL over the HTTP Engine API. Only erigon ships a full beacon client
(state-transition + LMD-GHOST fork choice + independent libp2p sentinel) that runs in the
same binary as the EL and drives it via an in-process, non-networked `ExecutionEngine`
binding. For any fukuii question about "can a client embed its own consensus layer / run
EL+CL as one process," Caplin (`cl/`) is the reference implementation to study; the other
five have nothing analogous. (This is PoS-only — ETC/PoW uses none of it; Ethash + ECIP-1017
emission is self-contained consensus with no beacon chain.)

## Use-case lens: fukuii's single-binary multi-network thesis

This maps directly onto fukuii's stated GTM: **enterprise single-binary multi-network**
(JPMC/E*TRADE/Fireblocks) and **light/end-user** operation. Today fukuii's PoS family
(ETH/Sepolia) needs an operator to run *two* processes — the EL and a separate beacon node —
plus JWT secret management and Engine-API port wiring. Caplin demonstrates the operational
simplification of collapsing that to one process: no second binary to deploy/upgrade/monitor,
no JWT rotation, no inter-process Engine-API surface to secure. If fukuii ever embeds a CL
for its PoS family, Caplin's architecture is the blueprint:

1. A narrow `ExecutionEngine`-style interface as the EL↔CL contract.
2. A **direct in-process** binding as the default (skip Engine-API/JWT entirely when
   co-located), with the Engine-API binding kept only for foreign-EL/foreign-CL interop.
3. State-transition + LMD-GHOST fork choice + an independent SSZ/libp2p gossip stack as the
   three CL pillars, bootstrapped in one background service (`RunCaplinService`) that takes
   the execution binding as a parameter.

Caution: embedding a CL is a *large* surface (Caplin is thousands of files across
`cl/transition`, `cl/phase1`, `cl/sentinel`, `cl/cltypes`) and is spec-tracking work that
never ends (Phase0→Gloas fork gates, blob/PeerDAS, ePBS/EIP-7732). The transferable *design*
(the narrow interface + direct binding) is cheap; a full home-grown beacon client is not.

## Gotchas / anti-patterns / things they later changed

- **Direct vs Engine-API is a real footgun in wiring.** `backend.go:1028`–`1044` shows the
  default is `ExecutionClientDirect`, but `config.CaplinConfig.EnableEngineAPI` silently
  swaps in `NewExecutionClientEngineLocal`. The two behave the same through the interface
  but have different performance/failure characteristics (direct = function calls; engine =
  serialization + an in-process server). Choosing Engine-API mode for a co-located EL is
  measurable overhead for no benefit.
- **`ExecutionEngine` "mimics" the Engine API but is not identical** (interface comment,
  `execution_client_direct.go` region / `interface.go:32`): it adds sync/range methods
  (`GetBodiesByRange`, `InsertBlocks`, `FrozenBlocks`, `HasGapInSnapshots`) that the real
  JSON-RPC Engine API does not expose. `ExecutionClientEngine` returns `ErrNotSupported`
  for several of these in remote mode (`execution_client_engine.go:47`). So the interface
  is a *superset* of the Engine API — remote/foreign-EL callers get a reduced capability
  set. Don't assume every method works over every transport.
- **`EmbeddedSupported` is an allow-list, not universal.** `cl/clparams/config.go:1666`
  gates which networks may run the embedded CL; unlisted networks fall back to requiring an
  external CL. Embedding is opt-in per network, not a blanket capability.
- **The two P2P stacks are genuinely separate resources.** Sentinel runs its own
  `discover.UDPv5` + libp2p host with its own peer pool and a `connectSem` semaphore
  guarding a known libp2p data race (`sentinel.go:96`–`:101`, referencing upstream issue
  #19603). Embedding a CL means running and supervising a *second* networking subsystem in
  the process, not reusing the EL's — extra fds, ports, and failure modes.
- **Consensus code is spec-tracked, not test-tracked.** `cl/CLAUDE.md` explicitly warns to
  review all changes against the upstream `consensus-specs`, not just local tests, and
  maintains per-directory spec maps (`phase1/forkchoice`, `transition`, `phase1/core/state`).
  A home-grown CL inherits this permanent spec-conformance burden across every fork gate.
