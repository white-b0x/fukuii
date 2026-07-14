# Topic — PoA consensus methods + ETC's deprecated PoA testnet (Kotti)
_Documented 2026-07-13. Cross-client survey (git-archaeology for deprecated). Cross-refs the per-client consensus-engines/multi-network docs._

Scope: the three live PoA families across the reference clients (Clique, IBFT2/QBFT, AuRa),
plus ETC's own deprecated Clique-based PoA testnet **Kotti** recovered via git archaeology.
Every claim carries a commit hash or `path:line`. Repos + pinned upstream SHAs:
go-ethereum `59e89e81e`, core-geth `4185df450`, besu `3fd233a4f9`, nethermind `0d09a09ed`,
erigon `f1d79d699e`.

Cross-refs (read, not duplicated):
`docs/research/clients/besu/consensus-engines.md` (the primary PoA/multi-consensus structural
reference — CliqueProtocolSchedule decorator + G1/G2/G3 seams), `nethermind/consensus-engines.md`
(self-declaring plugin registry, AuRa/Clique plugins), `besu/history-pow-etc.md` (besu's ETC
removal, Feb 2026), and the per-client `multi-network.md` files.

---

## Clique (EIP-225)

Geth-origin PoA: a fixed/voted **signer set** takes turns sealing empty-difficulty blocks. Header
`extraData` carries a 32-byte vanity + the signer list (only on epoch blocks) + a 65-byte seal;
the signer is recovered by ecrecover over the header. Voting is encoded in the header `coinbase`
(candidate) + `nonce` magic value.

**Reference mechanics (go-ethereum — the canonical source):**
- `consensus/clique/clique.go:55` — `epochLength = 30000` (checkpoint + reset pending votes).
- `consensus/clique/clique.go:65-66` — `diffInTurn = 2` (in-turn signer), `diffNoTurn = 1`
  (out-of-turn) — the difficulty-1/2 rule.
- `consensus/clique/clique.go:60-61` — `nonceAuthVote = 0xffffffffffffffff` (add signer),
  `nonceDropVote = 0x0000000000000000` (remove signer).
- `consensus/clique/clique.go:52` — `checkpointInterval = 1024` (vote-snapshot persistence).
- `consensus/clique/snapshot.go` — the running signer-set / vote-tally snapshot.

**Where each client carries Clique, and who is authority for what:**

| Client | Location | Validation | **Block production (sealing)** |
|--------|----------|:----------:|:------------------------------:|
| go-ethereum | `consensus/clique/{clique,snapshot}.go` | yes | yes (canonical) |
| core-geth | `consensus/clique/{clique,api,snapshot}.go` (geth-lineage, `+api.go`) | yes | **yes — sealing authority** |
| besu | `consensus/clique/…` (`CliqueProtocolSchedule`, `CliqueExtraData`, `CliqueBlockInterface`) | yes | **REMOVED** — `CliqueBesuControllerBuilder.createMiningCoordinator` returns `NoopMiningCoordinator` (`:71`); Clique mining without TTD is a hard error (`BesuController.java:365-371`) |
| nethermind | `Nethermind.Consensus.Clique/` (`Clique.cs`, `CliqueBlockProducer.cs`, `CliqueChainSpecEngineParameters.cs`) | yes | yes (`CliqueBlockProducer.cs` present) |
| erigon | — (no `consensus/clique`; grep at `f1d79d699e` empty) | no | no |

**Authority for Clique SEALING = core-geth** (geth-lineage, still produces). besu dropped Clique
*production* entirely (sync/validate only) — do **not** copy besu's stubbed sealer path when
building B7.1; besu is the authority for the *validation + seam structure* (extraData codec,
`ValidatorProvider`, `BlockInterface` — see `besu/consensus-engines.md` §"The three B7.1 seams"),
core-geth (and go-ethereum upstream) for the actual seal-and-produce logic. nethermind is a
secondary producing reference. erigon carries no Clique.

---

## IBFT 2.0 & QBFT (besu BFT)

