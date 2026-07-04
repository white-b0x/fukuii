# Nethermind — Agentic Tooling Patterns

Source: `.claude/repo-references/clients/nethermind/` (vendored full clone, verified genuine
git repository — `HEAD` at commit `0d09a09edd0a861d21c647ceaa7f9f5ea1c74255`, 2026-07-01).
All paths below are relative to that vendored clone root unless stated otherwise.

## Summary

Nethermind runs a mature, CI-integrated agentic setup: a portable `AGENTS.md` with a
load-triggered rules table, nine topic-scoped rule files under `.agents/rules/`, a
`.agents/skills/` → `.claude/skills/` symlink convention, and — the standout piece — two
production GitHub Actions workflows that invoke `anthropics/claude-code-action@v1` inside
CI itself: one posts a structured, branch-protection-gating PR review
(`claude-review.yml`), the other runs an on-demand gas-benchmark pipeline from a PR comment
(`gas-benchmark-analysis.yml`). Relative to fukuii, Nethermind's rules/skills/AGENTS.md
layer is roughly equivalent in shape (fukuii already has the same AGENTS.md+CLAUDE.md
split and the same symlink convention); the CI-review-bot layer is something fukuii does
not have at all, and is the most valuable transferable artifact in this repo.

## AGENTS.md / CLAUDE.md structure

**`AGENTS.md`** (root, 207 lines) is Nethermind's single portable agent-context file. Structure:

- **Lines 1–29**: prose "Coding guidelines and style" section — points to `CONTRIBUTING.md`
  and `.editorconfig`, states the "keep changes minimal, don't touch unrelated code" rule,
  the "always add a regression test for a bug fix" rule, an explicit "do not alter
  `src/bench_precompiles/` or `src/tests/`" carve-out, a self-documenting-code philosophy for
  inline comments, a detailed XML-doc-comment convention (`<summary>`/`<remarks>`/`<param>`/
  `<returns>`/`<exception>`/`<typeparam>`/`<inheritdoc/>`, each with a stated purpose), and a
  test-duplication rule that cross-references `test-infrastructure.md`.
- **Lines 30–46 — "Codebase Rules" (the load-trigger table)**: this is the routing
  mechanism. The instruction at line 34 is emphatic: *"You MUST read the relevant files
  before answering any query, reasoning, writing, reviewing, planning, or debugging any
  code — load additional files as soon as the task touches their domain. Do NOT skip
  loading a file because you think you already know the rules — always read from disk."*
  The table itself (lines 36–44) lists each of the nine `.agents/rules/*.md` files with a
  one-line "when to load" trigger — e.g. `coding-style.md` and `robustness.md` are both
  marked "Almost always. Load for any task requiring C#-specific reasoning"; `performance.md`
  is "Load when working on hot paths"; `di-patterns.md` is "Load when working with DI
  registration, service wiring, or component architecture." This is a lazy-load pattern:
  keep `AGENTS.md` itself short, and let the agent decide which deeper file to pull in based
  on task shape, rather than loading all nine files unconditionally on every session.
- **Lines 48–108 — Project structure**: three independent `.slnx` solutions
  (`Nethermind.slnx`, `EthereumTests.slnx`, `Benchmarks.slnx`) and an architecture map from
  entry point through consensus, state/storage, networking, tx pool, RPC, monitoring, and
  serialization — each bullet links a `src/Nethermind/Nethermind.<Area>/` directory.
- **Lines 110–123 — PR guidelines**: compile, add tests (with the exact `dotnet test`
  filter-flag invocation), run `dotnet format whitespace src/Nethermind/ --folder`, and fill
  in `.github/pull_request_template.md` — explicitly noting the template's checkboxes "drive
  automatic PR labeling" (i.e. `pr-labeler.yml` reads the PR body).
- **Lines 125–127 — Prerequisites**: points to `global.json` for the required .NET SDK.
- **Lines 129–207 — "Reproducible Benchmark Workflow Guidance"**: a long, highly detailed
  section documenting `run-expb-reproducible-benchmarks.yml` end to end — what the workflow
  does (config templating, Docker image build/reuse, `expb` tool install/execution, dotTrace
  snapshot handling), what to inspect in run output (mandatory log checks for `Exception`,
  `Invalid Block(s)`, `Unhandled`, `Fatal`, `ERROR`, and the normal-shutdown markers), a log
  structure reference (tab-separated `<job>\t<step>\t<timestamp>\t<message>` format, ANSI
  stripping caveat), and a "Notes for agents" subsection with operational caveats (e.g. the
  `EXPB_EVM_WARMUP=1` flag lowers variance but is a compute-only signal, not a storage-layer
  benchmark substitute; dotTrace XML reports are 50–70MB and must never be loaded in full —
  use `scripts/dottrace-report.sh top`/`compare` instead, which runs in under 2 seconds via
  grep+awk).

**`.claude/CLAUDE.md`** is exactly one line: `@../AGENTS.md` — a bare import, no
Claude-Code-specific content added on top. This differs from fukuii's own split: fukuii's
`CLAUDE.md` imports `AGENTS.md` via `@AGENTS.md` *and* layers substantial Claude-Code-only
orchestration on top (named subagents, Spec Kit routing, sprint tooling, continuation
protocol). Nethermind's `CLAUDE.md` adds nothing — all its agent guidance, including the
benchmark-workflow deep-dive, lives in the portable `AGENTS.md`. This is consistent with
Nethermind not (yet) running named subagents or a Spec Kit-style planning framework; there
is simply nothing Claude-Code-specific to add.

