# Fukuii Codebase Opportunity Map

Generated from best-practices research sprint, June 2026.
Sources: `scala/type-safety.md`, `pekko/typed-patterns.md`, `typelevel/patterns.md`, `evm-clients/snap-sync.md`

---

## Summary

**52 total opportunities across 9 categories.**
**11 high / 27 medium / 14 low priority.**

Already-tracked items (S1–S10, P1–P16) are listed under "Already Tracked" and excluded from the counts above.

---

## 1. Opaque Type Leakage — `.value` Inside Layer Boundary

**Maps to:** `scala/type-safety.md` §1–§3, §8–§9
**Violation count:** ~20 across 4 sub-clusters
**Priority:** high

The most impactful cluster. `TrieRoot`, `CodeHash`, and `BlockHash` opaque types are unwrapped with `.value` inside actor message handlers, coordinator state variables, and internal collection types — not at true I/O boundaries (RLP codec, RocksDB serialiser, wire encoding). The Typed actor command ADT carries `ByteString` in several places that should carry the opaque type, which means the compiler cannot catch `stateRoot ↔ storageRoot` transposition bugs in the sync layer.

### 1a — Actor-level `var` carrying `ByteString` instead of `TrieRoot`

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/fast/SyncStateSchedulerActor.scala` | 205 | `private var currentStateRoot: ByteString = ByteString.empty` — should be `TrieRoot` |
| `blockchain/sync/StorageRecoveryActor.scala` | 292 | `var currentRoot: ByteString = stateRoot` — both var and param should be `TrieRoot` |

**Fix:** Change the actor-level var types to `TrieRoot`; push `.value` to the single call site where the value enters `sync.initState` or the RLP boundary.
**Proposed rule:** S11 — Actor-level state vars that hold semantic domain types must use the opaque type, not the underlying raw type.

### 1b — Command ADT fields carrying `ByteString` instead of opaque type

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/StorageRecoveryActor.scala` | 63 | `PivotUnservable(rootHash: ByteString, ...)` — should be `TrieRoot` |
| `blockchain/sync/StorageRecoveryActor.scala` | 73 | `RecentRoot(…, stateRoot: Option[ByteString])` — should be `Option[TrieRoot]` |
| `blockchain/sync/StorageRecoveryActor.scala` | 91 | `activate(stateRoot: ByteString, ...)` — should be `TrieRoot` |
| `blockchain/sync/StorageRecoveryActor.scala` | 116 | `StateRootUpdated(stateRoot: ByteString, ...)` — should be `TrieRoot` |
| `blockchain/sync/StorageRecoveryActor.scala` | 140 | `StartStorageRecovery(stateRoot: ByteString, …)` — should be `TrieRoot` |
| `blockchain/sync/CombinedRecoveryScanner.scala` | 56 | `CombinedRecoveryScanner(scanRoot: ByteString, …)` — should be `TrieRoot` |

**Fix:** Change message field types to `TrieRoot`; propagate to callers; call `.value` only inside the MptStorage/RocksDB put boundary.
**Proposed rule:** S11 (also see `type-safety.md` §8)

### 1c — `Seq[(ByteString, ByteString)]` where second element is `TrieRoot`

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/CombinedRecoveryScanner.scala` | 21–22 | `missingStorageTries: Vector[(ByteString, ByteString)]` — second element is a storage root |
| `blockchain/sync/StorageRecoveryActor.scala` | 53 | `ScanResult(missingStorage: Seq[(ByteString, ByteString)])` — same |
| `blockchain/sync/StorageRecoveryActor.scala` | 124 | `missing: Seq[(ByteString, ByteString)]` parameter — same |
| `blockchain/sync/StorageRecoveryActor.scala` | 274, 446 | Same untyped tuple through multiple method layers |

**Fix:** Define `final case class StorageGap(accountHash: ByteString, root: TrieRoot)` and replace the raw tuple; the second element becomes `TrieRoot` and compiler catches any accidental swap.
**Proposed rule:** S11 extension — untyped tuple pairs where elements are semantically distinct must use a named case class or typed tuple alias.

### 1d — `.value` called mid-layer (not at codec/RocksDB boundary)

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/BytecodeRecoveryActor.scala` | 339–343 | `account.codeHash.value` used as key in `HashSet[ByteString]` and passed to `evmCodeStorage.get` |
| `blockchain/sync/StorageRecoveryActor.scala` | 501–508 | `account.storageRoot.value` stored in `HashSet[ByteString]` and added to missing tuple |
| `blockchain/sync/CombinedRecoveryScan.scala` | 47–56 | Same pattern: `codeHash.value`, `storageRoot.value` added to mutable collections |
| `blockchain/sync/fast/SyncStateSchedulerActor.scala` | 324, 327 | `root.value` assigned to `currentStateRoot: ByteString` and passed to `sync.initState` |
| `blockchain/sync/regular/BlockFetcher.scala` | 452 | `.recentCanonicalStateRoot.map(_.value)` — `TrieRoot` unwrapped before comparison to raw `ByteString` |

