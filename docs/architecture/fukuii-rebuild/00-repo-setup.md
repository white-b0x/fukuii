# 00 — repo setup: the clean-slate

_The foundation commit `7d2d2ae72` (skeleton) + the build/dep modernization. Everything the layer
docs are built on._

## Decision: a clean write, in place, on `july-mod-sprint`

The rebuild happens **on the trunk** (`july-mod-sprint`), not a separate branch — pre-1.0, no
users, nothing to run in parallel. The old client (`com.chipprbots.ethereum.*`, 1317 files) was
removed wholesale and the new client (`com.chipprbots.fukuii.*`) grows in its place, layer by
layer. The old tree is **fully preserved on branch `july-fourth`** (and in history) and is consulted
only as a reference. This is a clean write, not a migration: there is no coexistence, no strangler,
no on-disk/wire compatibility with old fukuii to preserve.

## The module layering (the structural fix)

The old codebase's #1 finding was a **13-package dependency cycle** (a strongly-connected
component): `domain` imported up into `db`/`mpt`/`ledger`/`jsonrpc`/`network`, with `db↔mpt`,
`ledger↔consensus`, `network↔blockchain` all cyclic. Cycles make a codebase un-reasoned-about —
you cannot build or test one piece in isolation.

The rebuild expresses each layer as its **own sbt module** with an explicit `.dependsOn`, so the
dependency graph is a **DAG (directed acyclic graph)** — every edge points *down*, and an upward
edge is a **compile error**. The cycle is not "cleaned up," it is made *structurally impossible*:
it would not compile. 14 modules under `modules/<name>/`:

```
bytes · common · crypto→bytes · rlp→bytes
domain → bytes,crypto,rlp,common
storage → domain,common        trie → domain,crypto,storage
evm → domain,crypto,rlp
execution → evm,trie,storage,domain
consensus → execution,evm,domain          (pow/pos as internal packages until a 3rd family lands)
network → domain,crypto,rlp,common
sync → network,consensus,execution,storage,trie
rpc → domain,execution,consensus,sync,network,storage
node → aggregates + dependsOn all          (composition root)
```

Rationale for the boundaries — the six specific cycles from the old code and how the layering
breaks each — is in `.local/docs/phase4/target-architecture.md`. Notably: `domain` is a **pure
value-type layer** with no upward edges (the old `Blockchain` facade traits that reached up move to
a higher layer); `common` is a **true leaf** (no `domain` dependency, breaking the old
`domain↔utils` cycle).

## Build configuration — harvested, not reinvented

The old `build.sbt` carried genuinely good *build tooling* (as opposed to old client *code*), which
was preserved verbatim into the new multi-module build:

- **`commonSettings`** and the **Scala 3 warning ratchet** — `-source:future`, `-Wunused:all`, and
  `-Wconf:id=E198:error` / `-Wconf:cat=unchecked:error` (unused symbols and unchecked patterns are
  **build errors**, not warnings). Every new module compiles under this from line one.
- The **`Integration`/`Benchmark`/`Evm`/`Rpc` config axes**, scalafmt/scalafix/scapegoat/scoverage
  wiring, and the **test-tier command aliases** (`compile-all`, `testEssential`/`Standard`/
  `Comprehensive`, `formatAll`, …), rewritten to the 14 new modules.
- Packaging (native-packager, assembly) with `mainClass := com.chipprbots.fukuii.node.Main` and the
  JDK-25 Docker base.

## Runtime & dependency floor

Modern floor, current-best-stable, set as the baseline every module is born on:

| | Version | Note |
|---|---|---|
| Scala | **3.3.8 LTS** | current 3.3 LTS line (next LTS 3.9 unreleased — bump when it ships) |
| JDK | **25 LTS** | `eclipse-temurin:25` |
| sbt | **2.0.2** (from 1.10.7) | build-definition layer now runs on Scala 3 (was Scala 2.12); see below |
| Pekko / Pekko-Typed | 1.6.0 | current (Pekko 2 unreleased) |
| cats-effect / fs2 | 3.7.0 / 3.13.0 | bumped |
| Netty | **4.1.136.Final** | security — fixes CVE-2026-42578 + CVE-2026-44249 (Socks5ProxyHandler/UPnP surface) |
| BouncyCastle / Guava | 1.84 / 33.6 | current |
| logback | **1.5.38** | security — CVE-2026-13006 (Janino condition injection) |

**sbt-2 cutover (landed).** The build tool moved from sbt 1.10.7 (build-definition layer on Scala
2.12) to **sbt 2.0.2** — the build definitions now compile on Scala 3. Every plugin moved to its
sbt-2 release (buildinfo 0.13.1, git 2.1.0, native-packager 1.11.7, assembly 2.3.1, scalafmt 2.6.1,
scoverage 2.4.4, ci-release 1.12.0, scalafix 0.14.7, updates 0.7.0). Plugins dropped were each
**replaced or explicitly flagged — never a silent capability loss** (the modernize-don't-drop rule):

- **`sbt-scapegoat`** (no sbt-2 artifact exists) → **replaced** by Scalafix `DisableSyntax`
  (`noNulls`/`noAsInstanceOf`/`noIsInstanceOf`) + Scala 3 `-Wvalue-discard`/`-Wnonunit-statement`.
  `noUniversalEquality` was deferred to a future `-language:strictEquality` MOD (not blindly enabled
  — it bans all `==`); the community `scaluzzi` set was verified abandoned.
- **`sbt-kanela-runner` / `sbt-javaagent`** (Kamon) — already dead-wired; dropped with no loss.
- **`sbt-api-mappings`** — no clean sbt-2 release; cosmetic (scaladoc cross-links); flagged to re-add.

Two **false-green build regressions** were caught and fixed at the cutover (sbt-2 changed task
resolution): `IntegrationTest/` → `It/`, and — more seriously — a bare `<module>/test` was silently
resolving to a **0-test no-op**, which would have made `testEssential`/`testAll` report success
while running nothing (the same class of bug the Systemic Review flagged as REPO-06). Every alias
now scopes through `Test /` explicitly. Full per-dependency audit + the drop-audit table:
`.local/docs/phase4/dep-build-floor-proposal.md`.

## Kept vs cleared

**Kept** (reference material, external fixtures, tooling — the design inputs and the test oracle):
`docs/` (incl. `docs/research/clients/` — the Systemic Review), `ets/tests` (ethereum/tests
submodule) + `hive/` (conformance simulators), `ops/` (Grafana dashboards), `.agents/`, `.claude/`,
`.local/`, `.github/`, `scripts/`, the format/lint configs, `LICENSE`/`NOTICE`.

**Cleared** (the old client — preserved on `july-fourth`): `src/`, and the old `bytes/`/`crypto/`/
`rlp/`/`scalanet/` sibling modules. Replaced by fresh modules under `modules/`.

## State at this commit

`sbt compile-all` — **PASS** (empty skeleton; the layering is enforced, no code yet). This is the
correctly-layered, modern-floor foundation the layer docs build on.
