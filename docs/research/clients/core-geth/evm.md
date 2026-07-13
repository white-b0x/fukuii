# core-geth — evm
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

> Framed as **ETC-specific diffs from vanilla go-ethereum** (baseline `59e89e81e`). core-geth
> reuses geth's `core/vm` opcode *implementations* almost verbatim; the ETC divergence is
> **how the active instruction set / gas schedule is selected** — a per-EIP transition model
> instead of geth's named-fork rule sets — plus the ETC-specific **fork schedule** those
> transitions encode.

## Architecture summary

The opcodes themselves (`opAdd`, `opDelegateCall`, `opRevert`, `opPush0`, …) are the same Go
functions as upstream geth. What differs is jump-table construction:

- **Vanilla geth**: builds a fixed ladder of *named* instruction sets —
  `frontierInstructionSet` → `homesteadInstructionSet` → … → `berlinInstructionSet` →
  `londonInstructionSet` → `shanghaiInstructionSet` → `cancunInstructionSet` → `prague`/`osaka`
  (`core/vm/jump_table.go:55-70`). Each is `newXInstructionSet() { is := newPriorInstructionSet();
  … ; return is }` — a chained inheritance. The interpreter picks **one** wholesale based on
  `evm.chainRules` (an `IsBerlin`/`IsLondon`/`IsShanghai`… boolean struct precomputed from the
  chain config at a given block/time).
- **core-geth**: builds **one** jump table *additively* from a flat base, gating each opcode/
  gas change on an individual EIP transition. `instructionSetForConfig(config, isPostMerge, bn,
  bt)` (`core/vm/jump_table.go:72`) starts from `newBaseInstructionSet()` and applies a long
  sequence of `if config.IsEnabled(config.GetEIPxTransition, bn) { instructionSet[OP] = … }`
  blocks. There is no `chainRules` boolean struct and no per-fork named table.

This additive model is the whole reason multi-geth/core-geth can host ETC: ETC's hard forks
are **not** the ETH forks. Each ETC fork activates a *bespoke subset* of ETH EIPs at an
ETC-specific block height, so a "pick one named ETH fork table" design cannot express it. A
flat "enable each EIP independently by its own transition block" design can.

## Key types / interfaces / files

- `core/vm/interpreter.go:82` — `table = instructionSetForConfig(evm.chainConfig,
  evm.Context.Random != nil, evm.Context.BlockNumber, &evm.Context.Time)`: the interpreter
  builds its table per-config rather than indexing a precomputed named set.
- `core/vm/jump_table.go:72` — `instructionSetForConfig(config ctypes.ChainConfigurator,
  isPostMerge bool, bn *big.Int, bt *uint64) *JumpTable`: the additive assembler. Representative
  gates: `GetEIP7Transition`→DELEGATECALL (`:76`), `GetEIP150Transition`→Tangerine gas reprice
  (`:87`), `GetEIP160Transition`→EXP gas (`:97`), `GetEIP140Transition`→REVERT (`:101`),
  `GetEIP214Transition`→STATICCALL (`:110`), `GetEIP211Transition`→RETURNDATASIZE/COPY (`:120`),
  `GetEIP145Transition`→SHL/SHR/SAR, …
- `core/vm/interpreter.go:113` — even runtime read-only enforcement is gated per-EIP:
  `IsEnabled(in.evm.chainConfig.GetEIP214Transition, ...)`.
- `params/config_classic.go:30-82` — the ETC mainnet fork schedule as per-EIP block numbers
  (the authoritative mapping; see table below).
- `params/types/ctypes` — `ChainConfigurator` interface: the `GetEIPxTransition() *uint64`
  getters and `IsEnabled(getter, blockNumber)` helper that replace geth's `*params.ChainConfig`
  + `Rules` struct.

## Design decisions & rationale

### ETC fork schedule → ETH EIP-set mapping (`config_classic.go`)

ETC's named forks map onto ETH opcode/gas sets at ETC-specific heights:

| ETC fork | ~ETH equivalent | Block (mainnet) | Representative EIPs enabled |
|----------|-----------------|-----------------|-----------------------------|
| (Homestead) | Homestead | 1,150,000 | EIP2, EIP7 |
| (Tangerine/Spurious) | EIP150 / EIP158 | 2,500,000 / 8,772,000 | EIP150 gas reprice; EIP161/170 |
| **Atlantis** | Byzantium | 8,772,000 | EIP100,140,198,211,212,213,214,658 |
| **Agharta** | Constantinople/Petersburg | 9,573,000 | EIP145,1014,1052 (note: **no EIP1283** — repriced separately) |
| **Phoenix** | Istanbul | 10,500,839 | EIP152,1108,1344,1884,2028,2200 |
| **Magneto** | Berlin | 13,189,133 | EIP2565,2718,2929,2930 |
| **Mystique** | London (partial) | 14,525,000 | EIP3529,3541 — **not** EIP1559/3198/3554 |
| **Spiral** | Shanghai (partial) | 19,250,000 | EIP3651,3855,3860,6049 — **not** EIP4895 withdrawals |

