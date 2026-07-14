# Warning Suppression — When and How to Legitimately Silence a Warning

**Domain:** Scala 3 language rules (compiler `@nowarn`) plus the two enforcement tools this
domain already claims as authorities (`scalafix` `@SuppressWarnings`/comment suppression,
`scapegoat` `@SuppressWarnings` inspection suppression) — see `../README.md`'s Authority map,
`scala3/ (enforcement)` row. **Owning specialist:** `mithril` (idiom sweep / suppression
sites), `wraith` (compile-error triage — references this doc, does not restate it). A
suppression on a consensus-critical path (`vm/`, `mpt/`, `crypto/`, `domain/`, `ledger/`)
additionally requires `forge` (PoW) or `beacon` (PoS) co-sign per
`consensus-change-protocol.md` — see "Consensus-tier amendment" below.

**Authority:**
`.claude/repo-references/scala3/library/src/scala/annotation/nowarn.scala` (the annotation's
own scaladoc — filter syntax and the `-Wunused:nowarn` staleness self-check),
`.claude/repo-references/scala3/compiler/src/dotty/tools/dotc/reporting/WConf.scala:63-111`
(the `cat=`/`msg=`/`id=`/`src=`/`origin=` filter grammar `build.sbt` and every `@nowarn` site
below actually use),
`.claude/repo-references/scalafix/docs/users/suppression.md` (the two scalafix suppression
mechanisms, their scoping trade-offs, and scalafix's own "document reason" and "unused
suppression" conventions),
`.claude/repo-references/scapegoat/README.md:350-374` ("Suppressing Warnings by Method or
Class"),
and, per the coordinator's direction to ground this in mature-client precedent rather than
Scala-ecosystem sources alone: `.claude/repo-references/clients/nethermind/src/Nethermind/Nethermind.Evm/Precompiles/PrecompileHelper.cs:15-17`
(C# `[UnconditionalSuppressMessage(..., Justification = "...")]` — the strongest cross-
language precedent, see below),
`.claude/repo-references/clients/besu/app/src/main/java/org/hyperledger/besu/cli/BesuCommand.java`
(Java `@SuppressWarnings` inline-comment convention, mixed discipline),
`.claude/repo-references/clients/{core-geth,erigon,go-ethereum}` (`//nolint:<linter>` —
cited as a **negative/mixed** precedent, see below),
plus fukuii's own scattered pieces this standard consolidates:
`.agents/protocols/process/warning-ratchet.md`, `.agents/protocols/code-style/comments.md`,
the `no-suppress-to-defer` memory policy, `.claude/sprints/queue/nowarn-candidates.md`, and
`.claude/sprints/queue/conformance-sweeps.md` (Sweep 3).

> **VALIDATE gate:** every citation above was checked against the cited file in-repo this
> session (2026-07-08) — not written from memory. See `../README.md`'s Governance section for
> what that check requires and why it exists.

## What this standard covers

fukuii's toolchain exposes four distinct suppression surfaces. This standard governs the
first three; the fourth is banned outright and exists here only to name it as banned:

1. **`@nowarn("cat=..."/"msg=...")`** — the Scala 3 compiler's own suppression annotation
   (`scala.annotation.nowarn`), scoped to the annotated definition, or, via the
   `expr: @nowarn(...)` ascription form, to a single expression.
2. **`@SuppressWarnings(Array("<InspectionName>"))`** — scapegoat's inspection-suppression
   convention, reusing the standard Java `@SuppressWarnings` annotation
   (`scapegoat/README.md:352`: "You can suppress a specific warning by method or by class
   using the java.lang.SuppressWarnings annotation").
3. **`@SuppressWarnings(Array("scalafix:<Rule>.<check>"))`** and
   **`// scalafix:ok <Rule>.<check>; <reason>`** — scalafix's two suppression mechanisms: the
   same Java annotation form (prefixed `scalafix:` so scalafix can detect it,
   `suppression.md:23-25`), and a comment form for locations an annotation cannot reach
   (`suppression.md:16-19`).
4. **Banned — blanket `-Wconf:...:s` (silent) at category or file scope.** Already stated in
   `warning-ratchet.md` Step 3 ("No blanket `-Wconf` silence, ever") and the
   `no-suppress-to-defer` policy; restated here because it is the one form this standard does
   **not** provide a legitimate-use path for. `build.sbt`'s only `-Wconf` verb in active use
   today is `:error` (ratchet promotion — `-Wconf:id=E198:error`,
   `-Wconf:cat=unchecked:error`) and one narrow, non-silencing `:s` exception scoped to a
   single named feature-warning category caused by intentional Pekko/library subclassing
   (`-Wconf:cat=feature:s`, `build.sbt:68`) — not a precedent for silencing a warning
   *category* as a stand-in for fixing or narrowly suppressing individual sites.

## The governing policy this standard operationalizes

Nothing here is new policy. This is the consolidated, cited form of decisions already made:

- **`no-suppress-to-defer`** (operator policy, 2026-07-08): suppression is only legitimate for
  code operating exactly as intended where the warning is genuinely unfixable, every
  suppression carries a per-site rationale, and suppression review happens only as a
  deliberate, reviewed pass after the relevant baseline (migration/cleanup) is established —
  never ad hoc mid-sweep to bury backlog.
- **`warning-ratchet.md`** Step 3: "Anything not fixed gets a narrow site-level suppression
  only... `@nowarn` at the exact site — not file-level, not build-level. One-line reason
  required; backlog reference required."
- **`comments.md`** sanctioned exception #2: `@nowarn("cat=...") // <reason> — see
  .claude/sprints/QUEUE.md §<ref>` is licensed as a "why" citation, not narration — the
  rationale line this standard requires is not a new carve-out against the default-no-comment
  policy, it is already-licensed content under `comments.md` category 1 ("workaround for a bug
  in a dependency") or category 2 ("non-obvious invariant the types don't enforce").
- **`nowarn-candidates.md`**'s admission rule, sourced from `batch-research-protocol.md` Rule
  (i): "No agent may disposition a finding as can't-fix / unfixable / library-inherent /
  genuine-boundary / `@nowarn`-candidate / suppress without first verifying no current or
  typed alternative exists — check the dependency set (`project/Dependencies.scala`) AND the
  authority repo under `.claude/repo-references/`, and cite that evidence in the finding
  itself." The incident that produced this rule: `scout`'s B1 scoping pass dispositioned ~165
  Classic Pekko TestKit sites as "unfixable — library API, not our code" without checking for
  a typed alternative; 115 sibling test files already used the Typed TestKit, already a
  project dependency (`pekko-actor-testkit-typed`) — the correct disposition was migration
  debt, not unfixable. This is the load-bearing gate a suppression must pass *before* any of
  the "correct form" rules below apply.

## Cross-repo precedent: what mature codebases actually do

The coordinator asked this standard be grounded in established practice, not invented. Five
authorities were checked; four converge on the same discipline, one is a useful negative
example.

| Authority | Mechanism | Scoping | Rationale discipline |
|---|---|---|---|
| **scala3** (`nowarn.scala:17-46`) | `@nowarn(filter)` | Site-level by construction; filter narrows further within the site (`cat=`, `msg=<regex>`, `id=`, `src=`, `origin=`) | No mandatory field, but the scaladoc's own examples model narrow filters over the bare, suppress-everything `@nowarn` (`@nowarn("msg=pure expression does nothing")` vs. bare `@nowarn`). Self-auditing: `-Wunused:nowarn` (folded into `-Wunused:all`, already on — `build.sbt:66`) flags a stale `@nowarn` as a warning automatically. |
| **scalafix** (`suppression.md:131-141`) | `@SuppressWarnings("scalafix:Rule")` / `// scalafix:ok Rule; reason` | Prefers `scalafix:ok` (single expression) over `scalafix:off`/`on` (arbitrary range) — "This is the last resort" (`suppression.md:99`) | Explicitly documents an optional `; <reason>` trailing clause ("Document reason for suppression," `suppression.md:131-141`) and auto-flags stale suppressions ("Unused suppression warnings," `suppression.md:143-154`) — the same self-auditing property as `-Wunused:nowarn`. |
| **scapegoat** (`README.md:350-374`) | `@SuppressWarnings("InspectionName")` or `"all"` | Named-inspection form is the modeled default; `"all"` is shown only on illustrative test-scaffolding code, not as the general recommendation | No mandatory reason field; discipline is scope-only. |
| **nethermind** (C#, mature reference client) | `[SuppressMessage(...)]` / `[UnconditionalSuppressMessage(...)]` | Attribute-level (narrowest — a single method in the strongest example) | **`Justification` is a first-class attribute parameter, not a comment convention** — `PrecompileHelper.cs:15-17`: `[UnconditionalSuppressMessage("Trimming", "IL2070", Justification = "The precompile types are statically known, and their Name properties are preserved.")]` on a single method. This is the clearest precedent of the five: the rationale is structurally mandatory-shaped and names the forcing cause (reflection-based trimming analysis cannot see what a closed, statically-known type set proves), not "known issue" or "TODO." Contrast: of 57 `SuppressMessage` sites in the vendored nethermind tree, most (ReSharper-only annotations) carry no `Justification` — the discipline is real but not universally applied even here. |
| **besu** (Java, reference client) | `@SuppressWarnings("checker")` | Method/class-level, one or several named checkers per site | Mixed: some sites carry a same-line rationale comment (`BesuCommand.java:583`: `@SuppressWarnings({"FieldCanBeFinal", "FieldMayBeFinal"}) // PicoCLI requires non-final Strings.`), most (the `FieldCanBeFinal`/`PrivateStaticFinalLoggers`/`UnstableApiUsage` sites throughout `BesuCommand.java`, `EthstatsOptions.java`, etc.) carry none. |
| **go clients** (core-geth, erigon, go-ethereum) — **negative precedent** | `//nolint:<linter>` | Line/block-level | **Inconsistent, cited here as the discipline *not* to import.** Most sites are bare (`crypto/blake2b/blake2b.go:305`: `//nolint:unused,deadcode`, no reason at all). The minority that do carry a reason cite a forcing external cause tersely and well when they bother — erigon `cl/beacon/handler/block_production.go:1482`: `//nolint:staticcheck until https://github.com/erigontech/erigon/issues/17943`, pointing at a tracked upstream issue. No `.golangci.yml` in either vendored repo enables `nolintlint` (the linter that would *require* a reason), so the good sites are voluntary discipline, not enforced. |

**The common thread across the disciplined examples** (nethermind's `Justification`,
scalafix's own doc guidance, scapegoat's per-inspection scoping, besu's better sites, and the
minority of well-formed `nolint` sites): narrowest possible scope, a rationale present *at the
suppression site itself* (not only in an external tracker), the rationale names the **forcing
external cause** (an upstream API/library shape, a reflection/erasure limit, a protocol
boundary) rather than a process statement ("known issue," "will fix later," "noisy"), and —
where the tool supports it — an automatic staleness check so a suppression that becomes
fixable eventually gets flagged rather than living forever. This standard adopts all four
properties as fukuii's rule, extending them uniformly across all three of fukuii's
suppression surfaces (§1 above) rather than leaving `@SuppressWarnings` sites to the weaker,
comment-optional scapegoat/besu norm.

## The standard, stated

### 1. When a suppression is legitimate — the verified-no-alternative gate

A suppression is legitimate only when **all** of the following hold:

**(a) The warning is correct and the code is operating as intended.** No restructuring of the
code itself removes the underlying cause — this is not a case of "the fix is inconvenient
right now."

**(b) No current, typed, or already-vendored alternative exists — Rule (i), applied without
exception.** Check `project/Dependencies.scala` for a fixed/typed replacement already
available, and check the authority repo under `.claude/repo-references/` for the upstream
API's actual current shape, and **cite that evidence at the site or in the suppression's
inventory entry** (`nowarn-candidates.md` / the post-admission sweep doc). Absent that check,
the only valid disposition is "separate item, needs its own scoping" — never "unfixable." This
is not advisory; it is the rule the TestKit incident (cited above) exists to prevent
recurring.

**(c) The category's fix hierarchy has been exhausted first, where one exists.** Some warning
categories have an established, preferred-order set of real fixes before suppression is even
considered — `unchecked-annotations.md`'s three-tier hierarchy (sealed ADT + exhaustive match
→ loud fall-through → `@unchecked` only if both are genuinely impractical) is the sibling
standard's worked instance of this same principle, and this standard's suppressions are
governed by the same ordering logic: suppress only after the real fix has been ruled out, not
instead of attempting it.

**(d) Never to defer work.** A suppression pass happens only as a deliberate, reviewed pass
after the relevant cleanup/migration baseline is established for that population of
warnings — never ad hoc, mid-sweep, to make a warning count look smaller. `nowarn-candidates.md`
is the parking lot for candidates awaiting that deliberate pass; a candidate belongs there with
its rationale analysis, **not yet suppressed**, until the pass actually runs.

### 2. The correct form — narrowest scope, mandatory rationale, inventoried

**Scope — narrowest available, always:**
- `@nowarn`: prefer a filtered form (`cat=deprecation`, `msg=<regex>`) over the bare
  `@nowarn` (which suppresses every warning at that site) — `nowarn.scala`'s own examples model
  this (`@nowarn("msg=pure expression does nothing") def f = { 1; deprecated() }` vs. bare
  `@nowarn`).
- `@SuppressWarnings`: name the exact inspection or `scalafix:Rule.check` — never
  `Array("all")` outside genuinely blanket test-scaffolding cases (`scapegoat/README.md`'s
  own `"all"` example is illustrative, not a recommended default).
- `// scalafix:ok`: prefer this single-expression form over `scalafix:off`/`on`, which
  scalafix's own docs call "the last resort" (`suppression.md:99`) — reserved for cases where
  neither an annotation nor `scalafix:ok` can target the location.
- Site-level only. Never file-level, never build-level (`-Wconf`) — see the banned form above.

**Mandatory rationale, at the site, naming the forcing cause:**

One line. States the forcing upstream constraint — a library/API shape, a reflection/erasure
limit, a protocol boundary — not "known issue," "TODO," or "will fix later." This is already
licensed content under `comments.md` category 1 (workaround for a bug/limitation in a
dependency) or category 2 (a non-obvious invariant the types don't enforce); a suppression
rationale is not a new exception to the default-no-comment policy, it is an instance of an
existing one. Extending `comments.md`'s already-mandated `@nowarn` reason-line format
(sanctioned exception #2) uniformly to `@SuppressWarnings` sites, per this cross-repo
precedent:

```scala
@nowarn("cat=deprecation") // <forcing cause> — see nowarn-candidates.md
def legacyBridge(...): Unit = ...
```

```scala
@SuppressWarnings(Array("rawtypes")) // jupnp 3.0.4 ActionInvocation ships raw generics — no
                                      // typed alternative in the vendored version
def invokeAction(...): Unit = ...
```

```scala
foo(null) // scalafix:ok DisableSyntax.null; constructing an invalid input deliberately to test null-safety handling
```

**Inventoried:** every suppression is recorded in `nowarn-candidates.md` (pre-admission
parking lot with its rationale analysis) or the post-admission sweep doc it graduates into
(`conformance-sweeps.md`-style). A suppression present in code but absent from that inventory
is itself a standard violation — mirrors `finding-resolution.md`'s "every finding gets
scheduled" rule, applied specifically to suppressions.

### 3. Consensus-tier amendment

A suppression on a consensus-critical path (`vm/`, `mpt/`, `crypto/`, `domain/`, `ledger/`)
requires `forge` (PoW) or `beacon` (PoS) co-sign before landing, per
`consensus-change-protocol.md`'s litmus (does the change affect the state root). Two
amendments beyond §2's general rule, mirroring `unchecked-annotations.md`'s Amendment 1:

- **Cite the guard, not prose.** Where the suppression rests on a specific invariant proven
  elsewhere in the code (a sealed hierarchy, a prior validation), name the exact
  method/line/type the reader can go re-verify — a citable fact goes visibly stale if the
  guard is refactored away; unverifiable prose does not.
- **A suppression must never silence a genuine fail-loud gap.** Before suppressing, ask: would
  fixing this the hard way change what the code does on an invalid or edge-case input? If yes,
  this is not a suppression candidate — it is a bug, and per
  [`fail-loud-invariants.md`](../../../research/best-practices/evm-clients/fail-loud-invariants.md)
  (Finding 4), the correct fix is a loud failure at the site the invariant is assumed, not a
  silenced warning. `nowarn-candidates.md`'s own "NOT candidates" section carries the
  canonical cautionary contrast: `BlockFetcher.scala:93`'s E029 warning on an unhandled
  `PeerDisconnected` case in a `messageAdapter[PeerEvent]` match is a genuine missing case,
  not a suppression candidate — "Investigate whether the case needs handling — do NOT
  `@nowarn`."

### 4. The ratchet relationship

Suppression is Step 3 of the four-step ratchet (`warning-ratchet.md`): Steps 1–2 inventory and
fix everything fixable first; Step 3 suppresses only the irreducible floor that remains, each
site carrying the rationale and inventory entry from §2 above; Step 4 promotes the cleared
category to a build error (`-Wconf:cat=<CATEGORY>:e`), with the Step-3 suppressions carrying
the narrow exemptions that keep the build green. A suppression written outside this sequence
— before the fixable population in its category has actually been fixed — is exactly the
defer-to-bury-backlog pattern `no-suppress-to-defer` bans.

**The self-auditing mechanism is already on for `@nowarn`.** `build.sbt:66` sets
`-Wunused:all`, which folds in `-Wunused:nowarn` (`ScalaSettings.scala:195`,
`allOr("nowarn")`) — a stale `@nowarn` that no longer suppresses anything becomes a compiler
warning automatically, the same property scalafix documents for its own suppression forms
(`suppression.md:143-154`, "Unused suppression warnings"). This means every `@nowarn` site in
fukuii is already self-checking for staleness; **`@SuppressWarnings` sites have no equivalent
automatic check** (neither scapegoat's README nor scalafix's `suppression.md` documents one
for the Java-annotation form beyond the `scalafix:`-prefixed subset), so those need periodic
manual review at the next suppression-focused sweep rather than relying on the compiler to
flag drift.

## Worked examples

### Already in the codebase, worth learning from

**`vm/PrecompiledContracts.scala:260`** (consensus-critical — `vm/`) — `return
ProgramResult(...) // scalafix:ok DisableSyntax.return`, immediately preceded by a rationale
comment: `// DEFER: nested guard inside the EIP-7823 (Unit-typed) validation block; the
method value (ProgramResult / MODEXP output) is produced below. An expression rewrite would
require restructuring the validation into a separate boolean and is byte-level risky for a
precompile result that feeds state. Keep the short-circuit.` This is a real, narrowly-scoped,
rationale-bearing comment suppression on a consensus-critical file — it names the forcing
cause (byte-level risk to a state-affecting precompile result from a restructuring) rather
than "return is convenient here." Two open items this standard surfaces rather than silently
fixes: (1) it is on a consensus path, so §3's forge co-sign requirement applies going forward
for any *new* site of this shape — whether this specific site has that co-sign on record is
outside this doc's scope to assert; (2) the `DEFER:` comment prefix is not one of
`comments.md`'s three enumerated sanctioned exception prefixes (`MIGRATION:`, the `@nowarn`
reason-line, the `implicit val` holdout note) — flagged here as a reconciliation point for
`comments.md`, not silently corrected by this authoring pass.

**`test/.../JsonRpcHttpServerSpec.scala:50`** (test-tier, no consensus gate) —
`@SuppressWarnings(Array("scalafix:DisableSyntax.null"))` on `override val config:
JsonRpcConfig = null`, with a class-level scaladoc four lines above explaining the rationale
("The null value here is never actually accessed in practice" — intercepted by the mock
framework). Correctly scoped (single `val`), rationale genuinely present, but not adjacent to
the annotation itself — new sites should put the one-line rationale directly at the
annotation per §2, not several lines away in a class doc.

### The 3 genuine-boundary sites (`conformance-sweeps.md` Sweep 3, GENUINE-BOUNDARY table)

All three currently lack the site-level rationale comment this standard requires — applying
§2 to each:

- **`network/ExternalIPDetector.scala:82`** — `@SuppressWarnings(Array("rawtypes"))`. Forcing
  cause: jupnp 3.0.4 ships a raw (non-generic) `ActionInvocation` type with no typed
  alternative in the vendored version — verified per Rule (i) against `project/Dependencies.scala`'s
  pinned jupnp version. Correct form once landed:
  `@SuppressWarnings(Array("rawtypes")) // jupnp 3.0.4 ActionInvocation ships raw generics — no typed alternative`.
- **`src/it/.../CommonFakePeer.scala:154`** — `@annotation.nowarn("msg=Matchable")`. Forcing
  cause: bridges Classic `Actor.receive`, whose type is `PartialFunction[Any, Unit]` — an
  inherent Classic-API shape with no Typed equivalent at this bridge point (test-tier, no
  consensus gate).
- **`blockchain/sync/SyncController.scala:2474`** — `@annotation.nowarn("msg=unused explicit parameter")
  latencyMs: Long`. Forcing cause: reserved-for-future parameter, already self-documented by
  name — but per `conformance-sweeps.md`, this site needs a fresh re-confirm that the parameter
  is genuinely still reserved (not simply forgotten) before the rationale comment can be
  written truthfully.

### The Pekko `Tcp` boundary — the clearest "irreducible floor" case

fukuii's E165 (`Matchable`) sites bridging Pekko `Tcp` in `network/ServerActor.scala` and
`network/rlpx/RLPxConnectionHandler.scala` are the canonical genuine-floor example this
standard's §1(b) check actually resolves to "no alternative exists," not just "looks
unfixable." `org.apache.pekko.io.Tcp`
(`.claude/repo-references/pekko/actor/src/main/scala/org/apache/pekko/io/Tcp.scala:50`) is an
`ExtensionId`-based, actor-message IO API rooted in the `actor` (Classic) package, addressed
by sending `Tcp.Connect`/`Tcp.Bind` messages and matching `Tcp.Event`/`Tcp.Command` subtypes —
there is no `Behavior`-typed counterpart to this specific abstraction. Pekko's typed-context
alternative for TCP is a structurally different abstraction — the Streams-based `Tcp` object
(`.claude/repo-references/pekko/stream/src/main/scala/org/apache/pekko/stream/scaladsl/Tcp.scala`,
`Source`/`Sink`/`Flow`-shaped) — not a drop-in typed replacement for the actor-message API
these two files bridge into; adopting it would be a genuine rewrite of the bridge, not a
suppression decision. Once a site is confirmed to still be a live Classic-Tcp bridge (not dead
code — verify per `dead-code-review.md` first), the correct suppression is:

```scala
@nowarn("msg=Matchable") // Pekko Tcp (org.apache.pekko.io.Tcp) is inherently Classic-only —
                          // no Behavior-typed equivalent for this actor-message IO API
```

Worth noting: Pekko's own source suppresses warnings too (`io/Tcp.scala:132,160`,
`@nowarn("msg=deprecated")`) — those two sites silence Pekko's own internal deprecated-overload
delegation, unrelated to the Classic/Typed boundary, so they are not themselves evidence for
the E165 case above; they are only further evidence that even the upstream library follows the
same narrow, filtered, targeted-suppression discipline this standard adopts, never a blanket
one.

## Conformance checks

Advisory inventory checks per the enforcement ladder in `../README.md` — they surface sites
for review, they do not fail a build on their own.

```bash
# All @nowarn sites, bare and fully-qualified (fukuii uses @annotation.nowarn at every
# current site — a bare `@nowarn` grep alone misses all of them)
grep -rn 'nowarn' src/main/scala src/test/scala src/it --include='*.scala'

# @SuppressWarnings sites — both scapegoat inspection names and scalafix:-prefixed
grep -rn '@SuppressWarnings' src/main/scala src/test/scala src/it --include='*.scala'

# scalafix comment-suppression sites
grep -rEn 'scalafix:ok|scalafix:off|scalafix:on' src/main/scala src/test/scala src/it --include='*.scala'
```

Every hit needs: (a) a narrow-scope suppression per §2, not a blanket one; (b) an adjacent
one-line rationale naming the forcing cause per §2; (c) an inventory entry in
`nowarn-candidates.md` or its post-admission sweep doc; (d) on a consensus-critical path, a
recorded `forge`/`beacon` co-sign per §3. A hit satisfying none of these is not yet a
suppression this standard admits — it is either a fixable warning masquerading as one (apply
Rule (i) before concluding otherwise) or a suppression written before this standard existed
and due for the rationale/inventory retrofit described above.

## Cross-references

- `.agents/protocols/process/warning-ratchet.md` — the four-step ladder; §4 above
  operationalizes its Step 3/4.
- `.agents/protocols/code-style/comments.md` — sanctioned exception #2 (`@nowarn` reason-line)
  and the category-1/2 licenses a suppression rationale is an instance of.
- `no-suppress-to-defer` (operator memory policy) — the standing rule this standard is the
  written, cited form of.
- `.claude/sprints/queue/nowarn-candidates.md` — the admission rule (Rule (i)) and the live
  pre-admission parking lot.
- `.claude/sprints/queue/conformance-sweeps.md` (Sweep 3) — the bytecode-verified
  FIX/GENUINE-BOUNDARY population cited in the worked examples above.
- `unchecked-annotations.md` — the sibling `scala3/` standard this one is modeled on (fix
  hierarchy before suppression, consensus-tier amendments, header/VALIDATE-gate shape).
- `.agents/protocols/process/consensus-change-protocol.md` — the litmus for when
  `forge`/`beacon` co-sign is required.
- [`fail-loud-invariants.md`](../../../research/best-practices/evm-clients/fail-loud-invariants.md) —
  Finding 4, the fail-loud-at-the-site principle a suppression must never violate.
- `.agents/protocols/process/finding-resolution.md` — the "every finding gets scheduled" rule,
  applied here to suppression inventory.
- `.agents/protocols/process/batch-research-protocol.md` — Rule (i)'s full text and the
  incident that produced it.
