# Nomenclature Protocol

**Scope:** how agents name networks, features, forks, and framework-level abstractions in
fukuii — code symbols, protocol docs, commit messages, and QUEUE.md prose alike. This is a
naming-convention doc, like `comments.md` and `logging-standards.md`, not a Scala-language
idiom ratchet like `scala3-style.md`'s S-series.

**Origin:** operator design decision, 2026-07-07, generalizing the discipline already forced
onto code symbols by the `PARITY-02`/`BEACON-CLZ-01` finding (`.claude/sprints/QUEUE.md`,
`hardfork-implementation-checklist.md`) up one layer, to the vocabulary agents choose before
any symbol is even written.

Used by: ALL agents naming networks, features, forks, or shared abstractions
Referenced by: forge.md, beacon.md, banksy.md, mithril.md, scala3-style.md, consensus-change-protocol.md

---

## Core principle

Consume the EVM ecosystem's established nomenclature; do not reinvent verbiage. Align names
with the reference clients (`.claude/repo-references/clients/{nethermind,erigon,besu,...}`,
core-geth, reth) and the specs (EIPs/ECIPs). Reusing the ecosystem's identifiers is free
interoperability with every tool, doc, and engineer that already speaks that vocabulary —
inventing a parallel identifier for something that already has one is pure cost with no
benefit.

## Identity comes from existing registries — don't invent parallel identifiers

| Concept | Canonical identifier | Source |
|---------|----------------------|--------|
| Network | **Chain ID** (EIP-155): 61=ETC, 63=Mordor, 1=ETH, 11155111=Sepolia, 137=Polygon, 56=BSC | The unique, ecosystem-wide network identifier — always prefer it over an ad hoc network label |
| Feature / behavior change | **Improvement-proposal number**: `EIP-NNNN`, `ECIP-NNNN`, and per-family series (e.g. `BEP-NNNN` for BSC) | fukuii's `ProposalId = Eip(n) \| Ecip(n) \| Custom(family, n)` mirrors this directly |
| Consensus engine | Named per spec: `Ethash`, `ETChash` (ECIP-1099), `EngineApi`, `Bor`, `Parlia`, `Snowman` | The spec that defines it |

If you're about to name something and an ecosystem-standard identifier already exists for
it, use that identifier — don't paraphrase it, abbreviate it, or give it a fukuii-local alias.

## The two-tier vocabulary (the load-bearing rule)

Every name in this codebase falls into one of two tiers. Getting this distinction right is
the entire point of this protocol.

### Tier 1 — neutral/conceptual ecosystem terms (framework-level vocabulary)

Network-agnostic terms: `PoW`, `PoS`, chain ID, `EIP-NNNN`/`ECIP-NNNN`, gas, base fee,
opcode, precompile, total difficulty. These describe a *concept*, not one network's history.
**Use these at the shared/framework level** — anything that spans or could span more than one
network family.

### Tier 2 — network-specific fork/event names (family-local instance labels only)

Names that belong to one network's actual history: "the Merge", pre-merge/post-merge, and
per-family fork names — `London`, `Paris`, `Shanghai`, `Cancun`, `Osaka` (ETH), `Olympia`
(ETC). These are **family-local instance labels only** — they name *that family's* release of
a proposal, never a shared/framework-level abstraction.

**The rule:** at the shared level, name a capability by its neutral proposal ID (`EIP-1559`),
never by one network's fork name for it (`London`) or one network's event name for it
("the Merge"). Each family maps the proposal onto its own release — that mapping lives in the
family's own fork-dispatch config, not in the shared abstraction's name.

### Corollary — this is the anti-conflation ratchet one layer up

`scala3-style.md`'s established discipline forbids `Eth*`/`Etc*` symbol sharing at the code
level — no shared opcode bundle, no shared fee schedule, no shared fork-dispatch object that
one network's edit could silently mutate for the other (`consensus-change-protocol.md`'s
hard-stop routing exists partly to catch exactly this). This protocol is the same discipline
applied to the vocabulary layer, *before* a symbol even gets written: a network-event name used
as if it were generic is the identical failure mode as a shared opcode bundle — it invites the
next person (or agent) to treat a one-family concept as if it applied everywhere.