Byzantine-fault-tolerant PoA for enterprise/consortium chains: a known **validator set** runs a
round-based propose/prepare/commit protocol with **instant finality — no reorgs** (a committed
block is final, unlike Clique's probabilistic fork-choice). QBFT is the successor to IBFT 2.0
(same protocol shape, cleaner extraData/vote encoding + on-chain validator-contract mode).

**besu = THE authority** (the only reference client that implements BFT PoA). Locations at
`3fd233a4f9`:
- `consensus/ibft`, `consensus/ibftlegacy` (IBFT 1 — kept for sync only, **mining is a hard
  error**, `BesuController.java:359-361`), `consensus/qbft`, `consensus/qbft-core`.
- Shared BFT plumbing in `consensus/common/…/bft/` — `BftBlockCreatorFactory.java:171-180`
  encodes validator list + vote + proposer seal into `extraData` (the producing-PoA "sealer"
  analogue); `BftContext` holds the live validator set.
- Validator-set sourcing (`consensus/common/…/validator/`): **block/vote mode**
  (`BlockValidatorProvider`), **contract mode** (`qbft/…/validator/TransactionValidatorProvider.java`,
  reads validators from an on-chain contract), **forking mode** (`ForkingValidatorProvider`,
  switches source per fork block).
- IBFT→QBFT migration = a `Map<Long, BesuControllerBuilder>`
  (`ConsensusScheduleBesuControllerBuilder`, `BesuController.java:399-421`) that registers **both**
  wire protocols across the boundary so peers gossip either side.

For fukuii's structural mapping (mechanism-decorates-fork-schedule, the G1/G2/G3 seams, the
`Map<block, builder>` transition shape), see `besu/consensus-engines.md` — not re-derived here.
No other reference client (go-ethereum, core-geth, nethermind, erigon) implements IBFT/QBFT.

---

## AuRa (Authority Round)

Parity-origin PoA used by the Gnosis/xDai chain family: **step-based** — time is divided into
fixed-duration steps, and the validator whose index matches `step % validatorCount` is the
primary sealer for that step. The validator set is typically read from an **on-chain validator
contract** (POSDAO), with a reporting/finalization layer.

**Two producing implementations; besu/core-geth/go-ethereum have none:**
- **nethermind** — `Nethermind.Consensus.AuRa/` (the richer impl): `AuRaStepCalculator.cs`
  (step→time), `AuRaValidatorFactory.cs`, `Contracts/ValidatorContract.cs` +
  `ValidatorContract.Posdao.cs` (on-chain validator set), `Contracts/ReportingValidatorContract.cs`,
  `AuRaBlockFinalizationManager.cs`, `AuRaSealValidator.cs`. Ships as a self-declaring plugin
  (`AuRaPlugin`, `SealEngineType => AuRa`) — see `nethermind/consensus-engines.md`.
- **erigon** — `execution/protocol/rules/aura/aura.go` (`Step` struct + `StepDurationInfo`,
  `:58-65`), with generated contract bindings `aura/auraabi/{gen_validator_set,gen_block_reward}.go`
  for the Gnosis validator-set / block-reward contracts.

AuRa is a **secondary** PoA reference (Gnosis-specific, Parity heritage). nethermind + erigon are
the authorities; it has no ETC or fukuii-roadmap relevance.

---

## ETC Kotti PoA testnet (deprecated — git archaeology)

**Kotti** was Ethereum Classic's public **cross-client Clique-based PoA testnet**, chain ID **6**
/ network **6**, launched ~Jan 2019 (genesis `timestamp 1546461831` ≈ 2019-01-02, per core-geth
`params/genesis_kotti.go` `DefaultKottiGenesisBlock`), operated by ETC Labs / the ETC ecosystem,
and deprecated ~2022. It ran the ETC fork schedule (Atlantis→Agharta→Phoenix→Magneto→Mystique)
under Clique rather than Ethash — i.e. ETC's own testnet was **PoA**, which is the direct historical
precedent for fukuii's B7.1 Clique work.

**Recovered config (core-geth, pre-drop `bcd0423e9^:params/config_kotti.go`):**
- `NetworkID: 6, ChainID: 6`; `KottiGenesisHash = 0x14c2283285a88fe5fce9bf5c573ab03d6616695d717b12a127188bcacfc743c4`.
- `Clique: { Period: 15, Epoch: 30000 }` (15 s block time, 30 000-block epoch — standard Clique).
- ECIP fork schedule: EIP-160/161/170 + Byzantium-eq (Atlantis) `716617`, Constantinople-eq
  (Agharta) `1705549`, Istanbul-eq (Phoenix, ECIP-1088) `2200013`, Berlin-eq (Magneto) `4368634`,
  London-partial (Mystique) `5578000`.