**Fix:** Lift the opaque type into the mutable collections and storage method signatures; call `.value` only inside the serialiser lambda.

---

## 2. `implicit class` Remaining (S4 Violations)

**Maps to:** `scala3-style.md` S4
**Violation count:** 41 non-consensus/non-domain occurrences
**Priority:** medium

S4 migration (extension methods over `implicit class`) is not yet started. The largest clusters are in the wire-protocol message codecs.

| File (path from src/main/scala) | Lines | Count |
|----------------------------------|-------|-------|
| `network/p2p/messages/SNAP.scala` | 86, 155, 255, 334, 430, 496, 565, 645 | 8 RLP encoder bridges |
| `network/p2p/messages/ETHPackets.scala` | 164, 224, 318, 407, 429, 782, 809, 860, 940, 975 | 10 ETH/wire codec bridges |
| `network/p2p/messages/ETH69.scala` | 51, 171 | 2 encoder bridges |
| `network/p2p/messages/WireProtocol.scala` | 20, 120, 155, 177 | 4 encoder bridges |
| `blockchain/sync/codec/MptNodeCodecs.scala` | 20 | 1 |

**Note:** `domain/`, `consensus/`, `crypto/` have 18 additional hits requiring FORGE/BEACON routing. The 41 non-domain hits are safe for mithril migration.

**Fix:** `implicit class FooEnc(val msg: Foo) extends RLPSerializable { ... }` → `extension (msg: Foo) def toRLPEncodable: RLPEncodeable = ...`
**Already tracked as:** S4

---

## 3. `null` Usage in Non-Classic Code (S2 Violations)

**Maps to:** `scala3-style.md` S2
**Violation count:** 8 actionable hits
**Priority:** medium

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/snap/SnapPathTrie.scala` | 58–61 | `private var first: Array[Byte] = null`, `private var last: Array[Byte] = null` — sentinel nulls |
| `blockchain/sync/snap/SnapPathTrie.scala` | 100, 122, 136, 142 | 4 null comparisons on `first`/`last` |
| `blockchain/sync/snap/actors/ByteCodeCoordinator.scala` | 651 | `var error: String | Null = null` — union-null accumulator |
| `db/dataSource/RocksDbDataSource.scala` | 285, 298 | `private var lastKey: Array[Byte] = null` — iterator sentinel |
| `BootstrapDownload.scala` | 66 | `Iterator.continually(zis.getNextEntry).takeWhile(_ != null)` — Java interop |

**Fix:** `SnapPathTrie` and `RocksDbDataSource` sentinels → `Option[Array[Byte]]`; `ByteCodeCoordinator` error accumulator → `var error: Option[String] = None`; `BootstrapDownload` → use `Iterator.continually(zis.getNextEntry).takeWhile(_ ne null)` at minimum (idiomatic Java-interop form; fully removing `null` here requires a custom iterator).
**Already tracked as:** S2

---

## 4. `asInstanceOf` Outside Consensus/VM/Crypto (S7 Violations)

**Maps to:** `scala3-style.md` S7
**Violation count:** 9
**Priority:** medium

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/PeersClient.scala` | 260 | `message.asInstanceOf[Message]` — message dispatch cast |
| `blockchain/sync/PeersClient.scala` | 285 | `ct.asInstanceOf[ClassTag[R]]` — ClassTag coercion for response routing |
| `jsonrpc/graphql/GraphQLSchema.scala` | 320 | `x.asInstanceOf[A]` — untyped GraphQL field resolution |
| `jsonrpc/graphql/GraphQLSchema.scala` | 321 | `other.asInstanceOf[A]` — same |
| `jsonrpc/EthTxJsonMethodsImplicits.scala` | 140–145 | 5× `item("field").asInstanceOf[BigInt]` — `Map[String, Any]` EIP-7702 authorization list deserialization |
| `network/rlpx/AuthHandshaker.scala` | 95, 135 | `nodeKey.getPrivate.asInstanceOf[ECPrivateKeyParameters]` — Bouncy Castle key cast (2 sites) |

