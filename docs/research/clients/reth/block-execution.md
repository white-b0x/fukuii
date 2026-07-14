# reth — block-execution
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth's block execution is a **three-layer trait stack**, glued together by one umbrella
config trait (`ConfigureEvm`), and driven by a top-level `Executor<DB>` trait. A chain
family plugs in its own executor by supplying a `ConfigureEvm` implementation — this is the
compile-time seam:

1. **`EvmFactory`** (in the external `alloy-evm` crate) — builds a per-transaction EVM
   (spec id, block env, precompiles).
2. **`BlockExecutorFactory` → `BlockExecutor`** (also `alloy-evm`) — the per-block layer:
   runs pre-execution system calls, executes each transaction in order, builds receipts,
   runs post-execution changes (withdrawals / EIP-7002/7251 requests), accumulates state.
3. **`BlockAssembler`** — takes execution output + state root and produces the final block
   header/body.

`ConfigureEvm` (`crates/evm/evm/src/lib.rs:181`) is the single trait a chain implements to
wire all three together. reth's concrete Ethereum implementation is `EthEvmConfig`
(`crates/ethereum/evm/src/lib.rs:85`), which is generic over the chain spec
(`EthEvmConfig<C = ChainSpec, EvmFactory = EthEvmFactory>`).

The actual execution **loop** lives in reth in `BasicBlockExecutor::execute_one`
(`crates/evm/evm/src/execute.rs:590`):

```
executor = strategy_factory.executor_for_block(db, block)   // build per-block executor
executor.apply_pre_execution_changes()                       // system calls (2935/4788)
for tx in block.transactions_recovered():
    executor.execute_transaction(tx)                         // + receipt build
result = executor.apply_post_execution_changes()             // withdrawals, requests
db.merge_transitions(BundleRetention::Reverts)               // fold into bundle state
```

Note: the concrete `BlockExecutor` trait, the concrete `EthBlockExecutor`, and the
`system_calls` module all live in the **external `alloy-evm` crate (pinned `0.37.0`,
`Cargo.toml:446`)**, re-exported by reth (`crates/evm/evm/src/lib.rs:8`,`:56`). reth owns
the *driver* (`Executor`/`BasicBlockExecutor`), the *config wiring* (`EthEvmConfig`), the
*assembler* (`EthBlockAssembler`), the *receipt builder* (`RethReceiptBuilder`), and the
*post-execution consensus validation* — but not the per-transaction/system-call body.

## Key types / interfaces / files

- `crates/evm/evm/src/execute.rs:32` — `Executor<DB>` trait: the top-level "execute a block,
  return `BlockExecutionResult`/`BlockExecutionOutput`" seam. Default methods provide
  `execute`, `execute_batch`, `execute_with_state_hook`, `execute_with_state_closure`.
- `crates/evm/evm/src/execute.rs:566` — `BasicBlockExecutor<F, DB>`: the generic `Executor`
  impl that wraps a `ConfigureEvm` strategy factory and a `revm` `State<DB>`.
- `crates/evm/evm/src/execute.rs:590` — `execute_one`: the canonical
  pre-changes → tx loop → post-changes → merge_transitions pipeline.
- `crates/evm/evm/src/lib.rs:181` — `ConfigureEvm`: the umbrella trait. Associated types
  `BlockExecutorFactory`, `BlockAssembler`, `NextBlockEnvCtx`, `Primitives`. Methods
  `executor_for_block`, `builder_for_next_block`, `evm_env`, `next_evm_env`.
- `crates/evm/evm/src/lib.rs:538` — `NextBlockEnvAttributes`: block-building inputs supplied
  by the consensus layer — `timestamp`, `suggested_fee_recipient`, `prev_randao`,
  `withdrawals`, `parent_beacon_block_root`. (All post-merge / Engine-API concepts.)
- `crates/evm/evm/src/execute.rs:294` — `BlockAssembler` trait; `:327` `BlockBuilder` trait;
  `:403` `BasicBlockBuilder` (payload-building path).
- `crates/ethereum/evm/src/lib.rs:85` — `EthEvmConfig`: the concrete `ConfigureEvm` for
  Ethereum. `:130` the `ConfigureEvm` impl. `:262` `ConfigureEngineEvm` (Engine-API payload
  → EvmEnv, the PoS entry point).
- `crates/ethereum/evm/src/build.rs:17` — `EthBlockAssembler`: builds the header (receipts
  root, withdrawals root, requests hash, base-fee, blob fields). Sets
  `nonce: BEACON_NONCE`, `ommers_hash: EMPTY_OMMER_ROOT_HASH`, `difficulty` from block env —
  i.e. post-merge header shape.
- `crates/ethereum/evm/src/receipt.rs:12` — `RethReceiptBuilder`: EIP-658 status-code
  receipt (tx_type, success, cumulative_gas_used, logs).
- `crates/ethereum/consensus/src/validation.rs:20` — `validate_block_post_execution`: the
  **separate** post-execution validation (gas used, receipts root, requests hash, BAL hash).
- `crates/ethereum/consensus/src/lib.rs:42` — `EthBeaconConsensus` (note the name: PoS/beacon
  is the default consensus type).
- `crates/evm/execution-types/src/execute.rs:18` — `BlockExecutionOutput<T>`;
  `execution_outcome.rs:49` — `ExecutionOutcome` (batch/range aggregation for the pipeline).

## Design decisions & rationale

