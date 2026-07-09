# Unchecked Consensus Invariants Must Fail Loud at the Site

Finding 4 of the reference-client cross-check (`reference-client-crosscheck.md`). This is
the finding the `@unchecked` coding standard cites at ratification: it establishes both the
reference-client rule (fail loud, at the site) and the exact JVM/Scala translation of it.

---

## Invariant

**A consensus invariant that the type system does not enforce MUST be checked and MUST fail
loud at the site where it is assumed — never deferred to a downstream symptom.** An
"impossible" node type, an out-of-range stack, or a malformed precompile input is a
programming/consensus error; the reference clients `panic`/throw immediately rather than
returning a wrong-but-plausible result that corrupts state further down.

## Evidence table

| Sub-claim | Client (weight) | Evidence (`file:line`) | Verdict |
|-----------|-----------------|------------------------|---------|
| Impossible MPT node type → `panic` at the site | go-ethereum | `trie/trie.go:222,356,479,729,759` — `panic("%T: invalid node")` | SUPPORTED |
| Stack under/overflow guarded immediately *before* opcode dispatch | go-ethereum | `core/vm/interpreter.go:189-192` — `minStack`/`maxStack` check returns `ErrStackUnderflow`/`ErrStackOverflow` before executing the op | SUPPORTED |
| Precompile input-shape rejected at entry | go-ethereum | `core/vm/contracts.go:792-793,858-871` — malformed input rejected at function entry | SUPPORTED |
| Typed node hierarchy + thrown exception at the access site | besu (**JVM analog — weighted**) | `evm/internal/FlexStack.java:99-106` — throws `UnderflowException`/`OverflowException`; `StoredNode.java:49,60` typed node dispatch | SUPPORTED |
| Precompile invariant thrown, not silently defaulted | besu (JVM) | `AbstractBLS12PrecompiledContract.java:159-186` — `IllegalStateException`/rejection at the invariant | SUPPORTED |

**Verdict: SUPPORTED.** Both language families fail loud at the site. The divergence is in
*how* the invariant is expressed, and that divergence is itself the standard.

## Two tiers, in preference order

1. **Prefer a typed guarantee** so the invariant cannot be violated. besu encodes MPT node
   kinds as a **visitor-pattern class hierarchy** (`StoredNode`, `BranchNode`, ...) so
   "impossible node type" is unrepresentable — there is no cast to get wrong. This is
   strictly better than a runtime check because the compiler enforces it.
2. **Where the type cannot carry the invariant, assert with a loud-throwing guard at the
   site** — geth's `panic("%T: invalid node")`, besu's thrown `UnderflowException`. Never a
   silent default, a swallowed branch, or a wrong-but-plausible return.

geth's `panic` at an impossible node type is the Go expression of tier 2; besu's typed node
hierarchy is tier 1. Given the choice (as we usually are, on the JVM), take tier 1.

## JVM / Scala translation — the `@unchecked` rule

besu's "thrown typed exception at the site" is **exactly** our "asserted match with a
loud-throwing fall-through case." Therefore:

> **A Scala `@unchecked` annotation is legitimate only above a `match` whose fall-through
> case itself fails loud** (throws or `sys.error`s with a message naming the violated
> invariant). `@unchecked` that suppresses exhaustivity over a match with a silent or
> plausible-default fall-through is the banned "defer downstream" anti-pattern — it converts
> a caught consensus error into quiet state corruption.

**Legitimate** (tier 2, loud fall-through — the reference-client `panic` equivalent):
```scala
(node: @unchecked) match {
  case b: BranchNode  => ...
  case l: LeafNode    => ...
  case e: ExtensionNode => ...
  // fall-through fails loud, exactly like geth's panic("%T: invalid node")
  case other => sys.error(s"invalid MPT node: $other")
}
```

**Banned** (silent/plausible fall-through — the "defer downstream" corruption):
```scala
(node: @unchecked) match {
  case b: BranchNode => ...
  case _             => EmptyNode   // ← wrong-but-plausible: corrupts state later
}
```

**Best (tier 1 — no `@unchecked` at all):** make the match exhaustive over a sealed
hierarchy so the compiler proves totality, mirroring besu's visitor-pattern node classes.
Reach for `@unchecked` only when a sealed hierarchy is genuinely unavailable, and then only
with a loud fall-through.

## Standard

- Consensus invariants the type system doesn't enforce are checked at the site and fail
  loud (`sys.error`/throw with the invariant named) — matching geth `panic` / besu thrown
  exception. Deferring to a downstream symptom is banned (see `AGENTS.md` "Fail loudly").
- Prefer sealed hierarchies + exhaustive matches (besu tier 1) over runtime guards.
- `@unchecked` is permitted **only** above a match with a loud-throwing fall-through case;
  it is banned above any match that can silently return a default.
