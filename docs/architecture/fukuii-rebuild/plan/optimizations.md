# Optimization & additive-feature catalog (cross-layer)

The cross-layer home the rubric (`REVIEW.md` §4.1) requires: **every performance/architecture optimization
and additive feature the SR documented, mapped to its layer with an explicit disposition** — so an
optimization the research found is never silently lost between layers. Sourced from the 7 Wave-1 mining
dossiers (each dossier's "OPTIMIZATIONS" + "OPTIONAL(role) additive" sections); the dossiers hold the full
per-item detail and citations, this file is the **index + disposition ledger**.

**Disposition legend:**
- **DEFAULT** — adopt as the baseline best practice.
- **OPTIONAL(role)** — build the *seam* now, ship the mode feature-flagged / role-gated (the feature-
  completeness hunt — a genuinely-worth-having pattern from any client, incl. erigon/reth/nethermind).
- **OBSOLETE** — understood and consciously **avoided** (named, not silent).
- **STRUCTURAL** — the *seam* must be designed into the foundation **now** or it's a future rewrite
  (future-proofing filter); the *occupancy* may defer.

Authority per concern is the `REVIEW.md` §3 map; **besu is the JVM-implementation guide throughout.**

---

## L0 — foundation (`bytes`/`crypto`/`rlp`)

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| Dual native+pure crypto backend behind one API | geth/core-geth/besu/erigon | **STRUCTURAL (seam)** / OPTIONAL(role) impl | Seam designed now (retrofit = every call site); native = mining-pool/archival throughput, pure = enterprise no-JNI single-binary. Pure path must re-add checks native does for free (erigon `signature_nocgo` low-S). |
| `constantTimeEquals` primitive | besu/nethermind | **STRUCTURAL (R11)** | L0 exposes it; keystore MAC/ECIES-tag/JWT call it. fukuii's keystore uses `==` today. |
| Per-instance (not global-static) curve/backend selection | (besu `SignatureAlgorithmFactory` = the anti-pattern) | **STRUCTURAL (R2)** | Global-static curve selection breaks multi-instance; per-instance/per-call. |
| Named-variant RLP registry (2 forms/type: storage-vs-wire) | nethermind `[Rlp.Decoder(key)]` | OPTIONAL(additive) | The reference for L1 blob consensus-vs-network-wrapper. |
| Alloc-benchmark harness (profile-driven) | erigon (`PERFBENCH.md`+baseline) | OPTIONAL(perf) — schedule w/ proving bench | The *harness discipline* transfers even where `unsafe`/cgo doesn't. |
| Zero-alloc value structs (`ref struct`/inline bytes) | nethermind | **OBSOLETE** | Doesn't port to JVM (opaque types erase wrapper, can't inline 32 bytes). |

## L1 — `domain`

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| `enum Transaction` (illegal field combos unrepresentable) | (fukuii divergence from besu optionals-on-one-class) | **DEFAULT** | Exhaustive; one `given RLPCodec` arm per variant. |
| `ISigner`/`ISignerStore` read/write split | nethermind | **STRUCTURAL (seam, R9/R11)** | Consensus depends only on `ISigner`; key-location a DI choice. The pivot for HSM/clef/validator-remote signing. |
| External-signer backends (SecurityModule/HSM, clef, Authorize-SignerFn) | besu / geth+nethermind / erigon | OPTIONAL(role: custody/validator) | Schedule at L8 with proving tests. |
| tx-hash `atomic`/lazy memoization | erigon | OPTIONAL(perf) | Parity-correct mutation (`mutable-state-parity` — needs no purity justification). |
| Compile-time `NodePrimitives`/`NodeTypes` family | reth | **OBSOLETE (for R2)** | Compile-time monomorphized families conflict with runtime multi-instance selection. |
| Permanent in-node account unlock (`personal_*`, unlock-forever) | geth (fukuii sits on it) | **OBSOLETE** | The custody anti-pattern; L1 signing holds no unlock state. |

