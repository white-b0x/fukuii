# Coherence pass 05 — full-plan re-audit (proper scope)

_The complete `coherence-pass-01`-style cross-layer re-audit the session called for — not the
session-delta subset (`coherence-pass-04`), the **whole plan**. Run 2026-07-15. Method: a deterministic
cross-reference-integrity sweep (all doc-links, finding-IDs, `§`-refs) + six read-only through-line
reviewers (the pass-01 six, each extended to integrate the session's new invariants) verifying every
cross-layer contract is declared at **both** ends, refs resolve, no contradiction, coverage complete.
This is the durable "found **and** fixed/scheduled" record._

## Headline

**The plan is coherent. The skeleton is clean; the prior four passes hold; the residual was a tight,
mostly-already-known set — no new structural breakage.** The mechanical sweep found the plan's own
research-grounding links were largely **broken** (wrong relative depth) — a real cohesion defect, now
fixed. The six through-lines confirmed the DAG, the R7 spine, NetworkFamily/pivot/TD, R2 isolation,
storage/SNAP, and security are consistently wired, surfacing ~13 residual gaps — **all applied inline or
scheduled**, plus one contradiction the pass caught that **this session had introduced** (MESS ownership).

## Mechanical cross-reference integrity (deterministic sweep) — FIXED

- **~20 broken research-grounding links** across the layer/record docs: the "grounded in `observations/…`"
  links used `../../research/` (resolves to `docs/architecture/research/`, nonexistent) instead of
  `../../../research/`, and the old-fukuii `july-fourth` links pointed at `docs/research/clients/fukuii/` when that
  research lives in `.local/docs/research/clients/fukuii/`. **All recomputed with `realpath` and fixed;**
  the sweep now reports **zero broken links**.
- **1 dangling finding-ref `RX-L2-30`** (referenced, never defined — L2's RX tops at RX-L2-28): reworded to
  the real disposition (deferred, §7). Zero dangling finding-IDs now.
- **Skeleton (Thread A):** module DAG **acyclic + down-only ✓**; every `.dependsOn` points to a real lower
  module; **R1–R11 every assigned matrix cell covered ✓**; **F1–F13 all homed with a disposition ✓**; all
  31 cross-doc `§`-refs resolve within each doc's section maxima **✓**; all five session additions
  (pivot-policy, bootstrap-source, TD-sourcing, chain-weight CF, anti-spoof) reachable/non-orphaned **✓**.

## Through-line residuals + disposition

| ID | Sev | Finding | Disposition |
|---|---|---|---|
| **F-4** | MED | L5 §6.4 said "forge owns … MESS ends" — contradicts banksy-owned/forge-cosigned everywhere else (a contradiction **this session introduced** via the TD-sourcing hand-edit) | **FIXED** (L5 §6.4 reworded: seal-validation=forge; MESS=banksy/forge-cosigned) |
| **A-1/C-5** | MED | txpool consumes L5 `ChainNotification` but its DAG deps omitted `consensus-api` (L8-F1, long-open) | **FIXED** (L8 §1 + §"Layer boundaries": `consensus-api` down-edge declared, inversion clarified) |
| **E-3** | MED | SNAP frontier-journal CFs + WAL-durability contract specified at L7 §6.8.1, silent in L2 (L2-F1/F2) | **FIXED** (L2 §5 declares the CFs; §5/§8 add the WAL-at-frontier-checkpoint contract + DoD) |
| **F-1** | HIGH | L9 constant-time was **JWT-only** in prose; MCP-token + external-signer compares uncovered (L9-D1; requirements.md says all three) | **FIXED** (L9 §5 generalized to every auth-gate secret/token/tag) |
| **F-3** | HIGH (custody) | L8 keystore had KAT-only crypto gate; encryption-side salt/IV-uniqueness test absent (L8-D1) | **FIXED** (L8 keystore tests + DoD: N-encryptions salt+IV uniqueness; the only gate, no SAST net) |
| **C-6** | HIGH→MED | state-diff payload: L4 §4 "settled" vs L9 "OPEN" (WB-R2, still open) | **FIXED (interim)** L4 §4 marked provisional + storage-agnostic/version-less-additive caveat; the joint L4/L5/L9 WB-R2 review remains the real fix |
| **B-1** | MED-LOW | L9 GraphQL `Config.config` isolation home asserted at L10, not committed at L9 (WB-R4(1)) | **FIXED** (L9 §5: injected per-`ChainInstance` `InstanceConfig` at the GraphQL boundary) |
| **D-2** | low-med | L5 §7 still listed "the two overloads deleted" as an L5 deliverable — contradicts L3 ownership | **FIXED** (L5 §7 reworded to L5's actual part; the collapse is L3's) |
| **F-2** | MED | constant-time enforcement lint promised (L0-F1) but unbuilt + not homed as a repo-wide CI gate | **FIXED (plan)** setup.md CI ratchet list now includes the constant-time lint as the single repo-wide home (build it at L0/L8/L9) |
| **D-4** | low | TD-sourcing names "L1 `difficulty`" but L1.md was silent (one-directional) | **FIXED** (L1 §3 breadcrumb: `difficulty` is permanent/consensus-critical, the TD-sourcing root) |
| **D-1b** | low | "registry" double-attributed to L5 + L10 | **FIXED (L5 side)** L5 §1 tightened to "definition; L10 wires live"; L10 §7 note **recorded** (LOW) |
| **F-5** | low | L5 §9 lacked a pointer to the §6.4 anti-spoof invariant (parallel to the pass-04 L6 §9 fix) | **FIXED** (L5 §9 pointer added) |
| E-1b, F-1b, A-2, D-5b | low/cosmetic | L7 doesn't name the `INodeStorage` path-keyed dep for SNAP heal; R11 matrix cell narrower than R11 prose; some `R#`/`F#` greppability tags absent; requirements.md R1 L6 row coarser than L6's dual-capability | **RECORDED** (LOW — seed the owning layer's build-time §10 register; not applied) |

**Holding (verified consistent, no change):** the R7 spine ADT/producer/transport/resume/prune (C-1..C-4);
NetworkFamily single-home + F11 depth + fork-dispatch + dual-capability + L10 instantiation (D-1/D-3/D-5/
D-6); R2 per-instance state + G-NL1 homing (except B-1) + the isolation grep gate; StorageProfile↔role,
prune barrier, bootstrap-source/era1 split, chain-weight CF (E-1/E-2/E-4/E-5); auth-gate unification,
constant-time carve-out, anti-spoof mechanics (F items). All prior `coherence-pass-01..04` resolutions
re-confirmed to hold in current prose.

## Record-doc reorganization (operator-requested)

The as-built record docs were loose at the `fukuii-rebuild/` root, mixing with the README, research-index,
and `plan/`. Moved into **`implementation-reports/`** (operator's chosen name): `00-repo-setup.md`,
`01-L0-foundation.md`, `02-L1-domain.md`, `L0-review.md`. All inbound
references rewritten (the plan docs' `../NN-*.md` "as-built record lands at" convention, the README index,
`AGENTS.md`'s record-doc-location line), the moved docs' own research links recomputed for the new depth,
and the README given a three-part-structure intro (`plan/` = intent · `implementation-reports/` = as-built
· `research-index.md` = asset map). Broken-link sweep after the move: **zero.** The root now holds only
`README.md`, `research-index.md`, `plan/`, and `implementation-reports/`.

## Verdict

**The plan is internally coherent and navigable.** DAG down-only + acyclic, full R/F coverage, all
cross-references resolve, no surviving contradiction (the one this session introduced is fixed), and the
research-grounding links — previously broken — now resolve. The remaining open *design* items are the
already-scheduled scoped reviews (WB-R1 scalanet, WB-R2 state-diff payload, WB-R3 reorg driver, WB-R4 L10)
and the LOW build-time-register items above — none is a wiring incoherence. This is doc-level cohesion;
the code-level wiring is proven at each layer's build gate.
