# Pekko Typed Patterns (P17+)

Continuation of `pekko-typed-api.md` (P1–P16). These patterns emerged from
reviewing the Pekko source, the fukuii LOOM migration, and gaps identified in
the P1-P16 protocol. Apply during LOOM migrations and PRISM reviews.

**Numbering note:** P14 and P15 were claimed in `pekko-typed-api.md` (testkit
config issues). P16 is typed constructor params. These begin at P17.

---

## P17 — `messageAdapter` belongs in `Behaviors.setup`, not in message handlers

**Status:** Recommended for new code and LOOM migrations. Violation exists in
`PeersClient.scala` (per-request adapter created inside receive).

`messageAdapter[U]` is documented as "one adapter per message class, last
registration wins." Calling it inside a message handler creates a new registration
on every message, silently replacing the previous one. If two co-existing adapters
for the same type `U` are both live (e.g., two in-flight peer requests expecting
`PeerRequestHandler.Result`), the second registration discards the first.

**Correct pattern — register in `Behaviors.setup` once:**
```scala
def apply(...): Behavior[Command] =
  Behaviors.setup { ctx =>
    // ✅ Registered once, shared for all messages of type U
    val prhAdapter: ActorRef[PeerRequestHandler.Result] =
      ctx.messageAdapter[PeerRequestHandler.Result](WrappedPRHResult(_))
    val peerEventAdapter: ActorRef[PeerEvent] =
      ctx.messageAdapter[PeerEvent] {
        case pd: PeerDisconnected => WrappedPeerDisconnected(pd)
        case _                    => throw new MatchError("unexpected PeerEvent")
      }
    receive(prhAdapter, peerEventAdapter)
  }
```

**When per-request adapters are genuinely needed** (e.g., per-request reply
routing): use `pipeToSelf` or `context.ask` instead of a per-message
`messageAdapter` — both are designed for one-shot request/response and survive
multiple in-flight calls correctly.

**Anti-pattern:**
```scala
// ❌ Creates a new adapter for each incoming RequestPeer message —
//    second registration silently replaces the first adapter
case RequestPeer(criteria) =>
  val prhAdapter = ctx.messageAdapter[PeerRequestHandler.Result](r => PRHResultCmd(id, r))
  ...
```

**Grep:**
```bash
# Find messageAdapter calls that appear inside case branches (indicative — needs manual review)
grep -rn "messageAdapter\[" src/main/ --include="*.scala" -B10 \
  | grep -B10 "case \|\.receiveMessage"
# Target: all messageAdapter calls should be inside Behaviors.setup, not receive/receiveMessage blocks
```

---

## P18 — `spawnAnonymous` only for truly identity-free workers; prefer named

**Status:** Recommended. Current violations in `ByteCodeCoordinator`, `AccountRangeCoordinator`, `SyncStateSchedulerActor`, `BlockImporter`, `FastSyncBranchResolverActor`.

P4 already mandates named actors. This extends P4 with a specific diagnostic
rule: `spawnAnonymous` is only appropriate when an actor has NO stable identity
and is truly interchangeable with every sibling (e.g., a pool of identical
workers where the worker index is tracked by the parent, not the actor name).
Use a named actor with a discriminator when:
- The parent uses `watchWith` to correlate termination to a specific piece of work
- The parent needs to stop a specific child by ref (e.g., half-finished work)
- Any log output needs to be traceable to the work unit

In practice: in fukuii, SNAP worker actors (`ByteCodeCoordinator`, `AccountRangeCoordinator`)
spawn workers anonymously. The parent tracks them in a map by ref, which works
but makes post-mortem log analysis nearly impossible.

**Correct pattern for worker pools:**
```scala
// ✅ Named with a stable counter or task hash
private var workerCounter = 0
private def createWorker(): WorkerRef = {
  workerCounter += 1
  val worker = context.spawn(ByteCodeWorker(config), s"bytecode-worker-$workerCounter")
  context.watchWith(worker, WorkerStopped(worker))
  worker
}
```

