# Actor message-protocol typing

**Domain:** Pekko Typed actor design. **Owning specialist:** `loom` (migration sites),
`prism` (review of new/existing Typed code), `flow` (streams-adjacent actor boundaries).
**Authority:** `.claude/repo-references/pekko/docs/src/main/paradox/typed/style-guide.md`,
`interaction-patterns.md`, `handling-actor-responses-with-scala3.md`, `from-classic.md`.

> **VALIDATE gate:** this doc's citations were checked against the four files above,
> in-repo, this session (2026-07-08) — not written from memory. See `../README.md`'s
> Governance section for what that check requires and why it exists.

## The standard

A Pekko Typed actor's message protocol is a `sealed trait Command`
(`style-guide.md:369-370`: "Incoming messages to an actor are typically called commands,
and therefore the super type of all messages that an actor can handle is typically `sealed
trait Command`"). `sealed` is load-bearing, not decorative: "It's recommended to use a
`sealed` trait ... as the compiler will emit a warning if a message type is forgotten in the
pattern match" (`style-guide.md:511-513`). This is the entire reason to prefer Typed over
Classic — Classic's plain `ActorRef` carries no type information, so "you can send any type
of message to a classic `ActorRef` even though the actor may not understand it"
(`from-classic.md:145-146`). A `Behavior[Command]` that isn't backed by a `sealed` ADT gives
up that guarantee while looking like it has it.

A message that should only be sent internally — a timer tick, an adapter wrapper — is
declared `private` but **must still extend the public `Command` trait**
(`style-guide.md:284`: "wrapper messages for `ask` or `messageAdapter`... must still
@scala[extend]@java[implement] the public `Command` @scala[trait]@java[interface]").

## Two sanctioned mechanisms for bridging a foreign protocol in

An actor frequently needs to receive responses from, or forward events originating in,
another actor's incompatible message type. Two mechanisms are sanctioned:

**1. `ctx.messageAdapter[ConcreteType](WrappedX(_))`**, registered once, in
`Behaviors.setup` (not inside a receive handler — re-registering per message silently
replaces the previous adapter, see the codebase-audit research doc's finding on this
distinct violation). The wrapper itself is a `Command` subtype:

```scala
sealed trait Command
private case class WrappedPeerDisconnected(msg: PeerDisconnected) extends Command

Behaviors.setup { ctx =>
  val adapter = ctx.messageAdapter[PeerDisconnected](WrappedPeerDisconnected(_))
  // adapter: ActorRef[PeerDisconnected] — hand this out to the foreign sender
  running(adapter)
}
```
"You can register several message adapters for different message classes. It's only
possible to have one message adapter per message class... A registered adapter will
replace an existing adapter for the same message class" (`interaction-patterns.md:118,
136-139`) — registering inside `Behaviors.setup` (a per-actor-lifecycle one-time block) is
what keeps this to exactly one registration.

**2. A Scala 3 union type `Behavior[Command | Response]`, narrowed to `Behavior[Command]`.**
Scala 3 removes the need for an adapter/wrapper in this case entirely:
"A distinction exists between an actor's public protocol (`Command`) and its internal
protocol (`CommandAndResponse`). The latter is the union of the public protocol and all the
responses the actor should understand... a `Behavior[CommandAndResponse]` is narrowed to a
`Behavior[Command]`. This works as the former is able to handle a superset of the messages
that can be handled by the latter" (`handling-actor-responses-with-scala3.md:6-9,21-23`).

```scala
sealed trait Command
type CommandAndResponse = Command | Backend.Response

def apply(): Behavior[Command] =
  behavior().narrow

private def behavior(): Behavior[CommandAndResponse] = Behaviors.receiveMessage {
  case cmd: Command            => ...
  case response: Backend.Response => ...
}
```

## The anti-pattern: `Any`-umbrella re-matched by concrete type

`Behavior[Any]` (or a message wrapping an `Any` payload, e.g. `WrappedExternal(msg: Any)`),
re-matched inside the receive handler by `case x: SomeConcreteType => ...`, is **not** a
sanctioned bridging mechanism. It defeats exhaustiveness checking — the actor's declared
protocol type gives no compile-time guarantee about what it can receive, and a case
forgotten at the receiver produces no compiler feedback, only a runtime `MatchError` or a
silent drop through a catch-all. See
[`../mantis-inheritance-ledger.md`](../mantis-inheritance-ledger.md)'s B1 entry — this is the
actor-message instance of a more general anti-pattern (B2).

**`Matchable` is not a substitute for this fix.** Widening `Any` to `Matchable` (or adding a
`[T <: Matchable]` bound at a call site that's really just `Any`) silences the E165 compiler
warning without restoring exhaustiveness — see
[`../scala3/matchable-e165.md`](../scala3/matchable-e165.md) and the ledger's B3 entry for
why that's a different, narrower fix aimed at a different problem (a genuinely generic
pattern-matched type parameter, not a protocol-bridging problem).

## Sanctioned exception: genuine Classic bridge / per-session child

`Behavior[Any]` remains correct — not a violation of the anti-pattern above — in two
documented situations:

1. **Bridging a genuinely-Classic caller with no way to supply a typed message**, e.g. an
   enclosing Typed actor whose Classic child hardcodes `context.parent` as its reply target
   with no `replyTo` to inject. This is the established fukuii pattern in
   `BytecodeRecoveryActor`, `StorageRecoveryActor`, `FastSyncBranchResolverActor`, and
   `ChainDownloader` (see `.claude/agents/loom.md` Pattern 11 for the fukuii-specific
   worked example — that pattern's *operational migration steps* stay in `loom.md`; this
   doc is the standard those steps implement).
2. **The per-session child actor pattern**, where the protocol is deliberately not public
   API but an implementation detail of the parent: "it may not always make sense to have an
   explicit protocol and adapt the messages... For this use case it is possible to express
   that the actor can receive any message" (`interaction-patterns.md:356`).

The anti-pattern is choosing `Any` when a typed alternative (adapter or union) is available
— not the mere existence of a `Behavior[Any]` site. Every `Behavior[Any]` in the tree should
map to one of these two documented reasons, or be a CAPSTONE-narrowing candidate (below).

## CAPSTONE narrowing path (post-migration)

Once every caller of a `Behavior[Any]` actor is itself Typed, narrow in two steps rather
than leaving the `Any` umbrella in place indefinitely:

1. Replace `Behavior[Any]` with `Behavior[Command | InternalMsg]` (drop the
   `case _ => Behaviors.same` catch-all — the compiler now rejects unknown message types).
2. If all cases in `InternalMsg` genuinely belong to the same protocol, fold it into the
   `sealed Command` ADT.

This is the same path `loom.md` documents as its "Post-CAPSTONE migration path" — that
file's operational steps for executing the narrowing per-actor reference this standard
rather than re-deriving it.

## Conformance checks

```bash
# 1. Inventory current Behavior[Any] sites — each hit needs one of the two sanctioned-
#    exception justifications above, or is a CAPSTONE-narrowing candidate.
grep -rn "Behavior\[Any\]" src/main/scala/ --include="*.scala"

# 2. messageAdapter registered outside Behaviors.setup (P17-adjacent violation: distinct
#    from this doc's anti-pattern, but frequently co-located) — manual triage of hits,
#    no single grep proves the violation; use as a targeted-review starting point:
grep -rn "messageAdapter" src/main/scala/ --include="*.scala" -B3 | grep -B3 "receiveMessage\|Behaviors.receive "
```

These are advisory checks per the enforcement ladder in `../README.md` — they inventory
sites for review, they do not by themselves fail a build.
