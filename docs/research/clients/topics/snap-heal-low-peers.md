# Topic — SNAP-sync healing under low peers + slow data

_Workstream-A follow-up research, 2026-07-14. Sources: read-only byte-level extraction from the vendored
trees under `.claude/repo-references/clients/{go-ethereum,besu,nethermind}` (plus the observed core-geth
= geth-verbatim inheritance). Motivated by the L7 SNAP heal robustness review
([`plan/L7.md`](../../../architecture/fukuii-rebuild/plan/L7.md) §6.8) and operator production testimony
that fukuii's re-pivot-on-heal approach "kept failing on ETC mainnet where there are very few snap
peers." The binding SR slot [`observations/sync.md`](../observations/sync.md) covered heal *shape* in one
line and never examined behaviour under peer scarcity — this doc closes that gap._

## The question

The reference clients' snap heal is documented and validated on **peer-rich ETH mainnet** (hundreds–
thousands of fast snap-serving peers). fukuii targets **ETC mainnet: ~1–5 snap-serving peers, high RTT,
low throughput**. Does each reference client's heal remain robust under scarcity, or does it structurally
*assume* peer abundance? Concretely: what happens to heal when serving is slow enough that heal cannot
finish within the ~128-block snapshot serve window?

**Headline:** all three references **structurally assume peer abundance**. None has a heal keep-up
detector, none actively seeks snap peers under scarcity, and two of the three degrade *worse* than fukuii
on a few-peer chain. The one direct precedent for fukuii's design — nethermind's `StaticSnapPivot` — pins
the pivot exactly as fukuii's heal-hold does, but is scoped to a pre-known checkpoint, so fukuii's
live-PoW-head hold is a **superset** of anything a mainstream client ships.

## Comparison table

| Dimension | go-ethereum | besu | nethermind | fukuii (AS-IS) |
|---|---|---|---|---|
| **Slow-peer load adaptation** | msgrate EWMA shrinks batch to a floor of 1, zeroes capacity on timeout (`msgrate.go:187-198`); slow peer **kept** | **none** — fixed 5 s timeout (`AbstractPeerRequestTask.java:41`), fixed 384-entry batch; slow peer **disconnected at 5 timeouts** (`PeerReputation.java:41,78`) | throughput/latency-ranked allocation sheds slow peer once a faster one is known (`BySpeedStrategy.cs:57`); batch **fixed** (`TreeSync.cs:32` explicit TODO) | adaptive per-peer byte budget (AS-IS "preserved as correct") |
| **Sole peer (N=1)** | serially hammers it; a stale-root **empty response → `statelessPeers`** (`sync.go:2929-2931`) → set collapses to zero → block until roll | `refreshPeers` guarded so sole peer safe from *that* path, but reputation-timeout can still drop it | **protects** the sole peer, re-pivots instead of punishing (`SnapSyncFeed.cs:180-207`) | binary stateless-peer detection + post-pivot cooldown |
| **Start gate under scarcity** | none (Engine/beacon-driven; starts with whatever peers exist) | **won't start untrusted snap below 5 confirming peers** (`PivotSelectorFromPeers.java:124-131`, `PivotBlockConfirmer.java:129`); trusted checkpoint bypasses (1 peer) | mode-selector gates on *eth* peers ahead of pivot (`MultiSyncModeSelector.cs:691`) — snap-peer scarcity invisible | checkpoint-service bootstrap + optimistic `selectSyncMode` (peerCount==0 stays SNAP) |
| **Heal slower than serve window** | pivot rolls on **blind block-distance, no heal-rate feedback** (`beaconsync.go:306-318`); livelock via stale-root→statelessPeers cascade | 60 s re-pivot + discard-partial-heal (`reloadTrieHeal`, `SnapWorldDownloadState.java:271-287`); incremental re-walk, **no keep-up guard, no give-up, no alarm** | `_hintsToResetRoot>=32`→fall-asleep+re-pivot (`TreeSync.cs:356-361`); on-disk node reuse converges; **no explicit heal-lag detector** | **heal-hold-on-stagnation**: pins pivot so long heal isn't orphaned (`SNAPSyncController.scala:1382-1412`) |
| **Progress preserved across re-pivot?** | **yes** — persisted nodes not re-fetched (`sync.go:604-606`) | **yes** — `getExistingData` skips already-correct nodes (`AccountTrieNodeHealingRequest.java:80-85`) | **yes** — content-addressed on-disk reuse (`TreeSync.cs:493-532`) | (heal-hold sidesteps the question by not re-pivoting) |
| **Active snap-peer seeking** | **none** — passive `discovery.go` advertises snap ENR, waits on peerJoin | **none** — capability-filtered but passive `waitForPeer` (5 s) | **none** — capability-filtered allocation, no target raise / discovery bias | dormant-retry waits for peers to re-index |
| **Pin-the-pivot config knob** | none (FrozenPivot is snap/2-internal) | none for PoW (checkpoint-pivot is the only bypass) | **`StaticSnapPivot`** (`SyncConfig.cs:57`) — first-class pin, checkpoint-scoped | heal-hold (live-head-scoped) |
| **Give-up / liveness** | **never** proceeds with incomplete state; blocks until `Pending()==0` or cancel | **never** proceeds; `markAsStalled` a `// TODO`, re-enqueues forever | **never** proceeds; loops until `CanFinalize` or cancel | **force-completes** (SNAP↔Fast bounce) + on-demand `StateNodeFetcher` — liveness over completeness, but *silent* |
| **Heal keep-up / convergence alarm** | none (only repeated "Pivot seemingly stale, moving") | none (only periodic "Healed N nodes" info) | none | (has watchdogs, but restart-the-world) |

