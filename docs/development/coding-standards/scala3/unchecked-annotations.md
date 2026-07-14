# `@unchecked` — Suppressing Exhaustivity and Erasure Checks

**Status:** Ratified (operator, 2026-07-08), grounded in the reference-client cross-check
(see [`docs/research/best-practices/evm-clients/reference-client-crosscheck.md`](../../../research/best-practices/evm-clients/reference-client-crosscheck.md)
for the methodology/verdict this ratification rests on — the consensus-safety amendments
below trace to `fail-loud-invariants.md`'s Finding 4 within that cross-check).

**Domain:** Scala 3 language rules. **Owning specialist:** `mithril` (idiom sweep / KEEP
disposition), `wraith` (compile-error triage — references this doc, does not restate it).
Consensus-path sites (`vm/`, `mpt/`, `crypto/`, `domain/`, `ledger/`) require `forge` (PoW)
or `beacon` (PoS) co-sign per `consensus-change-protocol.md` — see "Consensus-safety
amendments" below.

**Authority:**
`.claude/repo-references/scala3/library/src/scala/unchecked.scala` (the annotation's own
scaladoc — defines all three grammars in one paragraph),
`.claude/repo-references/scala3/docs/_docs/reference/changed-features/pattern-bindings.md`
(grammar 1), `.claude/repo-references/scala3/docs/_docs/reference/error-codes/E092.md`
(grammar 3), and
[`docs/research/best-practices/evm-clients/fail-loud-invariants.md`](../../../research/best-practices/evm-clients/fail-loud-invariants.md)
(Finding 4 of the reference-client cross-check — the fail-loud principle this standard's
consensus-tier rules encode, plus the JVM/Scala translation forge established from it).

> **VALIDATE gate:** this doc's citations were checked against the four files above, in-repo,
> this session (2026-07-08) — not written from memory. See `../README.md`'s Governance
> section for what that check requires and why it exists. The consensus-safety amendments
> below were authored by `forge` during this standard's co-review pass, not retrofitted.

## What `@unchecked` actually is

One annotation class (`scala.unchecked`), one purpose stated in its own scaladoc — "should
not be considered for additional compiler checks" (`unchecked.scala:17-19`) — but it
suppresses two *different* compiler mechanisms depending on where it's written, and a third
distinct trigger was added by Scala 3.2's pattern-binding tightening. Treating all three as
one interchangeable "silence the warning" move is the root of every historical misuse this
doc exists to prevent: each grammar has a different runtime failure mode if the suppressed
assumption is wrong, and the acceptability bar below is calibrated per grammar, not per
annotation.

## The three grammars (don't conflate)

### Grammar 1 — pattern-binding irrefutability

```scala
val first :: rest = elems: @unchecked
```

From Scala 3.2, a pattern used directly in a `val` binding (or a `for` generator) must be
*irrefutable* — the right-hand side's static type must conform to the pattern's type. A
`List[A]`-typed value decomposed with `::` is not statically provable non-empty, so the
binding is refutable and the compiler now warns (previously a silent Scala 2 behavior that
could throw `MatchError` invisibly). `expr: @unchecked` on the right-hand side tells the
compiler to accept the binding anyway: "I assert this pattern matches; if I'm wrong, throw."
(`pattern-bindings.md:10-32`.)

fukuii site (illustrative, not exhaustive — see "Site disposition" below):
`PivotBlockSelector.scala` decomposes `waitingPeers: List[PeerId]` with `additionalPeer ::
newWaitingPeers = waitingPeers: @unchecked` immediately inside a
`waitingPeers.nonEmpty` guard — the runtime check establishing the invariant sits one line
above the pattern that assumes it.

### Grammar 2 — match-scrutinee suppression

```scala
def f(x: Option[String]) = (x: @unchecked) match { case Some(y) => y }
```

Annotating the *subject* of a `match` expression suppresses the compiler's exhaustiveness
and reachability check for that match, even though the match itself is written with case
clauses that don't cover every constructor (`unchecked.scala:19-20,29-32`). **Zero sites use
this grammar in fukuii today** (`src/main/` and `src/test/`) — documented here for
completeness and because it is the grammar `fail-loud-invariants.md`'s example code is
written against; a future site using it is bound by the same-file conventions below.

### Grammar 3 — type-erasure in a type pattern

```scala
case list: List[String @unchecked] => ...
```

Type arguments and type refinements are erased at compile time, so a type pattern testing a
parameterized type's argument (`List[String]` vs. `List[Int]`) cannot be checked at runtime
— the JVM only sees `List`. The compiler reports **E092** and offers `@unchecked` on the
type argument as one of three fixes (wildcard `List[?]`, explicit runtime element check, or
`@unchecked` "if you're certain about the type") (`E092.md`, whole file). Unlike grammars 1
and 2, a wrong assumption here does not fail at the pattern site — it produces a silent,
wrong-typed binding that fails later (or never) as a `ClassCastException` at whatever point
the erased element is actually used. This deferred-failure property is why grammar 3 gets
its own, stricter tier below.

fukuii site: `BlockImporter.scala:224` —
`case FetcherResponse(BlockFetcher.PickedBlocks(blocks: NonEmptyList[Block @unchecked])) =>`.

## Reference-client authority: fail loud, at the site

`fail-loud-invariants.md` (Finding 4, SUPPORTED across go-ethereum and besu) establishes
that a consensus invariant the type system doesn't enforce must be checked and must fail
loud **at the site where it is assumed** — never deferred to a downstream symptom. geth
`panic`s on an impossible MPT node type; besu throws a typed exception (`FlexStack`
`UnderflowException`/`OverflowException`, `AbstractBLS12PrecompiledContract`
`IllegalStateException`) at the point the invariant is checked, not somewhere later.

**The JVM/Scala translation forge established from this (governs grammars 1 and 2):**

> A Scala `@unchecked` annotation is legitimate only above a construct whose failure mode
> *is itself* the loud, at-the-site fail-fast — a `match` whose fall-through case throws or
> `sys.error`s naming the violated invariant (grammar 2), or a pattern-binding whose
> compiler-generated `MatchError` on mismatch **is** that loud fall-through with no
> restructuring needed (grammar 1). `@unchecked` above a match with a silent or
> plausible-default fall-through is the banned "defer downstream" anti-pattern — see
> `fail-loud-invariants.md`'s legitimate/banned code pair for the canonical example.

Grammar 3 does not fit this translation cleanly — an erased-type mismatch fails as a
`ClassCastException` at first *use* of the wrongly-typed value, not at the pattern site
itself, so it is besu's "thrown typed exception" only when something else (see the sealed-
ADT amendment below) has already proven the type argument true independent of the erasure
check the annotation is suppressing.

