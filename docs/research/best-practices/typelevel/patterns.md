# Best Practices: Typelevel (cats / cats-effect / fs2) in Fukuii

Fukuii is a Pekko-actor-first codebase. Typelevel is a **supporting dependency**, not the
primary concurrency model. This document does not advocate migrating everything to IO; it
documents where typelevel already is, where it fits well, and where the boundary belongs.

Versions in use: cats-core 2.13.0 · cats-effect 3.6.3 · fs2 3.12.2 · log4cats 2.8.0

---

## Scope check

| What uses typelevel | Where |
|---|---|
| JSON-RPC service responses | `ServiceResponse[T] = IO[Either[JsonRpcError, T]]` — almost all of `jsonrpc/` |
| Actor↔IO bridge | `AkkaTaskOps` wraps Pekko ask patterns in `IO.fromFuture` |
| IO→actor bridge | `unsafeToFuture()` + `ctx.pipeToSelf` in sync actors |
| RocksDB iteration | `Resource[IO, RocksIterator]` + `Stream[IO, ...]` in `RocksDbDataSource` |
| Bloom filter loading | `Stream[IO, Either[IterationError, A]]` + `IO.memoize` in `LoadableBloomFilter` |
| Parallel ECDSA recovery | `IO.parTraverseN` in `SignedTransaction` |
| Parallel health checks | `parSequence` in `NodeJsonRpcHealthChecker` |
| Fork-ID validation | `Logger[F]`-polymorphic, `EitherT` for short-circuit |
| Discovery service | `Resource[IO, v4.DiscoveryService]` — full lifecycle management |
| Fire-and-forget import | `unsafeRunAndForget()` in `BlockImporter.importWith` |

Pekko actors own all long-running state, timers, supervision, and message dispatch. IO
appears at the **leaf level** inside actor message handlers — to wrap blocking calls, bridge
asks to IO, and express the RPC layer's service interface.

---

## 1. IO vs Future: the right boundary

**Rule:** Prefer `IO` over `Future` for any new async code written in IO-speaking layers
(JSON-RPC, Discovery, RocksDB iteration). Keep actors speaking `Future` at the `pipeToSelf`
boundary.

### Why

`Future` is eagerly evaluated and requires an `ExecutionContext` at every call site.
`IO` is a description: it composes without a runtime, is referentially transparent, and the
cats-effect work-stealing scheduler (1 thread per hardware core) is more efficient than
Pekko's dispatcher for fine-grained async work.

### The boundary that already works in fukuii

```scala
// Inside a Pekko Typed actor handler:
context.pipeToSelf(ioValue.unsafeToFuture()) {
  case Success(r) => GotResult(r)
  case Failure(e) => Failed(e)
}
```

`pipeToSelf` converts a `Future` result back into a typed actor message — the actor never
touches the IO runtime directly. This is the correct pattern. It is used consistently in
`HeadersFetcher`, `BodiesFetcher`, `StateNodeFetcher`, `StateStorageActor`, and others.

### What to avoid

- `unsafeRunSync()` inside an actor handler. It blocks the actor thread (pinning the Pekko
  dispatcher) until the IO completes. The only current occurrence is
  `SyncStateSchedulerActor` line 244, which starts a fiber with `.start` first — the
  `unsafeRunSync` there materialises the `start` call, not the fiber body. That specific use
  is acceptable but unusual; prefer `.start.unsafeToFuture()` + `pipeToSelf` for clarity.

- `IORuntime.global` scattered across 28 actor files. This works, but it means each actor
  independently reaches for the global runtime without coordination. Consider passing
  `IORuntime` through the actor props / companion object once per supervisor layer and
  threading it down. The discovery layer already does this (`DiscoveryServiceBuilder` takes
  `implicit scheduler: IORuntime`).

---

## 2. Resource for autoCloseable handles (already in use — keep doing this)

`Resource.fromAutoCloseable` is in production use for `RocksIterator`. This is the right
pattern for any handle with a `close()` lifecycle.

```scala
// RocksDbDataSource — correct
private def dbIterator: Resource[IO, RocksIterator] =
  Resource.fromAutoCloseable(IO(db.newIterator()))

// Usage: always via Stream.resource so the iterator closes when the stream terminates
def iterate(): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
  Stream.resource(dbIterator).flatMap(it => moveIterator(it))
```

### Upgrade: use `NonEmptyHotswap` for rotating resources

