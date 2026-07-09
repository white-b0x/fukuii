# Mutable State in Consensus / EVM / Crypto Code

**Status:** Ratified (operator, 2026-07-08), grounded in the reference-client cross-check.
**Owning specialist:** `forge` (PoW), `beacon` (PoS).

**Authority (the grounding that makes this ratifiable):** the reference-client evidence
tables in `docs/research/best-practices/evm-clients/mutable-state-parity.md` (Categories A
and B) and `.../evm-clients/constant-time-comparison.md` (Category C), governed by the rule
in [`docs/research/best-practices/evm-clients/reference-client-crosscheck.md`](../../../research/best-practices/evm-clients/reference-client-crosscheck.md) —
*a consensus/EVM coding standard is ratifiable only when grounded in reference-client
evidence.* Every category below is SUPPORTED across Go/Java/C# clients, with besu weighted
as the JVM-idiom witness.

---

## The rule in one line

**Mutability in `consensus/`, `vm/`, `domain/`, and `crypto/` is presumed *wrong* — except
in three sanctioned categories, each of which is the proven cross-client shape. A `var` or
mutable buffer outside those categories is a defect to fix, not a style choice.**

This is a ratchet against FP-purity rewrites of correct code *and* against undisciplined
mutation. Both directions are errors: converting a Category-A hot loop to persistent
structures is a parity/performance regression; introducing an unsanctioned `var` is debt.

---

## The "hot path" criterion (gates Category A)

A site is "hot path" only if it is on the **per-block / per-opcode / per-hash execution
path** — code that runs once per opcode, per Ethash mix access, per MPT node, or per
fee-market iteration. Startup, config, one-per-request setup, and test code are **not** hot
path and do not qualify for Category A. If you cannot name the per-execution loop the site
sits inside, it is not Category A.

---

## Sanctioned category A — allocation-free perf / fidelity hot-loop