- **Execution decoupled from validation.** `Executor::execute` runs "without any validation
  of the output" (`execute.rs:57`). Execution produces `BlockExecutionResult`; a *separate*
  consensus function `validate_block_post_execution` compares it to the header. This keeps
  the executor a pure state-transition function and lets sync, payload-building, and tracing
  reuse it without the validation coupling.
- **Factory indirection = compile-time family seam.** `ConfigureEvm` yields a
  `BlockExecutorFactory` (per-block), which yields per-block `BlockExecutor`s bound to the
  concrete spec/tx/receipt types via associated types. Everything is generic, monomorphized,
  no `dyn` in the hot path (`#[auto_impl]` provides `&`/`Arc` blanket impls).
- **`EthEvmConfig` made generic over chain spec** (`EthEvmConfig<C, EvmFactory>`, was
  hardcoded to `ChainSpec` — reth PR #16758). This is exactly the "make it generic over the
  chain" pattern a multi-network client needs.
- **Block-building and block-execution share one executor.** `BasicBlockBuilder`
  (`execute.rs:403`, sync/import path is `BasicBlockExecutor`) both call the same
  `BlockExecutor` methods (`apply_pre_execution_changes` / `execute_transaction` /
  `finish`), so payload production and payload validation cannot diverge.
- **State-hook mechanism.** `execute_one_with_state_hook` (`execute.rs:628`) threads an
  `OnStateHook` so witness/prefetch/tracing consumers observe post-execution state without
  a second pass.

## Notable patterns (the reusable idea)

**Pre-execution → transaction loop → post-execution, as three trait methods, is the
transferable skeleton.** The `BlockExecutor` contract factors block execution into:

- `apply_pre_execution_changes()` — chain-family "system calls" *before* any tx
  (Ethereum: EIP-2935 blockhash-in-state, EIP-4788 beacon-root-in-state);
- a per-tx `execute_transaction()` that also emits the receipt;
- `apply_post_execution_changes()` — chain-family finalization *after* all txs
  (Ethereum: withdrawals credit, EIP-7002/7251 request extraction; **this is the exact slot
  where a PoW client applies the block + ommer reward**).

For fukuii, this maps cleanly onto its dual-family need: an ETC (PoW) executor would put
ECIP-1017 emission + ommer rewards in `apply_post_execution_changes`, while an ETH (PoS)
executor puts withdrawals there — same trait, same driver loop, different family body. The
driver (`BasicBlockExecutor::execute_one`) never changes. That "one execution loop, family
supplies pre/post + tx body behind a trait" is the single most transferable idea here.

## Authority note

reth = the **trait-abstracted-executor** and **PoS-native** reference. It shows the cleanest
factory/trait seam for making the executor a per-family plug-in, and the cleanest pre/post
system-call structure.

**reth is NOT an authority for fukuii's ETC block reward.** There is no PoW/Ethash reward
path anywhere in reth's execution crates — a `grep` for `block_reward` across
`crates/ethereum/` and `crates/evm/` returns nothing; the only mention of "reward" is the
generic trait *doc comment* at `crates/evm/evm/src/lib.rs:77` ("Applying block rewards to
the beneficiary"), which the concrete Ethereum executor never exercises. Post-merge:
priority fees accrue to the beneficiary in-EVM, base fee is burned, withdrawals are
CL-pushed balance increments — no miner subsidy, no ommer reward, no `difficulty` mining
fields (the assembler hardcodes `nonce: BEACON_NONCE`, `ommers_hash: EMPTY_OMMER_ROOT_HASH`,
`build.rs:106`,`:115`). Even the default consensus type is `EthBeaconConsensus`.

For ETC ECIP-1017 fixed-supply emission and ommer rewards, **core-geth is the authority**
(PoW/ETChash). **go-ethereum** is the canonical ETH execution reference. Use reth only for
the *shape* of the executor abstraction, never for reward/emission semantics.

## Gotchas / anti-patterns / things they later changed

- **`execute` does not validate.** Calling `Executor::execute` and trusting the block is a
  bug — you must also run `validate_block_post_execution`. The two are deliberately separate
  (`execute.rs:57`).
- **The abstraction's *shape* is post-merge.** `NextBlockEnvAttributes` and
  `EthBlockExecutionCtx` carry `parent_beacon_block_root`, `withdrawals`, `prev_randao`,
  `slot_number` — Engine-API/CL concepts. There is no PoW-shaped context (no target
  difficulty, no seal/mix-hash inputs for mining). fukuii's PoW family needs a wider seam
  than reth's `ConfigureEvm` exposes; reth's genericity is real but its *vocabulary* assumes
  PoS. This is a divergence to record, not a pattern to copy verbatim.
- **The real per-tx/system-call body is out-of-tree.** `EthBlockExecutor` and the
  `system_calls` module are in `alloy-evm` (pinned `0.37.0`), not in reth. Reading only
  reth's `crates/` shows the driver/wiring, not the transaction-level execution. A fukuii
  reader must not assume "reth's executor" is fully in reth.
- **Block Access List (EIP-7928 / Amsterdam) threading** is interleaved into the loop
  (`execute.rs:600-618`, `bump_bal_index`), gated on `block_access_list_hash().is_some()` —
  an example of a new fork's per-tx bookkeeping being woven into the generic driver rather
  than hidden behind the family trait.
- **`EthEvmConfig` was recently made generic over chain spec** (`<C, EvmFactory>`); older
  reth hardcoded `ChainSpec`. Any doc or example referencing the non-generic form is stale.