`cats.effect.std.NonEmptyHotswap` (supersedes the deprecated `Hotswap` since CE 3.7.0, which
is not yet our version but available from 3.6+) is useful when a resource must be replaced
without releasing the old one first and without accumulating handles. Example use case:
rotating RocksDB column family handles or checkpoint file handles during import.

```scala
// CE 3.6+ pattern for resource rotation
NonEmptyHotswap(initialResource).use { hotswap =>
  // ... on rotation trigger:
  hotswap.swap(nextResource)
}
```

Do not use `Hotswap` — it is `@deprecated("Use NonEmptyHotswap", "3.7.0")`.

---

## 3. The correct actor→IO bridge: `Dispatcher` vs `pipeToSelf`

Two patterns exist for running IO from inside an actor:

| Pattern | When to use |
|---|---|
| `ctx.pipeToSelf(io.unsafeToFuture())` | Actor needs the result as a typed message (most cases) |
| `io.unsafeRunAndForget()` | True fire-and-forget — actor does not need the result |
| `cats.effect.std.Dispatcher` | Bridging from *non-actor* reactive interfaces (callbacks, Pekko HTTP routes) |

`BlockImporter.importWith` uses `unsafeRunAndForget()` — the result arrives back via `self !
ImportDone(...)` inside the IO chain itself, so no `pipeToSelf` is needed. That is a valid
pattern but requires careful handling to ensure the result message always arrives (the current
`handleError` callback inside the IO ensures this).

`Dispatcher` is the right choice at the **HTTP/WebSocket boundary** where a Pekko HTTP route
needs to run an `IO` and return a `Future[HttpResponse]`. The existing JSON-RPC layer
currently uses `unsafeToFuture` directly; a `Dispatcher` is cleaner because it is scoped to
the server's `Resource` lifetime:

```scala
// In the HTTP server resource (better than unsafeToFuture at each call site):
Dispatcher.sequential[IO].use { dispatcher =>
  val route: Route = pathPrefix("rpc") {
    post {
      complete(dispatcher.unsafeToFuture(handleRequest(req)))
    }
  }
  Http().newServerAt(...).bind(route)
}
```

---

## 4. Ref vs actor state: when to use which

Use **actor state** (immutable value passed to `context.become` / `Behaviors.receive`) for:
- State that changes in response to actor messages
- State that is co-owned with the actor's supervision tree

Use **`Ref[IO, T]`** for:
- State shared across IO fibers within a single actor's IO work (none currently in fukuii)
- State inside a `Resource` or `Stream` pipeline that outlives a single message handler

Use **`AtomicCell[IO, T]`** (CE 3.3+, available at 3.6.3) for:
- Shared state that needs effectful update functions (e.g., update requires an IO to produce
  the new value). `AtomicCell.evalModify` provides serialised, atomic, effectful updates.
  Simpler than `Ref` + `Mutex` for the common cache-update pattern.

```scala
// AtomicCell: best choice when the update itself is an IO
class PeerStatusCache(cell: AtomicCell[IO, Map[PeerId, PeerStatus]]) {
  def refresh(peer: PeerId, fetch: IO[PeerStatus]): IO[PeerStatus] =
    cell.evalModify { current =>
      fetch.map { status => (current.updated(peer, status), status) }
    }
}
```

**Do not** use `Ref` inside actor state for things already protected by the actor mailbox —
the actor model already serialises access. Adding a `Ref` on top is unnecessary indirection.

---

## 5. fs2 Stream vs Pekko Source: where each belongs

| Layer | Use | Reason |
|---|---|---|
| RocksDB iteration (current) | `Stream[IO, ...]` | Resource lifecycle fits IO; no Pekko needed |
| Bloom filter loading (current) | `Stream[IO, ...]` | One-shot fold into mutable structure |
| SNAP sync data flow | `Pekko Source/Flow/Sink` | Peer messages arrive as actor messages; backpressure is per-peer budget in actor state |
| P2P message demux | `Pekko Source` | Tightly coupled to Pekko Streams TCP layer |

**Do not mix** — converting between `Stream[IO]` and `Pekko Source` via the reactive-streams
bridge (`fs2-reactive-streams`) is possible but expensive and confusing. The bridge is
`@deprecated` in fs2 in favour of `fs2.interop.flow` (java.util.concurrent.Flow), which
Pekko Streams also supports. If a future need arises to pass an fs2 stream into a Pekko
graph, use `Flow.fromPublisher` with `stream.toPublisher` via `fs2.interop.flow`, not the
old `fs2.interop.reactivestreams` package.

### fs2 Channel for fan-out within IO pipelines