**Legitimate `spawnAnonymous` uses:**
- One-shot ask-style children that exist only for a single request-response cycle
  and whose identity is carried by the parent's internal state (not the actor name)
- Children where a random suffix is already appended (e.g., UUIDs) — though
  prefer a stable counter, which produces shorter, reproducible names in logs

**Grep:**
```bash
grep -rn "spawnAnonymous" src/main/ --include="*.scala"
# Review each hit: is the child's identity truly irrelevant? If the parent watchWith's it, name it.
```

---

## P19 — `PreRestart` signal: always handle when `Behaviors.supervise` is present

**Status:** Not enforced. Zero handlers in the current codebase. Two supervised
actors exist (`FastSync`, `FaucetSupervisor`).

`PreRestart` is a lifecycle signal delivered to the behavior that crashed,
**before** the new behavior instance is created. It is the correct place to
release external resources (open connections, subscriptions, timer handles)
when an actor is supervised with `restart` or `restartWithBackoff`. If not
handled, resource leaks accumulate silently on each restart cycle.

The current `PostStop` signal (P2) fires when an actor terminates permanently.
`PreRestart` fires on each restart. An actor under `restartWithBackoff` with
an open peer subscription that never calls `unsubscribe` in `PreRestart` leaks
one subscription per crash.

**When to add a `PreRestart` handler:**
- The actor opened subscriptions via `messageAdapter` (these are re-created on
  restart, leaving the old subscription ref dangling)
- The actor holds an external resource (DB connection, open channel, stream
  materializer)
- The actor started child streams that are not stopped by parent supervision

**Pattern:**
```scala
Behaviors.supervise(
  Behaviors.setup[Command] { ctx =>
    val sub = subscriptionManager.subscribe(ctx.self)

    Behaviors.receiveMessage[Command] { ... }
      .receiveSignal {
        case (_, PreRestart) =>
          // ✅ Fires before restart — release resources before new behavior starts
          subscriptionManager.unsubscribe(sub)
          Behaviors.same
        case (_, PostStop) =>
          // ✅ Fires on permanent stop — also clean up
          subscriptionManager.unsubscribe(sub)
          Behaviors.same
      }
  }
).onFailure[Exception](SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2))
```

**Grep — find supervised actors missing PreRestart handlers:**
```bash
# Find supervise blocks in main sources
grep -rn "Behaviors\.supervise\|\.onFailure\[" src/main/ --include="*.scala" -l
# Then for each file:
grep -rn "PreRestart" src/main/ --include="*.scala"
# Target: each file with supervise should have a PreRestart case
```

---

## P20 — `Behaviors.supervise` restart strategy must include `.withLimit`

**Status:** `FastSync.scala` uses unlimited `SupervisorStrategy.restart` — flag
in next PRISM review.

Unlimited restart (`SupervisorStrategy.restart` with no `.withLimit(...)`) causes
infinite restart loops on persistent failures (e.g., a codec bug that always throws
on the same message). The default behavior stops after the first failure has no
restart limit guard. Use `.withLimit(maxNrOfRetries, withinTimeRange)` or
`restartWithBackoff` for anything that might fail repeatedly.

**Prefer `restartWithBackoff` for I/O-bound actors** (peer connections,
subscriptions, DB writers) — it adds jitter, prevents thundering-herd restart
storms, and the `withCriticalLogLevel` escalation gives operational visibility.

```scala
// ❌ Unlimited restart — silent infinite loop on persistent failure
Behaviors.supervise(childBehavior)
  .onFailure[Exception](SupervisorStrategy.restart)

// ✅ Bounded restart with backoff
Behaviors.supervise(childBehavior)
  .onFailure[Exception](
    SupervisorStrategy
      .restartWithBackoff(100.millis, 10.seconds, randomFactor = 0.2)
      .withMaxRestarts(10)
      .withCriticalLogLevel(Level.ERROR, afterErrors = 3)
  )

// ✅ Bounded fixed-delay restart for local (non-I/O) workers
Behaviors.supervise(childBehavior)
  .onFailure[Exception](
    SupervisorStrategy.restart.withLimit(maxNrOfRetries = 5, withinTimeRange = 1.minute)
  )
```

