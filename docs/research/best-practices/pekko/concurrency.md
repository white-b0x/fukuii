# Best Practices: Concurrency Patterns (Scala/Pekko)

> **⚠️ Pre-migration reference — not current Typed-actor prescription.** These patterns
> describe **pre-migration Pekko Classic actor code** (SNAPSyncController,
> NetworkPeerManagerActor, etc.) as historical/handoff reference for code not yet migrated
> by LOOM. They are **NOT** the current best practice for new code — several of the
> "Fukuii — correct" patterns below (`context.become`, untyped `Receive`,
> `OneForOneStrategy`/`SupervisorStrategy`, `scheduler.scheduleOnce`) are exactly the
> Akka-Classic-era idioms `pekko-typed-api.md` lists as anti-patterns being migrated away
> from. See `.agents/protocols/code-style/pekko-typed-api.md` P1 (timers → `Behaviors.withTimers`), P2
> (`PostStop`/`PreRestart` signals), P3 (`replyTo` over `sender()`), P6 (two-behavior state
> machines), P9 (`watchWith`), and P19/P20 (`Behaviors.supervise`) for the current Typed
> equivalents. When writing new code or migrating existing code, follow `pekko-typed-api.md`,
> not this file.

Scala analogs to Go goroutine patterns and Rust async patterns found in reference clients.

---

## 1. Actor-per-Phase, Not Actor-per-Request

**Pattern (Fukuii — correct):**
```
SNAPSyncController (coordinator)
    ├── AccountRangeCoordinator (1 actor, manages all account range work)
    ├── StorageRangeCoordinator (1 actor, manages all storage work)
    ├── ByteCodeCoordinator (1 actor, manages all bytecode work)
    └── TrieNodeHealingCoordinator (1 actor, manages all healing work)
```

**Anti-pattern:** One actor per in-flight request — creates thousands of short-lived actors,
heavy GC pressure, expensive mailbox allocation.

**Go-ethereum equivalent:** One goroutine per phase (not per request). Goroutines multiplex
many in-flight requests via a single `reqMap` and a `select` loop.

---

## 2. Context.Become for State Transitions

**Pattern:** Use `context.become()` for FSM state changes within an actor.
This avoids the complexity of pattern-matching guards:

```scala
// Good: clear state isolation
def syncing: Receive = {
  case AccountRangeSyncComplete =>
    context.become(healingPhase)
}

// Anti-pattern: global mutable flag + guards everywhere
var phase: SyncPhase = AccountRangeSync  // tempting but error-prone
```

`SNAPSyncController` uses 5 distinct behaviors with `context.become()`.
Keep each behavior's `Receive` function focused on that phase's messages.

---

## 3. Functional Peer State (Immutable Map Swap)

**Pattern (NetworkPeerManagerActor):**
```scala
def handleMessages(peersWithInfo: Map[PeerId, PeerWithInfo]): Receive = {
  case PeerHandshakeSuccessful(peer, info) =>
    context.become(handleMessages(peersWithInfo + (peer.id -> PeerWithInfo(peer, info))))
}
```

The peer map is never mutated in-place. Each message produces a new map, passed to the
next `context.become()`. This is thread-safe (actor model guarantees single-threaded
message processing) and trivially testable (pure function on the map).

---

## 4. Dedicated Dispatcher for Blocking Operations

**Pattern (Fukuii healing-writer-dispatcher):**
```
// pekko.conf
healing-writer-dispatcher {
  type = Dispatcher
  executor = "thread-pool-executor"
  thread-pool-executor {
    fixed-pool-size = 2
  }
}
```

**Rule:** Never perform blocking DB operations on `sync-dispatcher`.
- `sync-dispatcher` serves all SNAP coordinators — blocking it stalls all SNAP activity
- `healing-writer-dispatcher` is dedicated to RocksDB batch writes during healing

**Go-ethereum equivalent:** Separate goroutine with a channel for DB batch writes.
**reth equivalent:** `tokio::task::spawn_blocking` for CPU/IO-bound ops.

---

## 5. Timer Cancellation on Response