`fs2.concurrent.Channel` (single consumer, multiple producers, closeable) is the right tool
when:
- Multiple IO fibers produce items that one consumer drains as a `Stream`
- Graceful shutdown is needed (producer calls `channel.close`, stream terminates after
  draining buffered items)

`fs2.concurrent.Topic` (multiple producers, multiple subscribers) is the right tool for
pub/sub within an all-IO subsystem. At present, fukuii uses Pekko Typed `Topic` for
block-imported pub/sub — correct, because consumers are actors. If a future pure-IO
subsystem (e.g., a WebSocket subscription server) needs the same events, pipe actor messages
into an `fs2.concurrent.Topic` via a `Dispatcher`-bridged channel.

---

## 6. Error accumulation: `Validated` for batch validation, `Either` for sequential

Fukuii uses `Either` throughout the validation pipeline (block validation, transaction
validation). This is appropriate for **sequential validation** where the first error should
short-circuit.

Use `cats.data.ValidatedNel` (or `ValidatedNec`) when you want to **accumulate all errors**
in a batch. The most natural places in fukuii:

```scala
// Accumulate all errors from a batch of transactions:
import cats.data.{Validated, ValidatedNel}
import cats.syntax.validated.*

def validateTx(tx: SignedTransaction): ValidatedNel[TxError, SignedTransaction] =
  (checkSignature(tx), checkNonce(tx), checkGas(tx)).mapN { (_, _, _) => tx }
    // Parallel applicative — all three checks run; errors accumulate

// Fold over a batch:
val results: ValidatedNel[TxError, List[SignedTransaction]] =
  txs.traverse(validateTx)
```

When the result of one validation is needed as input to the next, use `Either` (or `EitherT`
for effectful chains), not `Validated`. The `ForkIdValidator` demonstrates the correct
pattern: `EitherT` for short-circuit, `Logger[F]` for effect polymorphism.

---

## 7. `IO.parTraverseN` for CPU-bound parallel work (already in use)

`SignedTransaction.getSignedTransactionsParallel` and `SignedTransactionsFilterActor` both
use `IO.parTraverseN(parallelism)` to distribute ECDSA batch recovery across cores. This is
the correct pattern. Notes on getting it right:

- `parallelism = availableProcessors` is correct for CPU-bound work. For IO-bound work
  (network, RocksDB reads), a higher multiplier is appropriate because fibers block
  semantically, not actually.
- Batch into chunks before `parTraverseN` (as done in `SignedTransaction`) — one fiber per
  transaction is too fine-grained. Scheduler overhead dominates below ~50 items per fiber.
- The result of `IO.parTraverseN(...).unsafeRunSync()` inside an actor handler blocks the
  actor thread for the duration. For large batches, prefer `pipeToSelf` so the actor can
  continue processing other messages.

---

## 8. log4cats for IO-contextual logging

Only one file (`ForkIdValidator`) uses log4cats. All other IO-contextual logging uses
`org.slf4j.LoggerFactory.getLogger(getClass)` directly — a static SLF4J logger that is
thread-safe but not fiber-aware.

**The log4cats pattern is better inside IO-returning code** because:

1. `Logger[F]` is effect-polymorphic — tests can use `NoOpLogger` or a capturing logger
   without redirecting stdout.
2. It integrates with structured context (`StructuredLogger`, `withContext`) — useful for
   adding `peerId`, `blockNumber`, `requestId` to all log lines within a scope.

```scala
// Preferred pattern for IO-returning services:
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class MyService[F[_]: Monad: Logger](/* deps */) {
  def doWork: F[Result] =
    Logger[F].info("starting work") >> actualWork
}

object MyService {
  // Wire the implicit once at the composition root:
  given [F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]
}
```

**Keep** `org.slf4j.LoggerFactory` for:
- Static loggers inside actor `Behavior` bodies where `F` is not in scope
- The `fiberLog` pattern in `SyncStateSchedulerActor` — the static SLF4J logger is
  intentionally separate from the actor's `ctx.log` because it runs in the IO fiber, not the
  actor thread

---

## Cross-references

- Pekko actor patterns: `../pekko/concurrency.md`
- RocksDB iterator lifecycle: `../../agent-protocols/storage-rocksdb.md`
- `pipeToSelf` usage: `../../agent-protocols/pekko-typed-api.md` (P8)
- cats-effect source: `../../repo-references/typelevel/cats-effect/`
- fs2 source: `../../repo-references/typelevel/fs2/`