**The concrete, already-diagnosed instance of this failure** (`PARITY-02`,
`.claude/sprints/QUEUE.md`): `vm/OpCode.scala`/`vm/EvmConfig.scala` name ETC's real
block-number-dispatch Olympia opcode list `EtcOlympiaOpCodes` (correctly family-scoped), but
ETH's timestamp-dispatch path reuses the unprefixed `OlympiaOpCodes` — ETC's own fork name —
for its Cancun opcode list, and `OsakaOpCodes` is a bare alias (`= OlympiaOpCodes`), not an
independent definition. A future ECIP landing in ETC's actual Olympia fork could silently
mutate ETH's Cancun/Osaka opcode set through that shared name. Vocabulary discipline (this
doc) is what should have prevented the name from ever being chosen; the code-symbol de-alias
(`PARITY-02`'s Tier A, forge+beacon gated) is the repair once it already happened. Don't wait
for the repair — get the name right the first time.

## Watch-list + preferred substitutions

| Seen as (generic/framework-level use) | Use instead |
|----------------------------------------|-------------|
| `Merge` / `preMerge` / `postMerge` (as a general consensus-transition concept) | `PoW` / `PoS` (or "consensus transition") |
| `London` (as the base-fee-era concept) | `EIP-1559` |
| `Paris`/`Shanghai`/`Cancun`/`Osaka`/`Olympia` naming a *shared* abstraction | Keep these — they're correct — but only as **family-local** labels (e.g. ETH's own `OsakaOpCodes`, ETC's own `EtcOlympiaOpCodes`); never let one stand in for the other or for a framework-level concept |
| generic "beacon chain" (as a code/domain-naming choice) | "consensus layer" / "PoS engine" |

**Note on the `beacon` agent name:** the `beacon` subagent (`.claude/agents/beacon.md`) is
deliberately, permanently ETH-PoS-scoped — that name is fine and out of scope for this rule.
This protocol governs code/domain/doc naming, not the fixed roster of specialist agent names.

## What this doc does not cover

- Which specialist owns a given file or change — that's `consensus-change-protocol.md`'s
  routing table.
- Scala-language idiom (opaque types, given/using, extension methods) — that's
  `scala3-style.md`.
- Comment content and when a `//` is warranted — that's `comments.md`.

## Grep-verifiable ratchet — not currently feasible

Unlike `scala3-style.md`'s S-series or `logging-standards.md`'s ratchet targets, this
protocol does **not** currently have a mechanical `0 hits` check, and one isn't trivial to
build:

- Tier-2 fork names (`Osaka`, `Olympia`, `Cancun`, ...) are **correct and expected** to appear
  in family-local symbols (`OsakaOpCodes`, `EtcOlympiaOpCodes`) — a bare textual grep for
  those words can't distinguish a legitimate family-local label from a leaked shared
  abstraction. The violation is structural (does this symbol's *definition* live in a
  framework-shared path and get read by both families?), not lexical.
- `Merge`/`pre-merge`/`post-merge` are closer to grep-viable (fukuii has no legitimate reason
  for these to appear as a framework abstraction name), but still require reading each hit to
  confirm it isn't correctly describing ETH's actual historical event in an ETH-scoped
  comment or doc.

A quick sanity check —
`grep -rn "PreMerge\|PostMerge\|preMerge\|postMerge" src/main --include="*.scala"` — should
return 0 hits; treat any hit as a signal to investigate rather than expected noise, and
re-run it yourself rather than trusting a cached result here. A full source-code nomenclature
sweep against this protocol is separately scheduled (`.claude/sprints/QUEUE.md`); don't
duplicate it ad hoc. If that sweep produces a narrower, genuinely lexical pattern (e.g. a
specific banned identifier), promote it to a grep target here rather than leaving it
undiscoverable.