## Per-client findings

### go-ethereum — robustness is emergent from mainnet abundance, not engineered
- Adaptive throughput control exists (msgrate EWMA + a local heal throttle) but every mechanism optimizes
  *throughput given the peers you have* — none finds more peers, holds the pivot, or slows the chain.
- The pivot-move threshold is a blind block-distance constant (`2*64−8 = 120`, `beaconsync.go:306-318`)
  with **no heal-rate feedback**; the "state syncer is consulted first" comment (`:308`) only checks
  `FrozenPivot()`, which is non-nil solely for snap/2 BAL trie-gen — never for v1 heal.
- **The killer on a few-peer chain:** heal falls behind → pivot rolls → in-flight requests hit the now-
  stale old root → peers answer **empty** → geth reads empty as "peer lacks this state" → adds them to
  `statelessPeers` (`sync.go:2929-2931`). With ~3 peers, two or three empties collapse the usable set to
  **zero** and the loop blocks on `<-peerJoin` (`sync.go:739`) until the *next* roll resets the set
  (`sync.go:617`) — a thrash cycle that can repeat without converging.
- geth **never** commits partial state (`sync.go:689` → `downloader.go:1019-1027`); a chain it cannot heal
  in time **does not sync at all.**
- No config recourse: request timeouts (`ttlLimit=1min`, `rttMaxEstimate=20s`), `fsMinFullBlocks`, and the
  128-diff-layer retention are compile-time consts; no min-snap-peers, static-pivot, or heal-timeout flag.
- **The one idea worth stealing:** the deliberate `120 < 128` margin (keep your own pivot-move threshold
  safely under the peer serve window). fukuii's proactive-pivot-roll already has it.

### besu — assumes abundance in three compounding ways, and is the *weaker* reference
1. **Won't start untrusted snap below 5 confirming peers** (`PivotSelectorFromPeers.java:124-131`;
   `PivotBlockConfirmer.java:99,129` queries exactly `numberOfPeersToQuery=5` best peers and requires
   unanimity). On a 1–3-peer ETC chain the only way in is a **trusted checkpoint pivot**
   (`PivotSyncActions.java:144-160`, needs 1 peer) — which is precisely why fukuii ships
   `fukuii-checkpoint-service`.