**Fix (EthTxJsonMethodsImplicits):** Replace `Map[String, Any]` with a typed `SetCodeAuthorization` case class; eliminates all 5 casts.
**Fix (AuthHandshaker):** `nodeKey.getPrivate match { case k: ECPrivateKeyParameters => k.getD; case other => sys.error(...) }` — makes the failure explicit instead of a runtime `ClassCastException`.
**Fix (GraphQLSchema):** Add typed field extractors; this is bucket C (semantic risk).
**Already tracked as:** S7

---

## 5. Unbounded `SupervisorStrategy.restart` Without `.withLimit` (P20)

**Maps to:** `pekko/typed-patterns.md` P20
**Violation count:** 1
**Priority:** high

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/fast/FastSync.scala` | 425 | `.onFailure[Exception](SupervisorStrategy.restart)` — no `.withLimit` or `restartWithBackoff` |

A persistent codec failure or repeated bad peer message causes an infinite restart loop with no operational visibility. `FastSync` is the top-level sync coordinator — an infinite restart loop here silently stalls ETC sync without escalation.

**Fix:** Replace with `SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, randomFactor = 0.2).withMaxRestarts(10).withCriticalLogLevel(Level.ERROR, afterErrors = 3)`.
**Proposed rule:** P20 (from `typed-patterns.md`)

---

## 6. `PreRestart` Signal Missing From Supervised Actors (P19)

**Maps to:** `pekko/typed-patterns.md` P19
**Violation count:** 2 supervised actors, 0 `PreRestart` handlers in entire codebase
**Priority:** high

Zero occurrences of `PreRestart` in `src/main/scala`. Two actors use `Behaviors.supervise`:

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/fast/FastSync.scala` | 408–425 | `Behaviors.supervise(...).onFailure[Exception](restart)` — no `PreRestart` handler |
| `faucet/FaucetSupervisor.scala` | 37–42 | `onFailure[WalletException](stop)` + `onFailure[Exception](restartWithBackoff)` — no `PreRestart` handler |

Any `messageAdapter`-based subscriptions or external resource handles opened in `Behaviors.setup` leak on each restart cycle.

**Fix:** Add `.receiveSignal { case (_, PreRestart) => releaseResources(); Behaviors.same }` alongside any `PostStop` handler in each supervised actor.
**Proposed rule:** P19 (from `typed-patterns.md`)

---

## 7. `messageAdapter` Called Inside Receive Handler (P17)

