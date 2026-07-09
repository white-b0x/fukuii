# JSON-RPC domain — scope stub

**Scope:** Code-shape conventions for `jsonrpc/` — how a JSON-RPC method/handler is shaped
(request/response DTO shape, method registration/dispatch), serialization-codec conventions
(the json4s→circe surface — see `../dependencies/README.md` for the per-dependency
sanctioned-API-surface angle on the same two libraries), GraphQL schema/resolver shape
(`jsonrpc/graphql/`), rate-limiting, and transport wiring (HTTP/WS/IPC under
`jsonrpc/server/`, `jsonrpc/client/`, `jsonrpc/mcp/`). Distinct from `../networking/`, which
covers P2P/RLPx/ETH-wire-protocol code shape, not JSON-RPC/GraphQL — a JSON-RPC method
handler and a devp2p wire message have different owning specialists and different upstream
authorities, the same reasoning `../README.md`'s intro gives for splitting domains generally.

**Owning specialist:** `conduit`.

**Authority:** `.claude/repo-references/ethereum/execution-apis` (the ETH JSON-RPC method
specs — `eth_*`/`net_*`/`web3_*`); `.claude/repo-references/pekko-http` (HTTP/WS transport
routing DSL); `.claude/repo-references/sangria` (GraphQL schema/execution); `.claude/repo-
references/json4s` and `.claude/repo-references/circe` (serialization-codec surface — current
and migration-target respectively).

**Status:** net-new stub — no existing `.agents/protocols/` file covers this ground; this
domain had no home before this stub (`conduit` already used the authority repos above, but no
coding-standards domain owned the code-*shape* conventions they ground). First content
candidates: any B2 (json4s→circe migration) findings, and `conduit`-authored standards for
method/handler shape, per `../README.md`'s "new synthesis" / "commit-log mining" intake paths.