## `.agents/rules/` — full inventory

Nine files, all read in full. Fukuii verdict classifications used below: **Already
adopted** (fukuii has an equivalent), **Port now** (concrete, low-risk, worth adopting
verbatim or near-verbatim), **Needs design** (the underlying idea is good but requires
fukuii-specific adaptation before it can be ported), **Not portable** (language/framework
specific to C#/.NET, no Scala 3 equivalent).

### `coding-style.md` (27 lines)

C# style rules: prefer file-scoped namespaces, pattern matching/switch expressions over
control flow, `nameof` over string literals, `is null`/`is not null` over `==`/`!=` null
checks, `?.` null-conditional, `ArgumentNullException.ThrowIfNull`,
`ObjectDisposedException.ThrowIf`, structured XML doc comments cross-referenced back to
`AGENTS.md`'s tag-usage guidance, avoid `var` (spell out types, exception for deeply nested
generics), `Array.Empty<T>()`/`[]` over `new T[0]` (except inside attribute arguments, where
`new T[0]` is required because `Array.Empty<T>()` isn't a compile-time constant), "no LINQ
when a simple for/foreach works," trust null annotations rather than adding redundant
checks, comments explain *why* not *what*, consensus rules/algorithms must cite the EIP
number or Yellow Paper section, config keys must document units and defaults in XML docs,
DRY-extract 5+ line repeated blocks but don't over-extract trivial one-liners, move
type-parameter-independent methods out of generic classes to avoid redundant JIT
instantiations, no `#region`/`#endregion`, and a cross-reference to `code-lint.yml` as the
enforcement mechanism.

**Fukuii verdict — Not portable.** This is a C#-syntax rule sheet; nearly every rule is
either meaningless in Scala 3 (`var` avoidance, `nameof`, null-conditional operators,
`#region`) or already covered by Scalafmt/Scalafix + fukuii's own `scala3-style.md` protocol
(S1–S11). The two ideas worth lifting are general, not C#-specific, and fukuii already has
them: "cite the EIP/Yellow-Paper section for non-obvious consensus code" and "config keys
document units and defaults" are both already project convention in fukuii (see
`logging-standards.md` and the `forge`/`beacon` consensus-change protocol). No action
needed.

### `di-patterns.md` (109 lines)

Documents Nethermind's Autofac-based DI system and a custom DSL
(`Nethermind.Core/ContainerBuilderExtensions.cs`). Key content:
- A "Critical rules" block: never manually wire components that DI modules already
  register (check `Nethermind.Init/Modules/` first); tests/benchmarks should use production
  modules with overrides, not manual construction.
- A **production modules table** (lines 10–23) mapping each of nine modules
  (`NethermindModule`, `DbModule`, `WorldStateModule`, `BlockProcessingModule`,
  `BlockTreeModule`, `NetworkModule`, `DiscoveryModule`, `RpcModules`, `PrewarmerModule`,
  `BuiltInStepsModule`) to what it registers and when to touch it.
- A **WorldState architecture note** (lines 25–32): `IWorldState` was refactored to
  separate storage concerns (now `IWorldStateScopeProvider`) from snapshot/journaling logic
  (still in `IWorldState`); `IWorldStateScopeProvider` instances are explicitly non-shareable
  across block-processing contexts.
- **Singleton vs Scoped guidance** (lines 34–45) with a code example showing the "wrong"
  case (registering a per-block-stateful component as singleton leaks state across blocks).
- A **five-step "adding a new component" recipe** (lines 47–54): identify owning module →
  register with correct lifetime → use `AddDecorator` if wrapping → use `AddComposite` if
  aggregating → use `Bind` if aliasing → never put test stubs in a production module.
- A **test modules table** (lines 56–63): `PseudoNethermindModule`,
  `TestBlockProcessingModule`, `TestEnvironmentModule`, `PseudoNetworkModule`, each with what
  it overrides relative to production (lines 65–78) — e.g. `TestEnvironmentModule` swaps
  `IDbFactory`→`MemDbFactory`, `ILogManager`→quiet `TestLogManager`, `ITimestamper`→
  `ManualTimestamper`.
- A **DSL reference** (lines 80–92) showing the fluent builder surface:
  `AddSingleton<T>()`, `AddScoped<T>()`, `AddDecorator<T,TDecorator>()`,
  `AddComposite<T,TComposite>()`, `Bind<TTo,TFrom>()`, `Map<TFrom,TTo>(selector)`,
  `AddModule(...)`.
- A **preferred test-setup pattern** (lines 94–104) using direct DI over legacy wrappers.
- An **anti-pattern callout** (lines 106–109): resolving dependencies inline via
  `ctx.Resolve<T>()` inside a registration lambda is discouraged because it hides the
  dependency graph from reviewers when `Foo` gains new constructor params; prefer
  `.Add<IFoo, Foo>()` so the DI container infers the graph, and avoid injecting
  `IComponentContext` into unrelated classes (pass `Func<IFoo>`/`Lazy<IFoo>` instead).