**Maps to:** `pekko/typed-patterns.md` P17
**Violation count:** 1 confirmed
**Priority:** high

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/PeersClient.scala` | 224 | `val prhAdapter = ctx.messageAdapter[PeerRequestHandler.Result](r => PRHResultCmd(id, r))` — inside `running(...)` receive case handler, invoked on every `RequestPeer` message |

Pekko documentation: "one adapter per message class, last registration wins." Creating a new adapter per-request silently replaces the previous one. With multiple in-flight peer requests in `PeersClient`, the second `RequestPeer` message's adapter registration overwrites the first — the first request's response gets misrouted to the second request's `id`.

The other `messageAdapter` calls (lines 89–98) are inside `Behaviors.setup` scope — correct.

**Fix:** Hoist the adapter into `Behaviors.setup`. Since each PRH needs a distinct `id` for routing, embed `id` into `PeerRequestHandler.Result` and use a single adapter: `ctx.messageAdapter[PeerRequestHandler.Result](WrappedPRHResult(_))`. Alternatively, use `context.ask` per request — it is designed for one-shot request/response and is safe under concurrent in-flight requests.
**Proposed rule:** P17 (from `typed-patterns.md`)

---

## 8. `context.watch` Without `watchWith` in Typed Actors (P9)

**Maps to:** `pekko-typed-api.md` P9
**Violation count:** 3 in Typed actors
**Priority:** medium

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/regular/BlockFetcher.scala` | 70 | `context.watch(headersFetcher)` — Typed actor, no `receiveSignal` handling `Terminated` |
| `blockchain/sync/regular/BlockFetcher.scala` | 77 | `context.watch(bodiesFetcher)` — same |
| `blockchain/sync/regular/BlockFetcher.scala` | 84 | `context.watch(stateNodeFetcher)` — same |

`BlockFetcher` is a Typed `AbstractBehavior`. The three children are spawned and watched but there is no `receiveSignal` or `onSignal` handler. If a child dies, `Terminated` fires into dead letters and `BlockFetcher` continues silently with a dead child reference.

Note: `RLPxConnectionHandler` hits at lines 207/239 are inside `ClassicActor` (confirmed at line 197) — not violations.

**Fix:** Convert to `context.watchWith(x, FetcherStopped(x.path.name))` and add a `case FetcherStopped(name) =>` handler that logs and restarts or escalates.
**Already tracked as:** P9

---

## 9. `IORuntime.global` Scattered Across Actor Files (Typelevel §1)

**Maps to:** `typelevel/patterns.md` §1
**Violation count:** 26 call sites outside consensus/crypto
**Priority:** low

Representative sites (full list is 26 files):

| File (path from src/main/scala) | Pattern |
|----------------------------------|---------|
| `blockchain/sync/regular/BodiesFetcher.scala` | `given runtime: IORuntime = IORuntime.global` |
| `blockchain/sync/regular/BlockFetcher.scala` | same |
| `blockchain/sync/regular/BlockImporter.scala` | same |
| `blockchain/sync/fast/SyncStateSchedulerActor.scala` | `implicit private val ioRuntime: IORuntime = IORuntime.global` |
| `consensus/pow/PoWMiningCoordinator.scala` | `implicit private val scheduler: IORuntime = IORuntime.global` |
| (21 additional sync actor files) | — |

Each actor independently reaches for the global runtime. The discovery layer (`DiscoveryServiceBuilder`) already passes `IORuntime` as a constructor parameter — the correct pattern. Sharing a single wired runtime allows coordinated shutdown metrics.

**Fix:** Pass `IORuntime` as an implicit constructor parameter from the top-level supervisor (`NodeApp`) down through the actor hierarchy. The `given runtime: IORuntime = IORuntime.global` givenDecls become `(implicit runtime: IORuntime)` constructor params. One supervisor layer change propagates to all 26 sites.
**Proposed rule:** TL1 — `IORuntime.global` is only permitted at the composition root (`NodeApp`). All other actors receive `IORuntime` as a constructor implicit.

---

## 10. `spawnAnonymous` for Watched Workers (P18)

**Maps to:** `pekko/typed-patterns.md` P18
**Violation count:** 6
**Priority:** medium

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `blockchain/sync/fast/SyncStateSchedulerActor.scala` | 425, 442 | Two anonymous state-sync workers |
| `blockchain/sync/fast/FastSyncBranchResolverActor.scala` | 352 | Anonymous request handler |
| `blockchain/sync/regular/BlockImporter.scala` | 830 | Anonymous resolver |
| `blockchain/sync/snap/actors/ByteCodeCoordinator.scala` | 703 | `context.spawnAnonymous(ByteCodeWorker(...))` |
| `blockchain/sync/snap/actors/AccountRangeCoordinator.scala` | 1055 | `ctx.spawnAnonymous(AccountRangeWorker(...))` |

