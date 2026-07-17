# RX — per-item reference-client verification (Wave 5)

_The **depth** pass. Where the Systemic Review and the Wave-1→4 review sequence swept **subsystems**
(breadth — many concerns held in view at once), RX comes back **item by item** and checks each specific
implementation decision against the **actual vendored reference-client source**, not the research-doc
abstraction. The item is the focal point, not the subsystem._

## Why this exists (the two-axis argument)

The prior waves catch **structural** error (a missing module, a double-defined ADT, an upward DAG
edge). They cannot reliably catch **semantic** error — the case where our *understanding* of one
specific mechanism is wrong because it was formed during a broad sweep with twenty things in view. A
subsystem sweep pattern-matches "besu does X here" and moves on; the misread is inherited by the
research doc, then by the plan.

RX is the antidote and it is **the operator's documented failure mode's direct guard**
([[research-into-cohesive-plan-before-building]]): the recurring failure is *building from an
inaccurate abstraction* (F-BN-1 chain-split, F-RLP-1, the plugin-api miss). RX converts every plan
item from "grounded in the research doc" to "grounded in verified, byte-cited reference-client source."
**The per-item evidence entry becomes the implementation spec** — at build time you transcribe verified
evidence, you do not re-derive.

**This is a gate.** A `plan/*.md` is not `READY FOR IMPLEMENTATION` until every one of its registry
items has an RX entry with a verdict, and every `CORRECTS` verdict has been applied back into the plan.

## The evidence base — read source, not abstractions

The six external reference clients are vendored under
`/media/dev/2tb/dev/fukuii/.claude/repo-references/clients/`:

| Client | Role in RX | Notes |
|---|---|---|
| **go-ethereum** | ETH-family byte-authority for shared EVM/RLP/RPC/Engine-API | the reference the others track |
| **core-geth** | **ETC-frozen byte-authority — Go** (ECIP-1017/1099/1100/1010/1041), PoW head | FROZEN, still-living; **one of two independent ETC authorities** (see caveat) |
| **besu** (`clients/besu`, on `upstream`) | **THE JVM implementation guide** — read its Java alongside geth's Go, always | vanilla besu; our stack's constraints; structural mirror for shared behavior |
| **besu-etc** (`clients/besu-etc`) | **ETC-frozen byte-authority — JVM.** besu's OWN historical ETC implementation, checked out one commit before besu removed ETC (`upstream` `1167c5a544^` = `eb4248c997`): `ClassicProtocolSpecs.java`, `ClassicBlockProcessor.java` (ECIP-1017), `ClassicDifficultyCalculators.java` (ECIP-1010/1041), `classic.json` | **The independent JVM cross-check to core-geth's Go** — and JVM-native, so *closest to fukuii's stack*. Use for EVERY frozen-ETC item. |
| **nethermind** | plugin/extensibility, per-module pools, HalfPath storage | .NET, structural cross-check |
| **erigon** | staged/flat-state, StateChanges/remote-KV, MCP, Bor packaging | perf + product-family shapes |
| **reth** | modularity/SDK, ExEx, ForkCondition, NodeTypes, nibble-path | Rust; port the *idea*, not the crate model |

The **7th** is **fukuii's own `july-fourth`** — the `clients/fukuii/*.md` SR snapshots + the reference tree on
branch `july-fourth`. Every RX item states what fukuii *currently* does so the plan's "improvement over
`july-fourth`" claim is itself verified.

**Two distinct besu things — do not confuse them.** (1) `clients/besu` on `upstream` = **vanilla besu**
(the JVM implementation guide for *shared* behavior). (2) `clients/besu-etc` = **besu-the-project's own
historical ETC** (the independent frozen-value JVM authority). Separately, the besu repo's `main` branch
is **the operator's ETC-overlay / olympia-besu** — that is **fukuii's OWN** Olympia-draft implementation
(a useful reference for the *draft* ECIP-1111/1112/1121/1122 items — e.g. it already implements
"credit treasury before miner per ECIP-1111 ordering" — accessible via `git -C clients/besu show
main:<path>` / `git log main`), but it is **NOT an independent authority** for frozen values (it is us).

**RX reads the actual source files** (grep + read under the vendored trees), cites `client/path:line`,
and quotes the operative code. A path the plan cites that is **not present in the vendored tree is
itself an RX finding** (the plan referenced something we cannot verify).

## The "all clients" rule — and the ETC "absence is evidence" caveat

Per the operator's intent, each item gets **a look from every client that has the topic** — even a
non-authority client's take is a data point that either corroborates or contradicts.