2. **Punishes slow peers** instead of adapting: fixed 5 s timeout, fixed 384-entry batches, and a
   **disconnect at 5 timeouts** (`PeerReputation.java:41,78`) — on a scarce chain this drops the very
   peers you need.
3. **60 s re-pivot + discard-partial-heal** (`reloadTrieHeal`, `SnapWorldDownloadState.java:271-287`) with
   the re-check interval and request timeout both **hard-coded** (not config). The re-walk is incremental
   (`getExistingData` skips correct nodes), so a healthy-but-slow 3-peer chain with a small per-round
   delta **converges** — but there is a real **livelock window**, and besu has **no keep-up detector, no
   give-up, and no alarm.** Its own stall path `markAsStalled` is an unfinished `// TODO`
   (`SnapWorldDownloadState.java:160-163`).
- besu is a good *structural* mirror (pipeline steps, checkpoint anchor) but its heal is tuned for ETH-
  mainnet density; on the ETC-scarcity axis it is weaker than fukuii, not the gold standard.

### nethermind — the strongest of the three, and the one direct precedent for heal-hold
- **Slow-peer routing:** throughput/latency-ranked allocation (`BySpeedStrategy.cs:57`) sheds a slow peer
  once a faster one is known; below the `desiredPeersWithKnownSpeed=5` floor it force-samples unmeasured
  peers (`:60-62`), so a 3-peer chain keeps probing the slow peer. Batch size is **fixed** (`TreeSync.cs:32`
  — explicit "consider peer-specific request limits" TODO).
- **Sole peer:** protected — after 5 window-failures with no successes it re-pivots and clears the window
  (`SnapSyncFeed.cs:180-207`), never `ReportWeakPeer`-ing the only peer.
- **Re-pivot preserves on-disk content-addressed nodes** (`TreeSync.cs:493-532`), so heal converges across
  a slowly-advancing head rather than restarting — the `2 min` re-pivot floor ≈ ~9 ETC blocks of drift,
  well inside the 128 window, so convergence is *likely* but **not guaranteed** (no heal-lag detector).
- **Gaps:** batch not per-peer-adaptive; snap-peer scarcity is invisible to the mode selector (it counts
  *eth* peers via `AnyPostPivotPeerKnown`, `MultiSyncModeSelector.cs:691`) so an eth-but-no-snap-peer node
  spins without progress; no active snap-peer acquisition.

#### `StaticSnapPivot` — a mainstream client shipping fukuii's heal-hold (the key finding)
nethermind's `StaticSnapPivot` (`SyncConfig.cs:57`) is a first-class "pin the pivot" config knob that does
exactly what fukuii's heal-hold aims at — heal runs to completion against **one unchanging root**, never
orphaned by a re-pivot:
1. **Pins the pivot** to the configured `SyncPivot` block (`StateSyncPivot.cs:50-52` — `StaticSnapPivot ?
   blockTree.SyncPivot.BlockNumber : Math.Max(...)`), so every `UpdateHeaderForcefully`/`GetPivotHeader`
   resolves to the same frozen block.
2. **Disables the pivot-updater** (`StartingSyncPivotUpdater.cs:95`).
3. **Relaxes peer gating for scarcity** — state/snap can proceed against a peer merely *at* the pivot, not
   strictly ahead (`MultiSyncModeSelector.cs:306,499`).
4. **Skips the head-distance wait** (`StateSyncRunner.cs:128`).
5. **Requires a known checkpoint** (`PivotNumber`+`PivotHash`, `InitializeNetwork.cs:115-121`) and the node
   is deliberately **idle** afterward, awaiting a CL or `ExitOnSynced` (`StateSyncRunner.cs:58-59`).

**The scoping nuance that matters for fukuii (live PoW ETC head):** nethermind's pin is scoped to a
*pre-known operator/CL-supplied checkpoint* — the "sync to a frozen checkpoint and hold" case, which maps
directly onto **fukuii's checkpoint-service flow.** It is **not** a "track a live PoW head but freeze the
pivot only while heal catches up, then release" mode. For a live ETC head with no CL, nethermind runs the
*default* re-pivoting path and relies entirely on on-disk node reuse to converge — with no hold and no
keep-up detector.

