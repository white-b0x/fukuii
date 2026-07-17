# Research-utilization pass (pass 03)

_Prompted by `coherence-pass-02` WB-R5, which found the SR/research library **systematically
under-linked** (6/10 `topics/` + nearly all `best-practices/` ZERO-referenced). If the library wasn't
linked, the earlier reviews (including the "done" built layers L0/L1 and the L2 thread) may have been
conducted without it. This pass **re-audits every layer against the research assets that were previously
unconsulted** — the built layers L0/L1 (code + plan + RX vs their research-index row) and the plan layers
L2–L9 (a "research-delta": what the `best-practices/`+`topics/` library surfaces that an
SR-observation-only pass missed). L7 skipped (fully research-grounded in Workstream A / L7-review);
L5/L10 topic gaps already surfaced+linked in `coherence-pass-02`. Read-only; 8 parallel reviewers,
2026-07-14. Continuation of [`coherence-pass-02.md`](coherence-pass-02.md)._

## Headline

**The hypothesis holds: re-reviewing with the research library utilized surfaced real, additive findings
at nearly every plan layer — including one consensus-adjacent guard gap, three security/correctness HIGHs,
and a custody-grade keystore gap — none of which the SR-observation-only passes caught.** The two BUILT
layers (L0, L1) are the exception and the reassurance: both **genuinely apply their research** and are
byte-exact against references — the unsafe "done = complete" assumption did **not** bite for shipped code
(L0 had a separate 3-lens gate review; L1 was rebuilt fresh, byte-cited). So the pattern-library orphaning
was a **plan-completeness** hazard, not (so far) a shipped-code defect.

**Coverage-matrix caveat:** `coherence-pass-02`'s "ZERO-referenced" counts scanned only `plan/L*.md` +
`plan/rx/L*.md`. Several assets ARE consumed in `setup.md`/`requirements.md`/`L0.md` (e.g.
`scala-security-tooling-2026` is wired plan-wide via `setup.md` §60/§96/§120 + R11 + the `L0.md:179`
constant-time lint ratchet). The under-linkage is real at the *layer* level but the library is less
orphaned overall than a plan-only grep implied.

## Built-layer verdicts (the core worry — RESOLVED)