## When acceptable / when must-fix (general rule)

**Acceptable**, in the general case: a genuine invariant that (a) actually holds, (b) the
type system cannot practically be made to express, and (c) carries a one-line rationale
comment at the site per `comments.md` category 2 ("non-obvious invariants or constraints the
types don't enforce"). fukuii already has this rationale-comment convention in active use —
`PivotBlockSelector.scala:364`'s `case None => Behaviors.stopped // unreachable: guarded by
`exists` above` states the invariant and where it's established in one line, at the site.
This standard requires the same discipline on every `@unchecked` site: name the guard or
postcondition the annotation depends on, don't just silence the warning.

**Must-fix**, in the general case: an `@unchecked` masking a genuinely non-exhaustive match
that can actually be hit — the type system's complaint is correct and the code has a real
gap. Fix hierarchy, in preference order:
1. **Sealed ADT + exhaustive match** — make the "impossible" case unrepresentable, mirroring
   besu's visitor-pattern MPT node hierarchy (tier 1 in `fail-loud-invariants.md`).
2. **A `case _` (or fall-through pattern) that fails loud** — `sys.error`/throw naming the
   violated invariant, matching geth's `panic` (tier 2).
3. `@unchecked` above either of the above, only if 1 and 2 are both genuinely impractical.

## Consensus-safety amendments (forge co-review)

The general rule above is necessary but not sufficient on consensus-critical paths (`vm/`,
`mpt/`, `crypto/`, `domain/`, `ledger/`). Three amendments apply there, established by
`forge` during this standard's co-review:

### Amendment 1 — cite the guard line, not prose

On a consensus-critical site, the rationale comment required above must **name the specific
guard or postcondition and where it lives** — a line reference or a named method/check the
reader can go verify — not a prose restatement of "this is always non-empty." Prose rots
silently when the guard it describes moves or is refactored away; a citable line is a
concrete thing a future reviewer (or `wraith`) can re-check and a concrete thing that goes
visibly stale (a dangling reference) if the guard disappears, rather than silently stale
(unverifiable prose that nobody re-checks).

```scala
// ✅ cites the guard
if waitingPeers.nonEmpty then
  val additionalPeer :: newWaitingPeers = waitingPeers: @unchecked // guarded above: waitingPeers.nonEmpty

// ❌ prose only — nothing to re-verify against
val additionalPeer :: newWaitingPeers = waitingPeers: @unchecked // waitingPeers is never empty here
```

### Amendment 2 — grammar 3 gets a stricter tier: sealed-ADT pinning or must-fix

Because a wrong grammar-3 assumption defers to a `ClassCastException` at some later use
site rather than failing at the pattern itself, "genuine invariant, can't be typed" is **not
sufficient** for a KEEP disposition on a consensus-critical grammar-3 site. It is acceptable
**only** when the erased type argument is actually pinned by a sealed ADT the type
system already enforces elsewhere, and the rationale must name that ADT and case:

```scala
// ✅ acceptable — FetchResponse is sealed; PickedBlocks is a monomorphic case class whose
// `blocks` field is declared `NonEmptyList[Block]` with no other type ever constructible
// at that position. The E092 warning fires because Scala's erasure check can't see the
// unapply already proved this; the sealed ADT is what actually proves it.
case FetcherResponse(BlockFetcher.PickedBlocks(blocks: NonEmptyList[Block @unchecked])) =>
  // pinned by: BlockFetcher.FetchResponse (sealed trait) → PickedBlocks(blocks: NonEmptyList[Block])
```

A grammar-3 `@unchecked` over a runtime-provided generic in a state-root path — decoded RLP
content, a deserialized wire message, anything not already narrowed by a sealed hierarchy —
has no such proof standing behind it and is **must-fix**: replace with an explicit runtime
element check (E092's third offered fix) or restructure so the type is proven before the
pattern, not asserted at it.

### Amendment 3 — irrefutable-pattern (grammar 1) `@unchecked`: remove, don't keep

Unlike grammar 3, a grammar-1 site's compiler-generated `MatchError` on mismatch already
satisfies "fail loud, at the site" for free — no restructuring needed for that property
alone. That is exactly why grammar 1 does **not** get the general "genuine invariant, can't
be typed, keep with rationale" default: the annotation itself protects nothing beyond what a
`case _ => sys.error(...)` fall-through or a narrower type would already give at comparable
or lower cost, and every grammar-1 site kept permanently normalizes suppressing an
exhaustivity check the compiler is otherwise willing to enforce almost for free. Default
disposition for grammar 1 is **removal** — restructure into a genuinely irrefutable pattern
(a typed fix, e.g. `NonEmptyList` at the boundary instead of `List`) or an explicit `match`
with a loud fall-through case. KEEP-with-rationale on grammar 1 is admissible only where
`forge`/`beacon` co-review has confirmed no such restructuring is practical inside a
consensus-critical hot path, and even then amendment 1's citation requirement applies.

## Conformance checks

Advisory inventory checks per the enforcement ladder in `../README.md` — they surface sites
for review, they do not fail a build on their own.

```bash
# Grammar 1 — pattern-binding irrefutability (expr: @unchecked at end of a val binding line)
grep -rn ': @unchecked *$' src/main/scala --include="*.scala"

# Grammar 2 — match-scrutinee suppression ((x: @unchecked) match) — expect 0 today; a nonzero
# hit is new grammar-2 usage and must be checked against the "acceptable/must-fix" rule above.
grep -rEn '\(.*: @unchecked\) match' src/main/scala --include="*.scala"

# Grammar 3 — type-erasure in a bracketed type pattern
grep -rEn '@unchecked\]' src/main/scala --include="*.scala"
```

Every hit from any of the three checks needs one of: a citing rationale comment per
Amendment 1 (consensus-critical paths) or `comments.md` category 2 (elsewhere), a sealed-ADT
pin named per Amendment 2 (grammar 3 only), or a disposition of "fix, not keep" per the fix
hierarchy above / Amendment 3 (grammar 1 default).

## Site disposition

A conformance sweep against this standard was run over the current `@unchecked` sites in
`src/main/` and `src/test/`, cross-referenced with the reference-client research above. The
per-site disposition (KEEP-with-rationale vs. fix, which sealed ADT pins which grammar-3
site, and any sites still needing a compile-time re-verify after a nearby EIP-driven change)
is tracked in `.claude/sprints/queue/conformance-sweeps.md` (Sweep 2), reached via
`.claude/sprints/QUEUE.md`'s `### Batch 4.5` pointer — not enumerated here, per
`doc-standards.md`: a per-site status list is exactly the kind of live count a future
commit silently invalidates, and QUEUE.md (plus its `queue/` staging directory) is this
project's one authoritative home for in-flight sweep/finding status.