## L2 — `storage`/`trie` (the optimization-dense layer)

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| **`StorageProfile` role×network selector** (multi-approach) | besu `DataStorageFormat` shape | **STRUCTURAL (R8)** | The #1 L2 item: {keying × pruning × flat × freezer × expiry × backend} from line one, not one hardcoded store. |
| Node-keying scheme indirection (hash **and** path) | nethermind `INodeStorage` (mutable `Scheme` + dual-read) | **STRUCTURAL (seam, R8)** | archival=hash / pruned=path as a role choice + online Hash→HalfPath migration. fukuii inherited core-geth-hash; path is the ref standard. |
| Streaming cursor + `HashBuilder` state-root substrate | reth (`TrieCursor`/`HashedCursor`, prefix-set) | OPTIONAL(validator/perf) — **seam-now** | Enables parallel state-root + flat-commitment; `ShardEnumerator`+`StackTrie` already align w/ go-ethereum `GenerateTrie` (`triedb/generate.go`, 16-partition; reth has no `GenerateTrie` — it contributes `HashBuilder`/`TrieCursor` only). |
| Flat-state accelerator on the live account path | geth snapshot / besu Bonsai | **DEFAULT (adopt-on)** | fukuii `FlatAccountStorage` populated but NOT on live read (asymmetric — the gap). |
| Online full-pruning (copy-live + atomic swap) | nethermind `FullPruningDb` | OPTIONAL(custody/end-user) | "The biggest missing operator capability"; can re-key Hash→HalfPath in the pass. |
| Composable `IPruningStrategy`/`IPersistenceStrategy` split | nethermind | **DEFAULT (reconcile w/ the 3-mode enum)** | "when to evict RAM" ⊥ "when to flush disk" as composed policies. |
| Live-tunable compaction (`ITunableDb`) | nethermind | OPTIONAL(perf) | Sync-phase HeavyWrite/DisableCompaction — a cited fix for fukuii's SNAP memtable-starvation incident. |
| Per-CF self-describing config (`SegmentIdentifier`) | besu | **DEFAULT** | Static CFs get BlobDB/GC flags; hot CFs cache priority. fukuii shares ONE `ColumnFamilyOptions` today. |
| Per-table tune-against-defaults (disable compression+bloom for hash tables) | reth `tx_hash_numbers` | OPTIONAL(perf) | Obs flags "under-explored." |
| Hot/cold freezer (append-only, number-addressed) + **TD retention** | geth freezer / core-geth (ETC TD) | **DEFAULT (storage-lifecycle)** — seam-now | fukuii has NO hot/cold split. Cold store MUST retain TD (core-geth ETC invariant). reth write-time-boundary freeze avoids the geth freeze-pass M2 bug. |
| Unify freezer/era1/expiry into one sharded cold format | geth tail-groups + reth whole-file | OPTIONAL(archival) | One fixed-range format buys freeze+distribute+expire. |
| ERA1 standard bulk-history format | geth/besu/nethermind/reth all | OPTIONAL(archival) | fukuii's `CheckpointArchive` is bespoke (non-interoperable); use ERA1 if built. |
| BonsaiTrieLog `{prior,updated}` leaf-diff | besu | OPTIONAL(light) + **R7 enabler** | A clean serializable reorg-event source (fukuii's node-refcount snapshots are not). |
| Schema-version marker incl. active `StorageProfile` | besu `DATABASE_METADATA` + reth 3-marker/nethermind auto-detect | **STRUCTURAL** | fukuii has NONE; must record keying/pruning/freezer so a mode-mismatched datadir fails at open. |
| MDBX + flat Domains / secondary-engine split | erigon / reth | **OBSOLETE** | No mature JVM MDBX binding; a re-architecture, not a swap. |

## L3 — `evm`

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| `ForkActivation`/`ForkSchedule` family-blind dispatch **at L3** | reth `ForkCondition` | **STRUCTURAL (R1)** | fukuii already built it; collapse the 2 `forBlock` overloads. (Draft wrongly defers to L5.) |
| `GasCalculator`-as-strategy-object (unify fee values + gas computation) | besu | **DEFAULT** | fukuii splits fee-chain vs opcode `varGas` — touch both today. |
| Dense `IArray[OpCode]` + build-time `validate` | besu `OperationRegistry` | **DEFAULT** | fukuii uses `Map[Byte,OpCode]` (probe+box), no validate. |
| Branch-free `NoTracing` singleton | besu `NO_TRACING` | **DEFAULT** | fukuii fires from 2 slots every opcode. |
| Generic/zero-cost tracer (Inspector) | reth/nethermind | OPTIONAL(archival/RPC — R8) | `debug_*`/archival same path. |
| Multidimensional gas (EIP-8037) | erigon `mdgas`/nethermind | OPTIONAL(future-EIP) | Shape `GasCalculator` so a 2nd dim adds behind it; don't port the typed `Run` signature. |
| Inlined-switch hot loop | besu/nethermind | **OBSOLETE (measured-fragile)** | "Two dispatch tables of record" = bug surface; keep single dense table. |
| Immutable `ProgramState` interpreter loop | (fukuii-only) | **OPEN (benchmark-gated)** | `mutable-state-parity` says it's a divergence+regression; but it helps R2 isolation. Decide w/ a bench, not inertia. |

## L4 — `execution`

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| `ProtocolSpec` immutable per-fork bundle (wraps L3 `EvmConfig`, resolve fork ONCE) | besu | **STRUCTURAL** | fukuii re-derives `forBlock` at 6+ sites. Two resolutions (L3+L4) = the "ask mid-execution" shape besu eliminates. |
| Reward/finalize = sole economics seam, fail-LOUD selection | besu `rewardCoinbase` / nethermind (quiet-zero = the trap) | **DEFAULT (R1)** | ECIP-1017 goes where ETH withdrawals go; no `if(isPoW)`. Selection match fails loud. |
| `RequestType`→processor map + `noOp` degradation | besu `RequestProcessorCoordinator` | **DEFAULT** | fukuii hard-codes the system-call loop. |
| Per-block execution outcome + state-diff (feeds L5's reorg-aware event source) | reth ExEx / besu `BonsaiTrieLog` | **STRUCTURAL (R7, L4's half)** | L4 emits per-block `BlockExecutionOutcome`s (block + serializable state-diff); **L5 aggregates the reorg-aware `ChainNotification` wire ADT** (L5 owns reorg authority). L4 does not define the wire type. fukuii has neither. |
| Deferred commitment to block boundaries + flat-state reads | erigon `SetDeferCommitmentUpdates` | OPTIONAL(perf, R8) | fukuii `persistState` is **per-tx** (the churn erigon batches). |
| Prewarming + parallel post-loop bloom/receipts/root | nethermind | OPTIONAL(perf) | Lighter than queue-async; split it out. |
| Block-STM optimistic parallel execution | erigon exec3 | OPTIONAL(archival/perf) | Hard conflict edges (CREATE2/selfdestruct); not baseline. |
| ECIP-1017 explicit integer `4^era`/`5^era` | core-geth (byte-authority) | **DEFAULT (byte-critical fix)** | fukuii uses `BigDecimal.precision` — the top L4 correctness hazard. |

## L5 — `consensus` (framework)

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| `given NetworkFamily` typeclass **sized to XDC-stress depth** | reth type-safety + nethermind runtime + erigon packaging | **STRUCTURAL (R1)** | Must express rollup cluster-swap + new-tx-type + alt-BFT custom-header/block-tree, not just PoW/PoS. Occupancy defers; seam depth cannot. |
| Runtime self-declaring plugin registry (2-tier: auto-derived + `EmbeddedFamilies`) | nethermind `IConsensusPlugin` | **DEFAULT (runtime-openness)** | Port the self-declaration idea, not reflection/Autofac. |
| Reorg-aware event stream — the sole home of the `ChainNotification` wire ADT (from `ConsensusResult`) | reth ExEx | **STRUCTURAL (R7/R9)** | The requirements matrix lands "reorg events" at L5; L5 owns reorg authority, so `consensus-api` defines the `ChainNotification` ADT **once** (reth 3-case segment shape), fed by L4's per-block outcomes, imported by L9's `grpc-seam`. Carries the replay-by-height + monotonic-ID guarantee L9's resume cursor needs. |
| Conditional merge `EngineSchedule` (compose over any base + TTD-gate) | erigon `merge.New` / nethermind `MergePlugin` | **DEFAULT** | ETC never wrapped; absorbs the future Clique→PoS case. |
| CL-liveness guard (`effectiveThreshold`→"CL offline") | besu `PivotSelectorFromSafeBlock` | OPTIONAL(R9 embedded-CL) | Sync detects a stalled CL, fails loud. |
| CL+EL narrow in-proc `ExecutionEngine` contract | erigon Caplin | **STRUCTURAL (seam, R9)** | Shape the decomposed Engine-API driver as the ~15-method contract so an embedded CL attaches w/o JSON-RPC/JWT. |
| Internal CPU sealing (Olympia fork-testing vehicle) | core-geth | OPTIONAL(private-PoW-testnet, F5) | Machinery exists; mode-wiring + flag only. |
| Deep-reorg cap (geth 32 / besu 90_000) | geth/besu | **DEFAULT (safety)** | fukuii has none visible. |
| BFT proposer QBFT (instant finality) | besu | OPTIONAL(enterprise/consortium, Batch-7) | The seams map 1:1 onto besu G1/G2/G3. |
| Read-only `EngineReader`/`EngineWriter` split | erigon | OPTIONAL(R8 archival/relay/light) | A relay/archival node never constructs the sealer. |
| `else-means-ETC` / mandatory-merge-wrap / AuRa | geth / geth-PoS / Parity | **OBSOLETE** | Positive keying; conditional wrap; AuRa named-and-avoided. |

## L6 — `network`

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| Generic range-advertising wire multiplexer | besu `CapabilityMultiplexer` | **DEFAULT** | Replaces fukuii's `best()`-collapse + hand-picked `0x30` SNAP offset. Each `NetworkFamily` contributes its set. |
| Config-bounded advertised range (staged fleet rollout) | besu `getMin/MaxEthCapability` | OPTIONAL(R2 enterprise) | Cap a new wire version's advertisement without code. |
| Streams-Tcp RLPx stages (MOD-13) | (besu channel-pipeline structural mirror) | **STRUCTURAL (R5)** | Kill the Classic `pekko.io.Tcp` bridge; RLPx = stream stages. |
| Bootnode serving + ENR/DNS-tree authoring (F7/F8) | go-ethereum/core-geth (serve side) | OPTIONAL(infra, R9) — **the F8 gap** | fukuii covers *consume* side only; the serve/author side is missing. |
| Full wire range ETH68-71 (per-network) | besu (advertises 68-71) | **DEFAULT (F10)** | ETC=68-frozen; ETH=69/70/71. ETH70 handshake-unwired today; eth/71=EIP-8159 BAL. |
| Direction-keyed blacklist/ban policy | besu `PeerDenylistManager` | **DEFAULT (schedule the policy)** | L6 owns the type → owns the policy. fukuii's 365-day tier is a 2-orders-of-magnitude outlier. |
| DialRatio + per-IP inbound throttle (LAN-exempt) | geth | OPTIONAL(R2 consortium) | LAN-exemption matters for co-located fleets. |
| Sentry (P2P-as-gRPC-service) | erigon | OPTIONAL(product-family, R7) | Keep the consumer API message-passing-first so the lift is cheap. |
| `transport`-interface `MsgPipe` test seam | geth | OPTIONAL(test) | Whole stack over in-memory pipe, zero sockets. |

## L7 — `sync`

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| SNAP serving (workhorse) — DoS-bounded, role-gated | geth (size) + besu (size+wall-clock) | OPTIONAL(role: server/archival/bootnode, F9) | Default-off → on. fukuii covers serving-caps in DoD but not as a role feature. |
| SNAP/v2 (EIP-7928 BAL-diff) versioned `Syncer` | geth (gated) | OPTIONAL(per-network, F9) | v1=ETC+current-ETH, v2 where BAL applies. Unified `Syncer` so v1/v2 don't cross-load. |
| Staged-sync **resumability + ordered unwind** (not the pipeline) | erigon `SyncStageProgress` / reth `StageCheckpoint` | **DEFAULT (extract the pieces)** | Per-stage progress integer; plain-reverse unwind + centralized `on_stage_error` (reth) vs hand-tuned order (erigon). **Do NOT** adopt the staged Execution pipeline (MPT-incompatible). |
| Bounded-batch backpressure (re-enter) | erigon `ErrLoopExhausted` / reth `ExecOutput{done:false}` | **DEFAULT** | Cap write-tx/memory per commit. |
| Per-context peer allocation (`AllocationContexts`) | nethermind | OPTIONAL(perf) — **the anti-starvation seam** | Partition the pool so Snap and FastHeaders don't contend. |
| `[Flags]` combinable mode-set + `IsMultiFeed` | nethermind `MultiSyncModeSelector` | OPTIONAL(enterprise) | Treat "current mode" as a set, not a scalar. |
| FastBlocks backward-fill | nethermind | OPTIONAL(archival) | Distinct from fukuii's forward-only `ChainDownloader`. |
| Distance-thresholded mode selection (`MIN_BLOCKS_FOR_PIPELINE_RUN`) | reth | OPTIONAL(perf) | Clean numeric seam between bulk-catch-up and keep-up-with-tip. |
| Per-family `PivotBlockSelector` (injected seam) | besu | **DEFAULT** | fukuii uses a source-tag, not an injected strategy. |
| PoW-from-genesis head (core-geth) kept separate from SNAP-wire (geth) | core-geth | **DEFAULT (ETC) — regression trap** | "Modernizing" the head toward geth-HEAD/reth erases the CL-less PoW path. |
| DB-snapshot bootstrap (`Init.Snapshot`) | nethermind | OPTIONAL(archival) | A distinct checkpoint variant fukuii lacks. |

## L8 — `txpool`/`keystore`/`observability`

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| Layered/sub-pool structured txpool + composable filter chain | besu layered / nethermind `IIncomingTxFilter` / reth | **DEFAULT** | Replaces fukuii's flat Guava cache; single banksy `TipTooLowFilter` (kill 2 inline ECIP-1122 copies). |
| Fee-aware global-worst O(log n) eviction + 10% price-bump replace | nethermind `SortedPool` / reth `discard_worst` | **DEFAULT** | fukuii lacks both. |
| Direct-push-for-small-txs to √peers | go-ethereum eth/68 | OPTIONAL(adopt-or-defer) | fukuii announces hash-only for all (diverges from DEFAULT). |
| Per-instance metric registry (Bug-29) | (fukuii's shared static = the bug) | **STRUCTURAL (R2)** | Byte-identical `/metrics` across instances today. |
| 15 shipped Grafana dashboards versioned to metrics | (fukuii + erigon only) | **DEFAULT (R10/F6)** | Preserve + version; add erigon cross-network QA-regression dashboard as a perf gate. |
| Consensus-family-aware health (PoW liveness vs PoS CL-alive) | nethermind | **DEFAULT** | Machine-readable errors, decoupled from metrics port. |
| Keystore hardening (atomic-rename, zeroing, ProtectedPrivateKey) | geth (verify+rename) / nethermind (DPAPI) | **DEFAULT (R11)** | fukuii `Files.write` direct (torn-key risk); bare keys in heap. |
| Kamon metrics | (fukuii) | **OBSOLETE** | Dropped (also clears an sbt-2 blocker); Micrometer/Prometheus. |

## L9 — `rpc`/`grpc-seam`

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| **Native MCP/A2A/ACP agentic interface** | erigon (node-level MCP authority) | **DEFAULT (adopt+build) + STRUCTURAL auth** | Embedded-SSE + standalone stdio/SSE, schema'd tools, real actor-state, read-only-default. Additive-L9 **except the auth/capability gate (R11-structural)**. fukuii's MCP is 7.2% coverage today. **erigon proves the read-only transport + tool-schema half only** (`rpc/mcp/mcp.go`, 1:1 read-only `eth_*` mirror, no write tools, no auth — RX-XC-09); fukuii's **write-ops + real-actor-state + the R11 auth gate are fukuii-original** (MCP-2026 spec, not adopted from erigon), each MCP server **per-`ChainInstance`** (R2). |
| gRPC `grpc-seam` = ExEx-over-`StateChanges` (reorg + `FinishedHeight` + WAL) | reth (framework) + erigon (transport) | **DEFAULT (R7) — mechanism must be specified** | Tree-vs-pipeline WAL gate; reorg-always-delivered-below-finished-height; per-consumer-cap-1 + reorderable buffer; wake-after-drain deadlock guard; subscription-ready ack; pull-path = reconcile path. |
| METHOD_NOT_ENABLED vs METHOD_NOT_FOUND | besu `ApiGroupJsonRpcMethods` | **DEFAULT** | fukuii collapses both to `-32601`. |
| Per-module bounded concurrency (`debug_*`/`trace_*` ⊥ `eth_*`) | nethermind | OPTIONAL(enterprise) — recommend | fukuii's own Engine-API isolation pain argues for it. |
| Engine-API dedicated `ActorSystem`+`IORuntime` isolation | (fukuii-superior; keep) | **DEFAULT** | Prevents peer/sync flood starving `fcU`. |
| circe (not json4s) / Caliban 3.1.2 (not Sangria) | — (MOD-06/14) | **DEFAULT (R6)** | Born-modern, sentinel-gated. |
| `AccountChange.incarnation` in the state-diff wire record | erigon | **OPEN (owner: vault+forge)** | Selfdestruct/recreate; expensive to change post-ship (R7 out-of-process readers). |
| Nil-hook / not-installed-wrapper short-circuit for mutation-notification | go-ethereum (`state_processor.go:77` + `statedb_hooked.go:44,168`; **no `HasHook()` symbol** — grep-confirmed absent) | **DEFAULT (hot-path)** | Reason-tagging must be ~free when no consumer attached — don't install the hooked wrapper, not a per-mutation branch. |

## L10 — `node`

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| Typed guardian tree + `CoordinatedShutdown` + drain-before-DB-close | geth `Lifecycle`/`ShutdownTracker` + erigon `WaitIdle` | **DEFAULT (R5)** | fukuii is Classic-rooted; no unclean marker, no drain. |
| B7.0.5 `given NetworkFamily` registry wired + `EngineId.fromMarkers` shipped | nethermind SPI (idea) | **STRUCTURAL (R1)** | Retire the cake god-trait + `else-means-ETC`. |
| Spawn-time `ActorRef[Command]` DI | besu `ServiceManager` | **DEFAULT (R5)** | Replaces the ~90-builder cake. |
| Full R2 leak elimination (`ShutdownHookBuilder`, `ioRuntime` diamond, direct `Config.` reads) | (fukuii leaks) | **STRUCTURAL (R2)** | Add an isolation-regression grep DoD. |
| Retire `StorageConsistencyChecker` auto-shutdown-on-gap | (fukuii anti-pattern) | **DEFAULT (fix)** | Replace with marker + `fixDatabase` recovery. |
| Profile layer replaces sysprop network-swap | besu `CascadingDefaultProvider` | **DEFAULT (R2)** | Two instances can't select networks via a process-global sysprop. |
| Concurrent step-init (topo-sorted) | nethermind `IStep` | **OBSOLETE (consciously)** | "Hides undeclared-dep bugs under races"; use supervised phase-ordering. |
| Embedded-or-remote wiring (`direct.*` shim) | erigon | OPTIONAL(product-family, R7/R9) | The MCP embed-vs-standalone + dRPC-gateway lever. |

## Cross-cutting — testing / build / observability

| Optimization / feature | Client(s) | Disposition | Note |
|---|---|---|---|
| Assert-fails-**with-reason** ratchet | erigon `Fails(pattern,reason)` / reth `expect_exception` | **DEFAULT (R10)** | Replace fukuii's `BrokenEthTest` tag-hide (a re-passing fixture must flip loudly). |
| Assert-nonzero-fixture-count | (the REPO-06 false-green class) | **DEFAULT (R10)** | 8/9 ethtest specs ran zero tests nightly while green. |
| Fork-in-test-name (dual-family selectability) | besu | **DEFAULT (R10)** | ETC-Olympia and ETH-Osaka independently `--tests`-selectable. |
| besu acceptance-cluster DSL | besu | **DEFAULT (R10)** — the L10 multi-instance test vehicle | Boot PoW-ETC + PoS-ETH, verify isolation. Also Batch-7 GTM. **The fukuii port must EXTEND besu's `ThreadBesuNodeRunner` with assertions besu's own DSL cannot make (RX-XC-10)** — per-instance metric-registry isolation, per-instance auth, and no cross-`ChainInstance` mutable-state bleed — because besu's DSL boots with `NoOpMetricsSystem` (`:669`) + same-curve nodes and therefore can't test these R2 properties (the test-side of the "besu never solved R2" finding). |
| Content-hash fixture manifest + failure budgets | erigon | OPTIONAL | Seeded-RNG replay (reth). |
| Olympia fixture generation (mgen-analog) | core-geth `mgen` | **DEFAULT (ETC)** | The frozen `etclabscore/tests` stops at Mystique; Olympia needs generated vectors. |
| Auto-doc `update-book-cli` + `git diff --exit-code` | reth + go-ethereum `check_generate` | **DEFAULT (R10/F4)** | + `check_baddeps`-style CI DAG-enforcement gate. |
| Single version source + checksummed supply-chain gate | besu BOM + `verification-metadata` | **STRUCTURAL (R6, setup)** | sentinel-gated; no sbt-native equivalent → needs design. |

---

_Next: this catalog feeds Wave 2 — each layer's enrichment adopts/schedules/rules-out every row homed to it,
and the multi-pass (`REVIEW.md` §6b) checks that no row is left unaddressed. The per-item detail + citations
live in the 7 Wave-1 dossiers (transcripts); this file is the disposition index._