Workers that are anonymously spawned are invisible in logs and actor hierarchy dumps. `ByteCodeCoordinator` and `AccountRangeCoordinator` track them by ref in a map — this works for dispatch but makes post-mortem analysis of worker crashes impossible.

**Fix:** Add a stable counter suffix: `context.spawn(ByteCodeWorker(config), s"bytecode-worker-$workerCounter")`. Convert `watchWith(worker, WorkerStopped(name))` to carry the discriminator.
**Proposed rule:** P18 (from `typed-patterns.md`)

---

## 11. Manual SLF4J MDC in Typed Actors (P22)

**Maps to:** `pekko/typed-patterns.md` P22
**Violation count:** 1 utility file + all callers
**Priority:** low

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `utils/Logger.scala` | 22–24 | `MDC.put(key, value)` + `MDC.clear()` — manual MDC lifecycle, not integrated with `context.log` |

`withContext` is a hand-rolled MDC wrapper. In Typed actors, calling it bypasses `context.log`'s thread-safety. `Behaviors.withMdc` provides the same capability natively: static MDC set at actor start, optional per-message lambda for dynamic context. It integrates directly with `context.log`.

**Fix:** Migrate high-value Typed actors (`PeerActor`, `PeerRequestHandler`, `SNAPSyncController`) to `Behaviors.withMdc[Command](staticMdc = Map("peerId" -> peer.id.value))`. Retain `Logger.withContext` for Classic actors and off-thread code where `withMdc` is unavailable.
**Proposed rule:** P22 (from `typed-patterns.md`)

---

## 12. `unsafeRunSync` Blocking Actor/Storage Threads (Typelevel §1)

**Maps to:** `typelevel/patterns.md` §1
**Violation count:** 3 actionable in production sync/storage code
**Priority:** medium

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `db/storage/HealingFrontierStorage.scala` | 43 | `.unsafeRunSync()(IORuntime.global)` — blocks caller thread during storage read |
| `db/storage/PathNodeStorage.scala` | 146 | `.unsafeRunSync()` — blocks if called from actor handler |
| `db/storage/PathNodeStorage.scala` | 156 | `.unsafeRunSync()` — same |
| `jsonrpc/server/ipc/JsonRpcIpcServer.scala` | 104 | `.timeout(awaitTimeout).unsafeRunSync()` — blocks IPC server thread per request |

The `SyncStateSchedulerActor` occurrence at line 244 (materializing `.start`, not the fiber body) is the documented acceptable case. `FaucetHandler` and `SignedTransaction` are at composition root / CLI boundary — lower risk.

**Fix:** `HealingFrontierStorage` and `PathNodeStorage` should return `IO[Result]` and be called via `pipeToSelf` from actor handlers. `JsonRpcIpcServer` should use `Dispatcher.sequential` scoped to the server's `Resource` lifetime.
**Proposed rule:** TL2 — `unsafeRunSync` is only permitted at the composition root (app startup, CLI utilities) and in test code. Production actor and storage code must use `pipeToSelf` or `IO.unsafeRunAndForget`.

---

## 13. `println` / `System.err.println` in Main Sources (S9 Violations)

**Maps to:** `scala3-style.md` S9
**Violation count:** 6 (8 raw hits, 2 are legitimate TUI/CLI use)
**Priority:** low

| File (path from src/main/scala) | Line | Pattern |
|----------------------------------|------|---------|
| `crypto/SignatureValidator.scala` | 26, 35, 39, 44, 55 | 5× `System.err.println(...)` in ECDSA validation diagnostics |
| `crypto/EcKeyGen.scala` | 19 | `println(...)` — CLI key-generation output (stdout-to-terminal is intentional here) |
| `console/Tui.scala` | 112, 195 | `term.writer().println(...)` — JLine terminal API (not a violation — intentional TUI output) |

`SignatureValidator` is in `crypto/` — requires FORGE review before changing. `EcKeyGen` stdout is intentional. The 5 `SignatureValidator` sites are the actionable S9 hits.

