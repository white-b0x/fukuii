# go-ethereum — consensus-engines

_Commit/branch documented: `59e89e81e57814a96c429c5cdcaa6ca2e0d6b143` (branch `upstream`,
`v1.17.4-32-g59e89e81e`). Vendored at `/media/dev/2tb/dev/reference-clients-evm/go-ethereum`
(also mirrored read-only at `.claude/repo-references/clients/go-ethereum`, same SHA). Documented
2026-07-13._

_Folds in the B7.0 engine-axis research (`.local/docs/research-july/b7.0-engine-axis-decision.md`,
2026-07-13) — the `beacon.New` wrapper, TTD routing, and the current-HEAD "PoS-only" narrowing were
first surfaced there; this doc cites and expands them to the full subsystem rather than re-deriving._

## Architecture summary
go-ethereum models consensus behind a single **algorithm-agnostic** interface, `consensus.Engine`
(`consensus/consensus.go:59`), against which the rest of the node (block import, miner, header
verification) is written — the engine is swappable and its errors are kept private to force callers
through the shared `consensus` error set. Three concrete engines exist: `ethash` (PoW),
`clique` (PoA), and `beacon` (the PoS/merge engine). Crucially, `beacon` is **not** a peer of the
other two — it is a **decorator** that wraps an inner "eth1" engine (`beacon.Beacon{ethone}`,
`consensus/beacon/consensus.go:61-71`) and routes each header to either the inner engine's
pre-merge rules or its own post-merge rules based on a **content-derived** test (`difficulty == 0`).
Fork/hardfork dispatch is not the engine's job: it lives in `params.ChainConfig`, which the engines
query via `Is<Fork>(num[, time])` predicates — block-number-keyed for pre-merge forks,
timestamp-keyed for post-merge forks. On current HEAD, `CreateConsensusEngine` has been narrowed so
that **only PoS networks boot** — the engine is always `beacon`-wrapped, and standalone real PoW
sealing has been removed.

## Key types / interfaces / files
- `consensus/consensus.go:59-106` — **`consensus.Engine`**, the 11-method algorithm-agnostic
  interface: `Author`, `VerifyHeader`, `VerifyHeaders` (batched, async via channels), `VerifyUncles`,
  `Prepare` (fills consensus header fields inline), `Finalize` (post-tx state mods — rewards /
  withdrawals), `Seal` / `SealHash`, `CalcDifficulty`, `Close`. This is the load-bearing abstraction
  another client compares against.
- `consensus/consensus.go:32-56` — **`ChainHeaderReader` / `ChainReader`**, the narrow blockchain
  views engines are handed (they get `Config()` + header/block lookups, nothing more) — the DI seam
  that keeps engines decoupled from the full blockchain.
- `consensus/errors.go:21-41` — shared `consensus.Err*` values (`ErrUnknownAncestor`,
  `ErrInvalidNumber`, `ErrInvalidTerminalBlock`, …). Engine-specific errors are deliberately kept
  package-private so swapping the engine can't leak engine-specific error identity.
- `consensus/beacon/consensus.go:61-71` — **`Beacon` struct + `New(ethone)`**, the merge wrapper.
  `New` panics on a nested `*Beacon` (`:67-69`) — the wrapper is single-layer by construction.
- `consensus/beacon/consensus.go:413-418` — **`IsPoSHeader(header)`**: `header.Difficulty == 0`.
  The whole merge routing hinges on this one content-derived predicate.
- `consensus/beacon/consensus.go:420-423` — `InnerEngine()` exposes the wrapped eth1 engine.
- `consensus/ethash/ethash.go:29-40` — **`Ethash` struct + `NewFaker()`**. On this HEAD the struct
  holds only fake-mode flags (`fakeFail/fakeDelay/fakeFull`); there are no DAG/mining fields left.
- `consensus/ethash/ethash.go:76-78` — **`Seal` panics**: `"ethash (pow) sealing not supported any
  more"`. Real PoW mining is gone.
- `consensus/ethash/consensus.go:100-300` — ethash header verification (extradata size, timestamps,
  difficulty match, gas, EIP-1559 attrs) + **`CalcDifficulty`** (`:305-332`, the full
  Frontier→Homestead→Byzantium→…→GrayGlacier ice-age-bomb ladder) + `accumulateRewards`
  (`:558-583`, static block reward + uncle rewards). Note: this retains *difficulty/field*
  verification but **no `verifySeal`/hashimoto PoW-hash check** — that was removed (grep for
  `verifySeal|hashimoto|mixDigest` in the package returns nothing).