**Fukuii verdict — Not portable (mechanism), but the *shape* is worth studying.** fukuii has
no Autofac equivalent — Pekko/Scala wiring is manual constructor injection, not a DI
container — so none of the module names, lifetime API, or DSL transfers. However the
*pattern* of "one markdown table mapping subsystem → owning module/component → when to
touch it" is a good documentation shape fukuii doesn't currently have for its own actor/
component wiring, and the "never manually construct what production wiring already
provides — test overrides live in a dedicated test-only module, never inline" principle is
directly portable as prose guidance even without the DI container underneath it. If fukuii
ever writes a "component wiring" reference doc, this file is a good structural template —
but it is a **Needs design** item, not a straight port.

### `test-infrastructure.md` (119 lines)

The single rule file for all test/benchmark projects (`*Test*`/`*Benchmark*` under
`src/Nethermind/`). Content:
- **`TestBlockchain`** (lines 5–22): legacy DI wrapper, must always be `using`-disposed;
  documents what it provides (`BlockTree`, `StateReader`, `TxPool`, `BlockProcessor`,
  `MainProcessingContext`, "30+ other components... wired via `PseudoNethermindModule`") and
  states "Don't mock what TestBlockchain provides" — use DI, not `Substitute.For<>()`.
- **`E2ESyncTests`** (line 24–26): reference for multi-instance sync test setup through
  Autofac with dynamic container creation.
- **Benchmark setup** (lines 28–40): use production DI modules with `DiagnosticMode.MemDb`
  overrides rather than manually constructing `WorldState`/`TrieStore`/`BlockProcessor`.
- **DI anti-pattern example** (lines 42–67): a full side-by-side "WRONG" (manual `new
  WorldState(...)` chain) vs. "correct" code block for both unit tests and benchmarks —
  stated as the general rule: "if production modules already wire a component, use them —
  don't construct it yourself."
- **Test guidelines** (lines 69–79): add tests to existing files rather than new ones; never
  duplicate parameterized test bodies — use `[TestCase(...)]`/`[TestCaseSource(...)]`; when
  only *parts* of tests are similar, extract shared arrange/assert into helpers/builders/base
  fixtures; keep each test body focused on what's unique to that case.
- **DotNetty `IByteBuffer` in tests** (lines 81–84): prefer `.AsDisposable()` /
  `DisposableByteBuffer`; use `PooledBufferLeakDetector` for leak-detection tests.
