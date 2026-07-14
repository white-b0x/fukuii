# Reth — Library-First Architecture & Doc-Site Patterns

Source: `.claude/repo-references/clients/reth/` (vendored full clone, verified genuine —
`.git/` present; `origin` points at a fork, `white-b0x/reth`, but the `upstream` remote is
`https://github.com/paradigmxyz/reth.git` and the checked-out tree at `3d76b93c` (2026-07-01)
matches Paradigm's `reth`). Reth is unusual among the clients in this research tree because
it is explicitly designed to be consumed as a **library**, not only run as a binary — every
section below reads that design intent as load-bearing, not marketing copy, and traces it to
the crates/examples/docs that actually implement it. Every claim is traceable to a file in
the vendored clone; where a specific line number isn't pinned down, the citation names the
section instead of inventing one.

---

## The "library-first" design philosophy (docs/design/goals.md)

`docs/design/goals.md` (84 lines, read in full) is short and opens with the plainest possible
framing of intent: "Our goal in building Reth, apart from improving client diversity, is to
create a client that delivers maximally along each of the following dimensions" — three
pillars, each given its own `##` section (`goals.md:5-9`):

1. **Performance** (`goals.md:13-38`) — framed entirely around one pipeline: `RPC -> EVM ->
   Cache -> Codec -> DB` (`goals.md:29`), with an explicit claim that **the bottleneck is not
   the EVM interpreter** but state access and I/O management, so "the largest optimizations to
   be made are closest to the DB layer" (`goals.md:35`). The section closes with an aspiration
   that is itself a configurability signal: run fast enough that some data (e.g. transaction
   receipts) never needs to be persisted at all and can be regenerated on demand
   (`goals.md:37`) — i.e. performance work is explicitly in service of a smaller, more
   flexible disk footprint, not an end in itself.
2. **Configurability** (`goals.md:41-61`) — this is the pillar the task brief asks to trace
   concretely, and the file is explicit about the mechanism. Three stated motivations
   (`goals.md:45-55`):
   - **"Control over tradeoffs"** — a direct refusal to make opinionated choices on behalf of
     every user, because "almost any given design choice or optimization... comes with its own
     tradeoffs."
   - **"Profiles"** — the goal of letting the *community* build configuration presets fit to
     different operator profiles (archive node, RPC provider, MEV searcher) rather than Reth
     itself shipping a fixed set of modes.
   - **"Extension to EVM-compatible L1s and L2s"** — stated as "another consequence of a
     configurable design," i.e. the L1/L2-extensibility goal is presented as *downstream of*
     configurability, not a separate feature.

   The "How" subsection (`goals.md:57-61`) names the actual mechanism in two words:
   **"Modularity & generics."** The full sentence: "We prioritize a modular design for Reth
   with reasonable (and zero-cost!) abstractions over generic interfaces. We want it to be
   quick and easy for others to extend or adapt the implementation to their own needs."
   Concretely, this is the design decision that makes `examples/custom-evm`,
   `examples/custom-hardforks`, `examples/custom-node-components`, and the entire ExEx
   subsystem (see below) possible: Reth's node-builder, EVM factory, and chain-spec types are
   generic traits/type-parameters a downstream crate can implement, not concrete structs a
   downstream project would have to fork the binary to change.
3. **Open-source-friendliness** (`goals.md:65-84`) — framed around sustaining contributor
   momentum ("Maintaining a client implementation is *hard*... a known challenge"), with two
   concrete levers named: verbose/thorough documentation "accessible to anyone with a basic
   understanding of Ethereum" (`goals.md:79`), and disciplined issue tracking so "anyone in the
   community can stay on top of the state of development" (`goals.md:83`). This is the pillar
   that indirectly explains the scale of the Vocs documentation site and the `examples/`
   directory covered below — both exist because the goals document treats documentation depth
   as a first-class deliverable, not an afterthought.

**Why this matters for reading everything else in this report:** "Configurability" here is not
a vague adjective — it cashes out as a specific architectural commitment (generic traits over
concrete types) that shows up mechanically in every example crate below, and the goals
document itself states the causal chain: modularity/generics → easy extension → community
presets and L1/L2 forks. This is the single most important thing to understand before reading
`examples/` — those 30 crates exist *because* this design goal was taken literally, not as a
demonstration bolted on afterward.

---

## docs/repo/layout.md — per-crate workspace map

`docs/repo/layout.md` (217 lines, read in full) is Reth's canonical "what lives where" map —
one short paragraph of context per functional group, then a flat bullet list of crates in that
group, each bullet **one line**: crate path (linked), then a one-sentence description. No
diagrams, no prose beyond the minimum needed to orient a reader; it reads as a table of
contents for the workspace rather than an architecture explainer (that job belongs to
`CLAUDE.md`'s "Core Components" list and to the Vocs site's `run`/`sdk` sections, not to this
file).

The grouping (`layout.md:5-26`, given as a table of contents at the top of the file) is, in
order: **Storage** (`layout.md:38-55`, 11 crates — `storage/codecs`, `storage/libmdbx-rs`,
`storage/db`, `storage/db-api`, `storage/provider`, `storage/nippy-jar`, etc.), **Networking**
(`layout.md:58-92`, split into Common/Discovery/Protocol/Downloaders sub-groups — `net/network`,
`net/discv4`, `net/discv5`, `net/dns`, `net/eth-wire`, `net/ecies`, `net/downloaders`),
**Consensus** (`layout.md:94-100`, 3 crates — `consensus/common`, `consensus/consensus`,
`consensus/debug-client`), **Execution** (`layout.md:102-109`, 4 crates — `revm`, `evm/evm`,
`evm/execution-types`, `evm/execution-errors`), **Sync** (`layout.md:111-117`, 3 crates —
`stages/api`, `stages/stages`, `stages/types`), **RPC** (`layout.md:119-157`, split into
Transports/Common/Utilities — `rpc/rpc`, `rpc/rpc-api`, `rpc/rpc-engine-api` — with an explicit
callout that `rpc/rpc-engine-api` "is *not* an interface crate despite the confusing name",
`layout.md:135`), **Payloads** (`layout.md:160-169`, 7 crates — `transaction-pool`,
`payload/builder`, `payload/basic`, `payload/validator`), **Primitives**
(`layout.md:172-178`, 3 crates — `primitives`, `primitives-traits`, `trie`),
**Ethereum-specific** (`layout.md:180-191`, 3 crates under `crates/ethereum/` —
`reth-ethereum-engine-primitives`, `reth-ethereum-forks`, `reth-ethereum-payload-builder`), and
**Misc** (`layout.md:193-207`, 12 small utility crates — `tasks` (an "executor-agnostic task
abstraction... supports blocking tasks and handles panics gracefully"), `metrics/*`,
`tracing`, `fs-util`, `static-file`, `e2e-test-utils`).

Cross-checked against the live tree: `find crates -mindepth 2 -maxdepth 4 -name Cargo.toml`
returns **108 crates** under `crates/`, and the root `Cargo.toml`'s workspace-member list
totals roughly 142 lines of path entries (including `bin/`, `examples/`, and `testing/`
members alongside `crates/`) — `layout.md`'s ~45 named crates are a curated top-level map
covering the architecturally significant ones, not an exhaustive per-crate index; it explicitly
groups by *function* (Storage/Networking/Consensus/...) rather than by directory depth, which
is why a 108-crate tree can be summarized in ~45 one-line bullets without feeling incomplete —
sub-crates like `storage/codecs/derive` or `net/eth-wire-types` are folded as sibling bullets
under their parent group rather than nested.

**The one-line-per-crate convention is the portable pattern here**, not the specific grouping
(which is Ethereum-execution-client-specific and already has a close fukuii analog — see the
verdict table). What makes this file effective is that every bullet answers exactly one
question — "what is this crate for" — in under 25 words, with no crate appearing without a
description and no description spanning more than one sentence. A reader can `Ctrl+F` a crate
name from a compiler error or an import path and get an answer in one line, every time.

---

## examples/ — 30 confirmed library-usage example crates (not ~26)

`examples/README.md` (read in full) opens with the literal instruction that frames the whole
directory: **"To run an example, use the command `cargo run -p <example>`."** This is the
concrete, mechanical proof-point of "library-first" — every example is a standalone binary
crate that depends on Reth as a library dependency (typically via the `reth-ethereum`
umbrella crate) and links against it with `cargo run -p <name>`, exactly like any other
consumer of a published Rust crate would. There is no separate "SDK" distinct from the node
binary's own crates; the same `reth-ethereum` types used to *build* the node are re-exported
for anyone building *on top of* it.

**Confirmed count: 30 example crates with their own `Cargo.toml` + `src/`**, not the ~26 the
task brief expected:

```
beacon-api-sidecar-fetcher   custom-node-components    manual-p2p
beacon-api-sse               custom-payload-builder    network
bsc-p2p                      custom-rlpx-subprotocol    network-proxy
custom-auth-http-middleware  custom-rpc-middleware      network-txpool
custom-beacon-withdrawals    custom-state-root          node-builder-api
custom-dev-node              db-access                  node-custom-rpc
custom-engine-types          exex-subscription          node-event-hooks
custom-evm                   exex-test                  polygon-p2p
custom-hardforks             full-contract-state        precompile-cache
custom-inspector             rpc-db                      txpool-tracing
```

**A stale-index finding worth flagging on its own merits (a second instance of the pattern the
book.toml section below documents at repo-root scale):** `examples/README.md`'s own
category tables (grouped as Node Builder / ExEx / RPC / Database / Network / Mempool / P2P /
Misc) link only **20 of the 30** actual example crates. Ten exist on disk, build as real
crates, and are absent from the README's index entirely: `beacon-api-sidecar-fetcher`,
`custom-beacon-withdrawals`, `custom-hardforks`, `custom-rlpx-subprotocol`,
`custom-state-root`, `exex-subscription`, `exex-test`, `full-contract-state`,
`network-proxy`, and `node-builder-api`. The README's own "ExEx" section (line ~19-21) even
explicitly *defers* ExEx coverage to an external repository
(`https://github.com/paradigmxyz/reth-exex-examples`) rather than mentioning that this
in-tree `examples/` directory now also contains two ExEx crates itself
(`exex-subscription`, `exex-test`) — those appear to have been added after the README's ExEx
section was last touched. This is the same class of drift documented independently for
Nethermind's `AGENTS.md` (missing `Stateless.slnx`/`Nethermind.Xdc`/etc.) and fukuii's own
`docs/architecture/README.md` (indexing 5 of 17 files) — a hand-maintained index next to a
fast-growing directory, silently falling behind. See the verdict table.

### Three representative examples, read in full, characterizing "library-first" in practice

**`custom-node-components/src/main.rs`** (119 lines) shows the shallowest possible
customization: swap one component (the transaction pool) while keeping every other default.
`main()` is nine lines: `Cli::parse_args().run(async move |builder, _| { ... })`, and the
customization is exactly one call —
`.with_components(EthereumNode::components().pool(CustomPoolBuilder::default()))`
(`main.rs:29-33`). The `CustomPoolBuilder` type (`main.rs:42-118`) implements the
`PoolBuilder<Node, Evm>` trait generically over `Node: FullNodeTypes<...>` and
`Evm: ConfigureEvm<...>` — i.e. this isn't a hardcoded override of one concrete struct, it's a
trait implementation the node-builder's generic machinery will accept in place of the stock
pool builder. The example is otherwise production-shaped: it wires the same transaction-backup
and pool-maintenance background tasks (`spawn_critical_with_graceful_shutdown_signal`,
`spawn_critical_task`, `main.rs:88-114`) the real default pool builder wires, so "custom" here
means "compile-time swap one generic parameter," not "reimplement the surrounding
plumbing."

**`db-access/src/main.rs`** (250 lines) is the example that most directly demonstrates the
"library, not just a binary" claim — it opens a **read-only** MDBX transaction against a
running (or offline) Reth datadir from a **separate process** and exercises the provider
traits directly: `HeaderProvider`, `BlockReader`, `TransactionsProvider`, `ReceiptProvider`,
`StateProvider`, `AccountReader` (`main.rs:8-14`). The file's own header comment states the
intent precisely: "Providers are zero cost abstractions on top of an opened MDBX Transaction
exposing a familiar API to query the chain's information without requiring knowledge of the
inner tables" (`main.rs:16-21`), and explicitly notes these abstractions carry **no caching** —
caching is `EthApi`'s job, not the provider layer's — so a library consumer gets exactly the
raw-data-access primitive without inheriting the RPC server's caching policy. Concretely it
demonstrates: header lookup by number/hash + range (`main.rs:57-75`), transaction lookup by
id/hash with block metadata (`main.rs:78-108`), block lookup by number/hash/source
(`main.rs:111-148`), bloom-filtered receipt/log scanning using a `Filter` builder with
`event_signature`/`topic1`/`topic2` (`main.rs:176-209` — a hand-rolled ERC-20 `Transfer` event
filter), and state queries including a Merkle proof fetched and verified against the block's
state root (`provider.proof(...)`, `proof.verify(state_root)`, `main.rs:242-246`). This is not
a toy — it is the exact set of primitives a block explorer, indexer, or analytics tool would
need, exposed as an ordinary Rust API over a datadir path.

**`exex-subscription/src/main.rs`** (181 lines) is the most structurally interesting of the
three: it combines two "library-first" surfaces at once — an ExEx (see below) *and* a custom
JSON-RPC subscription endpoint (`jsonrpsee`'s `#[rpc(server, namespace = "watcher")]` macro,
`main.rs:37-43`) — to build a live "watch this address's storage slots change" WebSocket feed
with **zero external infrastructure**: no separate indexer process, no message queue, just an
`mpsc` channel bridging an RPC subscription handler and the ExEx's block-commit loop
(`main.rs:95-161`). On every `ChainCommitted` notification it walks `execution_outcome.bundle
.state` for the subscribed address and pushes a `StorageDiff { address, key, old_value,
new_value }` (`main.rs:110-130`) to every open subscriber. The `main()` function
(`main.rs:163-180`) is the clearest illustration of composability: a single `builder` call
chains `.extend_rpc_modules(...)` and `.install_exex(...)` before `.launch()` — two independent
extension mechanisms (custom RPC surface, custom execution-triggered logic) attached to the
same default `EthereumNode` in one fluent call.

### fukuii comparison

fukuii has no `examples/` directory and no equivalent "run this against the library, not the
binary" surface — there is no `reth-ethereum`-style umbrella re-export crate, no
`cargo run -p <example>`-equivalent invocation for a standalone consumer of fukuii's domain
types, and no documented pattern for opening fukuii's RocksDB datadir read-only from a
*separate JVM process* the way `db-access` opens Reth's MDBX read-only from a separate OS
process. This is not a gap to "fix" reflexively — fukuii is not currently positioned as an
embeddable library, and manufacturing a forced fukuii translation for each of the 30 examples
would invent relevance that isn't there. See the verdict table for an honest per-category
call.

---

## ExEx plugin architecture

### What triggers an ExEx and what state it derives from

`docs/vocs/docs/pages/exex/overview.mdx` (61 lines, read in full) defines the concept plainly:
"An Execution Extension is a task that derives its state from changes in Reth's state... They
are called Execution Extensions because the main trigger for them is the execution of new
blocks (or reorgs of old blocks) initiated by Reth" (`overview.mdx:12-16`). Three example use
cases are named explicitly in the same sentence: **rollups, bridges, and indexers**
(`overview.mdx:13`), with a link out to Paradigm's own blog post for a longer treatment of
what can be built. Architecturally (`overview.mdx:20-39`, a Mermaid `graph LR`), one Reth
process hosts N ExExes, each connected by two arrows: `Reth -->|Notifications| ExEx` and
`ExEx -->|Events| Reth` — a **bidirectional but strictly typed** channel, not an open-ended
plugin API. Critically, `overview.mdx:41-46` states what ExExes are *not*: **not separate
processes** — "ExExes are compiled into the same binary as Reth, and run alongside it, using
shared memory for communication." A remote/out-of-process variant exists but is a
deliberately separate, more complex pattern (see Remote below), not the default.

### The mechanism, in order

`docs/vocs/docs/pages/exex/how-it-works.mdx` (61 lines, read in full) states the core
primitive in one sentence: "ExExes are just [Futures]... that run indefinitely alongside
Reth" (`how-it-works.mdx:36-37`), installed via the node builder and driven by Reth itself,
which is documented as owning five lifecycle responsibilities (`how-it-works.mdx:42-49`):
polling ExEx futures, sending notifications (chain commits/reverts/reorgs, from both historical
and live sync), processing events emitted back by ExExes, **pruning only the data every
installed ExEx has confirmed it has processed**, and shutting ExExes down on node shutdown.
The pruning interaction is called out as deserving "a special mention" (`how-it-works.mdx:53`):
an ExEx **should** emit `ExExEvent::FinishedHeight` to tell Reth what it has processed
(`how-it-works.mdx:55-56`) — Reth will only prune state older than the *lowest* unconfirmed
height across all installed ExExes, and an ExEx will only ever receive notifications for block
numbers strictly greater than its own last-confirmed height (`how-it-works.mdx:58-60`). This
is the mechanism that makes ExExes safe to run against a pruned/full node rather than requiring
every ExEx-hosting node to be an archive node.

`docs/vocs/docs/pages/exex/hello-world.mdx` (96 lines, read in full) walks the minimal
implementation as a live-runnable-snippet-driven tutorial (see the doc-site tooling section
below for the `[!include ...]` mechanism itself): a plain async function that loops on
`ctx.notifications.try_next().await?`, logs each of the three notification variants (chain
commit/revert/reorg), and emits `ExExEvent::FinishedHeight` whenever a commit notification
carries a committed chain (`hello-world.mdx:66-89`). Two callouts are given explicit
`<div class="warning">` treatment in the rendered doc: the ExEx's future must **never
resolve** — a node with an ExEx that exits will itself exit (`hello-world.mdx:58-64`) — and
the `FinishedHeight` event is "a very important part of every ExEx" specifically because it's
"the only way" to unblock pruning (`hello-world.mdx:82-89`).

`docs/vocs/docs/pages/exex/tracking-state.mdx` (68 lines, read in full) extends the same
example into a stateful `struct` implementing `Future` manually (rather than an anonymous
async fn), explicitly framed as a testability trade-off: "Having a stateful async function is
also possible, but it makes testing harder, because you can't access variables inside the
function to assert the state of your ExEx" (`tracking-state.mdx:18-23`). It tracks
`first_block` and a running `transactions` counter that is decremented (via `saturating_sub`,
to tolerate a reorg arriving before any commit has been counted) on reverts and incremented on
commits (`tracking-state.mdx:55-64`) — a minimal worked example of exactly the "derives its
state from changes in Reth's state" framing from the overview page.

`docs/vocs/docs/pages/exex/remote.mdx` (172 lines, read in full) documents the **explicitly
separate-process** variant: an ExEx that forwards every notification over gRPC (via Tonic) to
an external consumer binary, "so that this chapter" contrasts directly with the in-process
default described above. It is framed as materially more complex (Protobuf schema definition,
build-time codegen via `build.rs`, a `bincode`-serialized notification stream, and an explicit
warning to raise gRPC's max message size to `usize::MAX` because "notifications can get very
heavy," `remote.mdx:145-150`) — i.e. the docs are honest that out-of-process ExEx consumption
is a deliberate escape hatch with real cost, not the recommended default path.

### Example use cases named across the four pages

Consolidating what's stated explicitly rather than inferred: **rollups, bridges, indexers**
(`overview.mdx:13`), plus the concrete worked example across `hello-world`/`tracking-state`/
`remote` of a **live storage-change subscription/watcher** (also demonstrated as a real,
in-tree crate at `examples/exex-subscription`, read above) — i.e. the documented use cases and
the actual example code are consistent with each other, not aspirational-only.

### fukuii comparison

fukuii has no plugin/extension-point architecture analogous to ExEx — no notification stream
of chain-commit/revert/reorg events consumable by third-party code compiled into the same
binary, no `FinishedHeight`-style pruning-coordination contract, and no equivalent of "install
custom logic without forking the node." This is architecturally the largest single gap this
report identifies, and also the least portable in isolation — see the verdict table for why
"port ExEx" is not a reasonable ask on its own, only a reasonable thing to *keep in mind* if
fukuii's product direction ever moves toward supporting third-party extensions (rollup/bridge/
indexer builders) against a running node.

---

## Doc-site tooling (Vocs) — and a stale-config cautionary tale

### The live stack: Bun + Vite + MDX + React, confirmed

`docs/vocs/CLAUDE.md` already documents the Vocs site's day-to-day authoring workflow in
detail (MDX content under `docs/pages/`, `sidebar.ts` navigation, snippets under
`docs/snippets/`) — this section does not repeat that content, only confirms and extends it
with what the task brief specifically asked to verify. A future dedicated
`reth/agentic-tooling-pattern.md` (none exists yet in this research tree, matching the pattern
already noted in the Erigon report for its own `agents.md` files) would be the right place for
a full `docs/vocs/CLAUDE.md` vs. fukuii's `CLAUDE.md`/`AGENTS.md` comparison; this report only
establishes the tooling facts needed for the build/release picture.

`docs/vocs/vocs.config.ts` (82 lines, read in full) confirms: `defineConfig` from the `vocs`
package (`vocs.config.ts:1`), `srcDir: 'docs'` + `outDir: 'docs/dist'` +
`renderStrategy: 'full-static'` (`vocs.config.ts:9,13-14`), a top-nav linking `Run`/`SDK`/
`Rustdocs`/GitHub/a pinned version-and-releases dropdown (`vocs.config.ts:20-38`), and two
Vite plugins (`vocs.config.ts:52-81`) that rewrite relative `.mdx` links in the CLI summary
page differently for `serve` vs. `build` — i.e. Vocs itself is a Vite-based static-site
generator, and this config reaches into Vite's plugin API directly rather than treating Vite
as fully hidden. `docs/vocs/package.json` confirms the runtime stack precisely: `vocs@^2.0.11`
depending on `vite@^8.0.16` and `react@19.2.4`/`react-dom@19.2.4` (plus `waku@1.0.0-beta.0`,
Vocs's underlying RSC-capable meta-framework, and `mermaid@^11` for diagram rendering), with
all `package.json` scripts (`dev`, `build`, `preview`, `check-links`, `generate-redirects`)
invoked via `bun` (per `docs/vocs/CLAUDE.md`'s own command list and the CI workflow below,
which pins `bun-version: v1.2.23`).

**Live runnable code snippets are a real, wired mechanism, not aspirational.** Every ExEx
tutorial page read above uses a literal `[!include ~/snippets/sources/exex/<chapter>/...]`
directive (e.g. `hello-world.mdx:21,29,50,71` for `Cargo.toml` and three progressively-built
`src/bin/N.rs` files) that Vocs expands at build time into the actual file contents from
`docs/vocs/docs/snippets/sources/exex/` — confirmed on disk: 18 real files under
`docs/vocs/docs/snippets/sources/exex/{hello-world,tracking-state,remote}/`, including a real
`Cargo.toml` per chapter, a `build.rs` and `proto/exex.proto` for the gRPC remote chapter, and
numbered `src/bin/{1,2,3}.rs` files that are literally the incrementally-built example code
shown step-by-step in `hello-world.mdx`/`tracking-state.mdx`. **Note the actual path differs
from what might be assumed**: the snippets directory lives at
`docs/vocs/docs/snippets/sources/`, not a top-level `docs/snippets/` — it is nested one level
deeper, inside the Vocs app's own `docs/` source root (`vocs.config.ts`'s `srcDir: 'docs'` is
relative to `docs/vocs/`). The practical effect of this mechanism: every code block shown in
the ExEx tutorials is a real file that can be compiled and tested independently of the prose
around it — the documentation cannot silently drift from working code the way a hand-copied
code block can, because the snippet **is** the source of truth, transcluded rather than
duplicated.

### The vestigial `book.toml` finding — a concrete "stale config" cautionary example

`book.toml` (23 lines, read in full) exists at the **repository root** and is unambiguously an
[mdBook](https://rust-lang.github.io/mdBook/) configuration file: `[book]` section with
`src = "book"` (`book.toml:6`), an `[output.html]` section pointing `theme = "book/theme"`
(`book.toml:11`) with `git-repository-url`, `default-theme = "ayu"`, and fold/preprocessor
settings (`links`, `index`, `template`) that are all mdBook-specific configuration keys with no
meaning to Vocs, Vite, or any other tool actually used in this repository.

**Confirmed: no `book/` directory exists anywhere in the repository.** A recursive,
case-insensitive search for any directory named `book` (excluding `.git/` and `node_modules/`)
returns zero results. `book.toml` names `src = "book"` and `theme = "book/theme"` as its two
load-bearing paths, and neither exists — this file cannot actually build anything; if
`mdbook build` were invoked against it today it would fail immediately on a missing source
directory. This is dead, leftover configuration from what the surrounding evidence indicates
was a prior docs-site migration (mdBook → the current Vocs-based React/Vite site) that was
never cleaned up: the actual documentation content lives entirely under `docs/vocs/docs/pages/`
as MDX, built by Vocs/Vite/Bun as confirmed above, with zero mdBook involvement anywhere in the
active toolchain.

**The CI job compounds the naming confusion rather than resolving it.** `.github/workflows/
book.yml` (90 lines, read in full) is named `book` (`book.yml:4`) and its own **file header
comment reads "Documentation and mdbook related jobs"** (`book.yml:1`) — a comment that is
itself stale in exactly the same way `book.toml` is. The job it actually runs
(`build`, `book.yml:16-67`) does the following, in order: installs LLVM
(`.github/scripts/install_llvm.sh`), installs **Bun** `v1.2.23` (`book.yml:29-32`), installs
**Playwright's Chromium browser** specifically because "Required for rehype-mermaid to render
Mermaid diagrams during build" (`book.yml:34-39`), installs a nightly Rust toolchain to build
rustdoc output (`book.yml:41-45`), runs `bash scripts/build-cargo-docs.sh` to generate the
embedded Rustdoc pages, then `cd docs/vocs/ && bun run build` (`book.yml:47-49`) — **Vocs**,
not mdBook, and no `mdbook` invocation anywhere in the file — followed by a set of hard
`test -f`/`grep -q` assertions against the generated `docs/dist/public/` tree (index page,
overview page, SDK page, logo, OG image, embedded Rustdoc index, and a
`content="rustdoc"` meta-tag check, `book.yml:50-58`) before uploading the built
`docs/vocs/docs/dist/public` directory as a Pages artifact and deploying it on pushes to
`main` (`book.yml:61-89`). Every substantive tool this job invokes — Bun, Vite (via Vocs),
Playwright, Mermaid, rustdoc — is exactly the Vocs stack confirmed above; the job is named
"book" purely for historical reasons (it once built the mdBook site, before the migration to
Vocs, and the workflow filename/job-name/header-comment were never renamed to match).

**The lesson for fukuii, stated directly.** This is a concrete, low-stakes but real example of
exactly the failure mode fukuii's own doc tooling should be audited against periodically: a
config file (or a comment, or a CI job name) can persist for an arbitrarily long time after the
tool it configures has been fully replaced, because nothing forces it to be deleted — it
doesn't fail a build, it doesn't show up in a directory listing anyone routinely checks, and a
plausible-looking name ("book") gives no visual signal that it's dead. fukuii's own doc
tooling is confirmed MkDocs Material-driven (`mkdocs.yml`, per the sibling self-audit at
`../fukuii/build-release-pattern.md`) with no competing doc-site config found in that same
audit — but the *general* habit this Reth finding argues for is a periodic, cheap check:
grep the repo root and `docs/` for config files belonging to tools that are no longer in the
active build/CI path (an old `Dockerfile.docs`, a retired `_config.yml`, a Sphinx `conf.py`
from a still-earlier migration, etc.), and check whether any CI job name or header comment
still refers to a tool the job no longer actually runs. This costs a few minutes at a sprint
boundary and catches exactly the kind of drift that, left unaddressed indefinitely, misleads
a future contributor (or a future agent) into believing a dead file is live configuration.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable (contingent) | Reasoning |
|---|---|---|
| `docs/design/goals.md`'s three-pillar framing (Performance / Configurability / Open-source-friendliness), with "Configurability" cashed out concretely as "Modularity & generics" → community profiles + L1/L2 extensibility | **Not portable as a document to copy, but worth writing fukuii's own equivalent** | fukuii doesn't have a single short "why does this client exist and what does it optimize for" document analogous to `goals.md` — `AGENTS.md`'s opening paragraph states *what* fukuii is (multi-network EVM client, PoW/PoS families) but not *why* those specific design axes were chosen or what tradeoffs they imply. A short, Reth-goals-style document (a page, not a chapter) stating fukuii's own performance/configurability/community priorities would be a cheap, high-clarity addition — but it should reflect fukuii's actual current priorities, not import Reth's library-first framing wholesale, since fukuii is not architected as an embeddable library today. |
| `docs/repo/layout.md`'s one-crate-per-line, function-grouped workspace map (Storage/Networking/Consensus/Execution/Sync/RPC/Payloads/Primitives/Misc, ~45 crates summarized from a 108-crate tree) | **Already largely ported** | fukuii's module surface is far smaller (6 real sbt subprojects + 4 test-scoped configurations per the fukuii self-audit) and doesn't need a 45-bullet functional map — `AGENTS.md`'s existing module table plays this role already, at appropriate scale for fukuii's actual module count. The *convention* (one line, one sentence, no crate/module omitted) is worth holding fukuii's own table to as a standard, and the fukuii self-audit already flags that `AGENTS.md`'s module line conflates real subprojects with test-scoped configs — a smaller version of the same "keep this list honest" problem `layout.md` solves for Reth at much larger scale. |
| `examples/` — 30 standalone, `cargo run -p <name>`-able library-usage crates demonstrating custom EVMs, custom node components, custom payload builders, custom RPC middleware, direct MDBX access from a separate process, etc. | **Not portable (contingent on product direction)** for all 30 individually | fukuii is not architected as an embeddable library today: there is no `reth-ethereum`-style umbrella re-export crate, no documented pattern for a separate JVM process opening fukuii's RocksDB datadir read-only, and no generic-trait extension points analogous to `PoolBuilder`/`ExecutorBuilder`/`EvmFactory`. Manufacturing per-example fukuii translations would invent relevance that doesn't exist. If fukuii's product direction ever shifts toward supporting third-party tooling built against a running node (indexers, custom RPC namespaces, alternate EVM configs for a fork), the closest single starting point would be `db-access` (read-only external-process data access) and `custom-node-components` (swap one component via a trait) as the two lowest-complexity patterns to study first — but this is speculative, not a current gap to close. |
| `examples/README.md` linking only 20 of 30 real example crates (10 undocumented: `beacon-api-sidecar-fetcher`, `custom-beacon-withdrawals`, `custom-hardforks`, `custom-rlpx-subprotocol`, `custom-state-root`, `exex-subscription`, `exex-test`, `full-contract-state`, `network-proxy`, `node-builder-api`) | **Port now (as a process lesson, not code)** | The same drift class already documented for Nethermind's `AGENTS.md` and fukuii's own `docs/architecture/README.md` — a hand-maintained index next to a fast-growing directory falls behind. No fukuii action needed beyond continuing the existing habit (already recommended in the Nethermind report) of periodically diffing a directory's real contents against whatever index document claims to enumerate it. |
| ExEx (Execution Extensions) — in-process, Future-based plugin architecture triggered by chain commit/revert/reorg, with a `FinishedHeight`-event contract that gates pruning to only-what-every-installed-ExEx-has-processed | **Not portable (contingent on product direction)** | This is the single largest architectural gap identified in this report, and also the one requiring the most upstream design work to port meaningfully: fukuii would need (a) a stable, versioned notification stream of commit/revert/reorg events safe to expose to third-party code compiled into the same JVM process, (b) a pruning-coordination contract analogous to `FinishedHeight` so fukuii's own SNAP-sync/pruning logic doesn't delete state an installed extension still needs, and (c) a decision about whether "compiled into the same binary" (Reth's model) or an out-of-process model (Reth's own `remote` ExEx escape hatch, which the docs themselves frame as materially more complex) fits fukuii's JVM/Pekko-actor architecture better. Worth a genuine design spike if/when fukuii wants to support rollup/bridge/indexer builders directly, not a drop-in port — flagged here so a future evaluation of "should fukuii support plugins" starts from a correct model of what ExEx actually guarantees (notification ordering, pruning safety) rather than reinventing those guarantees from scratch. |
| Vocs (Bun + Vite + React 19 + MDX + Mermaid) as the doc-site engine, with live-transcluded runnable code snippets (`[!include ~/snippets/sources/...]`) so tutorial code blocks are real, independently-buildable files rather than hand-copied prose | **Not portable wholesale; the snippet-transclusion principle is worth studying** | fukuii's doc site is MkDocs Material (`mkdocs.yml`, confirmed via the fukuii self-audit) — a materially simpler static-site generator with no React/Vite build step and no live-snippet-transclusion mechanism; fukuii's code examples in `docs/` are hand-written prose blocks, not includes of real compiled/tested source files. Replacing MkDocs with a Vocs-equivalent stack is not justified by this finding alone (MkDocs Material already serves fukuii's audience-tab navigation and Mermaid-via-`pymdownx.superfences` needs adequately, per the fukuii self-audit). The narrower, genuinely portable idea is the **transclusion principle itself**: MkDocs supports snippet inclusion via `pymdownx.snippets` (already a common Material-for-MkDocs extension) — fukuii could evaluate wiring its own tutorial-style docs (if any grow to Reth's ExEx-tutorial density) to include real, compiled/tested source files rather than hand-maintained code blocks, without adopting Vocs/React/Bun to do it. |
| `book.toml` at repo root — a syntactically valid mdBook config (`src = "book"`, `theme = "book/theme"`) with **no `book/` source directory existing anywhere in the repository**, left over from a prior mdBook → Vocs docs-site migration | **Port now (as a process lesson, not code)** | The concrete, cheap habit to adopt: at a sprint boundary or doc-tooling review, grep the repo root and `docs/` for configuration belonging to tools no longer in the active build/CI path, and check whether any CI job name or header comment still references a retired tool. fukuii's own doc tooling audit (the sibling `../fukuii/build-release-pattern.md` self-audit) found no competing doc-site config today — but this Reth finding is valuable specifically as a demonstration of *how quietly* such drift can persist (a plausible file name, a stale header comment restating the wrong tool, a CI job literally named after the dead tool) without ever causing a build failure that would force someone to notice and fix it. |
| `.github/workflows/book.yml` — job named `book`, header comment says "mdbook related jobs," but the job body installs Bun/Playwright/Mermaid and runs `bun run build` (Vocs) with zero `mdbook` invocation | **Port now (as a process lesson, not code)** | Same lesson as `book.toml` above, doubled: even the CI job's own header comment is stale, meaning a reader trusting the comment rather than reading the actual `run:` steps would draw an entirely wrong conclusion about what the job does. Reinforces that comments and file/job names are not self-verifying — the only reliable check is reading what the steps actually execute, which is exactly the discipline this whole research-document series (and the sibling self-audit format) already applies. No fukuii-specific action beyond continuing that discipline for fukuii's own `.github/workflows/*.yml` naming. |

---

*Compiled from a direct read of every file cited above in the vendored clone at
`.claude/repo-references/clients/reth/`. Line numbers refer to that clone's current checkout
(`3d76b93c`, 2026-07-01); re-verify against `git log` if the vendored copy is refreshed.*