- **Shared behavior** (EVM, RLP, RPC, Engine-API, sync wire, storage): read **all six**. The
  per-concern **byte-authority** (from `REVIEW.md §3` / each layer's §3) still decides *which* client's
  values are canonical; the other five are the cross-check that we read the authority correctly.
- **ETC-frozen values** (ECIP-1017/1099/1100/1010/1041, PoW head, MESS): there are **TWO independent
  authorities — verify against both, never core-geth alone**: (1) **core-geth** (Go, still-living
  ECIP authority) and (2) **besu-etc** (`clients/besu-etc` — besu-the-project's OWN historical JVM ETC
  implementation, pre-removal). Cross-checking a Go and a JVM implementation of the same frozen value is
  a far stronger check than one source — and besu-etc, being JVM, is the *directly-applicable* reference
  for how fukuii should implement it. **If the two agree → strong CONFIRMS; if they diverge → a finding**
  (name which is right and why: core-geth is the living spec authority, but a besu-etc JVM divergence may
  reveal a real implementation subtlety fukuii must handle). The **other four** clients (go-ethereum,
  nethermind, erigon, reth) never carried / removed ETC — **their absence is the positive evidence**
  that core-geth + besu-etc are the authorities, and it flags any place the plan wrongly cross-references
  one of those four for an ETC value. Never reproduce an ETC frozen value from geth/reth/erigon/nethermind
  or from vanilla `clients/besu` (`upstream` — ETC already removed).

## Item registry — what gets verified

Every item the plan commits to implement. The registry is **already written** — RX does not invent it:

1. **`optimizations.md`** — every ~90 disposition rows (DEFAULT / STRUCTURAL / OPTIONAL / OBSOLETE),
   per layer + cross-cutting.
2. **Per-layer seams & decisions** — each `L{n}.md` §2 (SR verdicts), §4 (besu structural mirror), §5
   (Scala-3 idiom targets), §6 (improvements over `july-fourth`), §7 (deferrals landing here).
3. **`feature-ledger.md`** — F1–F13.
4. **The R1–R11 downward constraints** each layer claims to satisfy (`requirements.md` matrix cells).

## Risk tiers — depth of read (every item gets an entry; the tier sets how deep)

| Tier | Scope | Depth of read |
|---|---|---|
| **A** | Consensus-critical (all L4/L5 reward/fork/seal/state/gas items; ETC ECIP values) **+** the cross-layer STRUCTURAL spine (R7 `ChainNotification`, `NetworkFamily` depth, `StorageProfile`/`INodeStorage`, `ForkActivation`, the R11 auth gate, `ExecutionEngine` verb-pair, the prune-barrier, the `chain` facade) | **Full** multi-client deep-read; byte-cited quotes of the operative code in every client that has it; forge/beacon co-sign for consensus items |
| **B** | DEFAULT non-consensus items | The **byte-authority** client read in full + **≥2** cross-check clients; cite the authority, note where cross-checks agree/differ |
| **C** | OPTIONAL(role) seams, OBSOLETE (named-and-avoided) items | Confirm the seam/pattern **exists in ≥1 client** and that the disposition (defer / avoid) is safe; a shorter entry |

## The four questions (the RX standard) — every item answers all four

RX is not just "does the source match what we wrote" (accuracy). It is a **decision review**: for each
item, the byte-cited source is the evidence used to answer four questions explicitly. An item is not
RX-complete until all four are answered.

1. **Is this the appropriate decision?** — Given what the source actually shows, is the plan's
   *disposition* (DEFAULT adopt / STRUCTURAL seam-now / OPTIONAL(role) / OBSOLETE avoid) the right call?
   Not "did we read it right" but "is doing this the right thing." (E.g. an item marked DEFAULT that the
   evidence shows only one niche client does, and for a reason that doesn't apply to fukuii, may be the
   *wrong* decision even if we read that client correctly.)
2. **Is this what we should be implementing?** — Is this the right *thing to build* — versus a better
   alternative the multi-client read reveals, versus **nothing** (YAGNI / the seam suffices / a simpler
   mechanism achieves the goal)? The all-clients look exists precisely to surface the better option a
   single-authority read would miss.
3. **Is our understanding of the implementation *and its blast radius* correct?** — Two parts: (a) the
   **mechanism** — do we understand how it actually works (the byte-cited source is the check); and (b)
   the **blast radius** — do we correctly understand *everything it touches*: which other layers/items/
   consensus paths it ripples into, what depends on it, what breaks or must change if we implement it.
   An item can be individually accurate yet have an under-estimated blast radius — that is a finding.
4. **If any of the above is "no": what is the correct answer, and *why*?** — State the corrected
   decision / mechanism / blast-radius, with the source-grounded rationale. Never just "this is wrong" —
   always "…and here is the right answer, because the source shows X."

## Spec malleability — draft (fukuii-owned) vs finalized (fixed authority)

Which specification an item implements changes what a `CORRECTS` is *allowed to touch*:

- **Finalized specs — verify conformance only; the spec is fixed authority.** ECIP-1017 / 1099 / 1100 /
  1010 / 1041 (finalized ETC), every ETH EIP, and the EVM / RLP / crypto rules are **locked**. RX
  verifies our *implementation plan* conforms byte-exactly; a `CORRECTS` fixes the **plan** to match the
  spec, **never the spec**. The spec is the authority (core-geth for frozen ETC; go-ethereum for ETH).

- **Draft specs — the design itself is in scope; a `CORRECTS` may propose a SPEC edit.** The **Olympia
  ECIPs — ECIP-1111 (base-fee floor / treasury redirect), ECIP-1112, ECIP-1121 (Olympia opcodes/gas),
  ECIP-1122 (MIN_MINER_TIP / the 1-gwei miner-tip floor)** — are **fukuii's own forward work and are
  DRAFT, not locked**. The most-current drafts live at **`/media/dev/2tb/dev/ECIPs/_specs/`** (NOT the
  vendored `.claude/repo-references/ECIPs/` copy — memory `olympia-ecip-specs-source`); core-geth
  `config_classic.go` / `config_mordor.go` is the fukuii-`main`-overlay reference impl. For these, RX's
  four questions extend to the **spec design itself**: Q1/Q2 ask not only "does our plan match the draft"
  but "**is the draft's design the appropriate one, aligned to best practices**" — specifically the
  **base-fee redirect *ordering*** (treasury-credit vs miner-reward sequence), the **1-gwei miner-tip
  floor**, and the **base-fee floor** and how they compose. Where the draft is suboptimal against how the
  reference clients implement the analogous mechanism (EIP-1559 base-fee/tip settlement + ordering in
  go-ethereum `consensus/misc/eip1559` + `core/state_transition.go`, besu London), a `CORRECTS`
  **proposes a spec edit** (source-grounded, with the why), not merely a plan edit.

  **But draft ≠ unilateral.** These ECIPs are still consensus-critical (state-root-affecting once
  activated) and the spec is an **operator-owned** decision. A proposed spec alignment is **flagged for
  forge (base-fee/treasury economics — ECIP-1111) / banksy (tip floor / gas-target — ECIP-1122) /
  operator** — RX surfaces "the draft could be better; here is the source-grounded proposal and why," it
  does **not** rewrite the draft or apply a spec change without that sign-off.

## Verdict taxonomy — what each triggers

Each item's RX entry ends in exactly one verdict, derived from the four questions:

- **CONFIRMS** — all four answered "yes": the decision is appropriate, it *is* what we should build, and
  our understanding + blast radius are correct. No plan change. (Cite the source that confirms it.)
- **CORRECTS** — Q1, Q2, or Q3 is "no": the decision is wrong (should be a different disposition, a
  different mechanism, or not built at all), or the mechanism/blast-radius understanding is wrong. **Q4
  gives the corrected answer + why.** A plan edit is required and applied back into the owning
  `L{n}.md` / `optimizations.md` before READY; a structural correction (seam/ownership/DAG/blast-radius
  that crosses layers) re-enters a Wave-3 coherence mini-check.
- **SHARPENS** — the decision is right and it *is* what we should build (Q1/Q2 yes), but Q3 reveals a
  load-bearing detail the plan omits — a mechanism detail (off-by-one, ordering, a bound, a guard) **or
  an under-stated blast-radius edge** (a layer/item it also touches that the plan didn't name). **The
  detail is folded into the plan** (the layer's §6/§7, a DoD vector, or the relevant seam's
  blast-radius note) so it isn't rediscovered at build time.

## Evidence-entry format (per item)

```
### RX-NN · <item> · Tier <A|B|C> · owner-layer L<n>
- **Plan claim / disposition:** <what the plan says + DEFAULT/STRUCTURAL/OPTIONAL/OBSOLETE>
- **fukuii `july-fourth`:** <what fukuii does today (clients/fukuii snapshot / july-fourth)>
- **Reference source (byte-cited):**
  - go-ethereum `path:line` — <quote / operative behavior>
  - besu `path:line` — <quote> (JVM-implementation lens)
  - core-geth `path:line` — <quote> (ETC-frozen where applicable; or "absent — removed ETC" as evidence)
  - <other clients that have the topic>
- **Q1 appropriate decision?** <yes/no + why, vs the evidence>
- **Q2 what we should implement?** <yes/no + why — vs a better alternative the multi-client read shows, or YAGNI>
- **Q3 understanding + blast radius correct?** <mechanism: yes/no; blast radius: the layers/items/consensus paths it touches + what depends on it — and whether the plan states them>
- **Q4 if any "no": the correct answer + why** <source-grounded>
- **Verdict:** CONFIRMS | CORRECTS | SHARPENS
- **Plan edit (if CORRECTS/SHARPENS):** <the exact change + where it lands>
```

## Output & identifiers

- Per-layer evidence docs: `plan/rx/L{n}.md` (+ `rx/setup.md`, `rx/cross-cutting.md`). Each holds that
  layer's item entries.
- Items are numbered **RX-NN** within their layer doc (e.g. `RX-L5-07`). This is the "RX series."
- This `README.md` is the index + method; `_rollup.md` (written at the end) lists every `CORRECTS`/
  `SHARPENS` and confirms each was applied — the gate evidence for READY.

## Exit criteria (the READY gate)

A layer is RX-complete when: (1) every registry item has an RX entry that **explicitly answers all four
questions** (appropriate decision? what we should build? understanding + blast radius correct? if not,
the correct answer + why) and ends in a verdict; (2) every `CORRECTS` is applied back into the plan and
any structural / cross-layer-blast-radius correction re-passed Wave-3; (3) every `SHARPENS` detail
(mechanism or blast-radius edge) is folded in. Only then does that `L{n}.md` earn `READY FOR
IMPLEMENTATION`. The whole plan is READY when all layers are.