**Exception: `SupervisorStrategy.stop`** is correct when a specific domain
exception means the actor must not continue (e.g., `WalletException` in
`FaucetSupervisor`). Do not add a limit or backoff to `stop`.

**Grep:**
```bash
grep -rn "SupervisorStrategy\.restart\b" src/main/ --include="*.scala" \
  | grep -v "withLimit\|restartWithBackoff\|WithLimit"
# Target: 0 hits — all restart strategies must have a limit or be backoff
```

---

## P21 — `ChildFailed` signal over `Terminated` for supervised child crash diagnosis

**Status:** Not used anywhere in the codebase. All watches use `watchWith` (P9)
or `watch` + `Terminated`.

`ChildFailed` is a subtype of `Terminated` emitted specifically when a direct
child actor terminates due to an **uncaught exception** (rather than a voluntary
`Behaviors.stopped`). It carries the `cause: Throwable` that killed the child.

When a coordinator uses `watchWith(child, ChildStopped(child.path.name))`, it
loses the exception context. Using `watch(child)` and matching on `ChildFailed`
in `receiveSignal` provides the crash cause for logging and conditional recovery.

This is most useful in coordinator actors that supervise workers and need to
distinguish "worker finished cleanly" from "worker crashed with X."

**Pattern:**
```scala
ctx.watch(worker)  // plain watch, not watchWith

Behaviors.receiveMessage[Command] { ... }
  .receiveSignal {
    case (ctx, ChildFailed(ref, cause)) =>
      // ✅ Worker crashed — cause is available for logging and routing
      ctx.log.error("Worker {} crashed: {}", ref.path.name, cause.getMessage, cause)
      rescheduleWork(ref)
      Behaviors.same
    case (ctx, Terminated(ref)) =>
      // ✅ Worker stopped cleanly (Behaviors.stopped)
      recordCompletion(ref)
      Behaviors.same
  }
```

**Important:** `ChildFailed` only arrives for **direct children** that crash
**without** a `Behaviors.supervise` wrapper. If the child has a supervision
strategy, the supervisor catches the exception and the parent sees `Terminated`
(clean) after max-restarts is exceeded, not `ChildFailed`.

**Grep — find coordinators using watchWith that lose crash cause:**
```bash
grep -rn "watchWith\|case.*Terminated\b" src/main/ --include="*.scala" -l
# Review each: would ChildFailed be more useful than a generic watchWith message?
```

---

## P22 — `Behaviors.withMdc` for per-actor structured logging context

**Status:** Not used in the migrated codebase. MDC is set manually via raw
`org.slf4j.MDC` in `Logger.scala` — that approach is unsafe on actor threads
and not integrated with Pekko's context.log.

`Behaviors.withMdc` wraps a behavior with structured logging context (MDC)
that is injected automatically before each message is processed and cleared
after. It works with `context.log` (which is already actor-thread-safe) and
enables log correlation without manual `MDC.put`/`MDC.clear` pairs.

Two variants:
- **Static MDC:** set once at actor start, applies to all messages
- **Per-message MDC:** invoked before each message, enables message-specific context

**Pattern (sync coordinator with peer context):**
```scala
def apply(peerId: PeerId, chainId: Int): Behavior[Command] =
  Behaviors.withMdc[Command](
    staticMdc = Map("peerId" -> peerId.value, "chainId" -> chainId.toString),
    mdcForMessage = {
      case ProcessBlock(block)   => Map("blockNumber" -> block.number.toString)
      case ProcessHeader(header) => Map("blockHash" -> header.hash.toHex)
      case _                     => Map.empty
    }
  )(
    Behaviors.setup { ctx =>
      // ctx.log now includes the MDC context automatically
      ctx.log.info("Coordinator started")  // logs: peerId=X, chainId=61
      receive(ctx)
    }
  )
```

