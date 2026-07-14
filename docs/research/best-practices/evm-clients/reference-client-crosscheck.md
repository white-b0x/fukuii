# Reference-Client Cross-Check Methodology

The captured procedure and authority model behind every consensus/EVM coding standard
fukuii ratifies. This is the EVM analog of `../scala/` and `../pekko/`: the durable
evidence base that `docs/development/coding-standards/consensus/` and `.../evm/` cite.

**Governance rule (binding):** *A consensus/EVM coding standard is ratifiable only when
grounded in reference-client evidence.* This document — and the topic files beside it — is
where that evidence lives. A proposed standard with no multi-client evidence table is not
ratifiable; it is a hypothesis. "It compiles and the tests pass" establishes correctness of
one implementation, not that the *shape* is the client-universal one we should standardize.

---

## 1. Client-Coverage Map — who is authoritative for what

Do not re-derive this per investigation. Each client's authority is bounded by its
consensus family, its host language, and whether it vendors its own EVM/PoW internals.

| Client | Language / VM host | Authoritative for | Do NOT cite for |
|--------|--------------------|-------------------|-----------------|
| **core-geth** | Go | **PoW/ETC** rules and the **only multi-consensus** client — the **Ethash authority** (still vendors the full Ethash inner loop); ECIP fidelity | — |
| **go-ethereum** | Go | **Canonical PoS/ETH reference**; EIP fidelity; EVM interpreter internals; MPT | ETC-specific ECIP behavior (stripped post-fork) |
| **besu** | **Java / JVM** | **Closest analog to our Scala/JVM idioms — weight heavily for JVM-idiom questions** (object-oriented EVM, typed node hierarchies, JVM concurrency primitives) | Ethash — **removed post-merge**, not an Ethash source |
| **erigon** | Go | Performance patterns (buffer pooling, staged execution); secondary EVM/Ethash cross-check | Primary EIP authority (defer to geth) |
| **reth** | Rust | PoS architecture patterns (Rust analog) | **EVM/Ethash internals — interpreter is the external `revm` crate, not vendored → uncitable for opcode/stack/gas/Ethash internals** |
| **nethermind** | C# | **JWT / Engine-API auth reference**; secondary consensus-affecting RLP cross-check | Ethash internals |

**Language-idiom weighting.** For a question of *how to shape JVM code* (mutable buffer vs.
persistent structure, exception vs. sentinel, concurrency primitive choice), besu is the
primary witness — it faces the same GC, the same JIT, the same `synchronized`/`Atomic*`
toolkit we do. For a question of *EIP/Ethash byte-fidelity* (what the code must compute),
geth and core-geth are primary. These two axes are independent: a besu idiom that diverges
from geth's *shape* is fine; a besu idiom that diverges from geth's *computed result* is a
consensus bug in besu, not a standard for us.

---

## 2. The process — building a multi-client evidence table

For any candidate consensus/EVM coding pattern:

1. **State the claim as an invariant**, not a preference. ("Hot-path EVM buffers are
   mutated in place" — not "we should use `var` here.")
2. **Build the evidence table**: one row per client that has a bearing, each with a concrete
   `file:line` citation and a verdict.

   | Claim | Client | Evidence (`file:line`) | Verdict |
   |-------|--------|------------------------|---------|
   | <invariant> | core-geth | `path:line` | SUPPORTED / AMEND |
   | | go-ethereum | `path:line` | SUPPORTED / AMEND |
   | | besu (JVM analog — weighted) | `path:line` | SUPPORTED / AMEND |

3. **Weight by the coverage map**: a JVM-idiom claim needs besu SUPPORTED to ratify; an
   EIP/Ethash-fidelity claim needs geth/core-geth SUPPORTED. A claim contradicted by the
   weighted-primary client is **AMEND**, not SUPPORTED-with-caveat — rewrite the claim until
   the primary witness supports it.
4. **Record nuance as a first-class row.** If the pattern is applied *conditionally* in the
   reference (e.g. constant-time compare at MAC sites but plain compare for non-secret
   data), the condition is part of the standard, not an asterisk. See
   `constant-time-comparison.md` for the canonical worked example.
5. **Cite every client `file:line` in the topic file.** Line numbers drift; keep the
   surrounding symbol name in the citation so a moved line is still locatable
   (`besu EVM.java runToHaltV2 "Benchmark before refactoring"`), and re-verify against the
   vendored copy at `.claude/repo-references/clients/<client>/` before ratification.

**Verdict taxonomy:** `SUPPORTED` (primary-weighted client(s) implement the invariant as
stated) · `AMEND` (evidence contradicts or narrows the claim — rewrite before ratifying).
There is no "PARTIAL" verdict; partial evidence means the claim is mis-scoped.

---

## 3. Where the evidence lives

| File | Contents |
|------|----------|
| `reference-client-crosscheck.md` (this file) | Coverage map, process, governance rule |
| `mutable-state-parity.md` | Findings 1–2: mutable hot-path buffers and concurrent Engine-API state are parity-correct, not debt |
| `constant-time-comparison.md` | Finding 3: constant-time compare required at auth/MAC sites (with the "not universal" nuance) |
| `fail-loud-invariants.md` | Finding 4: unchecked consensus invariants must fail loud at the site; JVM/Scala translation for `@unchecked` |
| `anti-patterns.md` | Proven-broken approaches (sync/P2P/config), pre-dates this cross-check |

**Vendored authority:** all `file:line` citations resolve against
`.claude/repo-references/clients/{core-geth,go-ethereum,besu,erigon,reth,nethermind}/`.
Spec authority (what the code must match) is `.claude/repo-references/{EIPs,ECIPs}` and
`ethereum/tests`; this research covers *code shape*, the specs cover *semantics*.