- **L0** (`bytes`/`common`/`crypto`/`rlp`) — **COMPLETE, research applied.** `constant-time-comparison`
  applied at the ECIES MAC (`ConstantTime.constantTimeEquals`; the one plain compare is signing vs the
  signer's own non-secret pubkey — correct); `fail-loud-invariants` honored across RLP/crypto; `type-safety`
  clean (mithril rated it GREEN). **No non-constant-time secret compare, no silent crypto fallback.** One
  **LOW** (L0-F1): the constant-time *enforcement lint* promised by RX-L0-16 / `L0.md:179` was never built
  — a missing regression guard that bites once L8 keystore-MAC + L9 JWT land.
- **L1** (`domain`) — **COMPLETE, byte-exact, zero consensus divergence.** Sender recovery (EIP-155
  v-unwind, the N-1 value gate in geth's exact order, low-S malleability, EIP-7702 authority recovery),
  blob two-form, typed-tx string-wrapping, open-tail header, and a golden Cancun header re-hashing
  byte-exact to go-ethereum — all verified in built code + tests. Three **LOW** (external bloom vector;
  `UInt256`-vs-`uint64` nonce modeling, RLP-identical; bench-gated hash-memo deferral). No rework.

## Plan-layer additive findings (from the previously-orphaned library)

| ID | Sev | Asset (previously unconsulted) | Finding |
|---|---|---|---|
| **L6-D1** | **HIGH** | `evm-clients/p2p.md` §1 | eth/69 drops `TotalDifficulty` from `Status` — the PoW heaviest-chain metric — and the plan lacked an explicit invariant for how PoW fork choice survives that. **Original framing (cap PoW at eth/68) was CORRECTED by the operator 2026-07-15: eth/68 is deprecating EVM-wide, so PoW MUST ride eth/69+.** **RESOLVED IN-PLACE** (L6.md §5 "TD-sourcing invariant", 2026-07-15) the *right* way: fork choice + MESS source TD from **local chain-weight** (cumulative header `difficulty`, always present on every wire version), **never** `Status.td` — so PoW advertises the full eth/68–71(+) range like PoS and the wire's TD removal is irrelevant. fukuii's `july-fourth` `PivotBlockSelector` ETH69 G1/G5 gates already do header-derived chain-weight. **eth/68 = HARD backward-compat requirement (the live ETC network is core-geth/eth-68), not a fallback → fukuii is DUAL-capable through the Olympia migration (eth/68 + eth/69+, per-peer negotiation).** forge-cosigned; DoD: heaviest-chain election + MESS work on eth/69+ with no wire TD, while interoperating with the live eth/68 network. |
| **L9-D1** | **HIGH** | `evm-clients/constant-time-comparison.md` | Constant-time compare is scoped to the Engine-API **JWT only**; the R11 gate converges 3 credential surfaces — JWT, **MCP OAuth/session tokens**, **external-signer secret** — and the latter two secret/tag compares are uncovered **timing-oracle** sites. Generalize to every secret/token/tag equality in the auth gate. |
| **L9-D2** | **HIGH** | `typelevel/patterns.md` §3 | The IO→`Future` bridge at the Pekko HTTP/WS boundary is unspecified; a born-modern IO transport should use a `Dispatcher` scoped to the server `Resource`, not scattered per-call `unsafeToFuture`. |
| **L9-D3** | **HIGH** | `pekko/typed-patterns.md` P19 | WS-subscription + grpc-seam consumer-handle actors hold releasable resources (Topic subs, gRPC streams, WAL handles) but have **no `PreRestart`/`PostStop` cleanup** → leak one sub/stream per restart. Zero `PreRestart` handlers exist today. |
| **L8-D1** | **HIGH** (custody) | `scala-security-tooling-2026.md` §2/§3/§5 | Keystore's only crypto gate is a **KAT round-trip vs fixed vectors** — it pins the vector's salt+IV and never exercises fukuii's *encryption-side* random generation. A static/reused IV under **AES-128-CTR** passes the KAT but is a catastrophic **key-XOR leak on the 2nd keyfile** — and the security library confirms **no SAST net** catches crypto-misuse (Semgrep CE/Scapegoat have no such detectors). Add an encryption-side salt+IV uniqueness test (N encryptions of same key+pw → all unique). |
| **L2-F1** | MED | `topics/snap-porting-reference.md` §5 | The kept `SnapSyncProgressStorage` (which the port doc says *beats* nethermind) has **no home in L2's `enum Namespace` / profile-CF-set inventory** — a keep-without-review as a schema omission. Declare the SNAP-progress CFs; L2↔L7 coordination. |
| **L2-F2** | MED | `snap-porting-reference.md` §5 + `fail-loud-invariants.md` | L2's `Tune` seam invites a WAL-off bulk-write knob; nethermind's `DisableWAL` on range commits is safe **only** because it re-runs the whole phase. With L7's persisted frontier, a lost memtable would mark missing nodes "done" — **silent state corruption**. L2's write contract must guarantee WAL durability at frontier-checkpoint boundaries (no unqualified WAL-off). |
| **L3-D1** | MED | `evm-clients/fail-loud-invariants.md` row 2 | The per-op **stack under/overflow guard** — the canonical example in the very doc L3 cites — is absent from L3's fail-loud invariant inventory; and L3 misses geth/besu's **centralized pre-dispatch** placement (driven by the build-`validate`d `delta`/`alpha`) that the `IArray`+`validate` rewrite sets up for free. |
| **L4-D1** | MED | `evm-clients/mutable-state-parity.md` Finding 1 | `mutable-state-parity` was never consulted, so L4 — which **owns** the state-transition accumulators (cumulative-gas/receipt/log, the besu-`BonsaiWorldStateUpdateAccumulator`-shaped state-diff builder RX-L4-15 depends on, base-fee/blob-fee loops) — lacks the parity-correct carve-out, and its **§10 mithril modernization mandate is unbounded** over exactly those hot-path mutable accumulators the asset says must stay mutable (benchmark, not purity). |
| **L6-D2** | MED | `evm-clients/p2p.md` §3 | Global **AllPeers** SNAP-request-serving subscription **before** Status is not captured, yet DoD is "hive devp2p green" — hive sends snap requests pre-Status; without the global sub they're silently dropped. |
| **L8-D2** | MED | `fail-loud-invariants.md` | txpool **state-unavailable → nonce-only admission** is a silent reduced-validation regime; L8 owns both `txpool` and `observability` but wires it to **no metric/health** → defers discovery to a downstream symptom. Emit a gauge + feed the health check. |
| **L9-D4/D5** | MED | `pekko/typed-patterns.md` P17 · `typelevel/patterns.md` §4 | Per-subscription `messageAdapter` inside handlers silently drops events (last-registration-wins) — use `pipeToSelf`/`ask`; and fiber-shared per-instance RPC state (Principal store, rate-limit counters) needs `Ref`/`AtomicCell` — the R2 `object{var}` grep gives false assurance for fiber-shared `var`. |
| **L3-D2, L4-D2/D3, L6-D3/D4/D5, L8-D3, L9-D6, L0-F1, L1-F1/2/3** | LOW | various | Opaque-gas type at the `GasCalculator` seam; deferred-item parity/chunking notes; `Behaviors.withMdc` per-peer logging (P22 names L6's actors); nethermind received-`BreachOfProtocol` blacklist ratification + reth backoff-scaling; Gitleaks allowlist for keystore KAT fixtures; bounded-restart/`ManualTime` timer tests; L0 lint; L1 bloom vector / uint64 / hash-memo. |

## Cross-cutting themes (what the library surfaced that per-layer SR passes structurally couldn't)

1. **Constant-time comparison — incomplete auth-surface coverage.** Applied at L0 ECIES + L6 + L9 JWT, but
   **missing at L9's MCP-token + external-signer compares** (L9-D1) and the **enforcement lint isn't built**
   (L0-F1). A single security invariant, partially covered — exactly the gap a cross-cutting asset catches.
2. **Fail-loud — three silent-fallback gaps** the `fail-loud-invariants` doc surfaced independently: L3-D1
   (stack guard not in the inventory), L8-D2 (silent degraded admission), L2-F2 (WAL-off silent corruption).
3. **Pekko Typed lifecycle (P17–P25)** — the born-Typed actors introduced across L6/L9 need
   `PreRestart` cleanup (L9-D3), non-per-call `messageAdapter` (L9-D4), bounded restart + `ManualTime`
   tests (L9-D6), `withMdc` (L6-D3). `pekko/typed-patterns` names the specific actors; no SR observation does.
4. **`mutable-state-parity` bounds the modernization mandate** — L3 handles it (ProgramState OPEN); L4
   doesn't, leaving mithril unbounded over the state accumulators (L4-D1). Any layer with a mithril §10
   mandate over hot-path mutable state needs the carve-out.
5. **SNAP-persistence storage residue** — the cross-layer SNAP finding (Workstream A) has schema (L2-F1) +
   WAL-durability (L2-F2) residue L2 must carry, not just L7.

## Verdict + disposition

- **Built layers sound** — L0/L1 do not need rework (L0-F1 lint + L1 LOWs are build-time hardening).
- **No plan layer needs a *new* full robustness review** beyond the four already flagged in
  `coherence-pass-02` (WB-R1..R4). These are **additive plan-completeness findings**, not contradictions.
- **HIGH findings (schedule into the owning layer's build-time §10 register):** L6-D1 (ETC eth/68
  invariant + guard, forge co-sign), L9-D1 (constant-time across all auth surfaces), L9-D2 (Dispatcher
  bridge), L9-D3 (`PreRestart` cleanup), L8-D1 (keystore encryption-side IV-uniqueness test). MED/LOW seed
  the same registers.
- **Process fix (feeds WB-R5):** these findings existed because the layer plans didn't consult their
  `best-practices/`+`topics/` rows. The [`../research-index.md`](../research-index.md) asset→layer map now
  makes that mandatory at build; this pass is the evidence the map is load-bearing. When the WB-R5 linkage
  pass wires the remaining assets into each layer header, re-run this delta for L5/L7/L10 to close the set.