**Why this matters for fukuii:** RLPx peer sessions produce log lines that are
currently impossible to correlate without grepping for the peer address in
surrounding lines. Adding `peerId` and `remoteAddress` as static MDC on
`PeerActor` and `PeerRequestHandler` would make peer-session tracing trivial.

**Note on `ClassTag`:** `withMdc[T: ClassTag]` requires an explicit type param
because the interceptor uses `ClassTag` to let private protocol messages bypass
the interceptor. Use the same `T` as the actor's `Command` type.

**Grep — find actors using raw MDC instead of withMdc:**
```bash
grep -rn "org\.slf4j\.MDC\|MDC\.put\|MDC\.clear" src/main/ --include="*.scala"
# Target: replace with Behaviors.withMdc in Typed actors
```

---

## P23 — `ManualTime` for all tests that exercise `withTimers` behavior

**Status:** Partially enforced — `PeerRequestHandlerSpec` and `FilterManagerSpec`
use `ManualTime`. Other timer-bearing actors (e.g., `SNAPSyncController`,
`BlockchainHostActor`) are not tested with time advancement.

Tests that exercise timeout behavior using real wall-clock time are inherently
flaky on loaded machines (NUC under `testEssential` load = 24 min, high
contention). `ManualTime` gives deterministic control: timers only fire when
the test explicitly advances the virtual clock.

**Pattern:**
```scala
class MyActorSpec
    extends ScalaTestWithActorTestKit(ManualTime.config)
    with AnyFlatSpecLike {

  val manualTime: ManualTime = ManualTime()

  "MyActor" should "timeout after 5 seconds" in {
    val probe = testKit.createTestProbe[Response]()
    val actor = testKit.spawn(MyActor())
    actor ! SendRequest(probe.ref)

    // Advance virtual clock — timer fires immediately, deterministically
    manualTime.timePasses(5.seconds)
    probe.expectMessageType[RequestTimedOut]
  }
}
```

**Anti-pattern — wall-clock sleep for timer tests:**
```scala
// ❌ Flaky: fails under CPU load when the 5s window elapses before assertion
actor ! SendRequest(probe.ref)
Thread.sleep(5100)  // hope 5s timer fired within 5.1s wall-clock
probe.expectMessageType[RequestTimedOut]
```

**Constraint:** `ManualTime.config` disables the real scheduler. Any actor
under test that uses `context.system.scheduler` (not `withTimers`) will NOT
be controlled by `ManualTime` — another reason to enforce P1.

**Grep — find timer tests without ManualTime:**
```bash
grep -rn "withTimers\|startTimerWithFixedDelay\|startSingleTimer" src/test/ --include="*.scala" -l \
  | xargs grep -L "ManualTime"
# Target: 0 hits — all timer specs should use ManualTime
```

---

## P24 — `LoggingTestKit` to assert supervision restarts and error paths

**Status:** `PoWMiningCoordinatorSpec` is the only file using `LoggingTestKit`.
Actors with `Behaviors.supervise` have no log-level tests for their crash paths.

`LoggingTestKit` (typed testkit, requires Logback) verifies that a log event
matching specified criteria is emitted within the timeout. It is the correct
tool for testing:
- That a supervised actor logs an ERROR on crash before restart
- That a recovery path logs the expected WARN/INFO
- That an actor does NOT log unexpected errors (use `.withOccurrences(0)`)

**Pattern:**
```scala
import org.apache.pekko.actor.testkit.typed.scaladsl.LoggingTestKit

"supervised actor" should "log ERROR on restart" in {
  val actor = testKit.spawn(SupervisedActor())

  // ✅ Assert that ERROR containing "connection failed" is logged
  LoggingTestKit
    .error("connection failed")
    .withOccurrences(1)
    .expect {
      actor ! TriggerFailure
    }
}

// ✅ Assert NO unexpected errors during happy path
LoggingTestKit
  .error("")       // empty string matches any ERROR
  .withOccurrences(0)
  .expect {
    actor ! NormalMessage
  }
```

**`withOccurrences(0)` pattern** checks for the absence of a matching log
event. Use it in happy-path tests to confirm no ERRORs are emitted during
normal operation — catches regressions where a code path accidentally logs
at ERROR level.