**Fix (FORGE routing required):** Replace 5 `System.err.println` in `SignatureValidator` with SLF4J `logger.debug(...)` calls; these are diagnostic-only and do not belong on stderr in a production node.
**Already tracked as:** S9

---

## Gaps — Patterns from Research Completely Missing in Fukuii

These patterns are described in the research files but have zero implementation in the codebase:

1. **`Behaviors.withMdc`** (P22): Not used at all. All MDC is manual SLF4J. Highest-value missing pattern given the peer-session tracing benefit for `PeerActor` and `PeerRequestHandler`.

2. **`PreRestart` signal handlers** (P19): Zero occurrences. Two supervised actors (`FastSync`, `FaucetSupervisor`) have no resource-release path on restart — only on permanent stop.

3. **`ChildFailed` signal** (P21): Zero occurrences. All crash-cause information is lost when child actors die. Coordinators with `watchWith` get a generic `ChildStopped(name)` message but cannot distinguish crash from clean stop or log the exception.

4. **`LoggingTestKit` for supervised actor specs** (P24): Only one use (`PoWMiningCoordinatorSpec`). `FastSync` and `FaucetSupervisor` have no log-level assertions for crash paths.

5. **`ManualTime` for timer tests** (P23): Partially enforced (2 specs use it). Timer-bearing sync actors (`SNAPSyncController`, `BlockchainHostActor`) are not tested with time advancement — flaky candidates under full `testEssential` NUC load.

6. **`ValidatedNel` for batch transaction validation** (Typelevel §6): Zero uses. The `SignedTransactionsFilterActor` batch path uses `Either` (sequential short-circuit) — accumulating per-tx errors with `ValidatedNel` would give callers the full error set instead of failing on the first bad transaction.

7. **`log4cats` for IO-returning services** (Typelevel §8): Only `ForkIdValidator` uses log4cats. All other IO-returning services use static SLF4J loggers — not fiber-aware, not injectable in tests with a capturing logger.

8. **snap/2 protocol** (snap-sync §12–§24): `Capability.scala` defines `SNAP1` only. No `GetAccessListsMsg`, `AccessListsMsg`, `BALCatchUpActor`, or `GenerateTrie` path. Explicitly a future gap — requires EIP-7928 activation on ETH/Sepolia; not applicable to ETC.

---

## Already Tracked — Findings Matching Existing S1–S10 / P1–P16

These are confirmed violations the existing protocol already flags. They appear in this audit's grep output but do not need new proposed rules.

| Standard | Finding | Current Count from Audit |
|----------|---------|--------------------------|
| S2 | `null` in `SnapPathTrie`, `RocksDbDataSource`, `ByteCodeCoordinator`, `BootstrapDownload` | 8 hits |
| S4 | `implicit class` in network message codecs and sync codec | 41 non-consensus hits |
| S7 | `asInstanceOf` in `PeersClient`, `GraphQLSchema`, `EthTxJsonMethodsImplicits`, `AuthHandshaker` | 9 hits |
| S9 | `System.err.println` in `SignatureValidator` | 5 hits |
| P4/P18 | `spawnAnonymous` in `ByteCodeCoordinator`, `AccountRangeCoordinator`, `SyncStateSchedulerActor`, `FastSyncBranchResolverActor`, `BlockImporter` | 6 hits |
| P9 | `context.watch` without `watchWith` in Typed `BlockFetcher` | 3 hits |

**S3 (given/using over implicit):** COMPLETE per protocol — override-chain sites intentionally retained as `implicit val`.
**S6 (`&` over `with`):** 337 occurrences, already batched in Part 1 backlog.
**S8 (`isInstanceOf`):** 0 hits outside consensus/vm/crypto — clean per this audit.
**S1 (no `return`):** Not in scope for this audit; already tracked.
**P20:** `FastSync.scala` unlimited restart — explicitly flagged in `typed-patterns.md` P20 as a known issue. Promoted to new finding #5 above.
**P17:** `PeersClient.scala` per-request `messageAdapter` — flagged in `typed-patterns.md` P17. New finding #7 above.
