# Mantis-Inheritance Anti-Pattern Ledger (Part B)

Fukuii is a fork of IOHK Mantis. This ledger tracks patterns inherited from that fork (or
introduced during the Scala 2→3 / Classic→Typed migrations) that conflict with the
standards housed elsewhere in this directory. Each entry names the anti-pattern and points
at where the *actual rule* lives — this file does not restate rule content that already has
a home in a domain doc. If a ledger entry starts growing its own reference citations and
worked examples instead of pointing at `scala3/`, `pekko/`, etc., that is drift back toward
the housing problem this whole directory exists to avoid.

Every entry below passed the VALIDATE gate in `README.md` before being seeded here — same
maker/checker/operator loop as any other admission to this directory.

## Entry format

```
### <ID> — <short anti-pattern name>

**Anti-pattern:** what the code does that's wrong (one paragraph, no rule restatement).
**Why it's wrong:** the failure mode it enables (one paragraph).
**Conforming target:** a link into this directory's domain docs — not inline rule content.
**Reference authority:** pointer only — "see <conforming target>'s citations", not a
  second copy of the file:line evidence.
**Owning specialist:** who reviews/fixes instances of this.
**Grep check:** the conformance command the owning specialist runs.
```

Do not add a live count, a percentage-complete, or a "seen N times" claim to an entry —
per `documentation.md` (once migrated) / `.agents/protocols/code-style/doc-standards.md`
(today), that belongs in `.claude/sprints/QUEUE.md` or the one authoritative subsystem doc
for that fact, not here. A ledger entry is a standing rule pointer, not a progress report.

---

### B1 — `Behavior[Any]` (or any `Any`-payload wrapper) re-matched by concrete type

**Anti-pattern:** A Pekko Typed actor's `Behavior[Any]`, or a message case class wrapping
an `Any` payload (e.g. `WrappedExternal(msg: Any)`), is re-matched inside the receive
handler by concrete type (`case x: SomeConcreteType => ...`) as though it were an
exhaustiveness-checked sealed match.

**Why it's wrong:** It defeats exhaustiveness checking — the actor's declared protocol type
gives no compile-time guarantee about what it receives, and a new message variant added on
the sender's side produces zero compiler feedback at the receiver. The failure mode is a
silent `case _ => Behaviors.same` drop or a runtime `MatchError`, found in production or a
flaky test, never at compile time.

**Conforming target:** [`pekko/actor-message-typing.md`](pekko/actor-message-typing.md) —
the sealed `Command` protocol standard, its two sanctioned bridging mechanisms, and the
documented sanctioned exception (genuine Classic-bridge / per-session-child) that this entry
is *not* flagging.

**Reference authority:** see `pekko/actor-message-typing.md`'s citations (Pekko style guide,
interaction patterns, Scala 3 union-response handling).

**Owning specialist:** `loom` (migration sites), `prism` (review of new/existing Typed
code).

**Grep check:** `grep -rn "Behavior\[Any\]" src/main/scala/` to inventory current sites;
cross-check each against `pekko/actor-message-typing.md`'s sanctioned-exception list before
treating a hit as a violation rather than an accepted exception.

---

### B2 — `Any`/`Matchable`-widened value re-discriminated by a `case x: T` chain (general form)

**Anti-pattern:** The same shape as B1 but outside actor code — a plain method or
deserializer taking an `Any` (or unnecessarily `Matchable`-widened) parameter and branching
on concrete type via pattern match, functionally identical to a manual `isInstanceOf` chain
but reading as more "checked" than it is.

**Why it's wrong:** No exhaustiveness guarantee is actually being verified; a case handled
today can be silently forgotten tomorrow because nothing forces every branch to exist. This
is a real distinct shape, not caught by a grep for `isInstanceOf[` — the pattern match
compiles to equivalent bytecode without an explicit `isInstanceOf` call anywhere in source.

**Conforming target:** [`scala3/matchable-e165.md`](scala3/matchable-e165.md) — give the
value a real sealed type before it needs branching; narrowing the *declared* type is the
fix, not adding more branches to the `Any`/`Matchable` match.

**Reference authority:** see `scala3/matchable-e165.md`'s citations (Scala 3 `Matchable`
reference, E165).

**Owning specialist:** `mithril` (general Scala 3 idiom sweep); `loom` for the actor-message
instance (= B1).

**Grep check:** no single reliable grep (see "Why it's wrong" above) — this is a review-time
pattern, not currently ratchet-eligible. Candidate future check: `grep -rn "case .*: \w+ =>" `
scoped to functions with an `Any`- or `Matchable`-typed parameter, manually triaged.

---

### B3 — `[T <: Matchable]` bound used to keep matching on what is really `Any`

**Anti-pattern:** A generic type parameter is given a `Matchable` upper bound to silence
E165, at a call site where every actual caller passes one of a small fixed set of unrelated
types (e.g. always `Command` or `Response`, never anything else) — i.e. the "generic" is
really just `Any` wearing a bound.

**Why it's wrong:** `Matchable` is the correct fix for a *genuinely* generic, caller-
parametric type that needs to be pattern-matched. Applied to a call site that is really
passing a small closed set of unrelated types, it documents the same weakness E165 exists to
catch instead of fixing it — this is B1/B2 in a thinner disguise.

**Conforming target:** [`scala3/matchable-e165.md`](scala3/matchable-e165.md) — the
worked correct-vs-incorrect example and the fix hierarchy (sealed type / union type over a
bound-widened generic).

**Reference authority:** see `scala3/matchable-e165.md`'s citations (E165 solution section,
`Matchable` reference).

**Owning specialist:** `mithril`.

**Grep check:** `grep -rn "\[T <: Matchable\]" src/` (or `<: Matchable` more broadly) to
inventory existing bound usage; for each hit, confirm more than one *unrelated* concrete
type is genuinely instantiated at different call sites — a single-caller or two-related-
types-from-one-union instantiation is a B3 candidate, not a correct use.

---

## Ledger maintenance

New entries follow the format above and go through the same VALIDATE gate as any other
admission (`README.md`'s "Governance" section) — a candidate anti-pattern is proposed via
`.claude/sprints/QUEUE.md` per `finding-resolution.md`/`inline-cleanup.md`, checked by the
owning domain specialist against the cited authority, and ratified by the operator before
landing here. Filling this ledger out fully against the systemic-review documents is an
ongoing follow-on (see `QUEUE.md`), not a one-session task — this file currently seeds three
entries as the proof that the "conforming target" pointer pattern works end to end, not as a
complete inventory.
