# fukuii Coding Standards

A **directory**, not a file. The surface this covers — Scala 3 idiom, Pekko Typed actor
design, per-dependency conventions, EVM/consensus code shape, networking/wire-protocol
shape, and the cross-cutting rules (logging, comments, nomenclature, dead code,
documentation) — is too wide for one document to stay both complete and readable.
Organizing by domain also matches who owns review: a Pekko actor-typing question and a
Scala 3 `Matchable` question have different owning specialists and different upstream
authorities, and should live in different files for the same reason `forge` and `herald`
are different agents.

**Standards content lives here, in exactly one place.** Agent charters
(`.claude/agents/*.md`) and protocol files (`.agents/protocols/`) do not restate what a
standard says — they carry operational role guidance (how an agent acts, what order it
does things in) plus a pointer into this directory. If you find standards content
(a rule's *definition*, not a *reference to* the rule) inside an agent charter or a
protocol file outside this directory, that is drift and should be corrected by moving the
content here and leaving a pointer behind. See `mantis-inheritance-ledger.md`'s intro for
why this matters beyond tidiness: re-explaining a rule from memory in a second location is
exactly how this codebase re-accumulated Mantis-era debt the first time.

**How an agent file points here.** A reference from a charter or protocol is an
instruction, not a summary: *observed pattern → look up the correct pattern in
`coding-standards/<domain>/`; if it isn't documented there, research the authority repo
under `.claude/repo-references/` and add it to the coding standards (VALIDATE gate) before
acting.* Do not restate what the standard says, narrate the framework, or embed status
snapshots in the agent file.

## Index

| Path | Scope | Status |
|---|---|---|
| `mantis-inheritance-ledger.md` | Part B: Mantis-fork-inherited anti-patterns, each with a conforming target in this directory | Seeded (3 entries) |
| `scala3/matchable-e165.md` | `Matchable`, E165, the `[T <: Matchable]` bound, and the corrected E003 fact (not `extends Actor`) | **Exemplar — full content** |
| `scala3/unchecked-annotations.md` | `@unchecked`'s three grammars (pattern-binding irrefutability / match-scrutinee suppression / type-erasure), consensus-tier Amendments 1-3 | **Full content** |
| `scala3/warning-suppression.md` | When/how to legitimately suppress a warning (`@nowarn`, scalafix/scapegoat suppression), the consensus-tier amendment | **Full content** |
| `scala3/README.md` | Scope stub for the rest of the Scala 3 domain (S1–S12, G1–G3 — pending migration from `scala3-style.md`/`scala3-given-migration.md`) | Stub |
| `pekko/actor-message-typing.md` | Sealed `Command` protocols, `messageAdapter`/union-type bridging, why `Behavior[Any]` re-matched by type is an anti-pattern | **Exemplar — full content** |
| `pekko/README.md` | Scope stub for the rest of the Pekko domain (P1–P26, TL1/TL2 — pending migration from `pekko-typed-api.md`) | Stub |
| `dependencies/README.md` | Scope stub — per-dependency conventions, supply-chain currency gate | Stub |
| `evm/README.md` | Scope stub — EVM execution-layer code-shape (opcodes, gas, interpreter; net new) | Stub |
| `consensus/README.md` | Scope stub — consensus-path code-shape (fork dispatch, validation, rewards, PoW/PoS engines; net new) | Stub |
| `consensus/mutable-state.md` | Mutable state in consensus/EVM/crypto code — the hot-path/guarded-field/constant-time sanctioned categories, everything else is a defect | **Full content** |
| `networking/README.md` | Wire-protocol/RLPx code-shape conventions (net new; no existing protocol source) | Stub, except `networking/peer-disconnect-blacklist.md` — DRAFT, pending operator ratification |
| `jsonrpc/README.md` | Scope stub — JSON-RPC/GraphQL code-shape conventions: method/handler shape, serialization-codec surface, transport wiring (net new) | Stub |
| `storage/README.md` | Scope stub — RocksDB/`DataSource` storage-layer conventions (pending migration from `storage-rocksdb.md`) | Stub |
| `testing/README.md` | Scope stub — test-code standards: spec-style, mocking, determinism, multi-client-conformance shape (net new) | Stub |
| `logging.md` | Cross-cutting: logging API, levels, message format | Stub |
| `comments.md` | Cross-cutting: default-to-no-comment policy, sanctioned exceptions | Stub |
| `nomenclature.md` | Cross-cutting: neutral ecosystem terms vs. network-family-local labels | Stub |
| `dead-code.md` | Cross-cutting: what shapes count as dead vs. never-wired (the three-verdict *process* stays in `dead-code-review.md`, a protocol) | Stub |
| `documentation.md` | Cross-cutting: what makes a durable file durable (invariants + pointers, never live counts) | Stub |

Stub files carry only scope, owning specialist, and authority repo — no rule content yet.
They exist so the directory shape is right from day one and so a follow-on migration pass
has a named destination per protocol file (see "Per-domain migration plan" below).

## Authority map

Every domain below names the vendored reference repo(s) under `.claude/repo-references/`
that ground it, so a problem in any domain already has a known place to look before
inventing an answer from memory. This is the **domain-framed** complement to
`.claude/agents/REFERENCES.md`, which is **repo-framed** (one row per repo, listing which
specialist uses it, its clone path, key sub-paths, and branch conventions) — do not
duplicate that detail here; link to it. Every repo currently vendored under
`.claude/repo-references/` is accounted for below, either mapped to a domain or explicitly
called out as not a standards authority.

| Domain | Authority repo(s) | What it grounds |
|---|---|---|
| `scala3/` | `scala3`, `scala2`, `docs.scala-lang` | Scala 3 language idiom, error codes, and migration cookbook; `scala2` as the migration-source reference for recognizing Scala 2-era patterns |
| `scala3/` (functional-style idiom) | `typelevel/cats`, `typelevel/cats-effect`, `typelevel/fs2` | FP idioms (Functor/Monad/Traverse, IO/Resource/Fiber, streaming) at the specific boundary points fukuii already uses them — not a general license to rewrite in Typelevel style |
| `scala3/` (enforcement) | `scalafix`, `scapegoat` | The lint/inspection rule catalogues that *enforce* scala3/ ratchets via `warning-ratchet.md` — not a separate style source, the mechanism that turns a ratified rule into a build gate |
| `pekko/` | `pekko`, `pekko-http`, `pekko-connectors`, `pekko-management`, `virtuslab/pekko-serialization-helper` | Typed actor API + Streams internals; HTTP/WS routing DSL; streaming-connector idiom; discovery implementations (DNS-SD/K8s/Consul); cluster-safe serialization markers |
| `storage/` | `rocksdb` | `DataSource`/`WriteBatch`/`ReadOptions`/iterator-lifecycle/cache-config Java API (mirrors `options.h` 1:1) |
| `dependencies/` | `circe`, `json4s`, `sangria` (+ any other vendored library) | Per-dependency sanctioned API surface — the concrete authorities behind this domain's "which surface of library X is sanctioned" scope |
| `evm/` | `clients/besu`, `clients/core-geth`, `clients/go-ethereum`, `clients/nethermind`, `EIPs`, `ECIPs`, `ethereum/tests`, `ethereum/yellowpaper`, `docs/research/best-practices/evm-clients/` (digested cross-client evidence) | Opcode/gas-table/interpreter code shape. `clients/reth` is **not** citable here — its EVM interpreter is the un-vendored `revm` crate, per `evm/README.md`'s existing governance note |
| `consensus/` | `clients/besu`, `clients/core-geth`, `clients/go-ethereum`, `clients/nethermind`, `clients/reth` (PoS side only), `EIPs`, `ECIPs`, `ethereum/consensus-specs`, `ethereum/tests`, `ethereum/yellowpaper` | Fork-dispatch, validation, block-reward, and hard-fork implementation shape (PoW → `forge`; PoS → `beacon`) |
| `consensus/` (`crypto/`) | `bouncycastle` (`bcgit/bc-java`) | Cryptographic primitive implementation shape for `crypto/` — grounds fukuii's `bcprov-jdk18on`/`bcpkix-jdk18on` 1.84 usage (per `project/Dependencies.scala`); `crypto/` is in scope of `consensus/`'s domain per `consensus/README.md` |
| `networking/` | `ethereum/devp2p`, `clients/besu`, `clients/core-geth`, `clients/go-ethereum`, `clients/nethermind`, `pekko` | RLPx/discovery/ETH-wire handshake, Snappy framing, and multi-client interop shape |
| `jsonrpc/` | `ethereum/execution-apis`, `pekko-http`, `sangria`, `json4s`, `circe` | JSON-RPC method/handler shape, GraphQL schema/resolver shape, serialization-codec surface, HTTP/WS/IPC transport wiring |
| `testing/` (new — see below) | `scalamock`, `hive`, ScalaTest (the framework itself — not a vendored repo) | Spec-style, mocking, determinism, and multi-client black-box conformance-test conventions |

**Not a standards authority:** `spec-kit` — the upstream Spec Kit workflow/template repo.
Grounds the `speckit-*` skills' feature-development *process*, not a coding standard; no
domain in this directory cites it.

**Repos grounding a cross-cutting file rather than a domain directory:**
- `typelevel/log4cats` → `logging.md` — the IO-safe logging abstraction over SLF4J fukuii
  actually uses (`Logger`/`SelfAwareLogger`, `asyncLog`, CE-context logging).

**Repos cited by more than one domain (listed once above per domain, not duplicated per-line):**
- `EIPs` / `ECIPs` — `evm/` and `consensus/` (spec text) and `networking/` (fork-ID / wire-
  visible fork-schedule negotiation — `herald`'s concern with the same spec repos).
- `hive` — home domain is `testing/` (it's a multi-client compliance *test orchestrator*),
  but its simulators are the live conformance authority `forge`/`beacon` actually run against
  (`simulators/ethereum/`, `simulators/eth2/`) and `herald` (`simulators/devp2p/`).

See `.claude/agents/REFERENCES.md` for the per-repo detail (clone path, key sub-paths,
branch conventions, which specialist uses it) behind every row above.

## Governance

### Conformance target (read this before implementing anything against a standard)

**Conformance target = the best-practice form the standard NAMES.** Implementations
must produce one of the named/exemplar forms in the relevant coding-standards doc.
"Lower churn," "less risk," "zero-behavior-change," "out of scope for this pass," and
"fewer files touched" are SIZING and PLANNING inputs — they are NEVER justifications to
ship a non-named hybrid, a partial form, or a shortcut. If the conformant form is large
or high-effort, that is a reason to size it, split it into properly-scoped commits, or
schedule it — not a reason to implement a lesser form as a substitute. Large-but-
conformant beats small-but-hybrid. Any deviation from a named form requires explicit
operator ratification, with the deviation and its rationale documented at the site and
in the standard; deviation is never the default and is never chosen by an agent or the
orchestrator on churn/risk grounds alone.

Note the distinction this principle draws: "zero-behavior-change" is a legitimate
*safety property* to assert about a conformant migration (the refactor preserves
observable behavior) — it is illegitimate only when used as a *reason to stop short* of
the named form (i.e., "we'll leave it non-conformant because changing further might
alter behavior" when the named form is in fact achievable safely). Same test applies to
"for this pass": scoping a large conformant change into multiple correctly-sequenced
commits is fine; scoping it into one commit that lands a hybrid and calls the rest
someone else's problem is not.

### The intake → validate → admit → use loop

Nothing enters this directory by being written once and trusted. Every standard —
however it originates — passes through the same gate before it's authoritative:

```
INTAKE                         VALIDATE                        ADMIT              USE
  new synthesis         →   accuracy + currency review   →  merge into      →  enforce
  commit-log mining              against the cited             coding-standards/    (warning-ratchet.md)
  existing-protocol            .claude/repo-references/*                        consult
  migration                    authority                                          (agents point here)
                                                                                  capture
                                (maker proposes →                                 (new candidates feed
                                 owning domain specialist                          back into intake)
                                 checker validates →
                                 operator ratifies)

                              FAILS review → logged as a "bad-protocol" finding
                              (finding-resolution.md dispositions) → corrected or
                              retired AT THE SOURCE, never admitted as-is
```

**This gate is not limited to admitting new standards — it also governs any finding's
disposition that claims something can't be fixed.** A "can't-fix" / "unfixable" /
"library-inherent" / "genuine-boundary" verdict is itself a claim requiring the same
accuracy-vs-authority check as a new standard: verify no current or typed alternative
exists (the dependency set plus the relevant `.claude/repo-references/*` authority) before
accepting the disposition — see `batch-research-protocol.md` Rule (i) for the canonical
rule text and the incident (2026-07-08, a TestKit-migration finding misclassified as
"library API, not our code") that produced it.

**Why this gate exists, concretely:** `wraith.md` carried an E165 "cleared by Wave-3-LOOM
migration" framing that was never checked against the Scala 3 language reference — E165 is
the `Matchable` warning and has nothing to do with `extends Actor`/Classic-actor migration
progress (that's tracked by a plain `grep -rn "extends Actor"`, not any compiler error
code). The mislabel survived unquestioned in a protocol doc for as long as it did precisely
because there was no accuracy-vs-authority check gating what protocol docs were allowed to
assert. `scala3/matchable-e165.md` in this directory is both the fix and the proof that the
gate works: it was checked against `.claude/repo-references/scala3/docs/_docs/reference/`
this session before being written, not authored from memory and assumed correct.

**Three intake paths, one gate:**

1. **New synthesis** — an agent or the operator writes a standard for a pattern that has no
   existing protocol-doc source (e.g. the `evm/` and `networking/` domains, which start
   from zero). Cite the reference-repo authority inline, by file and line, as the standard
   is drafted — not added after the fact.
2. **Commit-log mining** — a recurring fix pattern observed across commits (e.g. the same
   `sender()`→`replyTo` shape fixed independently three times) is proposed as a candidate
   standard. The candidate must be traceable to an authority before admission, not admitted
   on pattern-frequency alone.
3. **Existing-protocol migration** — the ratified target state for `.agents/protocols/code-
   style/*` (see "Per-domain migration plan" below). **This is review-then-admit, not
   copy.** A protocol file being migrated is not grandfathered past the gate merely because
   it has been in use — each rule's content is re-checked against its cited authority as
   part of the move. A rule that no longer matches its authority (the authority repo moved,
   or the citation was wrong to begin with) is a bad-protocol finding, corrected or retired
   at the source, and does not get carried into this directory unexamined.

**Maker/checker, always — nothing self-admits:**

- **Maker**: proposes the standard (new synthesis, mined candidate, or protocol-migration
  draft) with its authority citation.
- **Checker**: the owning domain specialist validates the citation against the actual
  vendored file — not the paraphrase, not memory of the paraphrase. Consensus-adjacent
  content (EVM/gas, hard forks) is checked by `forge` (PoW) or `beacon` (PoS); networking
  content by `herald`; storage content by `vault`; general Scala 3 idiom by `mithril` or
  `wraith`; Pekko actor idiom by `loom`.
- **Operator**: ratifies. No standard is authoritative on maker or checker say-so alone.

A citation that turns out to be inaccurate at VALIDATE time is not a paperwork problem —
it's the exact failure mode this gate exists to catch before the doc is trusted by every
agent that reads it afterward.

### Enforcement ladder

Once admitted, a standard starts **advisory** — surfaced in review (`prism`, `eye`), not a
build gate. It **ratchets to a build error only after a rule's conformance sweep hits
zero** for the current codebase — see `.agents/protocols/process/warning-ratchet.md`'s
four-step pattern (inventory → risk-stratified commit → `@nowarn` deferral, never blanket
`-Wconf` → promote to error). Admission into this directory and ratchet-to-error are two
different events; a standard can be admitted, advisory, and still have open conformance
work tracked in `.claude/sprints/QUEUE.md` for a long time before it ratchets.

### Reference repos are living upstream authorities

Every standard in this directory cites its authority as a specific file and line inside
`.claude/repo-references/*` — not a paraphrase, not "per the Pekko docs" with no pointer.
Those repos are pulled fast-forward at specialist-agent session start and can move. When an
upstream pull changes a cited source, the standard that cites it needs re-validation — this
is the same currency discipline `.agents/protocols/tooling/dependency-currency.md` applies
to build-pin versions, applied here to prose citations instead of version numbers. A
standard whose citation no longer matches the current upstream text is itself a
bad-protocol finding under the VALIDATE gate above, not a fact to silently leave stale.

### Capturing a new standard candidate

Encountered a pattern worth ratifying while doing unrelated work? One line, not a detour:
add a candidate entry (the pattern, a proposed grep-verifiable conformance check, and a
guess at the authority citation) to `.claude/sprints/QUEUE.md`'s findings-resolution log or
Chase & Deferred Items section — per `.agents/protocols/process/finding-resolution.md` and
`.agents/protocols/process/inline-cleanup.md`, every finding gets one of the three
dispositions, never left flagged-but-unscheduled. The operator ratifies candidates into a
real intake pass; do not self-admit by writing directly into this directory mid-task.

## Per-domain migration plan (ratified; not executed this pass)

The code-style content currently housed in `.agents/protocols/code-style/*` migrates into
this directory over follow-on passes, one domain at a time, each pass going through the
VALIDATE gate above. **Operational protocols are excluded** — they describe how an agent
acts (sequencing, delegation, test cadence), not what a standard says, and stay protocols
permanently: `testing-protocol.md`, `warning-ratchet.md`, `worktree-protocol.md`,
`background-script-execution.md`, `compound-command-scratch.md`, and the process-tier docs
in `.agents/protocols/process/` generally (pre-migration checklists, migration-handoff,
risk-stratified-commit, dead-code-review's three-verdict *procedure*, sprint-lifecycle).

| Source protocol file | Target in this directory | Owning specialist (checker) | Note |
|---|---|---|---|
| `code-style/scala3-style.md` (S1–S12) | `scala3/` (per-rule files, TBD split) | `mithril` (idiom), `wraith` (compile-error correctness) | `matchable-e165.md` is the first rule migrated out early, as this pass's exemplar |
| `code-style/scala3-given-migration.md` (G1–G3) | `scala3/` | `mithril` | Operational pitfalls specific to one migration wave — assess at migration time whether this is a standard (stays) or a completed-migration retrospective (retires to `.claude/sprints/log/`) |
| `code-style/pekko-typed-api.md` (P1–P26, TL1/TL2) | `pekko/` (per-rule files, TBD split) | `loom` (migration sites), `flow` (streams-adjacent rules) | `actor-message-typing.md` is the first rule migrated out early, as this pass's exemplar |
| `tooling/dependency-currency.md` | `dependencies/` | `sentinel` | `sentinel` is the sole gated path for dependency changes; this migration does not change that gate |
| `code-style/logging-standards.md` | `logging.md` | Cross-cutting — no single owning specialist; `prism` reviews | |
| `code-style/comments.md` | `comments.md` | Cross-cutting; `prism` reviews | |
| `code-style/nomenclature.md` | `nomenclature.md` | Cross-cutting; `forge`/`beacon` co-check network-family-local labels | |
| `code-style/doc-standards.md` | `documentation.md` | Cross-cutting; `warden` reviews | |
| `process/dead-code-review.md` | Partial — `dead-code.md` gets only the *style-classification* content (what shapes count as dead vs. never-wired), if any is later separable from the decision procedure | `mithril`, `prism` | The three-verdict adjudication *procedure* itself is operational and stays in `dead-code-review.md` as a protocol; do not migrate it |
| *(none yet)* | `evm/` | `forge` (PoW), `beacon` (PoS) | Net-new authorship — no existing protocol file covers this; first content likely mined from `docs/research/best-practices/evm-clients/` and the systemic-review documents |
| *(none yet)* | `networking/` | `herald` | Net-new authorship — no existing protocol file covers this |
| `storage/storage-rocksdb.md` | `storage/` | `vault` | The DataSource-contract / iterator-lifecycle / cache-config *style* content migrates; any genuinely operational recovery *procedure* stays a protocol |
| *(none yet)* | `consensus/` | `forge` (PoW), `beacon` (PoS) | Net-new authorship — `consensus-change-protocol.md` is operational routing and stays a protocol; this is consensus code *style* only |

Each row above is a full VALIDATE-gated migration pass on its own, not a mechanical file
move — see "Existing-protocol migration" in the intake section.
