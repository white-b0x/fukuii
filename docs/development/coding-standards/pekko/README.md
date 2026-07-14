# Pekko domain — scope stub

**Scope:** Pekko Typed actor-idiom conventions for fukuii — timers, `replyTo`, named
children, `messageAdapter` placement, supervision, `ActorRef[T]` typing,
`Behaviors.withMdc`, `ManualTime`, `LoggingTestKit`, `.narrow`, dispatcher-starvation
avoidance, and Cats Effect/Typelevel integration rules at the actor boundary. See
[`actor-message-typing.md`](actor-message-typing.md), already migrated as this directory's
exemplar (sealed `Command` protocols, `messageAdapter`/union-type bridging).

**Owning specialist:** `loom` (migration sites), `prism` (review), `flow` (streams-adjacent
actor boundaries).

**Authority:** `.claude/repo-references/pekko/` — primarily `docs/src/main/paradox/typed/`
and `actor-typed/src/main/scala/` for canonical Typed API source; also
`.claude/repo-references/virtuslab/pekko-serialization-helper`,
`.claude/repo-references/pekko-connectors`, `.claude/repo-references/pekko-http`,
`.claude/repo-references/pekko-management` for serialization, streaming-connector,
HTTP/WebSocket routing, and cluster-discovery (DNS-SD/K8s/Consul) idiom respectively.

**Status:** one file migrated (`actor-message-typing.md`). The remaining P1–P26 and TL1/TL2
rules (`pekko-typed-api.md`) currently live in `.agents/protocols/code-style/` pending the
per-domain migration pass described in `../README.md`. Do not restate their content here
ahead of that pass — link to the protocol file in the meantime.