## Synthesis — implications for fukuii's L7 heal

1. **fukuii's heal-hold intent is validated, not over-engineering.** The peer-rich references have no
   hidden equivalent fukuii is duplicating — geth and besu have *nothing* for scarcity (geth livelocks via
   the statelessPeers cascade; besu refuses to start below 5 peers and its stall path is a TODO), and
   nethermind's `StaticSnapPivot` is the same pin fukuii built, only checkpoint-scoped. On the ETC-scarcity
   axis fukuii is **ahead of** its own ETC authority (core-geth = geth-verbatim).
2. **The right shape for fukuii's live-head heal-hold** = nethermind's `StaticSnapPivot` mechanics
   (pin the pivot, disable the updater, relax peer gating, skip the head-distance wait) but scoped as a
   **temporary hold-until-heal-drains-then-release** rather than a permanent checkpoint pin — the superset
   nethermind does not offer. Reuse `StaticSnapPivot`'s citations as the precedent for the checkpoint-
   bootstrap variant; build the live-head variant as fukuii's own contribution. **This is realized as a
   per-network `pivotPolicy` axis on the existing per-family selector seam** (`RollingPivot` for peer-rich
   ETH, `AdaptiveHold` for scarce ETC, `StaticPivot` for checkpoint bootstrap) — nethermind is the base
   framework, fukuii's practices are evaluated as deltas on top. Design of record:
   [`plan/L7.md`](../../../architecture/fukuii-rebuild/plan/L7.md) §6.8.1.
3. **Preserve content-addressed heal progress across every pivot change** (geth `sync.go:604-606`,
   nethermind `TreeSync.cs:493-532`) — both references do it; it makes hold-vs-roll a decision about the
   *target only*, never a discard. This lets a re-pivot be cheap when the node *chooses* to roll.
4. **Build the heal keep-up / convergence detector none of the three has.** The hold/roll decision should
   be driven by *measured heal-rate vs serve-window-expiry-rate*, not a binary stagnation flag — and a
   genuine "heal is not converging" signal (fail-loud) is a capability all three references lack.
5. **Keep the liveness give-up, but make it fail-loud.** All three references block forever on an
   unhealable chain (correctness over liveness). fukuii's force-complete + on-demand `StateNodeFetcher`
   chooses liveness — defensible where "block until complete" means "never sync ETC" — but the *silent*
   mark-done violates fukuii's fail-loud discipline. Alert on incomplete state and keep pursuing background
   completeness; demote the on-demand fetch from primary completeness mechanism to a fail-loud safety net.
6. **Beat the references on snap-peer scarcity visibility** — count *snap-capable* peers distinctly (all
   three conflate or ignore this), and consider active snap-peer acquisition under scarcity (none do it).
7. **Checkpoint-service is the low-peer entry path** — besu confirms it (a trusted pivot is the *only* way
   into snap below 5 peers); fukuii's `fukuii-checkpoint-service` + the besu-style `(number,hash,TD)`
   anchor (L7 §6.3) is the correct convergence.

## Constants worth surfacing as config (all hard-coded in the references, to fukuii's detriment there)

| Lever | geth | besu | nethermind | fukuii direction |
|---|---|---|---|---|
| pivot serve/roll window | `120<128` const | `120` (config `getPivotBlockWindowValidity`) | `128` (`StateMaxDistanceFromHead`) | **derived, config-surfaced** (§6.8) |
| pivot re-check interval | (beacon loop) | 60 s **hard-coded** | (round-driven) | config knob |
| per-request timeout | `1min` const | 5 s **hard-coded** | 1 s allocate (`SyncConfig.cs:81`) | RTT-aware / config |
| min snap peers to start | none | 5 (`getSyncMinimumPeerCount`) | eth-peer-gated | low + trusted-pivot path |
| pin-the-pivot | — | — | `StaticSnapPivot` | heal-hold (live + checkpoint) |