- **`Assert.Multiple` guidance** (lines 86–118) — the most detailed subsection: use NUnit
  4's `using (Assert.EnterMultipleScope())` (preferred over the older lambda form) to
  surface every failing assertion in one run instead of one-per-CI-cycle; dedupe repeated
  field-by-field comparisons into a helper *before* reaching for multi-scope, since the
  helper benefits every caller; a worked example (`AssertReceipt`) wrapping a
  field-by-field comparison; explicit "wrap when" (N independent assertions on the same
  unmutated object; comparison helpers; per-iteration loop bodies — wrapped *per iteration*,
  not around the whole loop) and "do NOT wrap when" (assertions interleaved with
  state-mutating calls; a null-check immediately followed by a property access on that same
  value, since the second would NRE and you'd lose, not gain, information; loop iterations
  where each depends on the previous holding an invariant) lists; and a closing rule to wrap
  only the assertion block at the end of a test method, not the whole body, so setup/act
  stays outside the scope (exceptions there are genuinely diagnostic, not "additional
  failures").

**Fukuii verdict — Not portable (API specifics: NUnit, `TestBlockchain`, Autofac), Already
adopted (principles).** fukuii's own `test-infrastructure`-equivalent guidance
(`.agents/protocols/testing-protocol.md` plus the "Test guidelines" folded into
`AGENTS.md`'s build/test table) already carries the DRY-test-parameterization principle and
a per-phase test-cadence discipline; ScalaTest doesn't have an `Assert.Multiple` analogue
worth porting (ScalaTest's `matchers` already aggregate differently). No action needed
beyond noting the *shape* — "one file, one rule, covers both unit tests and benchmarks
uniformly" — is good organization fukuii's protocol docs already follow (one protocol per
concern).

### `robustness.md` (26 lines)

C#/.NET-specific silent-failure and resource-leak patterns, in four subsections:
- **Async** (lines 6–10): never `async void` (swallows exceptions) — use `async Task`; never
  `.Result`/`.Wait()` inside an `async` method (deadlock/thread-pool starvation) — always
  `await`; a missing `await` silently discards a result unless fire-and-forget is
  *documented* intent; I/O-performing async methods must accept a `CancellationToken`.
- **Resource management** (lines 12–16): `IDisposable`/`IAsyncDisposable` (especially `IDb`,
  streams, channels) must be `using`-wrapped; a detailed note on `ReadBytes(n)` allocating a
  ref-counted `IByteBuffer` — the allocating method owns the buffer until ownership
  transfers, at which point the receiver must `Release()`/`SafeRelease()` — stated as "the
  two most common leak/corruption sources in the networking layer"; never swallow exceptions
  in an empty `catch` — at minimum log.
- **Thread safety** (lines 18–20): shared mutable state (caches, peer tables, chain state)
  touched from multiple threads needs proper synchronization.
- **Safety** (lines 22–25): `unsafe` blocks require a comment justifying the safety
  invariant; untrusted input (P2P peers, RPC callers) must be validated before use — a crash
  from external input is a crash vector, not just a bug.

**Fukuii verdict — Not portable (mechanism: async/await, `IDisposable`, ref-counted
buffers are .NET-specific), Already adopted (equivalent risk categories, different
enforcement).** fukuii's Scala/Pekko equivalents of each risk already have coverage
elsewhere: "never fire-and-forget without documented intent" ≈ fukuii's `alert-wrapper-
protocol.md` (STOP-AND-ALERT vs. restart supervision) and `background-script-execution.md`;
resource leak patterns (uncancelled timers, missing `watchWith` cleanup, stream
materialization leaks) are the explicit charter of fukuii's ported `pekko-resource-audit`
skill (itself adapted from Nethermind's own `resource-leak-audit` skill — see the skills
section below); "validate untrusted P2P/RPC input" is standard practice already exercised
by the `herald` and `conduit` subagents. No new file needed; if a gap is ever found, it's
in `pekko-resource-audit`'s coverage, not a new protocol doc.

### `performance.md` (14 lines)

A flat bullet list of low-allocation/hot-path patterns actually used in the codebase: ref
structs for hot-path state (`EvmStack`, `EvmPooledMemory`); `Span<byte>`/`stackalloc` for
temp buffers; `ArrayPoolList<T>`/`ArrayPoolListRef<T>` for pooled memory; SIMD types
(`Vector256<byte>`, `Vector128<byte>`) for bulk memory ops;
`[MethodImpl(AggressiveInlining)]` on hot methods; `ZeroPaddedSpan` (readonly ref struct) for
zero-copy padded data; function pointers (`delegate*`) for opcode dispatch instead of
virtual calls; generic struct constraints (`where T : struct, IGasPolicy<T>`) for zero-cost
abstraction via per-type JIT specialization; `GC.AllocateUninitializedArray<byte>(length,
pinned: true)` for pinned arrays that avoid GC relocation; bool returns (not exceptions) for
error conditions in hot paths (e.g. out-of-gas).

**Fukuii verdict — Not portable.** Every item is a CLR/JIT-specific mechanism (ref structs,
`Span<T>`, `delegate*` function pointers, pinned GC arrays) with no Scala/JVM equivalent
worth forcing — the JVM's escape analysis, `@specialized`, and `Array`/`ByteBuffer` handling
solve the same problems differently. fukuii's `optimization-sprint` work (OPT-004 etc.,
tracked in memory) already independently targets JVM-appropriate hot-path patterns
(allocation reduction, specialization). Nothing to port; the *existence* of a short,
scannable "patterns actually used in this codebase" list (rather than generic performance
advice) is a good documentation habit fukuii's own performance-facing protocol docs already
follow.

### `package-management.md` (15 lines)

Documents Central Package Management (CPM): `Directory.Packages.props` sets
`ManagePackageVersionsCentrally=true`; `.csproj` files must use bare `<PackageReference
Include="Foo" />` with **no** `Version` attribute (adding one breaks the build); versions
live only in `Directory.Packages.props` as `<PackageVersion Include="Foo" Version="x.y.z"
/>`. A two-step "adding a new dependency" recipe follows this rule.

**Fukuii verdict — Not portable.** CPM is a .NET/NuGet-specific mechanism; fukuii's sbt/Ivy
dependency management (`build.sbt`, library dependency declarations) has no equivalent
split between reference and version, and fukuii's own supply-chain-security discipline
(global `~/.claude/CLAUDE.md` rules: exact pins for crypto/build packages, 7-day resolution-
age gate) already covers the underlying goal — centralized, auditable version pins — via a
different, JVM-appropriate mechanism. No action.

### `github-workflows.md` (32 lines)

Conventions for `.github/` automation:
- **Workflows** (lines 5–13): kebab-case naming; `concurrency: group:
  ${{ github.workflow }}-${{ github.ref }}` with `cancel-in-progress: true` for PR/push
  workflows; explicit triggers (no bare `on: push`); never log/echo secrets, scope env vars
  to the job that needs them; `nethermind-tests.yml`'s project list is the source of truth
  for test-matrix project names; reproducible-benchmark jobs use the `reproducible-
  benchmarks` runner label, others default to `ubuntu-latest`; config-rendering workflows
  must write to a temp path, never modify tracked source.
- **Actions** (lines 15–19): custom composite actions live under `.github/actions/<name>/`
  with `action.yaml` + scripts; scripts must be executable and Linux-safe unless noted;
  prefer stock `actions/*` actions, document any third-party action version/reason.
- **PR template** (lines 21–23): fill in Changes/type-of-change checkboxes/Testing/
  Documentation in `.github/pull_request_template.md` — checkboxes drive automatic labeling.
- **CODEOWNERS** (lines 25–27): keep paths/teams in sync with repo structure.
- **Notes for agents** (lines 29–32): don't change workflow logic (triggers/steps/matrices)
  without explicit user request; new workflows should follow existing concurrency/env/job-
  name conventions and cross-reference `AGENTS.md`'s benchmark-workflow section.

**Fukuii verdict — Already adopted.** fukuii already ported this file nearly verbatim as
`.agents/protocols/github-workflows.md` (per fukuii's own `CLAUDE.md` protocol table),
including the explicit "why CODEOWNERS is deliberately absent" note adapted for fukuii's
single-maintainer status. No further action — this is the one rule file in this directory
fukuii has already fully absorbed.

### `git.md` (11 lines)

Three rules: be wary of force-pushing, always confirm with the user first; when merging,
never silently drop features — if unsure which side of a conflict to keep, consult the user
interactively; new branches follow a `perf/`, `feature/`, `test/`, `fix/`, or `refactor/`
prefix convention.

**Fukuii verdict — Already adopted.** fukuii's `.agents/protocols/git-conventions.md` is
explicitly documented in fukuii's own `CLAUDE.md` as "ported from Nethermind's `git.md`" —
force-push confirmation and merge-conflict escalation are already carried over. fukuii's
branch-naming is governed separately by its own worktree-protocol (`wt/<id>`) rather than
the `perf/`/`feature/`/`test/`/`fix/`/`refactor/` prefixes, which is a deliberate deviation,
not a gap.

### `agent-skills.md` (21 lines) — symlink convention

**Already adopted — see fukuii's own `.agents/protocols/agent-skills.md`.** This file is
the direct model for fukuii's protocol of the same purpose (per the fukuii task context:
fukuii's version was modeled on this file this same session). For completeness, Nethermind's
exact rules, quoted verbatim, are:

> Canonical skill definitions live in `.agents/skills/`. Tool-specific directories
> (`.claude/skills/`, `.cursor/skills/`) contain **symlinks** to the canonical files — never
> independent copies.
>
> ## Rules
>
> - **Single source of truth**: Always create and edit skills in
>   `.agents/skills/<name>/SKILL.md`. Never place standalone skill files directly in
>   `.claude/skills/` or `.cursor/skills/`.
> - **Symlink per skill, not the directory**: Symlink individual skill subdirectories, not
>   the entire `skills/` folder — this avoids overriding tool-specific skills that other
>   contributors may have.
> - **Relative paths**: Symlinks must use the relative path `../../.agents/skills/<name>`
>   (relative to `.claude/skills/` or `.cursor/skills/`).
> - **Preserve on copy**: When copying `.agents/`, `.claude/`, or `.cursor/` to another
>   directory, use `cp -a` to preserve symlinks.
>
> ## Adding a new skill
>
> ```bash
> # From repo root
> mkdir -p .agents/skills/<name>
> # ... add SKILL.md there
> ln -s ../../.agents/skills/<name> .claude/skills/<name>
> ln -s ../../.agents/skills/<name> .cursor/skills/<name>
> ```

No further action needed — do not re-recommend porting this; fukuii already has it.

## `.agents/skills/` (existing coverage note)

Nethermind's canonical skill directory (`.agents/skills/`, symlinked into `.claude/skills/`
and mirrored in the (currently empty in this vendor snapshot) `.cursor/skills/`) currently
holds exactly four skills: `fix-nethtest`, `gas-benchmark`, `resource-leak-audit`, and
`review`. fukuii has already ported the general-purpose review lens (`review`) and the
resource-leak audit (`resource-leak-audit` → fukuii's `pekko-resource-audit`) as noted
above. The full catalog, per-skill SKILL.md structure, and trigger-phrase design are
intentionally **not** duplicated here — see (or write, if not yet present)
`docs/research/best-practices/evm-clients/repo-patterns/nethermind/dev-workflow-skills-
pattern.md` for the dedicated skill-catalog deep-dive. As of this writing that file does not
yet exist in this tree (`repo-patterns/nethermind/` is otherwise empty); this document
intentionally leaves that catalog to whichever pass authors it, to avoid two documents
drifting out of sync on the same four skills.

## CI-integrated AI review bot (`claude-review.yml`) — full mechanism

Path: `.github/workflows/claude-review.yml` (231 lines). This is the single most valuable
file in the vendored clone for fukuii's purposes — fukuii has no CI-integrated AI review
bot today, and this is a complete, working reference implementation using
`anthropics/claude-code-action@v1`.

### Two trigger modes, one workflow

The workflow's own header comment (lines 1–17) states the design plainly: there are two
independent paths through the same job, distinguished by a runtime classification step, not
by separate workflows.

1. **Review path** — auto-triggered on same-repository PR `opened`/`ready_for_review`
   (and on `unlabeled` specifically when the removed label was `wip`), or manually via a
   trusted maintainer commenting a variant of "@claude review". Runs Claude with a **fixed,
   hardcoded review prompt** (the user's own comment text is *not* used as the prompt in
   this path), captures a structured `mergeable` verdict via `--json-schema`, and posts a
   `claude-review/reviewed` commit status that branch protection can gate on.
2. **Mention path** — any other `@claude ...` comment/mention (e.g. `@claude explain X`,
   `@claude fix Y`). Runs Claude with the actual comment body as the effective prompt (no
   prompt override), does **not** post a commit status, and does not force structured
   output.

### Triggers (lines 21–32)

Five distinct GitHub event types wired into `on:`:
```yaml
on:
  issue_comment:
    types: [created]
  pull_request_review_comment:
    types: [created]
  issues:
    types: [opened]
  pull_request_review:
    types: [submitted]
  pull_request:
    types: [opened, ready_for_review, unlabeled]
```
`unlabeled` is deliberately included (line 30 comment) specifically so that removing the
`wip` label itself acts as a review trigger — the job's `if:` condition (below) narrows it
down to only the `wip`-label-removal case, not every `unlabeled` event.

### The job-level `if:` gate (lines 49–71) — the security-critical logic

This is a single large boolean expression, OR-ing together every legitimate way to enter
the job:

- **Same-repo PR, not WIP** (lines 50–62): `github.event.pull_request.head.repo.full_name
  == github.repository` (i.e. not a fork PR) AND either:
  - the event is `unlabeled` and the removed label was exactly `wip`, OR
  - the event is `opened`/`ready_for_review` AND the PR is not WIP by four independent
    signals: no `wip` label, title doesn't start with `WIP`, title doesn't contain
    `[WIP]`, title doesn't contain the lowercase `[wip]` variant.
- **`@claude` mention on an issue comment**, gated on
  `contains(fromJSON('["MEMBER","COLLABORATOR","OWNER"]'),
  github.event.comment.author_association)` (line 64).
- **`@claude` mention on a PR review comment**, same `author_association` allow-list
  (lines 65–66).
- **`@claude` mention inside a submitted PR review body**, same allow-list, checked against
  `github.event.review.author_association` (lines 67–68).
- **`@claude` mention in an issue body or title**, same allow-list, checked against
  `github.event.issue.author_association` (lines 69–71).

**The exact security rationale is spelled out in the comment block above the condition**
(lines 41–48): `issue_comment` and related events always run in the *base-repo* context
with full secret access, even for comments left on a fork's PR — so without the
`author_association` guard, an external contributor could get a Claude run (and burn the
project's Claude API budget) simply by commenting `@claude ...` on their own fork PR.
Auto-triggered reviews are separately restricted to same-repository PRs specifically because
fork PRs run with a read-only `GITHUB_TOKEN` and no secrets, meaning the Claude action
couldn't mint an OIDC token or post the `claude-review/reviewed` status even if it ran — so
auto-review on fork PRs is a structural no-op that's better prevented at the gate than
attempted and silently failing. A maintainer can still force a review on a fork PR by
commenting `@claude review` from an account with `MEMBER`/`COLLABORATOR`/`OWNER`
association, which runs in the base-repo context and does have secret access.

### Permissions (lines 75–81)

```yaml
permissions:
  contents: write
  pull-requests: write
  issues: write
  id-token: write   # Required by anthropics/claude-code-action for OIDC auth
  actions: read     # Required for Claude to read CI results on PRs
  statuses: write   # Required to post the claude-review/reviewed commit status
```
Each permission has an inline comment explaining exactly why it's needed — `id-token:
write` for the action's own OIDC handshake, `actions: read` so Claude can inspect other CI
job results while reviewing, `statuses: write` specifically for the commit-status step later
in the job.

### Step 1 — "Detect review intent" (lines 86–109)

A shell step that classifies the invocation into `is_review=true|false`, written to
`$GITHUB_OUTPUT`, gating every subsequent step's `if:`. Logic:
- Any `pull_request` event that reached this job (auto-triggers) is unconditionally a
  review (`is_review=true`) — the job-level `if:` already filtered out WIP and fork PRs.
- `issue_comment` / `pull_request_review_comment`: the job-level `if:` already guaranteed
  `@claude` is present; this step additionally checks (case-insensitively, via bash's
  `,,` lowercase expansion) whether the comment body also contains the substring
  `"review"`. If so → review path; otherwise → mention path (e.g. `@claude implement X`
  falls through to mention).
- `pull_request_review`: same substring check against the review body.

### Step 2 — Checkout (line 111–112)

Plain `actions/checkout@v6`, no special options.

### Step 3 — "Run Claude Code (review path)" (lines 114–151)

Gated on `steps.detect.outputs.is_review == 'true'`, `timeout-minutes: 20`, `id:
claude_review`, using `anthropics/claude-code-action@v1`. Key `with:` fields:
- `claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}` — the action
  authenticates via a stored OAuth token secret, not a raw API key.
- `track_progress: ${{ github.event.action != 'unlabeled' }}` — renders the action's
  v0.x-style live tracking comment with checkboxes, except on `unlabeled` events, which the
  comment notes aren't supported for that trigger.
- `prompt:` — a **fixed** multi-line prompt (this is what makes the review path
  deterministic regardless of what a human typed in a triggering comment): it interpolates
  `REPO` and `PR NUMBER` (falling back from `pull_request.number` to `issue.number` so it
  works whether the trigger was the `pull_request` event or an `issue_comment`), instructs
  Claude to review for correctness/edge-cases/regressions, security implications,
  performance ("this is a hot-path Ethereum execution client"), and adherence to
  `CONTRIBUTING.md` + `.agents/rules/`. It defines a four-tier severity taxonomy (Critical /
  High / Medium / Low) with an explicit meaning for each tier, instructs Claude to post a
  top-level summary via `gh pr comment` and use
  `mcp__github_inline_comment__create_inline_comment` for line-specific issues, and states
  the exact mergeability rule: `mergeable: false` in the structured output if any Critical/
  High/Medium finding exists that has **not** been explicitly acknowledged with rationale in
  a prior PR comment; otherwise `mergeable: true`. This "acknowledged with rationale beats
  an open finding" rule is notable — it lets a maintainer explicitly override a finding by
  commenting a justification, rather than requiring the finding to be literally fixed.
- `claude_args:` — three flags:
  - `--model opus` — the review path always uses Opus, not a cheaper model, regardless of
    what account/plan default is configured.
  - `--allowedTools "mcp__github_inline_comment__create_inline_comment,Bash(gh:*),WebFetch"`
    — a narrow, explicit tool allow-list: inline PR comments via an MCP server tool, `gh`
    CLI (scoped to any `gh` subcommand), and `WebFetch` (presumably for checking EIP specs
    or upstream references) — no generic `Bash(*)`, no `Edit`/`Write` (the review path is
    read-only against the codebase; it only writes comments/statuses via `gh`/MCP).
  - `--json-schema '{"type":"object","properties":{"mergeable":{"type":"boolean",...},
    "critical_count":{"type":"integer"},"high_count":{"type":"integer"},
    "medium_count":{"type":"integer"},"summary":{"type":"string"}},
    "required":["mergeable","critical_count","high_count","medium_count","summary"]}'` —
    this is the mechanism that forces Claude's **final result message** into structured
    JSON. The workflow comment (lines 118–120) is explicit that this JSON is *not* shown to
    humans directly — the human-facing review is the rich prose Claude posts via `gh pr
    comment` / the inline-comment MCP tool during the run; the JSON schema output is
    strictly the machine-readable verdict consumed by the next step.

### Step 4 — "Run Claude Code (mention path)" (lines 153–166)

Gated on `steps.detect.outputs.is_review != 'true'`, same 20-minute timeout, `id:
claude_mention`. No `prompt:` field is supplied at all — per the comment (lines 153–156),
omitting `prompt:` makes the action fall back to parsing the triggering comment body itself
as the instruction, preserving the classic "@claude do this thing" behavior (implementation
help, explanations, etc.) rather than the fixed review prompt. `claude_args` here is looser:
`--model opus` and `--allowedTools "Bash(gh:*),WebFetch"` — still Opus, still no generic
Bash, but no inline-comment MCP tool (this path isn't posting a structured code review) and
notably no `Edit`/`Write` either, so even the "implement X" mention path cannot push commits
directly — it can only read code and post comments/use `gh`.

### Step 5 — "Post claude-review status (success)" (lines 168–213)

Gated on `success() && steps.detect.outputs.is_review == 'true'` — i.e. only runs after the
review-path step completed without error. Mechanism:
1. Resolves the PR head SHA via `gh pr view "$PR_NUMBER" --repo "$REPO" --json headRefOid
   --jq .headRefOid` — computed fresh rather than trusted from the event payload, and works
   for both `pull_request` events (`PR_NUMBER` from `event.pull_request.number`) and comment
   events (`event.issue.number`).
2. Parses the prior step's `structured_output` (the JSON forced by `--json-schema`) via
   `jq`, with `// default` fallbacks on every field (`.mergeable // false`, counts `// 0`,
   `.summary // ""`) — the comment explicitly notes this defaults to the *most conservative*
   interpretation (not mergeable, zero counts) if the structured output is missing or
   malformed, rather than silently defaulting to "safe to merge."
3. Builds a human-readable status description: `"Claude reviewed: no blockers"` on
   `mergeable == true`, or `"Claude blockers: N critical, N high, N medium"` on false, then
   appends `" — <summary>"` if a summary string exists, truncated to GitHub's 140-character
   commit-status description limit (`"${DESC:0:140}"`) so the essence of the finding is
   visible directly in the PR checks list without opening the run.
4. Posts via `gh api "repos/$REPO/statuses/$SHA" -f state="$STATE" -f
   context=claude-review/reviewed -f description="$DESC" -f target_url="$RUN_URL"`, where
   `$STATE` is `success` or `failure` matching the `mergeable` boolean.

**What this status gates**: the comment on lines 172–174 states statuses are keyed by
`(sha, context)`, so a later re-review *overwrites* the previous verdict on the same commit;
but pushing new commits changes the SHA, leaving the new commit with **no** status at all —
which, under standard GitHub branch protection configured to require the
`claude-review/reviewed` context, blocks merge until `@claude review` is re-run manually on
the new SHA. This document does not find (and the workflow itself does not configure)
the branch-protection rule that consumes this status — that configuration lives in the
GitHub repository settings, not in-repo, and was out of scope for what's inspectable in this
vendored clone.

### Step 6 — "Post claude-review status (error)" (lines 215–231)

Gated on `failure() && steps.detect.outputs.is_review == 'true'` — the counterpart to step
5, firing when the review-path Claude action itself errored (API timeout, rate limit,
schema violation, etc.), not when Claude reviewed successfully and found blockers. Posts
`state=error` (distinct from `state=failure`) with a fixed description directing
maintainers to re-run via `@claude review`. The comment (lines 214–217) explains the
distinction is deliberate: `error` lets a reviewer distinguish "the workflow itself broke"
from "Claude looked at this and flagged real problems" — both block merge under branch
protection, but they mean different remediation actions.

### Summary of the mechanism as a whole

| Aspect | Review path | Mention path |
|---|---|---|
| Trigger | Same-repo PR open/ready-for-review/unlabel-wip, or `@claude review` from MEMBER/COLLABORATOR/OWNER | Any other `@claude ...` from MEMBER/COLLABORATOR/OWNER |
| Prompt | Fixed, hardcoded (ignores comment text) | None supplied — action parses the comment body itself |
| Model | `opus` | `opus` |
| Tools | `mcp__github_inline_comment__create_inline_comment`, `Bash(gh:*)`, `WebFetch` | `Bash(gh:*)`, `WebFetch` |
| Structured output | Forced via `--json-schema` (mergeable/counts/summary) | None |
| Commit status | Posts `claude-review/reviewed` (success/failure/error) | None |
| Can edit code | No (`Edit`/`Write` not in allow-list either path) | No |
| Fork PR behavior | Auto-review skipped entirely (gate); manual `@claude review` still works from a trusted maintainer, running in base-repo context | Same trust gate applies |

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable / Already adopted | Reasoning |
|---|---|---|
| `AGENTS.md` load-triggered rules table (lines 30–46) | Already adopted | fukuii's own `AGENTS.md`/`CLAUDE.md` split plus `.agents/protocols/` table follows the identical "one-line trigger per file" pattern. |
| `.claude/CLAUDE.md` = bare `@../AGENTS.md` import | Not portable (as-is) | fukuii deliberately layers Claude-Code-only orchestration on top of the import; Nethermind's zero-orchestration approach reflects it not (yet) running named subagents — not a gap to close. |
| `.agents/rules/coding-style.md` | Not portable | C#-syntax specific; Scala 3 equivalent already covered by Scalafmt/Scalafix + `scala3-style.md`. |
| `.agents/rules/di-patterns.md` | Needs design | Autofac API doesn't transfer, but the "one table: module → registers → when to touch" documentation shape is worth adapting for fukuii's manual Pekko/constructor wiring if such a reference doc is ever written. |
| `.agents/rules/test-infrastructure.md` | Already adopted (principles) / Not portable (API) | DRY-test and cadence principles already in `testing-protocol.md`; NUnit/`TestBlockchain`/`Assert.Multiple` specifics don't apply to ScalaTest. |
| `.agents/rules/robustness.md` | Already adopted (equivalent risk categories) | Covered by `pekko-resource-audit`, `alert-wrapper-protocol.md`, `background-script-execution.md`, and the `herald`/`conduit` untrusted-input discipline — different mechanism, same risk coverage. |
| `.agents/rules/performance.md` | Not portable | CLR/JIT-specific (ref structs, `Span<T>`, `delegate*`); fukuii's optimization-sprint work targets JVM-appropriate equivalents independently. |
| `.agents/rules/package-management.md` | Not portable | NuGet CPM has no sbt/Ivy equivalent; fukuii's supply-chain-security pinning rules already solve the underlying goal differently. |
| `.agents/rules/github-workflows.md` | Already adopted | Ported near-verbatim as fukuii's `.agents/protocols/github-workflows.md`, per fukuii's own `CLAUDE.md`. |
| `.agents/rules/git.md` | Already adopted | Ported as fukuii's `.agents/protocols/git-conventions.md`, per fukuii's own `CLAUDE.md`. |
| `.agents/rules/agent-skills.md` (symlink convention) | Already adopted | fukuii's `.agents/protocols/agent-skills.md` is modeled directly on this file. |
| `.agents/skills/` catalog (4 skills: `fix-nethtest`, `gas-benchmark`, `resource-leak-audit`, `review`) | Already adopted (2 of 4) | `review` and `resource-leak-audit` ported into fukuii (`pekko-resource-audit`). `fix-nethtest` and `gas-benchmark` are Nethermind-EF-test/benchmark-specific and have no direct fukuii analog yet; see the dedicated skills-catalog doc (to be written) for a full per-skill assessment. |
| **`claude-review.yml` — CI review bot with structured verdict + branch-protection status** | **Port now (as a design reference — requires fukuii-specific adaptation of the author-association/labels/model choice, but the mechanism is directly buildable)** | **This is the single biggest gap fukuii has: no CI-integrated AI review today. This workflow is a complete, battle-tested reference for exactly that — dual trigger modes, fork-PR security gate via `author_association`, `--json-schema`-forced structured verdict, and a commit-status gate branch protection can consume.** |
| `gas-benchmark-analysis.yml` — comment-triggered benchmark pipeline via Claude Code Action | Needs design | Same `anthropics/claude-code-action@v1` mechanism as `claude-review.yml` but wraps a skill invocation (`/gas-benchmark`) rather than a review; relevant if fukuii ever wants a comment-triggered CI benchmark pipeline, but lower priority than the review bot and requires fukuii to have an equivalent benchmark skill/pipeline first. |
| MCP server config (`mcp.json`/`.mcp.json`) | Not present | Confirmed absent anywhere in the vendored clone via `find`/`grep` — Nethermind's `claude-review.yml` references an MCP tool (`mcp__github_inline_comment__create_inline_comment`) that is provided by the `claude-code-action`'s own built-in GitHub MCP server, not a repo-local MCP config file. |