- `consensus/clique/clique.go:166-203` — **`Clique` struct + `New(config, db)`**: PoA engine with an
  LRU cache of vote `Snapshot`s and recovered signatures.
- `consensus/clique/clique.go:65-67` — `diffInTurn = 2` / `diffNoTurn = 1`: PoA encodes signer
  scheduling in the *difficulty* field (in-turn vs out-of-turn), reusing the same header field PoW
  uses for work.
- `consensus/clique/snapshot.go` — the authorization `Snapshot` (signer set + running votes),
  reached via `c.snapshot(...)` (`clique.go:379`), checkpointed every 1024 blocks
  (`checkpointInterval`, `:48`).
- `eth/ethconfig/config.go:232-242` — **`CreateConsensusEngine(config, db)`**, the selection point
  (see below).
- `params/config.go:420-500` — **`ChainConfig`** with the engine-config sub-objects (`Ethash
  *EthashConfig` `:478`, `Clique *CliqueConfig` `:479`) and every fork field.
- `params/config.go:800-805` — **`IsPostMerge`**; `:1248-1279` — `isBlockForked` / `isTimestampForked`,
  the two dispatch primitives.
- `consensus/misc/{dao.go,gaslimit.go,eip1559,eip4844}` — shared consensus-rule helpers (DAO
  extradata guard, gas-limit bounds, EIP-1559 base fee, EIP-4844 blob gas) that multiple engines
  call, kept out of any single engine.

## Design decisions & rationale
- **One algorithm-agnostic interface, engine-private errors** (`consensus/consensus.go:58`,
  `errors.go:42-45` comment). The node is written once against `Engine`; engines don't leak their
  own error types, so the engine is genuinely swappable. This is the canonical EL consensus
  abstraction every other client is measured against.
- **Merge as composition, not a new fork case** (`consensus/beacon/consensus.go:53-63`). Rather than
  add a "PoS" branch to ethash, geth introduced a thin `Beacon` engine holding one `ethone` inner
  engine and delegating per-header. Pre-merge headers fall through to `ethone`; post-merge headers
  get beacon's own rules. This keeps PoW/PoA verification intact for historical blocks while adding
  PoS on top.
- **Content-derived transition, not a stored transition block** (`consensus/beacon/consensus.go:83-113`,
  `413-418`). The live merge originally used terminal total difficulty (TTD), but TD requires
  replaying from genesis, which breaks once the chain tail is pruned. geth switched to the heuristic
  **`difficulty > 0 ⇒ PoW, difficulty == 0 ⇒ PoS`**, and additionally forbids reverting PoS→PoW
  (`parent.Difficulty==0 && header.Difficulty>0 ⇒ ErrInvalidTerminalBlock`, `:106-108`). `Prepare`
  and `CalcDifficulty` can't use `IsPoSHeader` (difficulty not set yet) so they instead consult
  `ChainConfig.IsPostMerge(num, time)` (`:337-343`, `:398-403`).
- **`VerifyHeaders` splits a batch at the transition point** (`consensus/beacon/consensus.go:120-186`).
  `splitHeaders` partitions on the first `difficulty==0`, runs the pre-batch through `ethone` and the
  post-batch through beacon's own verifier concurrently, then interleaves results in input order —
  transition-aware batch verification without losing async parallelism.
- **Fork dispatch lives in `ChainConfig`, split block-number vs timestamp** (`params/config.go:444`
  comment: "Fork scheduling was switched from blocks to timestamps here"). Pre-merge forks
  (Homestead…GrayGlacier, `MergeNetsplitBlock`) are `*big.Int` block numbers checked with
  `isBlockForked` (`:1248`); post-merge forks (Shanghai, Cancun, Prague, Osaka, Amsterdam, BPO1-5)
  are `*uint64` timestamps checked with `isTimestampForked` (`:1274`). Timestamp-fork predicates also
  gate on `IsLondon` (`:808-810`) so a timestamp fork can't accidentally activate on a chain that
  never reached London.
- **Difficulty field is reused across mechanisms.** PoW: real work target (ethash `CalcDifficulty`
  ice-age ladder). PoA: `1`/`2` for out-of-turn/in-turn signer scheduling (`clique.go:65-67`). PoS:
  constant `0` (`beacon.go:38`). The single header field is overloaded, which is exactly why the
  merge heuristic (`==0`) works.