**Constraint:** `LoggingTestKit` requires a running `ActorSystem` (i.e.,
`ScalaTestWithActorTestKit`, not `BehaviorTestKit`). It captures log events
at the Logback appender level and waits up to
`pekko.actor.testkit.typed.filter-leeway` (default 3 seconds).

**Grep:**
```bash
grep -rn "Behaviors\.supervise\|restartWithBackoff\|onFailure\[" src/main/ --include="*.scala" -l \
  | while read f; do
      base=$(basename "$f" .scala)
      grep -rn "LoggingTestKit" src/test/ --include="*${base}Spec*" &>/dev/null \
        || echo "MISSING LoggingTestKit: $f"
    done
# Target: every supervised actor spec should have at least one LoggingTestKit assertion
```

---

## P25 — `Behavior.narrow` for interface segregation on `self`

**Status:** Used once (`RegularSync.scala` line 73). Should be the standard
pattern for coordinator actors that expose a narrower protocol to children.

`ActorRef[T].narrow[U]` (where `U <: T`) returns an `ActorRef[U]` that accepts
only a subset of the parent's messages. It is a zero-cost cast (no wrapping,
no adapter). The use case in fukuii: a coordinator actor handles `ProgressProtocol`
messages (a subset of its full `Command` trait) sent by child actors. Passing
`ctx.self.narrow[ProgressProtocol]` ensures children cannot accidentally send
any other Command to the coordinator.

**Pattern:**
```scala
sealed trait Command
// Subset exposed to children:
sealed trait ProgressProtocol extends Command
case class BlockFetched(block: Block) extends ProgressProtocol
case class FetchFailed(reason: String) extends ProgressProtocol
// Internal/parent-facing:
case class Start(config: SyncConfig) extends Command

def apply(): Behavior[Command] =
  Behaviors.setup { ctx =>
    // ✅ Children receive a ref that only accepts ProgressProtocol
    val narrowedSelf: ActorRef[ProgressProtocol] = ctx.self.narrow[ProgressProtocol]
    val worker = ctx.spawn(BlockFetcher(narrowedSelf), "block-fetcher")
    ...
  }
```

**Contrast with `messageAdapter`:** `narrow` costs nothing and works only when
the message type is a subtype of the actor's Command. `messageAdapter` is needed
when transformation is required (e.g., wrapping a third-party type into a
Command). Prefer `narrow` when the child's reply type IS already a subtype of
Command.

**Grep:**
```bash
grep -rn "messageAdapter\[" src/main/ --include="*.scala"
# For each hit: is the mapped type a subtype of Command? If so, prefer .narrow instead.
grep -rn "\.narrow\[" src/main/ --include="*.scala"
# Expect: one per coordinator actor that exposes a sub-protocol to children
```

---

## Anti-patterns table (P17–P25 additions)

| Pattern | Problem | Protocol |
|---------|---------|----------|
| `ctx.messageAdapter` inside receive handler | One adapter per class — last registration wins, silently drops earlier ones | P17 |
| `ctx.spawnAnonymous` for watched workers | Invisible in logs; watchWith loses actor identity for diagnostics | P18 |
| `Behaviors.supervise` without `PreRestart` signal handler | Resource leak (subscriptions, connections) on each restart cycle | P19 |
| `SupervisorStrategy.restart` without `.withLimit` or backoff | Infinite restart loop on persistent failure | P20 |
| `watchWith` only, no `ChildFailed` match | Crash cause lost; parent cannot distinguish clean stop from crash | P21 |
| `MDC.put`/`MDC.clear` in Typed actor | Thread-unsafe, not integrated with `context.log` | P22 |
| Timer tests without `ManualTime` | Flaky under load; NUC at full `testEssential` fails timing-dependent tests | P23 |
| Supervised actor specs with no `LoggingTestKit` | Crash paths never verified; regressions silent | P24 |
| `messageAdapter` for same-hierarchy reply types | Zero-cost `.narrow` achieves the same result without a new ActorRef | P25 |
