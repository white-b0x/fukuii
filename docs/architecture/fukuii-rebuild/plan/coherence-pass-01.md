# Wave-3 coherence pass — findings register (pass 01)

_The cross-layer coherence review of the full `plan/*.md` set, run after Wave-2 enrichment. Six
read-only reviewers, one per through-line, verified producer→consumer contracts end-to-end. This is
the durable "found **and** scheduled" record (`finding-resolution.md` / README DETAILED-AUDIT lens f):
every finding below is either **resolved-in-place** (a prose edit, cited) or **scheduled** (a §7
work-item in the owning layer). None is left as a bare deferred note. At **build time**, each layer's
own §10 Findings register seeds from its rows here and is cleared before the layer is marked GREEN._

**Structural verdicts (all six threads):** the module DAG is **acyclic and down-only**; **requirements
R1–R11 coverage complete**; **feature-ledger F1–F13 coverage complete**. Two genuine cross-layer
contradictions (R7 event-source double-definition, R11-1 auth/wire-contract placement) + one true
find-mention-forget (the `chain` module) + a set of medium/minor gaps. All resolved below.

## Thread 1 — R2 multi-instance isolation (no contradictions)

| ID | Severity | Disposition | Where |
|----|----------|-------------|-------|
| R2-F1 | GAP | **Resolved** — `txpool` + `keystore` now scoped per-`ChainInstance` (not just the metrics registry), with tests + grep | `L8.md §7` |
| R2-F2 | GAP | **Resolved** — the `object…{var…}` isolation-regression grep added as an L9 DoD gate (was present in L2/L8/L10, missing in L9) | `L9.md §8` |
| R2-F3 | GAP | **Resolved** — the R2 isolation grep added as a repo-wide `check_baddeps`-style CI ratchet (single enforced home vs 4 hand-maintained) | `setup.md §6, §8` |
| R2-F4 | GAP (low) | **Resolved** — "`IORuntime` injected from composition, never a per-builder `lazy val`" stated at L6 (where the diamond's components live) | `L6.md §9` |
| — | OK | Immutable-shared ⊥ per-instance split agrees (L5/L10/L8/L2); R11 auth single-owned (L9 mech / L10 scope); R8 storage single-owned (L2 sel / L10 wire); full G-NL1 leak-set homed | verified |

## Thread 2 — R7 reorg-event / prune-barrier / gRPC (CONTRADICTIONS, resolved)

| ID | Severity | Disposition | Where |
|----|----------|-------------|-------|
| R7-1 | CONTRADICTION | **Resolved** — `ChainNotification` was defined 3× (L4 per-block 2-case, L9 per-segment 3-case, L5 `ConsensusResult`). Now single-homed: the reorg-aware wire ADT (reth 3-case segment) is defined **once in `consensus-api`**; L4 emits `BlockExecutionOutcome` (per-block); L9 **imports** L5's ADT | `L5.md §6.5`, `L4.md §1/§4/§5/§6/§7`, `L9.md §1/§5` |
| R7-2 | CONTRADICTION | **Resolved** — the reorg producer was doubly-claimed (L4+L5) while L4 disclaims reorg authority and L9's `grpc-seam` wired to `execution`. Pinned to L5 (owns fork-choice); `grpc-seam` now depends on `consensus-api` (+`execution` for `MutationReason`) | `L5.md §6.5/§10`, `L4.md §"Layer boundaries"`, `L9.md §1/§10`, `optimizations.md L4/L5` |
| R7-3 | GAP | **Resolved** — L9's `ExExHead` resume assumes a replayable-by-height + monotonic-ID source; that guarantee is now stated upstream at L5 | `L5.md §6.5/§11`, `L9.md §5` |
| R7-4 | non-issue | **Accepted as-is** — L7 is *off* the R7 external-event path (the matrix + `L7.md` already agree; only loose mission-framing said otherwise) | — |
| — | OK | Prune-barrier split clean (L2 hook / L9 `min()` registry); reorg-always-below-barrier; ExExHead+cap+deadlock-guard fully specified at L9; `AccountChange.incarnation` single-homed (vault+forge) | verified |

## Thread 3 — R1/R3 NetworkFamily depth + family-neutrality (substantially coherent)

| ID | Severity | Disposition | Where |
|----|----------|-------------|-------|
| R1-3 | GAP | **Resolved** — fork-dispatch type name reconciled: `ForkActivation` (L3-owned) everywhere; reth `ForkCondition` cited only as prior art (was drifting to `ForkCondition`/`TTD` at L5/L10) | `L5.md §5`, `L10.md §5` |
| R1-4 | GAP | **Resolved** — overload-deletion ownership de-duplicated: L3 owns the type + the `forBlock` collapse/deletion (its DoD certifies it); L5 routes consensus dispatch call-sites + owns the `ForkSchedule` registry | `L5.md §5/§6.3` |
| — | OK | `ForkActivation` seam correctly at L3 (prior "defer-to-L5" trap resolved); L5 `NetworkFamily` sized to full F11 depth with `// OPEN(depth)` DoD; L0–L4 each carry a grep-verifiable neutrality guard; no wrong-network symbol trap | verified |

## Thread 4 — R8 storage-approach completeness (substantially coherent)

| ID | Severity | Disposition | Where |
|----|----------|-------------|-------|
| R8-3 | GAP | **Resolved** — the dropped `mining-pool` role (fukuii's #1 GTM; L2 ships a `MiningPool` profile) added to L7's role→sync-mode table; bootnode noted as an F7/F8 addition beyond the six canonical roles | `L7.md §2` |
| R8-7 | GAP (low) | **Resolved** — `StorageProfile` `keying` vs `backend` axes disambiguated: `keying` is the sole hash/path axis, `engine` is the KV backend (RocksDb; MDBX OBSOLETE) — no invalid combination | `L2.md §5` |
| R8-4 | cosmetic | **Resolved** — SNAP-serving role-set normalized to `server/archival/bootnode` | `optimizations.md L7` |
| — | OK | Multi-approach-from-line-one (StorageProfile + INodeStorage scheme-indirection, STRUCTURAL); dual hash+path with online migration, no SNAP-island lock-in; F9 serving first-class/DoS-bounded/v1+v2; L9 serving-mode + prune-barrier + TrieLog alignment | verified |

## Thread 5 — R11 security & hardening (CONTRADICTION, resolved)

| ID | Severity | Disposition | Where |
|----|----------|-------------|-------|
| R11-1 | CONTRADICTION (structural) | **Resolved** — the auth+wire contract was placed in L9 but L5's Engine-API (below L9) was said to reuse `rpc`'s `JsonRpcRequest` types (upward edge). Corrected: L5's driver speaks the **typed verb-pair** (`ExecutionEngine` contract, §6.7 — no JsonRpc types); L9 owns the JWT-gated HTTP transport that translates JSON-RPC ↔ verb-pair and calls **down**; the auth gate stays at L9 where all inbound transports converge. **No new module needed** — L5.md §10/§6.7 already had it right; only L9's phrasing was wrong | `L9.md §1` |
| R11-2 | GAP | **Resolved** — L6 now uses L0 `constantTimeEquals` for the ECIES auth-tag/MAC (a declared L0 consumer that was silent — a timing side-channel); requirements R11 matrix reconciled | `L6.md §8`, `requirements.md R11 matrix` |
| R11-3 | GAP (low) | **Resolved** — L9's JWT/HMAC verify now explicitly uses `constantTimeEquals` | `L9.md §5/§8` |
| R11-4 | OK-note | **Resolved** — dropped the orphan "L1 (per-tx)" constant-time consumer (no real site) from L0's consumer list | `L0.md §7` |
| — | OK | Auth gate IS one unified seam (not three scattered checks); F12 fully homed in setup/CI (all six pieces concrete, sentinel-gated); per-instance auth single-owned | verified |

## Thread 6 — structural DAG + deferral-placement + ledger coverage

| ID | Severity | Disposition | Where |
|----|----------|-------------|-------|
| DAG-1 / DEF-ORPHAN-1 | CONTRADICTION | **Resolved** — the `chain` module (`Blockchain`/`BlockchainReader` facade) was referenced as an L9 dep + an L1/L2 relocation target "→ L7 `chain`" but defined nowhere. Now declared as a real L7-band module (`chain → storage, trie, domain`; consumed by `sync` + `rpc`), added to the README DAG + L7 scope/boundaries. (`chain` sits *above* L4/L5 — they use storage/trie directly — so the down-only edge holds and the old `domain↔Blockchain*` SCC dissolves) | `README.md`, `L7.md §1/§"Layer boundaries"` |
| DEF-2 | GAP | **Resolved** — the two constant-time consumer routings (L6 ECIES, L9 JWT) now land in the target layers with tests (= R11-2, R11-3) | `L6.md §8`, `L9.md §5/§8` |
| DEF-3 | OK-note | **Resolved** — the L3→L9 submit-seal-verify pointer corrected to "L5 (seal-verify, SUBMITWORK-VERIFY-SEAL-01) + L9 (RPC plumbing)" — it was already scheduled at L5 §6.10, only the pointer was wrong | `L3.md §7` |
| LEDGER-1 | OK-note | **Resolved** — F11 cross-referenced at its secondary homes (L3 `GasCalculator` second-dimension = zk-gas; L2 witness) so the not-forgotten check is greppable | `L3.md §7` |
| — | OK | DAG acyclic + down-only; every "→ L{n}" routing received (after chain fix); F1–F13 addressed on every home layer; R1–R11 every assigned cell has a decision | verified |

## Template conformance ([E]) — the §10 Findings register

The DAG reviewer found **no layer doc carries the template's dedicated §10 Findings register** yet
(agentic-alignment exists in all 12, as §10/§11/unnumbered). That register is a **build-time** artifact
— it accumulates and clears findings *as a layer is built* (DETAILED-AUDIT lens f). **This file is the
planning-phase equivalent:** all Wave-3 findings are recorded + dispositioned + located here. When each
layer's BUILD begins, its §10 register is created and seeded from that layer's rows above; the layer is
not marked GREEN until its register is empty. The trailing "Agentic alignment" section renumbers to §11
at that time (a mechanical per-doc step done at build, not retrofitted across 12 docs now).

## Next: Wave-4 adversarial pass

This record is the input to Wave 4 — the adversarial reviewer confirms each resolution above actually
holds in the edited prose (no half-fix, no new incoherence introduced by the edits), and hunts for what
the six threads missed. Iterate until a pass surfaces nothing, then mark each `plan/*.md` READY.
