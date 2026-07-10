# EVM Multi-Network Landscape → fukuii File-Tree Evolution

_Batch 5 research, 2026-07-10. Informs the Row 5.5-design step (consensus package/abstraction
reorganization). Measured against one invariant: **ETC + ETH byte-for-byte correctness stays green.**_

fukuii is a Scala 3 / Pekko-typed EVM execution client (ex-Mantis, `com.chipprbots`) supporting ETC+Mordor
(PoW) and ETH+Sepolia (PoS). We are reorganizing the file tree for a genuinely multi-network reality
(more L1s, L2s, sidechains, and — first-class — **private/permissioned PoA networks**) so the structure
evolves deliberately, not by accretion.

## The four documents

| Doc | Scope | Location | Lifespan |
|---|---|---|---|
| [`01-landscape-taxonomy.md`](01-landscape-taxonomy.md) | External EVM network/consensus landscape by the four seams | public | durable |
| [`02-private-networks.md`](02-private-networks.md) | Private/permissioned PoA/BFT capability spec (Besu/GoQuorum templates) | public | durable |
| [`03-evolution-plan.md`](03-evolution-plan.md) | Synthesis: seam abstractions, target tree, migration, risk register | public | durable |
| [`reference-client-tree-structure.md`](reference-client-tree-structure.md) | How Besu/geth/core-geth/Nethermind structure `consensus/` | public | durable |
| `00-current-state-map.md` | fukuii's CURRENT seam-encoding (the tree we're about to change) | **`.local/`** | ephemeral |

_(Location rule: durable knowledge about the outside world → public; a snapshot of fukuii internals
we're about to change → local. Litmus: "still true after the work it informs finishes?")_

## The taxonomy (used throughout — NOT a PoW/PoS binary)

A consensus mechanism decomposes into four largely-independent seams: **selection** (who may produce) ·
**scheduling** (who proposes, when) · **finality** (how irreversibility is reached) · **fork-choice**
(how the head is chosen). Emission/PoL/restaking is **economics, not consensus** — a separate concern.

## Headline recommendations

1. **The mechanism-leaf tree is confirmed** (all four reference clients agree): `consensus/{pow,pos,poa}`
   mechanism leaves + a **neutral, seam-decomposable spine** holding the abstractions + selector.
   Networks stay config/data (never packages); ETC stays a config-variant *inside* `pow/` (core-geth).
2. **Most EVM networks differ ONLY in selection + finality** — hosted by swapping a consensus *seam*,
   not new execution code. The 5.4 `ConsensusEngine` cutover **already** made the validation side of
   those seams pluggable at family granularity.
3. **The only genuinely-missing pieces are three block-PRODUCTION-side selection seams**: a
   signature-only `Sealer` (fukuii's biggest private-network gap — it verifies/mines but has no
   sign-only sealing path), a `ValidatorProvider` (authority set), and a `BlockInterface` (extraData
   codec). **Designed now, built when a Clique devnet is greenlit — NOT in Batch 5.**
4. **Minimal-viable, deferral honored:** fork-choice and finality stay single-impl (no seam) until BFT
   needs them. Nothing is built speculatively; the tree is shaped so PoA *can* land without a rewrite.
5. **Recommended first third *family*: a Clique static-signer devnet** — reuses fork-choice+finality
   unchanged, config-only genesis + bounded code, and the capability it forces (a sealer + selection
   seam) is exactly the generally-useful gap. (A network in an *existing* family is already config-only
   today.)

See [`03-evolution-plan.md`](03-evolution-plan.md) for the named Scala seams, the target tree with the
four 5.5b judgment-call recommendations, the migration sequence, and the consensus-critical risk register.