- Carried `RequireBlockHashes` checkpoints including a documented "BAD BLOCK" at height `2058192`
  (`invalid gas used`) — evidence of a real consensus incident on the live testnet.
- Bootnodes (`bootnodes_kotti.go`): `@q9f` enodes (`51.15.70.7:41235`, `51.15.41.19:30303`) + DNS
  `dnsPrefixETC + all.kotti.blockd.info`.
- Genesis `extraData` encodes a single Clique signer; `gasLimit 10485760`, `difficulty 1`.

**What each client carried, and when it was removed:**

| Client | Carried Kotti? | Form | Removed |
|--------|:--------------:|------|---------|
| **core-geth** | **yes** | `params/{config,genesis,bootnodes,alloc}_kotti.go` + CLI `--kotti` flags + forkid tests + regression RLP | **`bcd0423e9` (2023-07-03) "drop kotti support"** — ancestor of upstream `4185df450` (confirmed via `merge-base --is-ancestor`) |
| **besu** | **yes** | `config/src/main/resources/kotti.json` (815 lines): `chainId 6`, `clique {blockperiodseconds:15, epochlength:30000}`, `difficulty 0x1`, atlantis→mystique fork blocks, 10 bootnodes | **`ee0fa6b4b9` (#5816, 2023-08-29) "Drop Kotti Network support (ETC)"** — ancestor of upstream. (besu later removed **all** ETC support, Feb 2026 — see `besu/history-pow-etc.md`) |
| **nethermind** | **no chainspec** | only a doc comment in `Nethermind.Core/BlockchainIds.cs` — *"6: Kotti Classic, the public cross-client PoA testnet for Classic"* (seen in `-S 'Kotti'` context of `66e72f4a5`). No `kotti.json`/`kotti.cfg` ever shipped. | comment gone by upstream `0d09a09ed` (grep for `kotti` there is empty); nethermind retains only ETC network-id constants `EthereumClassicMainnet=61` / `EthereumClassicTestnet=62` (`BlockchainIds.cs:26-27`), no ETC/Kotti chain config |
| **go-ethereum** | **NO** | — | never had it (ETH client, no ETC/Kotti; `-S`/`--grep`/`grep` all empty at `59e89e81e`) |
| **erigon** | **NO** | — | never had it (all Kotti searches empty at `f1d79d699e`) |

So Kotti lived in the **two ETC-aware clients** (core-geth, besu), both of which dropped it within
~8 weeks of each other in mid-2023, a year+ after the network went dark. nethermind only ever
*documented* the chain-id; go-ethereum and erigon never touched it.

---

## Any other deprecated ETC networks

- **Morden** — the original ETC PoW testnet, chain id 2. Survives only as documentation in
  nethermind `BlockchainIds.cs:10` (*"2: Morden Classic, the public Ethereum Classic PoW testnet"*);
  no live config in any surveyed repo. Predecessor to Mordor.
- **Mordor** — the **current, NOT-deprecated** ETC PoW testnet (network 7 / chain id 63). Live in
  core-geth `params/config_mordor.go` at upstream `4185df450` (alongside `config_classic.go` for
  ETC mainnet). This is fukuii's active ETC testnet — out of scope for this "deprecated" survey but
  noted for contrast.
- **Astor** — **NOT FOUND**. No `astor` config, genesis, or log entry in core-geth, besu,
  go-ethereum, nethermind, or erigon (`--grep`/`-S` all empty). If Astor existed as an ETC testnet
  it left no trace in any surveyed reference client.
- (core-geth also once carried unrelated non-ETC nets — `mix`, `social`/`ethersocial` — removed in
  `a899229f2`; noted only to disambiguate the `-S` noise, not ETC.)

At upstream, the **only ETC chain configs core-geth still ships** are `config_classic.go` (mainnet)
and `config_mordor.go` (testnet) — Kotti and Morden are gone.

---

## Cross-client PoA matrix (method × client × current/deprecated × fukuii verdict)

| Method | go-ethereum | core-geth | besu | nethermind | erigon | Status | Authority | fukuii verdict |
|--------|:-----------:|:---------:|:----:|:----------:|:------:|--------|-----------|----------------|
| **Clique** (EIP-225) | validate+seal | validate+**seal** | validate only (seal removed) | validate+seal | — | current | **core-geth** (sealing); **besu** (seam structure) | **OPTIONAL (private-testnet / consortium)** — B7.1 planned, NET-02. In scope. |
| **IBFT 2.0** | — | — | validate only (mining=error) | — | — | current (legacy) | **besu** | OPTIONAL (enterprise) — QBFT preferred; carry for interop only |
| **QBFT** | — | — | **validate+seal** | — | — | current | **besu** | **OPTIONAL (enterprise / consortium / custody)** — instant finality; highest-value BFT for fukuii's enterprise scope (B7.2) |
| **AuRa** | — | — | — | **validate+seal** | validate+seal (Gnosis) | current (Gnosis-only) | **nethermind / erigon** | OBSOLETE for fukuii — Parity/Gnosis-specific, no ETC demand; skip unless a Gnosis-family target appears |
| **Kotti** (ETC PoA testnet) | never | dropped 2023-07 | dropped 2023-08 | doc-comment only | never | **DEPRECATED / dead** | (was core-geth + besu) | **OBSOLETE** — do not re-add; cite as *precedent* only |

---

## fukuii implications (B7.1 Clique, NET-02, private/consortium scope)

fukuii's product scope **explicitly includes private/consortium networks** (memory: file-tree &
seam direction; B7.1 Clique planned; NET-02 Clique leads Batch 7's Private Network Stack). PoA is
therefore squarely in scope, and this survey resolves the authority + verdict per method:

1. **Clique (B7.1 / NET-02) — build it, source the seal from core-geth/go-ethereum, the seam
   structure from besu.** core-geth is the sealing authority (besu deliberately stubbed Clique
   production, so its current `NoopMiningCoordinator` path is a trap — validate against it, don't
   copy it). besu is the authority for the *shape*: `CliqueProtocolSchedule` decorating a generic
   schedule + the three seams (Sealer / ValidatorProvider / BlockInterface) that already map 1:1
   onto fukuii's Batch-5-designed G1/G2/G3 (`besu/consensus-engines.md`). Clique constants are
   fixed and portable: epoch 30000, diff in-turn 2 / out-of-turn 1, auth/drop nonce magic values,
   1024-block checkpoint.

2. **Kotti is fukuii's legitimizing precedent, not a target.** ETC itself ran a **Clique** PoA
   testnet (Kotti, chain 6, period 15 / epoch 30000, ECIP fork schedule) — so a fukuii private/
   consortium Clique network is ETC-lineage-native, not a foreign graft. The network is dead and
   was dropped from both ETC-aware clients in mid-2023; do **not** re-add a Kotti chainspec.
   fukuii's live ETC testnet remains **Mordor** (PoW). Kotti's recovered `config_kotti.go`
   (period 15, epoch 30000, single genesis signer, `RequireBlockHashes` including a real bad-block
   incident at 2058192) is a concrete worked example of an ETC-flavoured Clique genesis for
   B7.1/custom-network authoring (`fukuii-custom-networks` skill).

3. **IBFT2/QBFT (B7.2) — besu-only, enterprise-facing.** For the enterprise/consortium/custody
   product line (JPMC/E*TRADE/Fireblocks per the mission memo), QBFT's **instant finality / no
   reorg** is the differentiator over Clique. besu is the sole authority; its `Map<block, builder>`
   IBFT→QBFT migration + dual-wire-protocol pattern is the reference for fukuii's `EngineSchedule`
   (B7.0-c). IBFT 1/legacy: carry only for interop, mining is unsupported even in besu.

4. **AuRa — decline for now.** No ETC or fukuii-roadmap demand; it's Gnosis/Parity-specific and
   pulls in an on-chain validator-contract + step-timing subsystem (nethermind/erigon). Revisit
   only if a Gnosis-family network becomes a target.

5. **Architecture takeaway (unchanged from the besu/nethermind consensus-engines docs):** add PoA
   as a **mechanism that decorates the fork schedule** (besu) selected by a **positive genesis
   marker with a single uniqueness guard** (nethermind's `require(sealEngines.size == 1)`), never
   an "else = PoW" fallthrough. Clique/QBFT slot in as fukuii `{pow,pos,poa}` mechanism leaves per
   the file-tree seam direction; Batch 7 is where they get built (Batch 5 delivered only the tree +
   seam shape).
