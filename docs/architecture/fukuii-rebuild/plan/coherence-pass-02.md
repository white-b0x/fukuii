# Wave-4 adversarial pass — cross-layer completeness audit (pass 02)

_The adversarial follow-up [`coherence-pass-01.md`](coherence-pass-01.md) called for (its "Next: Wave-4"
section): confirm each Wave-3 resolution actually holds in the edited prose, **and hunt what the six
threads missed**. Run 2026-07-14 as Workstream B of the L7-review kickoff
(`.local/docs/L7-review-and-cross-layer-audit-kickoff.md`), one read-only reviewer per layer
(L3/L4/L5/L6/L8/L9/L10 — L0/L1 built, L2 reviewed in the L2 thread, L7 reviewed in Workstream A), each
hunting four patterns SR-data-first then reference-source: (1) keep-without-adversarial-review,
(2) forward-pull-without-contingency, (3) scope-gap-vs-SR, (4) over-eager-seam. This is the durable
"found **and** scheduled" record (`finding-resolution.md`): every finding is resolved-in-place, scheduled
as a scoped review, or explicitly marked already-covered-by-`coherence-pass-01`._

## Headline

**The six coherence-pass-01 threads verified cross-layer *contracts* (producer→consumer, DAG edges,
type-homing). They structurally could not catch *subsystem-depth* keep-without-review — a mechanism kept
whole because "it runs today," never adversarially compared to best-in-class. That is exactly the L7
heal-hold pattern, and it is what Wave-4 surfaces.** The plan cores are **uniformly DECISIVE** (every
layer's RX pass is a genuine adversarial review — 0–1 CORRECTS each, mostly folded). The residual is a
small, sharp set of **four scoped subsystem reviews** plus one un-applied RX fold and a handful of
SR-default scope gaps. No layer needs a *full* re-review.

## Reconciliation with coherence-pass-01 (what is NOT re-reported here)

Several agent findings re-raised contracts `coherence-pass-01` already resolved; Wave-4 **confirms those
resolutions hold in prose** and does not re-open them:

- **R7 event-source spine (ADT / producer / transport):** `ChainNotification` single-homed in
  `consensus-api` (L5), L4 emits `BlockExecutionOutcome`, L9 imports L5's ADT, producer pinned to L5,
  `ExExHead` replay-by-height + monotonic-ID guaranteed at L5, prune-barrier split clean (L2 hook / L9
  `min()`), `AccountChange.incarnation` single-homed — **all confirmed holding** (CP01 Thread 2 / R7-1..4).
  The L9 audit independently re-verified the L2/L5 halves are flagged bidirectionally with fail-safes.
  → The R7 *contract* is resolved. What remains is **not** the contract but the **state-diff payload
  content shape** (WB-R2 below), which the threads did not examine.
- **R2 multi-instance isolation + G-NL1 leak homing:** txpool+keystore per-`ChainInstance`, IORuntime→L6,
  full G-NL1 leak-set homed (CP01 Thread 1). → Confirmed; the L10 audit's residual is *currency of the
  homed mechanism* (WB-R4), not the homing.
- **NetworkFamily depth:** L5 sized to full F11 depth **with a `// OPEN(depth)` DoD** (CP01 Thread 3).
  → The provisional flag the L5 audit asked for **already exists**; residual downgraded to a one-line SR-
  YAGNI-tension note (WB-L5b, LOW).
- **R11 auth unification + Engine-API typed verb-pair:** auth gate unified at L9, L5 speaks the typed
  `ExecutionEngine` contract not JsonRpc types (CP01 Thread 5 / R11-1). → Confirmed; the L10 R11 per-
  instance-auth worry is covered (the L9 gate design is coherence-reviewed).

## Per-layer verdicts

| Layer | Core verdict | Scoped review? | Highest residual |
|---|---|---|---|
| **L3 evm** | DECISIVE | No | HIGH: RX-L3-21 Olympia effective-EIP-set under-count **not folded** (pre-authored — applied by this pass) |
| **L4 execution** | DECISIVE core | **Yes (scoped)** | HIGH→MED: state-diff **payload content shape** + storage-agnosticism (SR caveats dropped; distinct from the resolved ADT homing) |
| **L5 consensus** | DECISIVE core | **Yes (scoped)** | HIGH: branch-import/**reorg-application driver algorithm** kept with constant-level review only |
| **L6 network** | DECISIVE except discovery | **Yes (scoped)** | HIGH: `scalanet` discovery kept from abandoned IOHK/Mantis fork, unreviewed + `00-repo-setup` "cleared" contradiction |
| **L8 observability** | DECISIVE | No | MED: txpool→`consensus-api` DAG edge undeclared (Wave-4 catch on a CP01 resolution) |
| **L9 rpc** | DECISIVE | No (1 build-scope call) | MED: grpc-seam "GREEN-and-wired" guard is **self-referential**; `eth_subscribe` activation-ordering dropped |
| **L10 node** | DECISIVE spine | **Yes (focused)** | MED×4: `fixDatabase` unreviewed; disk-watchdog SR gap; Hoodi/testnet-curation SR gap; N-whole-nodes differentiator inherited-not-argued |

## The four scoped subsystem reviews (Wave-4's core deliverable)

These are what the contract-coherence threads structurally missed — kept/committed *subsystems* not
adversarially reviewed. Each is the L7-heal-hold pattern; schedule each as a bounded review (SR-data-first
then source), **not** a full-layer re-review.

### WB-R1 · L6 `scalanet` discovery — keep-stale-fork, unreviewed, self-contradicted · HIGH
The entire discv4/discv5/ENR discovery wire is retained wholesale from the **abandoned IOHK/Mantis
`scalanet` fork** (SR `networking-p2p.md:18`), trusted as "audited" purely because it runs — no §6 line or
RX item asks whether it is spec-current/maintained. Best-in-class JVM peers diverge: **besu vendors
maintained Teku discv5; nethermind went first-party; reth wraps `sigp/discv5`.** Worse, `00-repo-setup.md`
marks the `scalanet` sibling module **"cleared"** while L6 §1/§9/§boundaries build the whole discovery
strategy on depending down on it — an unresolved cleared-vs-kept contradiction.
**Review:** reconcile cleared-vs-kept; adversarially weigh keep-stale-scalanet vs vendor-Teku-discv5
(besu's actual choice) vs rebuild-fresh, with scalanet's real maintenance/spec-currency assessed (ENR
EIP-778 revisions, discv5 changes) not assumed. Fold in the discovery source-mixing (`FairMix`) gap.

### WB-R2 · L4/L5/L9 state-diff **payload content shape** — settled-vs-OPEN split · HIGH→MED
Distinct from the resolved ADT homing: L4 §4 states besu's `BonsaiTrieLog{prior,updated}` **is** "a clean
serializable per-block state-diff" (settled), while **L9.md:197 holds the payload wire contract OPEN**
("version-less-additive until decided"). The SR (`exec-extensions.md:54`) flags two caveats L4 drops:
(a) besu's `TrieLog` is **Bonsai-path-based-tree-specific** — fukuii (RocksDB/MPT-inline) must map it or
make it **storage-agnostic**; (b) the on-disk/wire contract should be **version-less-additive**
(nethermind positional-RLP "absent = zero") for out-of-process readers.
**Review:** a single joint L4/L5/L9 decision that fixes the state-diff *payload* contract once —
storage-agnostic + version-less-additive — before L4's `execution` module fixes `MutationReason` + the
diff type as public API. (Note L9 already says version-less-additive; the gap is L4 stating besu's shape
as settled and dropping storage-agnosticism.) Pairs with the L9-F1 build-scope call below.

### WB-R3 · L5 branch-import / reorg-application **driver algorithm** · HIGH
The MESS *constants* (`128/25132/15/3840`) and the cycle-inversion are RX-byte-grounded, but the
**reorg-application driver itself** (execute-first-reorg ordering, the 8192-hop ancestry walk, deep-reorg-
cap composition with MESS) is relocated from the old ledger with **constant-level review only** and is
**single-authority (core-geth-only, JVM-first — besu-etc never implemented ECBP-1100)**. §6.12 *found* a
missing deep-reorg cap (geth 32 / besu 90_000; fukuii had none) precisely by the comparison the rest of
the driver never got — evidence more design-level gaps are latent.
**Review:** adversarial review of the reorg-application algorithm vs reth ExEx `into_inverted()` / geth
`SetCanonical` / besu `MergeCoordinator.updateForkChoice`, **forge-co-signed** — the direct analogue of
L7's SNAP heal-hold review, at the consensus reorg core.

### WB-R4 · L10 multi-`ChainInstance` isolation completeness + custody shutdown · MED (focused)
The largest blast radius in the plan, and RX-L10-10 itself flagged its cross-layer edges as "under-homed
into L6/L8/L9." Three focused checks, not a full-layer pass: (1) **isolation completeness** — verify the
G-NL1 retirement homes (`ioRuntime`→L6, GraphQL `Config.config`→L9, logback sysprop→L8) are carried as
*commitments in those layers' plans*, not asserted at L10 and dropped downstream (CP01 R2 says homed —
confirm the prose holds); (2) **custody shutdown** — `fixDatabase` repair path kept with zero adversarial
comparison to geth/erigon boot recovery (WB-L10a), plus the **missing disk-space watchdog → self-SIGTERM**
the SR marks a custody DEFAULT (go-ethereum `cmd/utils/cmd.go:134,144`; WB-L10b); (3) the **N-whole-nodes
differentiator architecture** inherited from AS-IS `FukuiiRuntime`, never argued vs shared-partitioned
multi-tenancy (WB-L10c).

## Full findings register (by layer)

Disposition key: **APPLIED** (folded now), **REVIEW** (→ a WB-Rn scoped review), **EDIT** (targeted plan
prose fix at build), **COVERED** (already resolved by coherence-pass-01, confirmed holding), **NOTE**
(honesty flag, well-mitigated).

| ID | Sev | Pattern | Finding | Disposition |
|---|---|---|---|---|
| L3-F1 | HIGH | 1 | RX-L3-21 Olympia effective-EIP-set (4 listed vs ~15 draft) not folded into §6/§8 | **APPLIED** (posture fold; EIP-set reconciliation → forge+operator at build) |
| L3-F2 | MED | 3 | EIP-2935 L3/L4 system-call split unnamed | EDIT (name it in §7 "routed out") |
| L3-F3 | MED | 2 | F11 `ProgramState` second-counter shape forward-pulled over RX-L3-01 (frame model) + L5 F11 design | EDIT (add the two-OPEN contingency to §7 F11) |
| L3-F4 | LOW | 1 | EIP-7702/7610 call/create keep needs the GeneralStateTests corpus to include those vectors | EDIT (§8 DoD note) |
| L4-F1 | HIGH→MED | 2+3 | state-diff payload shape settled-vs-OPEN + storage-agnostic/version-less-additive dropped | **REVIEW → WB-R2** |
| L4-F2 | MED | 4 | `MutationReason` + diff type committed as public API ahead of L5/L9 payload decision | REVIEW → WB-R2 (mark provisional) |
| L4-F3 | LOW | 4 | deferred-commitment toggle (erigon) built over unreviewed L2 cadence | EDIT (note L4→L2 contingency) |
| L4-F4 | LOW | 1 | orchestrator→preparator split kept without besu single-class comparison (non-consensus packaging) | NOTE |
| L5-F1 | HIGH | 1 | reorg-application driver algorithm, constant-level review only, single-authority | **REVIEW → WB-R3** |
| L5-F2 | MED | 3 | faker-family test gap (`NewFakeFailer`/`NewFakeDelayer`/`ModeTest`) omitted vs SR | EDIT (§7 floor or named-owner defer) |
| L5-F3 (WB-L5b) | LOW | 4 | NetworkFamily XDC field-depth: `// OPEN(depth)` exists (CP01) — add the SR-YAGNI-tension note | EDIT (one line; mostly COVERED) |
| L5-F4 | LOW | 2 | R7 event-source contract cross-check | **COVERED** (CP01 R7-3) — confirm consumer shapes at build |
| L5-F5 | LOW | 3 | MEV-builder / Shutter / instant-seal silently dropped vs no-silent-drop doctrine | EDIT (disposition line: →banksy/L8) |
| L6-F1 | HIGH | 1 | `scalanet` stale-fork kept unreviewed + cleared/kept contradiction | **REVIEW → WB-R1** |
| L6-F2 | MED | 3 | DialRatio ⅔-inbound + 30s per-IP throttle scoped OPTIONAL(consortium) — it's a geth DEFAULT | EDIT (DEFAULT the base; only LAN-exempt is consortium) |
| L6-F3 | LOW-MED | 1 | `PeerScore` 5-factor weights kept on shape-match, not calibration | EDIT / NOTE (calibrate post-interop) |
| L6-F4 | LOW-MED | 4 | F8 DNS-tree *authoring* built in-node; geth externalizes to `cmd/devp2p` | EDIT (build-vs-tool note) |
| L6-F5 | LOW | 3 | discovery source-mixing (`FairMix`) unaddressed | REVIEW → WB-R1 (fold in) |
| L6-F6 | LOW | 2 | eth/71 BAL serve-side over unbuilt L4/L5 BAL gen | EDIT (contingency row) |
| L8-F1 | MED | 2 | txpool consumes L5 `ChainNotification` but declares no `consensus-api` edge (down-only DAG) | EDIT (declare edge or specify inversion) — Wave-4 catch on CP01 R2-F1 |
| L8-F2 | LOW-MED | 3 | ethstats downgraded from SR mining-pool-role DEFAULT into generic enterprise no-op | EDIT (split to OPTIONAL(role: mining-pool); GTM) |
| L8-F3..F6 | LOW | 1/3/4 | dashboards coverage / metric self-doc / JSON-log scope / subpool seam | NOTE / EDIT (cosmetic) |
| L9-F1 | MED | 4 | grpc-seam GREEN-and-wired guard self-referential (wired consumer = the deferred seam) | EDIT (split DoD: baseline = in-proc reorg fix; gate cross-process on named consumer) — pairs WB-R2 |
| L9-F2 | MED | 3 | `eth_subscribe` activation-ordering (buffer-until-sub-ID) implemented for grpc-seam, dropped for mainline | EDIT (§5 + DoD; →conduit) |
| L9-F3 | LOW | 1 | `ServiceResponse[T]=IO[Either]` kept without EitherT/error-channel comparison (thin, no FP anchor) | NOTE |
| L10-F1 (WB-L10a) | MED | 1 | `fixDatabase` custody repair path kept with zero adversarial comparison | **REVIEW → WB-R4** |
| L10-F2 (WB-L10b) | MED | 3 | disk-space watchdog → self-SIGTERM (SR custody DEFAULT) missing | REVIEW → WB-R4 (+ EDIT §2) |
| L10-F3 | MED | 3 | Hoodi + testnet-curation policy (SR DEFAULT) not in the L10 profile set | EDIT (name Hoodi; carry curation policy or L1 cross-ref) |
| L10-F4 (WB-L10c) | MED | 1 | N-whole-nodes differentiator inherited from AS-IS, not argued vs shared-partitioned | REVIEW → WB-R4 (justify in §7/§9) |
| L10-F5 | LOW-MED | 1 | periodic-consistency-check folded into guardian but its only action (auto-shutdown) removed — unreconciled | EDIT (reconcile §5↔§6) |
| L10-F6 | LOW-MED | 2 | R11 per-instance-auth DoD over L9 gate | **COVERED** (CP01 R11-1) — confirm at build |

## Structural verdict

- **DAG / contracts:** the coherence-pass-01 resolutions **hold in prose** (spot-confirmed by the L4/L5/L8/
  L9/L10 audits reading current text). No new cross-layer *contradiction* surfaced — Wave-4's finds are
  completeness/subsystem-depth, not coherence.
- **Cores DECISIVE:** every layer's execution/consensus/wire core is adversarially RX-grounded; the plan
  does not hedge as its posture.
- **Four scoped reviews (WB-R1..R4)** are the actionable output — subsystem-depth keep-without-review the
  contract threads couldn't reach. Plus one applied fold (L3-F1) and ~a dozen targeted plan edits.
- **No layer needs a full re-review.** Schedule WB-R1..R4 (SR-data-first then source) and the EDIT-tier
  fixes into each owning layer's build-time §10 register.

## WB-R5 · Systemic research-asset under-linkage · HIGH (meta)

The Wave-4 agents (and the synthesis) initially **missed `coherence-pass-01.md` entirely** — because
**zero** of the eight layer plan docs and **zero** of the eight RX docs link it (only `README.md`/
`REVIEW.md` do). A full coverage matrix (`.local/scratch/research-linkage-audit.sh`) shows this is
systemic, not a one-off: **research assets we already own are not wired into the plan/RX docs, so their
findings don't reach the layer they inform** — the direct cause of several Workstream-B gaps.

**Confirmed causal links (a plan gap traces to an unlinked asset):**
- **L5-F2 (faker-family) + L5-F5 (dev/instant-seal)** ← [`topics/consensus-pow-cpu-dev-and-deprecated.md`](../../../research/clients/topics/consensus-pow-cpu-dev-and-deprecated.md) (**ZERO** plan/rx refs) — it catalogs exactly CPU-Ethash sealing, dev/instant-seal, and the faker/test PoW engines L5 under-scoped.
- **L10-F3 (Hoodi/testnet curation)** ← [`topics/pos-networks-and-testnets.md`](../../../research/clients/topics/pos-networks-and-testnets.md) (**ZERO**) — the concrete PoS testnet inventory incl. Hoodi, which L10's profile layer omits.
- **L5 PoA seams (Batch-7 `BlockInterface`/`ValidatorProvider`)** ← [`topics/consensus-poa-and-etc-testnets.md`](../../../research/clients/topics/consensus-poa-and-etc-testnets.md) (**ZERO**).
- **L6 `Blacklist` policy** ← [`best-practices/evm-clients/peer-disconnect-blacklist-policy.md`](../../../research/best-practices/evm-clients/peer-disconnect-blacklist-policy.md) (**ZERO**).

**Coverage matrix highlights (plan-doc refs / rx-doc refs):**
- **SR `topics/` catalogs: 6 of 10 are ZERO-referenced** — `consensus-l2-rollup-sidechain`, `consensus-methods-catalog`, `consensus-poa-and-etc-testnets`, `consensus-pow-cpu-dev-and-deprecated`, `mining-protocol-nonevm`, `pos-networks-and-testnets`. These inform **decisions** (which sealing modes, which testnets, which PoA family) — their absence is a *scope-gap generator*.
- **The `best-practices/` pattern library is almost entirely orphaned** — `pekko/typed-patterns`, `pekko/concurrency`, `scala/type-safety`, `typelevel/patterns`, `evm-clients/anti-patterns`, `evm-clients/peer-disconnect-blacklist-policy`, `evm-clients/reference-client-crosscheck`, `codebase-audit`, `scala-security-tooling-2026` are all ZERO. These are **build-time-consult** assets (how to code each layer idiomatically); their orphaning means the plans' idiom/pattern grounding isn't anchored to the research.
- **Many SR observations are `plan:1 rx:0`** — cited only by their own layer plan and **not even by the RX that verifies against them** (`block-execution`, `rpc-api`, `block-production`, `cl-engine`, `build-deps`, `primitives`, `testing` all rx:0). The RX docs verify against *reference-client source* but frequently don't cite the *binding SR observation* they're supposed to honor.

**Disposition:**
- **APPLIED now** (causally-demonstrated, high-confidence): header research-grounding notes added to
  **L5** (→ consensus-pow-cpu-dev-and-deprecated, consensus-poa-and-etc-testnets, consensus-methods-catalog),
  **L10** (→ pos-networks-and-testnets), **L6** (→ peer-disconnect-blacklist-policy). The corresponding
  findings (L5-F2/F5, L10-F3) upgrade from "SR scope gap" to "resolve by consulting the now-linked asset."
- **SCHEDULE → WB-R5 research-linkage pass** (bounded): wire each `topics/` catalog and each `best-practices/`
  doc into the header "research grounding" + build-time-consult list of every layer it informs (a
  should-link matrix, asset→layer), and add the binding SR observation citation to each RX doc that omits
  it. This is a mechanical-but-judgment pass over ~15 assets × their relevant layers; it converts an owned
  research library from orphaned to load-bearing. It is the generalization of this pass's own bootstrap
  miss (missing `coherence-pass-01`), now fixed for the coherence passes (back-link callout added to all 8
  layer docs) and to be finished for the rest of the library.

## Next

Land the L3-F1 posture fold (done), then schedule WB-R1 (scalanet), WB-R2 (state-diff payload, joint
L4/L5/L9), WB-R3 (reorg driver, forge-co-signed), WB-R4 (L10 isolation/custody/differentiator). The
EDIT-tier rows seed each layer's build-time §10 Findings register per `coherence-pass-01`'s convention.

**Continuation — [`coherence-pass-03.md`](coherence-pass-03.md) (research-utilization pass):** re-audited
every layer against the previously-orphaned `best-practices/`+`topics/` library (the WB-R5 assets). Built
layers L0/L1 verified sound; the plan layers surfaced additive findings the SR-observation-only passes
missed — 1 consensus-adjacent HIGH (L6-D1 ETC eth/68 TD-invariant), 3 security/correctness HIGH (L9-D1
constant-time-across-auth-surfaces, L9-D2 Dispatcher-bridge, L9-D3 PreRestart-leak), 1 custody HIGH (L8-D1
keystore IV-uniqueness), plus MED/LOW. All seed the owning layer's §10 register.
