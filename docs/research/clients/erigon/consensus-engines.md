# erigon — consensus-engines

_Commit/branch documented: `f1d79d699ed4b809abc0d177dcb539d8605edc41` (branch `main`,
`origin/upstream`/`origin/main`). Vendored read-only at
`.claude/repo-references/clients/erigon`. Documented 2026-07-13. Read-only research; no
fukuii source touched._

_Folds in the B7.0 engine-axis research (`.local/docs/research-july/b7.0-engine-axis-decision.md`,
2026-07-13), which already banked erigon's `CreateRulesEngine` type-switch, the conditional
merge wrap, and the `bor.New(bridge, heimdall)` extra-infra precedent — cited and expanded
here into the full subsystem rather than re-derived._

## Architecture summary

erigon renamed geth's `consensus` package to **`rules`** (`execution/protocol/rules`) and kept
the algorithm-agnostic-engine idea, but split the single interface into two halves:
**`EngineReader`** (the methods the *execution/RPC/tracing* path needs — thread-safe, no
header-verification or block-production) and **`EngineWriter`** (header verification, `Prepare`,
`Finalize`, `Seal`, difficulty). `rules.Engine = EngineReader + EngineWriter`
(`execution/protocol/rules/rules.go:109-113`). This split is load-bearing: the rpcdaemon,
trace worker, receipt derivation, and EVM block context are all typed against
`rules.EngineReader` alone, so a read-only/archive/RPC node can run "read-only consensus"
without ever constructing the full writer/sealing engine.

Four concrete engines exist: **ethash** (PoW), **aura** (AuthorityRound PoA, the
nethermind/OpenEthereum lineage), **bor** (Polygon), and **merge** (the PoS decorator). Engine
**selection** is a `switch` on the concrete *type* of the parsed consensus-config value
(`CreateRulesEngine`, `node/rulesconfig/config.go:43-107`) — a positive presence-of-config-field
dispatch with no "else means ethash" fallthrough, guarded a second time by
`chainConfig.{Aura,Bor} != nil`. The merge is composed on top **conditionally** — wrapped only
when `TerminalTotalDifficulty != nil`, so a never-merging chain is never force-wrapped.

Crucially, **bor proves a sidechain is a MODULE, not just config**: the whole `polygon/`
tree (`bor`, `bridge`, `heimdall`, `chain`, `db`, `sync`, `p2p`, `tests`, `tracer`) is a
first-class subsystem, and `bor.New` takes **external out-of-band infrastructure**
(a Heimdall span/validator oracle and a state-sync bridge) as constructor dependencies that
the base `Engine` interface knows nothing about.

## Key types / interfaces / files

### The rules-engine interface (the reader/writer split)
- `execution/protocol/rules/rules.go:109-113` — **`Engine = EngineReader + EngineWriter`**,
  "an algorithm agnostic rules engine." The composition of the two halves is the load-bearing
  abstraction other clients compare against.
- `execution/protocol/rules/rules.go:115-146` — **`EngineReader`** — "read-only methods … all
  should have thread-safe implementations": `Author`, `TxDependencies`, `IsServiceTransaction`,
  `Type() chain.RulesName`, `CalculateRewards`, `GetTransferFunc`, `GetPostApplyMessageFunc`,
  `ValidateBlockPostExecution`, `Close`. These are exactly the methods tx execution, receipt
  derivation, tracing, and RPC issuance need — **no header verification, no sealing**.
