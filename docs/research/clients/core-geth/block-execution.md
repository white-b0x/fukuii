# core-geth — block-execution
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

> Framed as **ETC-specific diffs from vanilla go-ethereum** (baseline documented at
> `reference-clients-evm/go-ethereum` HEAD `59e89e81e`). core-geth is a go-ethereum fork
> (originally "multi-geth"); the block-execution layer is where ETC's single largest
> consensus divergence lives — the **ECIP-1017 fixed-supply monetary policy**.

## Architecture summary

Block finalization in core-geth follows geth's `consensus.Engine.Finalize` /
`FinalizeAndAssemble` shape, but the reward accumulation is relocated out of the ethash
package into a dedicated `params/mutations` package so the *same* reward code can serve
multiple networks (ETC, ETH-classic-era, plus test/vanity chains) selected purely by chain
config. The pivotal fork in behavior is:

- **Vanilla geth**: `accumulateRewards()` is a private function inside
  `consensus/ethash/consensus.go`. The block reward is a fixed constant that steps down only
  at the two ETH monetary forks — `FrontierBlockReward` (5 ETH) → `ByzantiumBlockReward`
  (3 ETH) → `ConstantinopleBlockReward` (2 ETH) — and never again. Uncle/nephew rewards use
  the classic `(uncle.Number + 8 - header.Number) * reward / 8` and `reward / 32` formulas.
- **core-geth (ETC)**: reward accumulation dispatches on `GetEthashECIP1017Transition`. Once
  ECIP-1017 is active, the reward is recomputed **every block** from a *disinflationary era
  function* — 5 ETC, then reduced 20 % (×4/5) at the boundary of every 5,000,000-block era —
  and the uncle/nephew reward reductions change shape after Era 1.

