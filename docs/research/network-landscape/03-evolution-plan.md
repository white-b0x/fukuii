# fukuii File-Tree & Abstraction Evolution Plan (Part D synthesis)

_2026-07-10. Synthesis of Part A (fukuii current-state, `.local/`), Part B (landscape taxonomy, `01`),
Part C (private/PoA, `02`), and the reference-client tree survey. This is the durable target
architecture; the ephemeral fukuii current-state snapshot lives in `.local/`. Measured against one
invariant: **ETC + ETH byte-for-byte correctness stays green at every step.**_

## 1. What the 5.4 cutover already achieved (don't re-solve)

The multi-network framework is further along than a fresh audit assumes:

- **Consensus VALIDATION is already a family-pluggable seam.** `ConsensusEngine.{id, headerValidator,
  blockGenerator, finalizeBlock}` is the per-engine "ProtocolSpec slice"; `engineFor(mining, config)`
  is the selector; `TransitionBlockHeaderValidator` is the composite (go-ethereum `beacon` / Besu
  `TransitionProtocolSchedule` analogue). Selection+finality **validation** already varies by family.
- **Group-B execution axes are first-class.** Block-number vs timestamp fork dispatch (`EvmConfig.forBlock`
  overloads) and burn-vs-Treasury fee routing (`creditBaseFeeToTreasury`) already exist — the axes Part B
  found genuinely diverge at the execution layer.
- **A network within an EXISTING family is already config-only** (proven by mordor/gorgoroth/sepolia;
  `custom-chains-dir` even allows out-of-tree). No code for another PoW or PoS chain.

So the "which-seam-differs" finding (Part B: most EVM networks differ ONLY in selection+finality) is
**already accommodated on the validation side.** The remaining work is (a) the tree not yet *encoding*
these seams, and (b) the block-PRODUCTION side of selection for a genuinely new family (PoA).

## 2. Seam abstractions — minimal-viable, named, NOW vs DEFER