- `execution/protocol/rules/rules.go:148-199` — **`EngineWriter`** — the header-verification +
  block-production half: `VerifyHeader`, `VerifyUncles`, `Prepare`, `Initialize`, `Finalize`,
  `FinalizeAndAssemble`, `Seal`, `SealHash`, `CalcDifficulty`, `APIs`. (Naming is
  slightly counter-intuitive — `VerifyHeader` is read-only in spirit but lives in `Writer`
  because it's the validating-node half, not the execution/RPC half.)
- `execution/protocol/rules/rules.go:40-79` — **`ChainHeaderReader` / `ChainReader`**, the narrow
  blockchain views engines are handed (config + header/block lookups + `FrozenBlocks` /
  `FrozenBorBlocks` snapshot boundaries — the latter is a bor-specific addition to the shared
  reader). The DI seam that keeps engines decoupled from the full blockchain.
- `execution/protocol/rules/rules.go:87-107` — **`Reward` / `RewardKind`** (Author / EmptyStep /
  External / Uncle): a *typed reward-attribution enum* so different engines (AuRa empty-step,
  external reward-contract, uncle) return structurally-typed rewards, not a bare address+amount.
- `execution/protocol/rules/rules.go:257-263` — **`PoW`** sub-interface (`Engine` + `Hashrate()`),
  the ethash-only extension.

### Engine selection (positive type-switch, conditional merge)
- `node/rulesconfig/config.go:43-107` — **`CreateRulesEngine`**, the selection point. A
  `switch consensusCfg := config.(type)` on `*ethashcfg.Config` / `*chain.AuRaConfig` /
  `*borcfg.BorConfig` (`:49-96`), then `panic("unknown config")` if nothing matched (`:98-99`),
  then the **conditional merge wrap**: `if TerminalTotalDifficulty == nil { return eng } else {
  return merge.New(eng) }` (`:102-106`). No fallthrough default-to-ethash.
- `node/rulesconfig/config.go:71-86` — **aura also needs external infra**: it opens a dedicated
  consensus KV DB (`node.OpenDatabase(..., dbcfg.ConsensusDB, "aura", ...)`) before
  `aura.NewAuRa(chainConfig.Aura, db)`, panicking on any error. A second example (besides bor)
  of a mechanism whose construction needs more than the chain config.
- `node/rulesconfig/config.go:87-95` — **bor's double guard**: matched by `*borcfg.BorConfig`
  type *and* `chainConfig.Bor != nil && consensusCfg.ValidatorContract != ""` — bor only
  activates "for real" when a validator contract is configured (else the chain runs as plain
  ethash even with a bor config present).
- `node/rulesconfig/config.go:109-124` — **`CreateRulesEngineBareBones`**, the fake-mode/test
  path: selects config by `chainConfig.Aura/Bor != nil` presence, else a fake ethash, and calls
  `CreateRulesEngine` with `noVerify`/`withoutHeimdall` and nil infra.
- `execution/chain/rules.go:21-42` — **`RulesName`** string enum (`"aura"`/`"ethash"`/`"bor"`)
  with a `ValidRulesNames` set + `Validate()`. Mechanism identity is a validated spec-named
  string, layered on the chain config (`chain_config.go:50`, `Rules RulesName json:"consensus"`).
- `execution/chain/chain_config.go:118-124` — the consensus-config sub-objects:
  `Ethash *EthashConfig` / `Aura *AuRaConfig` (positive, `omitempty`), and **`Bor BorConfig`**
  (an *interface*, `json:"-"`) hydrated from `BorJSON json.RawMessage`. bor's config is an
  interface, not a pointer — a small divergence from the ethash/aura pointer-presence pattern.

### The merge decorator (conditional, content-derived — shared with geth)
- `execution/protocol/rules/merge/merge.go:66-84` — **`Merge` struct + `New(eth1Engine)`**,
  holding one inner `rules.Engine` (e.g. ethash, aura, bor). `New` **panics on a nested
  `*Merge`** (`:78`) — single-layer by construction, exactly geth's `beacon.New`.
- `execution/protocol/rules/merge/merge.go:98,125,151,161,255` — per-method routing on
  **`misc.IsPoSHeader(header)`** (the content-derived `difficulty==0` test); pre-merge headers
  fall through to the inner eth1 engine, post-merge headers get merge's own PoS rules. Note the
  aura special-case in `Prepare` (`:151`, `!IsPoSHeader || isAura`).
- Applied **only when `TerminalTotalDifficulty != nil`** (`node/rulesconfig/config.go:102-106`) —
  the conditional wrap fukuii's B7.0 §C Option 2 wants (ETC must stay permanently PoW-legal).

### BOR — the NET-01 reference (sidechain-as-module + external-infra injection)
- `polygon/bor/bor.go:290-316` — the **`Bor` struct**. Beyond the usual caches it holds the
  external-infra handles: `spanner Spanner`, `stateReceiver StateReceiver`, `spanReader`,
  `bridgeReader`. These are the deps the base `rules.Engine` interface has no concept of.
- `polygon/bor/bor.go:323-376` — **`bor.New(chainConfig, blockReader, spanner, genesisContracts
  StateReceiver, logger, bridgeReader, spanReader)`** — the constructor that **threads external
  out-of-band infrastructure as extra params**. Wired in `CreateRulesEngine` as
  `bor.New(chainConfig, blockReader, spanner, stateReceiver, logger, polygonBridge,
  heimdallService)` (`node/rulesconfig/config.go:92-94`) — the `polygonBridge *bridge.Service`
  and `heimdallService *heimdall.Service` are passed all the way down from the node wiring, not
  derived from config.
- `polygon/bor/bor.go:207-217` — the two injected **oracle interfaces**:
  - **`spanReader`** — `Span(ctx, id)` + `Producers(ctx, blockNum) (*heimdall.ValidatorSet, …)`.
    The Heimdall span/validator-set oracle. Consumed throughout header verification and sealing
    (`Producers` at `:622,678,700,949,1033,1050,1065`; `Span` + `CommitSpan` at `:1122-1139`) —
    **bor cannot verify or seal a header without asking Heimdall who the validators are.**
  - **`bridgeReader`** — `Events(ctx, blockHash, blockNum)` + `EventsWithinTime(...)`. The
    state-sync bridge oracle for Polygon L1→L2 deposit events, consumed at `:1257-1262`.
- `polygon/heimdall/service.go:39-57` — **`heimdall.Service`**: scrapes `Checkpoint` / `Milestone`
  / `Span` from Heimdall (Polygon's separate PoS/Tendermint checkpoint+validator chain) via a
  `Client`, keeping a `spanBlockProducersTracker`. `polygon/heimdall/client.go:24-46` — the
  `Client` interface (`FetchSpan`/`FetchCheckpoint`/`FetchMilestone`…), implemented over HTTP by
  `client_http.go:44-47` (`NewHttpClient(urlString, …)`). This is **a whole second chain's REST
  API** the consensus engine depends on — the definitive "engine needs external infra" case.
- `polygon/bridge/service.go` + `polygon/bridge/reader.go` — the state-sync bridge service the
  `bridgeReader` reads from.
- The module layout itself — `polygon/{bor,bridge,heimdall,chain,db,sync,p2p,tests,tracer}` — is
  the evidence: a sidechain family is a self-contained subsystem, not a fork field on the
  mainnet chain config.

### Read-only consensus (the reader-half payoff)
- `polygon/bor/bor.go:378-400` — **`bor.NewRo(chainConfig, blockReader, logger)`**: a *read-only*
  Bor constructed **without** `spanner`/`stateReceiver`/`bridgeReader`/`spanReader` — "used by
  the rpcdaemon and tests which need read only access." The concrete realization of the
  reader/writer split for bor: the RPC node builds an engine that satisfies the reader path but
  has none of the external infra the writer path needs.
- `cmd/rpcdaemon/cli/config.go:596,1075` — the rpcdaemon wires `bor.NewRo(...)`.
- `cmd/rpcdaemon/cli/config.go:1025-1069` — **`remoteRulesEngine`**: `HasEngine()` +
  `Engine() rules.EngineReader` (`:1037-1039`) — the rpcdaemon exposes only the **reader** half,
  lazily `init`'d in the background so rpcdaemon startup doesn't block on other services.
- Pervasive `rules.EngineReader` params confirm the split's payoff: `rpc/jsonrpc/eth_api.go:155`
  (`_engine rules.EngineReader`), `daemon.go:48`, `execution/exec/trace_worker.go:49`,
  `execution/protocol/state_processor.go:57,117,134`, `execution/protocol/evm.go:42`,
  `execution/receipts/derive.go:53`, `execution/engineapi/engine_server.go:136`. The whole
  execution/RPC surface is written against `EngineReader`, never `Engine`.

## Design decisions & rationale

- **Reader/Writer interface split for read-only consensus.** Splitting `Engine` into
  `EngineReader` (thread-safe, execution/RPC methods) and `EngineWriter` (verification/sealing)
  lets an rpcdaemon/archive node type its whole execution surface against the reader half and
  never construct the writer/infra-heavy engine (`bor.NewRo`, `remoteRulesEngine`). geth has one
  monolithic `consensus.Engine`; erigon's split is the notable improvement — the RPC path
  provably needs less than a validating node.
- **Positive type-switch selection, conditional merge.** `CreateRulesEngine` keys off the
  concrete config type with a `panic` on unknown and no default-to-ethash fallthrough — the
  positive-marker dispatch B7.0 §A.1 adopts. The merge is wrapped **only when TTD is set**, so a
  never-merging chain (ETC) is never force-wrapped — the exact *conditional* shape geth lost when
  it made the beacon wrap mandatory, and the one fukuii needs (B7.0 §C Option 2).
- **A sidechain is a module, not a config flag.** Polygon/Bor is a whole `polygon/` subtree with
  its own P2P, DB, sync, and two oracle services (Heimdall, bridge). erigon does not try to model
  Bor as extra fields on the mainnet chain config; it accepts that a foreign consensus family
  needs its own module and its own out-of-band data sources.
- **External-infra injection through the constructor, not the interface.** The base `rules.Engine`
  interface stays clean (it has no `Heimdall`/`bridge` methods); the extra infra is threaded into
  the concrete `bor.New(...)` constructor from the node wiring. The generic interface is not
  polluted by one family's dependencies — a good separation, but it means engine construction is
  *not* uniform across families (bor's constructor signature is bespoke). aura's DB-opening in
  `CreateRulesEngine` is the same idea at smaller scale.
- **Typed reward attribution.** `Reward{Beneficiary, Kind, Amount}` with a `RewardKind` enum lets
  AuRa (empty-step, external reward-contract) and PoW (author, uncle) return structurally-typed
  reward sets rather than an ad-hoc address→amount map — an OpenEthereum-lineage refinement.

## Notable patterns (the reusable idea)

1. **EngineReader / EngineWriter split → read-only consensus.** Partition the engine interface so
   the execution/RPC/tracing path depends only on the thread-safe reader half; a read-only node
   (`bor.NewRo`, `remoteRulesEngine.Engine() rules.EngineReader`) then runs consensus-aware RPC
   without the verification/sealing/external-infra machinery. The named pattern for this file.
2. **Sidechain-as-module with external-infra injection (the NET-01 reference).** A foreign
   consensus family (Bor/Polygon) is a self-contained `polygon/` module whose engine takes
   out-of-band services (Heimdall span/validator oracle over HTTP, state-sync bridge) as extra
   constructor deps the base `Engine` interface never sees. This is precisely the "extra-infra
   injection" pattern fukuii's B7.0.5 `NetworkFamily` design must account for — designed against a
   *real* second family, not speculatively.
3. **Positive type-switch dispatch + conditional decorator merge.** `switch config.(type)` with a
   `panic` on unknown (no ethash fallthrough), and `merge.New(eng)` wrapped only when TTD is set —
   the reference-uniform shape (shared with geth's `beacon`, besu's `TransitionProtocolSchedule`)
   for content-derived, skippable-for-PoW merge composition.
4. **Typed reward-attribution enum.** `RewardKind{Author,EmptyStep,External,Uncle}` +
   `Reward{...}` — a structural way to carry heterogeneous reward semantics across mechanisms.

## Authority note

**erigon is THE sidechain / Bor (Polygon) authority** and the authority for the
**EngineReader / EngineWriter read-only-consensus pattern** — per the Phase-0 authority model
("erigon — sidechain (Bor/Polygon), staged sync, performance/DB"). This file is the **direct
reference for fukuii's NET-01 (Polygon) network family** and for the **B7.0.5 external-infra
-injection design**: Bor's `New(…, polygonBridge, heimdallService)` is the canonical precedent
for a consensus engine that needs out-of-band infrastructure the base interface doesn't model,
and its `polygon/` module layout proves a sidechain is a module, not a config flag.

erigon is **not** the ETC/PoW authority (that is core-geth — ETChash/ECIP-1017/1099/1111/1122;
erigon has no ECIP awareness) nor the canonical ETH/PoS baseline (that is go-ethereum's
`consensus.Engine`). Its ethash/merge engines are a valid ETH-family cross-check, and its
**aura** engine is a secondary PoA reference (besu is the primary multi-consensus/PoA authority
for B7.1/B7.2). Where erigon *leads* is the reader/writer split and the sidechain module —
neither geth nor besu factor consensus this way.

## Gotchas / anti-patterns / things they later changed

- **`CreateRulesEngine` panics, doesn't return errors.** Unknown config (`:98-99`), aura DB-open
  failure (`:78-80,83-85`), and nested-merge (`merge.New` `:78`) all `panic`. Fine for a fatal
  boot path, but it means engine selection has no graceful-degradation story — do not copy the
  panic-on-unknown into a hot path.
- **bor's double activation guard is subtle.** A `*borcfg.BorConfig` that matches the type-switch
  still runs as **plain ethash** unless `chainConfig.Bor != nil && ValidatorContract != ""`
  (`:91`). The comment (`:88-90`) warns that "bor != nil will also be enabled for ethash" — a real
  footgun where a partially-configured bor chain silently degrades to PoW.
- **bor's config is an interface, not a pointer** (`chain_config.go:123`, `Bor BorConfig
  json:"-"`, hydrated from `BorJSON`) — diverges from the `*EthashConfig`/`*AuRaConfig`
  pointer-presence convention, so a naive "check the pointer is non-nil" dispatch doesn't
  generalize to bor. A structural inconsistency to be aware of when porting the presence-marker
  idea.
- **The reader/writer split leaks a bor-specific method into the shared reader.**
  `ChainHeaderReader.FrozenBorBlocks(align bool)` (`rules.go:69`) is on the generic reader every
  engine gets — a small Polygon-ism bleeding into the shared interface. A reminder that erigon's
  abstraction is not perfectly family-neutral.
- **`remoteRulesEngine` self-flags an incomplete unification.** `init` carries
  `// TODO(yperbasis): try to unify with CreateRulesEngine` (`config.go:1059`) — the rpcdaemon
  re-implements engine construction (aura remote-DB, `bor.NewRo`) separately from the node path.
  Cite the *shape* (reader-only remote engine), not it as finished art.
- **Naming counter-intuition.** `VerifyHeader`/`VerifyUncles`/`CalcDifficulty` are conceptually
  read-only yet live in **`EngineWriter`**, because the split is "execution/RPC half" vs
  "validating/producing-node half," not literally "reads state" vs "writes state." Read the
  comments (`rules.go:115-116,148-149`), not just the type names.