Two other ETC-relevant execution divergences: the **DAO irregular state change is absent**
(ETC's defining event — the chain that did *not* execute the DAO bailout), and every
fork-gated behavior is keyed off individual **per-EIP transition block numbers** rather than
geth's named-fork `chainRules`.

## Key types / interfaces / files

- `params/mutations/rewards.go:37` — `GetRewards(config, header, uncles)`: the dispatcher.
  Line 38: `if config.IsEnabled(config.GetEthashECIP1017Transition, header.Number)` → routes
  to `ecip1017BlockReward`; otherwise falls through to geth's legacy Byzantium/Constantinople
  step-down logic (lines 42–61).
- `params/mutations/rewards.go:66` — `AccumulateRewards(config, state, header, uncles)`:
  credits `header.Coinbase` with the miner reward and each `uncle.Coinbase` with its uncle
  reward via `state.AddBalance`. This is the single state-mutation entry point.
- `params/mutations/rewards_classic.go:27` — `ecip1017BlockReward(config, header, uncles)`:
  the ECIP-1017 reward assembler (see era math below).
- `params/mutations/rewards_classic.go:49` — `GetBlockEra(blockNum, eraLength)`: the era
  index function.
- `params/mutations/rewards.go:109` — `GetBlockWinnerRewardByEra(era, blockReward)`: the
  disinflation `reward × 4^era / 5^era`.
- `params/mutations/rewards.go:81` — `GetBlockUncleRewardByEra(...)` and `:98`
  `GetBlockWinnerRewardForUnclesByEra(...)`: era-aware uncle & nephew (inclusion) rewards.
- `consensus/ethash/consensus.go:620` — `Ethash.Finalize(...)`: the hook — its whole body is
  `mutations.AccumulateRewards(chain.Config(), state, header, uncles)`.
- `consensus/ethash/consensus.go:627` — `Ethash.FinalizeAndAssemble(...)`: calls `Finalize`,
  then `header.Root = state.IntermediateRoot(... GetEIP161dTransition ...)`; rejects any block
  carrying withdrawals (`ethash does not support withdrawals` — no beacon-chain path for PoW).
- `params/config_classic.go:50-51` — `ECIP1017FBlock: 5_000_000`, `ECIP1017EraRounds:
  5_000_000` for ETC mainnet.
- `params/config_classic.go:113-114` — `DisinflationRateQuotient = 4`,
  `DisinflationRateDivisor = 5` (the 20 % reduction ratio).
- `params/vars/protocol_params.go:27` — `FrontierBlockReward = 5e18` (the Era-0 base, shared
  with geth but never stepped down by Byzantium/Constantinople on the ECIP-1017 path).
- `params/config_mordor.go:92-93` — Mordor testnet: `ECIP1017FBlock: 0`,
  `ECIP1017EraRounds: 2_000_000` (ECIP-1017 active from genesis, shorter eras).
- `params/mutations/dao.go:50` — `VerifyDAOHeaderExtraData(...)`; `:106` `ApplyDAOHardFork`.
- `core/state_processor.go:71-74` — the DAO application gate.

## Design decisions & rationale

### ECIP-1017 era math (the fukuii-critical datapoint)

The reward for a block is derived in three steps. Assembler
(`rewards_classic.go:27-45`):

```
blockReward := 5e18                                   // FrontierBlockReward, wei
eraLen      := config.GetEthashECIP1017EraRounds()    // 5,000,000 on ETC mainnet
era         := GetBlockEra(header.Number, eraLen)     // zero-indexed era
wr          := GetBlockWinnerRewardByEra(era, blockReward)               // base era reward
wr         += GetBlockWinnerRewardForUnclesByEra(era, uncles, blockReward) // + nephew bonus
uncleRewards[i] = GetBlockUncleRewardByEra(era, header, uncle_i, blockReward)
```

**Era index** — `GetBlockEra` (`rewards_classic.go:49-62`):

```
if blockNum < 1: return 0
remainder = (blockNum - 1) mod eraLength
base      = blockNum - remainder
era       = base / eraLength            // (the final mod-by-1 in the source is a no-op)
```

The `-1` before the modulo is the subtle, byte-critical detail: it makes the era boundary
land so that block `N·eraLength + 1` opens era `N`. Concretely with `eraLength = 5,000,000`:
blocks `1 … 5,000,000` are Era 0 (index 0), blocks `5,000,001 … 10,000,000` are Era 1, etc.
Getting the off-by-one wrong shifts the entire reward schedule by one block and breaks
state-root parity.

**Disinflation** — `GetBlockWinnerRewardByEra` (`rewards.go:109-127`):

```
era == 0 : reward = 5e18                       (exact copy of the base)
era >= 1 : reward = blockReward × 4^era / 5^era
```

Computed as `q = 4^era`, `d = 5^era`, `r = blockReward × q / d` using `uint256` `Exp` — i.e.
integer exponentiation of the quotient/divisor *separately* before the multiply-then-divide,
so the result is `5e18 × (4/5)^era` with truncating integer division. This yields the ETC
emission ladder:

| Era | Blocks (mainnet) | Winner reward |
|-----|------------------|---------------|
| 0 | 1 – 5,000,000 | 5 ETC |
| 1 | 5,000,001 – 10,000,000 | 4 ETC |
| 2 | 10,000,001 – 15,000,000 | 3.2 ETC |
| 3 | 15,000,001 – 20,000,000 | 2.56 ETC |
| … | … | ×4/5 each era |

**Uncle & nephew rewards** — the reduction ECIP-1017 applied alongside the emission cut:

- *Era 0* keeps the ETH-legacy uncle formula (`rewards.go:84-92`):
  `uncleReward = (uncle.Number + 8 - header.Number) × 5e18 / 8`, and the nephew (winner's
  inclusion bonus) is `1/32` of the era reward per uncle (`rewards.go:76-77`,
  `getEraUncleBlockReward`).
- *Era ≥ 1* (`rewards.go:81-93`, `76-77`): **both** the uncle miner's reward and the winner's
  per-uncle nephew bonus become a flat `GetBlockWinnerRewardByEra(era) / 32`. The distance-
  weighted `(uncle.Number + 8 - header.Number)/8` formula is dropped entirely — a deliberate
  simplification baked into the spec so uncle economics also disinflate with the era.

`GetBlockWinnerRewardForUnclesByEra` (`rewards.go:98-105`) simply sums the per-uncle nephew
bonus over the (already-validated, ≤2) uncle set.

### DAO-fork absence

ETC is *defined* by not executing the DAO irregular state change. In core-geth this is not a
special case removed from the code — it is expressed as config. `VerifyDAOHeaderExtraData`
(`dao.go:50-58`) short-circuits to `nil` when `GetEthashEIP779Transition()` is `nil`, and
`state_processor.go:71-74` only calls `mutations.ApplyDAOHardFork(statedb)` when
`IsEnabled(GetEthashEIP779Transition, blockNumber)` *and* the block number equals the fork
block. The classic config leaves `DAOForkBlock` commented out / `nil`
(`config_classic.go:41`, `:126`), so the ~11.6M-ETH balance transfer to the WithdrawDAO
contract that vanilla geth performs at block 1,920,000 **never runs** on ETC. The DAO block
still appears in `RequireBlockHashes` (`config_classic.go:79`) as a plain checkpoint — ETC
extended the *original* chain past 1,920,000 unchanged.

### Fork gating via per-EIP transitions

Every execution-time fork decision uses `config.IsEnabled(config.GetEIPxTransition, num)`
against an individual per-EIP block number, not geth's monolithic `chainRules` struct. This
is what lets ETC assemble non-standard fork *groupings* (its Atlantis/Agharta/Phoenix/Magneto/
Mystique/Spiral forks each activate a bespoke subset of ETH EIPs at an ETC-specific height) —
see `evm.md` for the jump-table consequences.

## Notable patterns (the reusable idea)

- **Reward-as-config, not reward-as-code-branch.** By moving accumulation into
  `params/mutations` and dispatching on `GetEthashECIP1017Transition`, the monetary policy is
  a property of the chain configurator, not of the engine. fukuii mirrors this: its
  `BlockRewardCalculator.scala` is the ECIP-1017 era function, wired into the ledger's
  finalize path — validated byte-for-byte against `GetBlockWinnerRewardByEra` /
  `GetBlockUncleRewardByEra`.
- **Separate integer exponentiation of quotient and divisor** (`4^era` and `5^era` computed
  independently, then `×q/d`) is the exact arithmetic that must be reproduced — reordering to
  `(4/5)^era` in floating point, or multiplying before exponentiating, changes the truncation
  and desyncs the state root.
- **Absence expressed as `nil` config**, not as deleted code, keeps a single codebase serving
  both DAO-fork and non-DAO-fork chains.

## Authority note

**core-geth IS authoritative for ETC block rewards.** `rewards.go` +
`rewards_classic.go` at `4185df450` are the byte-exact reference for ECIP-1017 emission,
era boundaries, and the era-dependent uncle/nephew reductions. fukuii's
`BlockRewardCalculator.scala` is validated against these functions; when in doubt about a
reward value, the era index formula (`GetBlockEra`, note the `-1`) and
`GetBlockWinnerRewardByEra` here are the ground truth, not any prose spec. This layer serves
**all node roles** — it is consensus — but is of particular interest to the mining-pool /
validator use-case, since it defines the exact ETC issuance a miner is paid.

## Gotchas / anti-patterns / things they later changed

- **The off-by-one in `GetBlockEra`.** `(blockNum - 1) mod eraLength` — not `blockNum mod
  eraLength`. The first block of an era is `N·eraLength + 1`, not `N·eraLength`. This is the
  most common place a reimplementation desyncs.
- **Era-0 vs Era-≥1 uncle formulas are genuinely different**, not a smooth scaling. A
  reimplementation that keeps the distance-weighted `(uncle.Number + 8 - header.Number)/8`
  formula for all eras will produce correct Era-0 values but wrong Era-1+ uncle rewards.
- **`FrontierBlockReward` is shared with geth but the step-downs are not applied.** On the
  ECIP-1017 path the Byzantium (`3e18`) and Constantinople (`2e18`) constants in vanilla geth
  are never consulted — the disinflation function fully owns the schedule. Do not blend the
  two mechanisms.
- **Mordor differs from mainnet**: eras are 2,000,000 blocks and ECIP-1017 is active from
  block 0 (`config_mordor.go:92-93`) — test any era-boundary logic against *both* configs.
- **Withdrawals hard-rejected.** `FinalizeAndAssemble` errors on any non-empty `withdrawals`
  slice (`consensus.go:627`) — PoW ETC has no beacon-chain withdrawal system-call path, unlike
  post-Merge geth.