In-place-mutable buffers and imperative counters on the hot path. This is the universal
client implementation: EVM stack/memory/gas, the Ethash mix loop, MPT node hashing, the
EIP-4844 `fake_exponential` accumulator loop. See `mutable-state-parity.md` Finding 1 for
the full evidence table (core-geth `consensus/ethash/algorithm.go:365-388`, besu
`FlexStack.java:55-190` / `Memory.java:43-145` / `MessageFrame.java:207`, geth
`core/vm/stack.go:78-158`, and besu's own `EVM.java:471` — *"performance-critical code.
Benchmark before refactoring."*).

**Two valid drivers, either sufficient:**

1. **Performance** — the site is allocation-sensitive and the mutation avoids per-iteration
   garbage. besu's `EVM.java:471` comment is the standard: any rewrite needs a benchmark,
   not a purity argument.
2. **EIP / reference-pseudocode fidelity** *(beacon amendment 1)* — the imperative shape
   mirrors the spec's own pseudocode, so keeping it mutable minimizes translation risk.
   **`fake_exponential` is the canonical example** (geth `eip4844.go:215-227`, besu
   `BlobFeeMarket.java:60-73`): the EIP-4844 spec is an imperative accumulator loop, and the
   Scala port stays imperative to match it byte-for-byte.
   - **Fidelity caveat (must be verified, not assumed):** the Python `//` floor-division in
     the spec maps to Scala `BigInt./` **only because both operands are non-negative** here
     (`//` floors toward −∞, `BigInt./` truncates toward 0 — they diverge on negative
     operands). This equivalence holds for `fake_exponential` because factor, numerator, and
     accumulator are all ≥ 0; any new fidelity port must re-establish non-negativity before
     relying on it.

Category-A sites need **no** justification comment beyond a pointer to this standard.

---

## Sanctioned category B — stateful field

A `var` or mutable collection that *is* the legitimate state of a long-lived object,
guarded appropriately for its concurrency.

- **Single-threaded per-execution state** (PoW hot path, block executor internals): a
  mutable field mutated within one thread of control needs no guard. This is Finding 1's
  per-execution buffers viewed as object fields.
- **Concurrent cross-request Engine-API state** *(beacon amendment 2)*: mutable state shared
  across concurrent JSON-RPC requests — payload caches, invalid-block/tipset tracking,
  finality/sync snapshots — **guarded by an explicit lock or a concurrent/atomic
  collection**. This is a sanctioned PoS pattern, not a smell. Evidence: geth
  `eth/catalyst/api.go:93-127` (mutex-guarded `invalidBlocksHits`/`invalidTipsets` maps),
  `queue.go:48-71` (RWMutex payload cache); besu `PostMergeContext.java:42-61`
  (`AtomicReference` + `synchronized EvictingQueue`), `BadBlockManager.java:37-46` (Guava
  concurrent caches). See `mutable-state-parity.md` Finding 2.
  - The sanction is for **guarded** shared mutable state. Unsynchronized cross-request
    sharing is a defect, not Category B.

---

## Sanctioned category C — security constant-time

A mutable buffer or loop that exists specifically to make a comparison constant-time (or to
zero a secret). Byte comparison of secrets, MACs, and auth tags **must** use a constant-time
primitive. Evidence: geth `crypto/ecies/ecies.go:325` (`subtle.ConstantTimeCompare`), besu
`ECIESEncryptionEngine.java:276` (`Arrays.constantTimeAreEqual`), nethermind
`JwtAuthentication.cs:251` (`FixedTimeEquals`). See `constant-time-comparison.md`.

**Conditional, per the source finding:** this applies *at security-critical comparison
sites only*. Plain comparison remains correct for non-secret integrity checks on
already-authenticated data — matching besu's deliberate plain `Arrays.equals` for the
per-frame MAC at `Framer.java:321`. Do not blanket-convert every comparison to constant-time.

---

## The FIX category — everything else

A `var`/mutable site in scope that is **not** A, B, or C is a defect. The two idioms that
almost always fall here:

- **`var x = null` then conditional assignment** — a mutable-null placeholder standing in
  for what should be a `val` from an `if`/`match` expression or an `Option`. There is no
  sanctioned category for a null placeholder; rewrite to an expression-valued `val`.
- **Accumulator `var` outside a hot loop** — a `var` building a result where the hot-path
  criterion does not apply; rewrite to a fold/`map`/comprehension.

### The one Engine-API FIX has a consensus trap *(beacon amendment 3)*

The FIX at `EngineApiController.scala:119` (a mutable accumulation building the execution
payload response) **must preserve the V3 → V4 cumulative `.copy` ordering**. The response is
built by successive `.copy` calls that layer on version-specific fields; reordering or
dropping a layer silently omits the EIP-4788 (parent beacon block root) / EIP-4844 (blob
gas) fields, producing a **Prague/Osaka consensus break** rather than a compile error.
Any rewrite of this site must reproduce the exact cumulative-copy order and be validated
against the reference payload, not just compiled.

---

## The grep ratchet (two parts)

**Part 1 — hard check (must be zero):** no mutable-null placeholder idiom in the four
scoped trees.

```bash
grep -rn 'var [a-zA-Z_][a-zA-Z0-9_]* *: *[^=]*= *null\b' \
  src/main/scala/com/chipprbots/ethereum/{consensus,vm,domain,crypto}/ \
  --include='*.scala'
# Expected: zero matches. Any hit is a FIX-category defect.
```

**Part 2 — tripwire (inventory must not grow unreviewed):** the total count of `var` /
`mutable.` sites in the scoped trees is a tripwire, not a target. A CI or pre-PR diff that
*increases* the count must be accompanied by a category (A/B/C) assignment in review;
an unreviewed increase fails the ratchet.

```bash
grep -rcn '\bvar \|mutable\.' \
  src/main/scala/com/chipprbots/ethereum/{consensus,vm,domain,crypto}/ \
  --include='*.scala'
# The number is a baseline to compare against, not asserted here — live count lives in QUEUE.
```

Part 1 is a gate (zero). Part 2 is a review trigger (no unexplained growth). Neither number
is baked into this doc — see doc-standards: durable docs carry invariants, live counts live
in `.claude/sprints/QUEUE.md`.

---

## Site disposition

The per-site audit (each scoped `var`/mutable site classified KEEP-A / KEEP-B / KEEP-C /
FIX, including the `EngineApiController.scala:119` FIX and the mutable-null FIXes) is
**live status and lives in `.claude/sprints/queue/conformance-sweeps.md`** (Sweep 1),
reached via `.claude/sprints/QUEUE.md`'s `### Batch 4.5` pointer — not here, per
doc-standards: this durable doc must not carry counts or per-site status that go stale.
This file defines the categories; the queue tracks which sites sit in each and which
remain to fix.