**Pattern (SNAPRequestTracker):**
```scala
def trackRequest(requestId: BigInt, peer: Peer): Unit = {
  val timer = scheduler.scheduleOnce(timeout, self, RequestTimeout(requestId))
  pendingTimers.put(requestId, timer)
}

def completeRequest(requestId: BigInt, items: Int, bytes: Long): Unit = {
  pendingTimers.remove(requestId).foreach(_.cancel())
  rateTracker.recordSuccess(items, bytes)
}

def clear(): Unit = {
  pendingTimers.values.foreach(_.cancel())
  pendingTimers.clear()
}
```

**Critical:** `clear()` must be called on actor stop and on phase transitions. Leaked timers
fire after the actor stops, delivering messages to a dead actor's mailbox.

---

## 6. Per-Peer In-Flight Budget (not Global Semaphore)

**Pattern:**
```scala
private var maxInFlightPerPeer: Int = 5

private def inFlightForPeer(peer: Peer): Int =
  activeTasks.values.count(_.peer.id == peer.id)

private def dispatchIfPossible(peer: Peer): Unit = {
  var inflight = inFlightForPeer(peer)
  while (pendingTasks.nonEmpty && inflight < maxInFlightPerPeer && ...) {
    sendRequest(peer)
    inflight += 1
  }
}
```

**Why per-peer vs. global:** A global semaphore causes fast peers to wait for slow peers.
Per-peer budgets allow each peer to run at its maximum throughput independently.

**Dynamic adjustment:** `SNAPSyncController` sends `UpdateMaxInFlightPerPeer(n)` to
coordinators to increase/decrease concurrency dynamically (e.g., start with 0 during
Phase 1, raise to 5 when Phase 2 begins).

---

## 7. Deque for Mutable Task Queues (Not Immutable Seq)

**Pattern (Fukuii #1167 fix):**
```scala
// Good: O(1) amortized head/tail operations
private val pendingTasks: mutable.ArrayDeque[HealingEntry]
pendingTasks.prepend(failedTask)  // O(1) re-enqueue at head
pendingTasks.removeHead()         // O(1) dequeue

// Bad: was causing O(n^2) at healing scale
private var pendingTasks: Seq[HealingEntry]
pendingTasks = failedTask +: pendingTasks  // O(n) prepend
pendingTasks.head  // O(1) but the prepend is the bottleneck
```

**When deque matters:** At healing scale (tens of thousands of pending tasks), the O(n)
prepend in an immutable `Seq` causes quadratic behavior — each failure is O(n), and
failures are frequent (timeouts).

---

## 8. knownAvailablePeers for Redispatch

**Pattern (ByteCodeCoordinator, StorageRangeCoordinator):**
```scala
private val knownAvailablePeers = mutable.Set[Peer]()

// On PeerAvailable/HandshakeSuccess:
knownAvailablePeers.add(peer)

// On redispatch attempt (activeTasks empty but tasks remain):
knownAvailablePeers.foreach(dispatchIfPossible)
```

**Why needed:** When ALL active tasks time out simultaneously (e.g., after network blip),
`activeTasks` is empty. Without `knownAvailablePeers`, the coordinator can't dispatch
until the next `PeerAvailable` message arrives — which may never come if all peers are
already registered.

This was the root cause of a stall window in `ByteCodeCoordinator` that required BUG-S1
to fix (the set was being incorrectly cleared).

---

## 9. Supervision Strategy for Worker Actors

**Pattern (ByteCodeCoordinator):**
```scala
override val supervisorStrategy: SupervisorStrategy =
  OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
    case _: Exception => Restart
  }
```

**Rules:**
- Use `OneForOneStrategy` for worker actors (failure of one worker doesn't affect others)
- Use `AllForOneStrategy` only when workers share state that can't survive partial failure
- `Restart` on exceptions: recreates the actor, preserving parent state
- `Stop` on persistent failure (maxNrOfRetries exceeded): parent should re-create the actor
  or mark the phase as stalled

---

## Cross-References
- reth async patterns (Rust/Tokio to Pekko): `../ref-clients/reth/snap-protocol/async-concurrency.md`
- go-ethereum goroutine model: `../ref-clients/go-ethereum/snap-protocol/overview-state-machine.md`
- Fukuii concurrency model: `../fukuii-state/concurrency-model.md`