Two ETC-only entries in the same config that are *not* ETH EIPs:
- `ECIP1010PauseBlock`/`ECIP1010Length` (`:47-48`) — the difficulty-bomb pause/defuse.
- `DisposalBlock: 5,900,000` (`:53`) — permanent difficulty-bomb disposal.
- `ECBP1100FBlock`/`ECBP1100DeactivateFBlock` (`:86-87`) — MESS (ECIP-1100) subjective
  fork-choice window.
- `ECIP1099FBlock: 11,700,000` (`:88`) — **Etchash** epoch-doubling (see below).

### No EIP-1559 burn at the EVM level (upstream ETC)

ETC's classic config sets **no** `EIP1559FBlock` — it is `nil`, so the EVM never activates a
base-fee. Mystique deliberately takes only EIP3529 (gas-refund reduction) and EIP3541 (reject
`0xEF` contracts) from London, and skips EIP1559/3198/3554. Historically ETC has traditional
gas pricing with no burn. (ECIP-1559/Olympia adds a base-fee floor — but that is **fukuii's
Olympia overlay on `main`, NOT this `upstream` authority ref**; do not attribute it to
core-geth upstream.)

### ECIP-1099 / Etchash — a DAG concern, not an EVM one

`ECIP1099FBlock` doubles the Ethash epoch length (30,000 → 60,000 blocks) to cap DAG growth so
ETC stays mineable on 4 GB GPUs. This touches cache/dataset sizing in `consensus/ethash`, not
`core/vm`. It is listed here only to be explicit that it is **not** an opcode/jump-table
change — the EVM instruction set is unaffected by Etchash.

## Notable patterns (the reusable idea)

- **Additive, per-EIP jump-table construction** (`instructionSetForConfig`) instead of a
  ladder of named fork tables. This decouples "which opcodes/gas costs are live" from "which
  canonical ETH fork are we on," which is exactly what a fork whose EIP groupings differ from
  ETH's requires. fukuii's EVM must reproduce the *effective set at a given ETC block*, not a
  named ETH fork — the network-prefixed `Etc*OpCodes`/`Etc*FeeSchedule` objects encode the
  same idea.
- **`IsEnabled(getter, blockNumber)` as the universal gate** — one uniform mechanism for every
  fork decision (jump table, gas schedule, read-only enforcement, tx-type admission), rather
  than geth's split between a `Rules` struct (VM) and `ChainConfig.IsX()` methods (elsewhere).

## Authority note

**core-geth IS authoritative for the ETC fork schedule and effective opcode/gas set.** The
per-EIP block numbers in `params/config_classic.go` (and `config_mordor.go`) at `4185df450`
are the byte-exact heights fukuii must dispatch on; the additive rules in
`instructionSetForConfig` define which opcodes/gas costs are live at each height. The opcode
*implementations* are shared with go-ethereum and geth remains a valid cross-check for opcode
semantics — but the **activation schedule and EIP groupings are ETC's and only core-geth
encodes them**.

## Gotchas / anti-patterns / things they later changed

- **Do not map an ETC fork to a named ETH fork wholesale.** Agharta ≈ Constantinople but
  *omits EIP1283* (SSTORE net-gas metering) — it takes Petersburg-equivalent behavior. Mystique
  ≈ London but omits EIP1559. Spiral ≈ Shanghai but omits EIP4895 withdrawals. Reasoning "ETC is
  at London, so enable all London EIPs" produces wrong gas/opcode behavior.
- **`EIP2718` (typed-tx envelope) is enabled (Magneto) but `EIP1559` is not.** ETC accepts
  access-list (type-1) transactions but not dynamic-fee (type-2) — a subtlety that also shapes
  the txpool (see `txpool.md`).
- **Difficulty-bomb params (ECIP-1010/Disposal) sit in the same config struct as EIPs** but are
  consensus-difficulty concerns, not EVM concerns — do not treat them as opcode gates.
- **The per-EIP model has no `chainRules` boolean struct**; code ported *from* upstream geth
  that expects `evm.chainRules.IsLondon` must be rewritten to `IsEnabled(GetEIPxTransition, bn)`
  or it will silently read the wrong (or a non-existent) flag.
