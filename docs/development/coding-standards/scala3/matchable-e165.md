# `Matchable`, E165, and the corrected E003 fact

**Domain:** Scala 3 language rules. **Owning specialist:** `mithril` (idiom sweep),
`wraith` (compile-error triage — references this doc, does not restate it).
**Authority:** `.claude/repo-references/scala3/docs/_docs/reference/other-new-features/matchable.md`,
`.claude/repo-references/scala3/docs/_docs/reference/error-codes/E165.md`,
`.claude/repo-references/scala3/docs/_docs/reference/error-codes/E003.md`.

> **VALIDATE gate:** this doc's citations were checked against the three files above,
> in-repo, this session (2026-07-08) — not written from memory. See `../README.md`'s
> Governance section for what that check requires and why it exists.

## What E165 actually is

E165 ("Matchable Warning") fires when a `match` expression's scrutinee type does not extend
`scala.Matchable`. It is emitted under `-source:future` or `-source:future-migration`
(`error-codes/E165.md:11`) and is purely a Scala 3 language-level pattern-matching rule.
**It has no relationship to Pekko, actors, or Classic-vs-Typed migration status.**

`Matchable` is a universal trait, parent `Any`, extended by both `AnyVal` and `AnyRef`
(`matchable.md:89-93`). Concretely: `Any` itself does **not** extend `Matchable`, so a
scrutinee typed `Any` (or an unbounded/universally-bounded type parameter) triggers the
warning; a scrutinee typed `AnyRef`, a concrete class, or `T <: Matchable` does not
(`matchable.md:95`, `error-codes/E165.md:9`).

Three scrutinee shapes trigger it (`matchable.md:68-73`):
1. Type `Any` directly.
2. An unbounded type parameter or abstract type.
3. A type parameter or abstract type bounded only by some other universal trait (not
   `Matchable` itself).

The rule exists to stop pattern matching from quietly breaking type abstractions — Scala
3's own `IArray` example (`matchable.md:9-48`) shows a `match` on an unconstrained selector
recovering a supposedly-immutable array's mutable representation at runtime, with no
warning or error under the pre-`Matchable` rules. "No unbounded type parameter or abstract
type should be decomposable with a pattern match" (`matchable.md:48`) is the governing
principle.

## Fix hierarchy (in order of preference)

1. **Give the value a real sealed type before it needs to be matched.** If the scrutinee is
   `Any` because it's bridging two protocols with no shared type, that's usually the actual
   bug — see [`../pekko/actor-message-typing.md`](../pekko/actor-message-typing.md) and
   [`../mantis-inheritance-ledger.md`](../mantis-inheritance-ledger.md)'s B1/B2 entries for
   the actor-message and general-value instances of this. Fixing the type is always
   preferred over suppressing the warning on an `Any` scrutinee.
2. **Constrain a genuinely generic type parameter to `Matchable`** — the sanctioned,
   narrow fix, and the only case where adding the bound is itself the correct end state:

   ```scala
   // ✅ Correct — T is a genuine, caller-supplied type parameter (many unrelated
   // types are instantiated at different call sites; the method is truly generic)
   def example[T <: Matchable](x: T) = x match
     case s: String => s
     case _         => ""
   ```
   (`error-codes/E165.md:54-60`, `matchable.md:70-73`)

   The bound is **not** a general-purpose way to keep matching on what is really `Any`:

   ```scala
   // ❌ Incorrect — the "generic" is a thin disguise for Any; every real caller
   // passes one of two unrelated, already-known types
   def handle[T <: Matchable](x: T): Unit = x match
     case cmd: Command   => ...
     case resp: Response => ...
   // If no other T is ever instantiated, use a union type (Command | Response)
   // or a messageAdapter instead — see mantis-inheritance-ledger.md's B3 entry.
   ```

   An alternative narrower bound is also sanctioned when it fits the actual usage:
   `[T <: AnyRef]` (`error-codes/E165.md:62-70`).
3. **`.asMatchable` / `asInstanceOf[Matchable]` — sanctioned only for universal `equals`.**
   The compiler-documented escape hatch is `import compiletime.asMatchable; x.asMatchable
   match { ... }` (`error-codes/E165.md:43-48`). Its one clearly sanctioned use in the
   language reference itself is overriding `equals(that: Any): Boolean`, where a cast to
   `Matchable` is unavoidable and is guaranteed to succeed at runtime because `Any` and
   `Matchable` both erase to `Object` (`matchable.md:100-116`):

   ```scala
   override def equals(that: Any): Boolean =
     that.asInstanceOf[Matchable] match
       case that: C => this.x == that.x
       case _        => false
   ```
   Reaching for this outside the `equals`-contract shape is a signal to re-examine whether
   fix #1 or #2 above actually applies instead.

## The corrected E003 fact

**E003 is the deprecated `with`-type-operator warning** (`A with B` as a compound-type
expression → `A & B`), unrelated to actor migration status
(`error-codes/E003.md`, whole file). Pekko's `trait Actor` is not itself `@deprecated`, so
there is no compiler error/warning code that fires on "this actor still uses the Classic
API." An earlier version of this project's tooling conflated E003/E165 with Classic-actor
migration progress — that conflation has been corrected; do not reintroduce it.

**The correct migration-progress metric is a plain grep, not a compiler code:**

```bash
grep -rn "extends Actor" src/main/scala/com/chipprbots/ethereum/network/ \
                          src/main/scala/com/chipprbots/ethereum/blockchain/sync/
# Expect 0 in these two paths (Classic→Typed actor-definition migration is complete
# there) — see that subtree's AGENTS.md § Actor migration status for the current word.
```

E165 is likewise not a migration-progress signal for Classic actors — it is expected and
correct on the sanctioned `Behavior[Any]` sites documented in
[`../pekko/actor-message-typing.md`](../pekko/actor-message-typing.md), on
`TestProbe`/`equals(that: Any)` sites (fix #3 above), and on Pekko `Tcp` bridge code (Pekko's
`Tcp` API is inherently Classic-only and will not itself become Typed).

## Conformance checks

```bash
# 1. E003/actor-migration conflation check — should return 0 hits of "extends Actor"
#    in the two fully-migrated subtrees; a nonzero count is a regression signal, not
#    an E003 signal.
grep -rn "extends Actor" src/main/scala/com/chipprbots/ethereum/network/ \
                          src/main/scala/com/chipprbots/ethereum/blockchain/sync/

# 2. Matchable-bound inventory — every hit needs a genuine multi-type-instantiation
#    justification (fix #2 above), not a thin Any-disguise (see mantis-inheritance-
#    ledger.md B3).
grep -rn "<: Matchable\]" src/main/scala/ --include="*.scala"

# 3. asMatchable / asInstanceOf[Matchable] escape-hatch inventory — every hit should
#    be an equals(that: Any) override (fix #3 above) or carry an equivalent rationale.
grep -rn "asMatchable\|asInstanceOf\[Matchable\]" src/main/scala/ --include="*.scala"
```

These are advisory checks per the enforcement ladder in `../README.md` — they inventory
sites for review, they do not by themselves fail a build.