## Notable patterns (the reusable idea)
1. **Conditional decorator merge engine.** A `beacon`-style wrapper composes over *any* inner engine
   (ethash→PoS, clique→PoS) and routes per-header by a content-derived test — not a config-driven
   per-block engine schedule. B7.0 §B named this: the reference-uniform shape is a *relocation* of
   content-derived routing into a reusable conditional wrapper, and it must be **conditional**
   (skippable for a permanently-PoW chain like ETC) rather than geth's now-mandatory wrap.
2. **Positive engine config objects on the chain-spec** (`Ethash *EthashConfig` / `Clique
   *CliqueConfig`). Selection keys off *which typed sub-object is present*, not an external network
   enum — this is the seed of the "presence-of-field, not `NetworkType`" dispatch B7.0 §A.1 adopts
   (though geth's own fallthrough undermines it; see gotchas).
3. **Narrow reader interfaces as the engine DI seam** (`ChainHeaderReader`/`ChainReader`). Engines
   receive the minimum blockchain surface, keeping them testable and decoupled.
4. **Async batch header verification via `(quit chan, results chan)`** — the signature every engine
   implements, letting the importer parallelize and abort verification.

## Authority note
**go-ethereum is authoritative for the ETH / PoS baseline and for the canonical `consensus.Engine`
interface + EIP reference behavior** — per the Phase-0 authority model, "the `consensus.Engine`
interface, EIP reference behavior." Beacon's PoS header rules, EIP-1559/4844 header verification, and
the timestamp-fork dispatch model are the reference other clients (and fukuii's `beacon` path) align
to.

**go-ethereum is NOT an authority for ETC / PoW.** Current HEAD has *dropped standalone PoW*:
`CreateConsensusEngine` refuses to boot any chain without `TerminalTotalDifficulty`
(`eth/ethconfig/config.go:233-236`, log: "Geth only supports PoS networks"), the `ethash` engine's
`Seal` panics (`ethash.go:76-78`), and the real PoW-hash `verifySeal`/hashimoto path is gone —
`ethash` survives only as a *historical-header verifier* (difficulty ladder + field checks) wrapped
by beacon. **`core-geth` is the ETC/PoW authority** (ETChash/ECIP-1099, ECIP-1017/1111/1122);
aligning fukuii's ETC consensus to current go-ethereum would be a regression. fukuii diverges
deliberately: ETC must remain *permanently PoW-legal*, so fukuii must **not** copy geth's mandatory
beacon wrap — it needs the *conditional* wrap (erigon's shape, B7.0 §C Option 2).

## Gotchas / anti-patterns / things they later changed
- **Dropped standalone PoW (the headline change).** Real ethash mining and PoW-seal verification were
  removed post-merge; the release note literally directs legacy-network operators to "transition
  legacy networks using Geth v1.13.x" (`eth/ethconfig/config.go:234`). Any client that must keep a
  live PoW network (ETC) cannot follow HEAD here — this is the Phase-0 README's canonical example of
  "aligning ETC code to geth is a regression."
- **The `else-means-ethash` fallthrough** (`eth/ethconfig/config.go:238-241`): `if config.Clique !=
  nil { …clique… }; return …ethash…`. Ethash is the *default*, selected by the absence of a Clique
  config rather than a positive Ethash marker. B7.0 §A.1 flags this as the exact anti-pattern to
  avoid — `core-geth`'s own config type and besu both use *positive* keying with no fallthrough;
  fukuii currently replicates geth's bad shape (`BlockchainConfig.scala:513` defaults to ETC) and
  B7.0 exists to fix it.
- **No multi-/ambiguous-engine guard.** geth's selection can't express "both Ethash and Clique set"
  as an error — it just silently prefers Clique. nethermind's `CalculateSealEngineType` throws on >1
  seal engine; B7.0 §A.6 ports that guard regardless of dispatch style.
- **`IsPoSHeader` panics on nil difficulty** (`beacon.go:414-416`) and is explicitly unsafe for
  `Prepare`/`CalcDifficulty` (difficulty not yet set) — those must use `ChainConfig.IsPostMerge`
  instead. A subtle two-path transition test that's easy to get wrong.
- **The merge heuristic trades away provability.** The `difficulty==0` test (vs the original TTD)
  means you can no longer *prove* a past chain transitioned at the correct TTD (`beacon.go:87-99`
  comment). geth accepts this because the transition point is ancient/finalized; a client with
  different pruning assumptions should understand the trade.
- **`Amsterdam`/BPO/UBT fork plumbing is live but experimental** (`params/config.go:450-475`,
  `EnableUBTAtGenesis` is a "temporary flag only for binary devnet testing") — treat the
  bleeding-edge fork fields as unstable when comparing.