The bar (fukuii's anti-over-engineering rule + Part C): *cleanly covers ETC+ETH today, extends to
private-PoA next without rewrite.* A seam with one foreseeable impl → DEFER.

**Already exist (keep, from 5.4):** `ConsensusEngine` trait (the ProtocolSpec slice), `engineFor`
(selector), `TransitionBlockHeaderValidator` (composite), `EngineId` (Ethash/EngineApi real;
Clique/Qbft/Bor reserved). These map 1:1 onto Besu's `ProtocolSpec` / controller-builder / merge-composite.

**DESIGN NOW, BUILD WHEN PoA LANDS (not in Batch 5 — no consumer yet):** these are the three Part C
gaps, all block-production-side selection. Naming + placement fixed now so the tree admits them; impls
deferred to a Clique-devnet project.

| Seam (Scala) | What it abstracts | Why not now | Home |
|---|---|---|---|
| `trait Sealer` | signature-only block sealing (sign header hash → 65-byte seal, no nonce search) — widen `ConsensusEngine.sealer` from `Option[Mining]` (PoW-typed) to `Option[Sealer]` | no non-PoW sealer consumer until Clique; the ECDSA primitive already exists in `RestrictedPoWSigner.signHeader`, just welded to mining | neutral spine (trait); `pow/` + future `poa/` impls |
| `trait ValidatorProvider` | "authorized signer set at block N" (static-genesis / vote / contract) | Besu's `ValidatorProvider`; no authority-set concept until PoA | neutral spine |
| `trait BlockInterface` | decode/encode proposer/vote/signer-list from `extraData` | `extraData` is opaque `ByteString` today; nothing needs to read it until PoA | neutral spine |

**DEFER as seams (single impl — do NOT abstract yet, per Part C G8/G9):**
- **Fork-choice** — two hard-wired impls today (`BranchResolution` heaviest-TD+MESS for ETC;
  `ForkChoiceManager` CL-driven for ETH). Clique reuses heaviest-chain unchanged → no third impl → no
  seam. Abstract only if/when a network needs a genuinely new rule.
- **Finality** — implicit today; only BFT (QBFT/IBFT) forces an explicit gadget. Defer with the BFT
  round machine (`EngineId.Qbft` already reserves the slot).

**The master switch (address deliberately, don't over-abstract):** `NetworkType` is a hard 2-value enum
(`BlockchainConfig.scala:31`, ETC/ETH) and every family branch fans out from it (`engineIdFor`,
`creditBaseFeeToTreasury`, `isEth`). A third family = a new enum case + a new arm at each compiled
match. This is acceptable *as the deliberate extension point* (adding a case forces the compiler to
surface every arm needing attention — a feature, not only debt), but Part D flags it as the single
highest-fan-out touch point; a future family should audit all `NetworkType` matches as its checklist.

## 3. Target tree — CONFIRMS the 5.5b proposal, with one refinement

Parts B + C + the reference-client survey **confirm** the mechanism-leaf tree (`consensus/{pow,pos,poa}`
+ neutral spine), and sharpen it: **the neutral spine holds the seam ABSTRACTIONS + selector; the
mechanism leaves hold the family IMPLS.** Networks stay config/data (core-geth precedent). This is
Besu's four-layer shape.

```
consensus/
  ConsensusEngine.scala        (neutral: EngineId, trait, engineFor selector)
  Sealer / ValidatorProvider / BlockInterface   (neutral seam traits — designed now, impls later)
  TransitionBlockHeaderValidator.scala          (neutral composite)
  ValidatorsExecutor.scala                       (neutral, TTD-aware selector)
  validators/{,std}/  mining/  blocks/  eip1559/ (neutral)
  pow/   EthashEngine impl + Ethash/ECIP + (future) a PoW Sealer impl
  pos/   EngineApiEngine impl + EngineApi/ForkChoice/PoSBlockHeaderValidator   (was engine/)
  poa/   (created WHEN Clique is built — EngineId.Clique already reserved; no empty dir now)
  pow/mess/  (ECIP-1100 fork-choice policy — PoW/ETC-only)
forkid/ forks/  (framework-layer, unchanged)
```

**This resolves the 4 open 5.5b judgment calls (recommendations for the 5.5-design step):**
1. **`ConsensusEngine.scala` split vs whole-file → LEAN SPLIT.** The seam model wants family impls in
   their leaf: neutral `EngineId`+trait+`engineFor`+seam-traits stay in the spine; `EthashEngine` →
   `pow/`, `EngineApiEngine` → `pos/`. (forge+beacon confirm at 5.5-design — it touches both domains.)
2. **`engine/` → `pos/` rename → YES** (Besu's `merge`/PoS-module analogue). Coordinate Batch 6 B2's
   kickoff to reference `pos/`.
3. **`mess/` → `pow/mess/`** (ECIP-1100 is PoW/ETC-only fork-choice policy; banksy-owned, forge-cosign).
4. **`poa/` → do NOT create an empty dir now.** `EngineId.Clique/Qbft/Bor` already reserve the seam;
   create `poa/` with real content when a Clique devnet is greenlit. (Anti-over-engineering: no empty
   scaffolding.)

## 4. Migration path — scope line

**Batch 5 (Row 5.5) does STRUCTURE, not PoA implementation.** The tree reorg + confirming the seam
shape is the deliverable; it makes PoA *possible to slot in*, per the operator's "build so
implementation is a possibility." Building the G1–G3 seams belongs to a **future Clique-devnet
project**, not Batch 5 (no consumer yet → premature).

Ordered, each step keeps ETC+ETH green:
1. **(Batch 5, Phase B) 5.5b pure-move reorg** — mechanism leaves + neutral spine (the tree above).
   Pure relocation, byte-identity gate. Designs (names, not impls) the three neutral seam-trait homes.
2. **(future) Sealer widening** — `ConsensusEngine.sealer: Option[Mining]` → `Option[Sealer]`; extract
   the ECDSA-seal primitive out of `RestrictedPoWSigner` into a neutral `Sealer`; PoW mining implements
   it. Byte-identical for ETC/ETH (they don't use a signature-only sealer).
3. **(future) ValidatorProvider + BlockInterface** — introduced together with the first Clique impl.
4. **(future) Clique static-signer devnet** — the recommended first third *family* (Part C): a new
   `NetworkType` case + `EngineId.Clique` impl composing Sealer+ValidatorProvider+BlockInterface, PoA
   header ruleset, thin scheduler. Config-only genesis + bounded code. This is the "add a family"
   proof; a network within an existing family is already config-only today.

## 5. Risk register — consensus-critical touch points (each protected by the ETC+ETH invariant)

| Touch point | Risk | Protection |
|---|---|---|
| `NetworkType` enum expansion | new arm silently mis-handled at a fan-out site | compiler exhaustiveness on every `match` (make them non-`@nowarn`); ETC/ETH byte-identity harness unchanged |
| `sealer: Option[Mining]` → `Option[Sealer]` | widening changes PoW/PoS sealing | PoW mining wraps the same primitive; ETC/ETH produce identical seals; `FinalizeCutoverByteIdentitySpec`-style proof |
| `extraData` `BlockInterface` codec | mis-encode → header RLP / fork-id hash drift | ETC/ETH `extraData` stays opaque pass-through; ForkId golden-hash harness (Row 5.8) unchanged |
| 5.5b file moves | import cascade breaks a consumer | pure-move byte-identity gate: compile-all + re-run harnesses from new locations (per `fukuii-tree-classification.md`) |
| `engineFor` third-family arm | wrong engine at a boundary | family-resolution spec extended per new family; ETC/ETH resolution untouched |

## Headline

The 5.4 cutover already made selection+finality **validation** pluggable; Batch 5's 5.5b gets the
**tree** to encode it (mechanism leaves + neutral seam-decomposable spine, confirmed by all four
reference clients). The only genuinely-missing pieces are three block-PRODUCTION-side selection seams
(Sealer, ValidatorProvider, BlockInterface) — **designed now, built when a Clique devnet is greenlit**,
not in Batch 5. Nothing here is built speculatively; the tree is shaped so PoA *can* land without a
rewrite, which is the whole point.
