# Coherence pass 04 — session-delta wiring verification

_A focused cross-layer coherence pass over the **cross-layer invariants added during the 2026-07-14/15
working session** — material that postdates the last full coherence verification (`coherence-pass-01`,
Wave-3) and was threaded into the layer docs by hand as it came up. Method (same as pass-01): for each
invariant/contract that spans >1 layer, verify (a) every layer it touches **declares** it consistently,
(b) all `§`/type cross-references **resolve**, (c) **no contradiction** between layers, (d) **no
one-directional boundary** (producer states an ownership split the consumer doesn't reciprocate). Scoped —
only the ~5 session invariants, not a full re-audit. Read-only verification; fixes applied in-place and
logged below._

## Why this pass exists

The plan had a coherence-verified baseline (`coherence-pass-01`), but the session added consensus-critical
cross-layer material — the eth/69+ **TD-sourcing invariant**, the **anti-spoof pipeline**, the
**per-network pivot-policy**, the **bootstrap-source / BitTorrent seam**, and the **L2 first-class
`chain-weight` CF** — faster than it was coherence-checked. Verifying the TD-sourcing thread live surfaced
that it was stated at L6/L2 but **missing from its implementing layers L5/L7** (fixed mid-session); this
pass confirms that fix holds and checks the rest to the same bar.

## Findings

| Thread | Layers spanned | Verdict | Detail |
|---|---|---|---|
| **1 · TD-sourcing + anti-spoof** | L1·L2·L5·L6·L7 | **WIRED ✓** (fixed this session) | L6 §5 states the invariant; L2 `chain-weight` CF + atomic write back it; **L5 `verifySeal`+MESS and L7 `PivotBlockSelector`+MESS now reference it** (were missing → added). End-to-end chain `L1 difficulty → L5 verifySeal → L2 chain-weight write → L7 sum/compare → L5 MESS score` stated identically at every hop. All `§5` cross-refs resolve (invariant is at L6:220, inside §5=175–265). No contradiction. |
| **2 · Per-network pivot-policy** | L7·L5·L2 | **WIRED ✓** | L7 §6.8.1 correctly declares its down-dependency on L5's `NetworkFamily` typeclass (the injected per-family selector, RX-L7-10) and L2's `MiningPool` `StorageProfile` pairing. L5 need not reciprocate a downstream *consumer* construct. **Note:** the per-family selector must be available at L7-controller-build time — a *confirm-at-build* item already tracked by RX-L7-10, not a doc gap. |
| **3 · Bootstrap-source / BitTorrent** | L7·L2 | **GAP → FIXED** | L2 states the ownership split cleanly and repeatedly ("L2 owns the era1 byte-canonical file + accumulator + manifest; **L7 owns the transport**", `L2.md` §Layer-boundaries + era1 rows). **L7 §6.9 did not reciprocate** — it described the torrent transport without citing L2 owns the format. **Fix applied:** added the layer boundary to §6.9 (L2 owns format; L7 §6.9 owns only transport + anchor-verify). |
| **4 · L2 `chain-weight` CF ↔ `chain` facade** | L2·L7 | **WIRED ✓** | L2 declares the first-class CF + atomic block+TD write (BUG-W7); the L7 `chain` facade exposes `total-difficulty read+write` over it (`L7.md` §1). Consistent. |
| **5 · eth/68 dual-capability migration** | L6 (internal) + R1 | **WIRED ✓** | Present + consistent across the R1 row (L6:66), the §5 "eth/68 retained" rationale (L6:91-94), the §5 invariant (L6:230-233), and the §8 DoD (L6:260). **Contradiction sweep CLEAN:** no surviving "cap at 68 / hard-bounded to 68 / maxEthCapability=69 fails" remnant of the earlier (reversed) wrong edit — the only `68` mentions are the corrected dual-capability statements. |

## Fixes applied by this pass

1. **L7 §6.9** — added the L2-owns-era1-format / L7-owns-transport boundary (reciprocates L2), closing the
   Thread-3 one-directional gap.
2. **L6 §9** — added a discoverability pointer to the §5 TD-sourcing invariant (a consensus-adjacent
   invariant was sitting under §5 "Scala 3 idiom targets"; a reader scanning §9 "Risks & consensus-critical
   flags" now finds it). Placement of the invariant itself left in §5 — moving the section would break the
   verified `§5` cross-refs for no wiring benefit.
3. *(earlier this session, pre-pass)* **L5 point-4 sub-bullet + L7 §6 preserve line** — wired the
   TD-sourcing invariant into its two implementing layers (the gap that motivated this pass).

## Verdict

**The session's cross-layer invariants are now consistently wired**, all cross-references resolve, and no
contradiction survives. The pass found **1 genuine one-directional gap (Thread 3, fixed)** + confirmed the
mid-session TD-sourcing fix holds + 2 minor discoverability items (1 fixed, 1 noted). This does **not**
replace a full `coherence-pass-01`-style re-audit of the whole plan — it verifies only the session deltas.
The standing recommendation from `coherence-pass-02/03` (run the WB-R5 linkage pass, then re-delta L5/L7/L10)
still holds; when the four WB-R1..R4 scoped reviews land, their new contracts should get the same
session-delta treatment.
