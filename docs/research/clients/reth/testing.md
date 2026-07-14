# reth — testing
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth's testing surface splits into four Rust-idiomatic layers, none of which is a
translated port of geth/besu — they lean on Cargo's built-in test harness plus the
Rust ecosystem's `proptest`, `criterion`, and `rayon`:

1. **`testing/ef-tests/`** — the Ethereum Foundation conformance consumer. A small
   trait-based harness (`Case` / `Suite` / `Cases`) that walks the JSON fixture trees
   from `ethereum/tests` (and EEST's `execution-spec-tests`), decodes each fixture into
   typed models, replays blocks through the real reth execution/consensus/trie stack
   against a throwaway MDBX + static-file provider, and asserts the post-state root and
   per-account DB state. This is the conformance/multi-network use-case lens.
2. **`testing/testing-utils/`** — deterministic random-data generators (seedable RNG)
   and a genesis allocator, shared by unit/integration tests across crates.
3. **`testing/runner/`** — a thin `clap` CLI (`ef-tests` as a library) so fixtures can
   be run outside `cargo test`.
4. **`crates/e2e-test-utils/`** — spins up real in-process nodes (`NodeTestContext`,
   engine-API driver, wallet, network harness) for end-to-end scenarios (payload
   production, forks, reorgs, sync/import).

Property-based testing (`proptest`) and micro-benchmarks (`criterion`) live *inline*
in the crates they cover (e.g. `crates/trie/`, `crates/transaction-pool/`), not in a
central directory — the correctness/custody use-case lens.

## Key types / interfaces / files

- `testing/ef-tests/src/case.rs:13` — `trait Case`: `load(path) -> Self` +
  `run(self) -> Result<(), Error>` + `description()`. The unit of a single JSON fixture.
- `testing/ef-tests/src/case.rs:30` — `struct Cases<T>` and `Cases::run` (line 37):
  runs all loaded cases with `into_par_iter()` (rayon data-parallelism across fixtures).
- `testing/ef-tests/src/suite.rs:11` — `trait Suite` with associated `type Case`;
  `run()` (line 19) walks top-level dirs, `run_only(name)` (line 34) recursively
  `WalkDir`s for `*.json`, loads each into a `Case`, runs, then `assert_tests_pass`.
- `testing/ef-tests/src/cases/blockchain_test.rs:45` — `impl Suite for BlockchainTests`
  and `BlockchainTestCase` (line 55), the only concrete `Case`. This is the harness core.
- `testing/ef-tests/src/cases/blockchain_test.rs:193` — `run_case`: creates a test
  provider factory, inserts genesis state/hashes/history, decodes RLP blocks, and for
  each block runs `pre_execution_checks` → `batch_executor.execute` →
  `validate_block_post_execution` → **recompute state root and compare to
  `block.state_root`** → write state. The byte-exact conformance gate.
- `testing/ef-tests/src/cases/blockchain_test.rs:101` — `run_single_case`: the
  expected-failure protocol — a fixture may assert a block *should* fail
  (`expect_exception`); the harness matches the failing block number against the
  expectation (`expected_failure`, line 93), with a documented `UncleFromSideChain`
  exemption (line 82).
- `testing/ef-tests/src/cases/blockchain_test.rs:368` — `should_skip`: a hardcoded
  allow-list of fixture filenames to skip, **each with a comment stating why**
  (unparseable `bigint 0x00`, too-slow `Call50000_sha256`, revm-parity skips, outdated
  EOF tests). Determinism/known-divergence ledger.
- `testing/ef-tests/src/result.rs:15` — `enum Error` with a first-class `Skipped`
  variant (line 18; explicitly "should not be treated as a test failure") and
  `BlockProcessingFailed { block_number, err }` so failures name the exact block.
- `testing/ef-tests/src/result.rs:103` — `categorize_results` → passed/failed/skipped,
  and `print_results`/`assert_tests_pass` (line 94): the suite fails only if the
  `failed` bucket is non-empty; skipped is reported, not fatal.
- `testing/ef-tests/src/models.rs:267` — `enum ForkSpec` (Frontier…Merge…) with
  `to_chain_spec` (line 332) / `to_chain_spec_inner` (line 345) mapping each fixture's
  named fork to a real `ChainSpec` via `ChainSpecBuilder` + `ForkCondition::Block(n)`.
  `Account::assert_db` (line 217) checks balance/nonce/bytecode/storage slot-by-slot
  against the MDBX tables.
- `testing/ef-tests/tests/tests.rs:7` — `general_state_test!` / `blockchain_test!`
  declarative macros generate one `#[test]` per fixture directory (Shanghai,
  stCreate2, ValidBlocks, …); `eest_fixtures` (line 104) runs the EEST tree if present.
  Gated behind `#![cfg(feature = "ef-tests")]` so fixtures aren't a default build cost.
- `testing/runner/src/main.rs:14` — `clap`-based CLI reusing `BlockchainTests` as a lib.
- `testing/testing-utils/src/generators.rs:67` — `rng()`: **seedable via `SEED` env
  var** so a random-data test failure is reproducible; `BlockParams`/`BlockRangeParams`
  drive random block/tx/header generation.
- `crates/e2e-test-utils/src/lib.rs:1` + `testsuite/README.md` — e2e node harness;
  the README mandates every crate's e2e binary be named `e2e_testsuite` (nextest/CI
  filter contract) with `harness = true` in `Cargo.toml`.
- `crates/trie/db/tests/fuzz_in_memory_nodes.rs:29` — representative `proptest!` block
  (`ProptestConfig { cases: 128 }`) fuzzing trie state-root computation over randomly
  generated `BTreeMap` state updates. ~10 `proptest!` sites, ~37 `Arbitrary` derives,
  17 crates depend on `proptest`.
- `crates/transaction-pool/benches/saturated_pool.rs:7` — representative `criterion`
  bench (`criterion_group!`/`criterion_main!`); benches live under each crate's
  `benches/`, run via `cargo bench`, not `cargo test`.

## Design decisions & rationale

- **Trait-based fixture harness, not a monolith.** `Case`/`Suite`/`Cases` are three
  tiny traits; a new fixture format = one `impl Case`. The generic machinery
  (directory walking, parallel run, pass/fail categorization, reporting) is written once.
- **Replay through the real stack.** `run_case` doesn't stub the EVM — it inserts
  genesis into a real test provider (MDBX + static files), executes via the production
  `EthEvmConfig` batch executor, runs the production `EthBeaconConsensus` header/body
  checks, and recomputes the state root with the production trie. Conformance means the
  fixture exercises the same code as mainnet, so a passing fixture is real evidence.
- **State root is *the* oracle.** After every block the harness recomputes the trie root
  and compares to the fixture's `block.state_root` (line 269); only at suite end does it
  also assert per-account DB values. The root check is the cheap, total byte-exactness gate.
- **Skipped ≠ failed, and every skip is justified.** `Error::Skipped` is a distinct enum
  arm; `should_skip` is an explicit filename allow-list where each entry carries a
  reason comment (including "skipped by revm as well" parity links). Divergences are
  documented, not silently swallowed.
- **Expected-failure fixtures are first-class.** Invalid-block fixtures assert failure at
  a *specific* block number; `run_single_case` verifies the failure lands on the right
  block, not merely that something failed.
- **Parallelism for free.** `Cases::run` uses rayon `into_par_iter`; `run` inside a case
  file uses `par_bridge_buffered().with_min_len(64)` — fixtures are independent, so the
  suite scales across cores without bespoke threading.
- **Determinism via seeded RNG.** `generators::rng()` reads `SEED`; a random-data test
  that fails prints/uses a seed so it's replayable — the Rust answer to flaky randomness.
- **Feature-gated cost.** ef-tests are behind `feature = "ef-tests"` and e2e tests behind
  a named `e2e_testsuite` binary, so the default `cargo test` stays fast; heavy
  conformance/e2e runs are opt-in in CI.

## Notable patterns (the reusable idea)

**`proptest` property-based testing is the single most transferable idea for fukuii.**
reth doesn't just run vendor fixtures; it *generates* thousands of random inputs and
asserts invariants (e.g. `fuzz_in_memory_account_nodes` asserts the incrementally-updated
trie root equals a from-scratch recomputation over 128 random state-update sequences,
each 10 updates deep). This is exactly what fukuii's Scala tooling already has an
analogue for — **ScalaCheck** — but reth's usage shows the high-value targets: trie/MPT
root equivalence, RLP round-trip (encode∘decode == id), and serialization/`Arbitrary`
derivation. The pattern to port: for every codec and every incremental-vs-batch state
computation, write a ScalaCheck property with a fixed seed for reproducibility, sitting
*next to* the deterministic ethereum/tests fixture consumer rather than replacing it —
fixtures catch known cases, properties catch the unknown ones.

Secondary transferable pattern: the **`Case`/`Suite` two-trait split** cleanly separates
"how to load+run one fixture" from "how to discover and aggregate a tree of them" — a
shape fukuii's own `ethereum/tests` consumer can mirror to add new fixture formats
(EEST, hive) without duplicating the walk/parallelize/report scaffolding.

## Authority note

go-ethereum is the reference oracle: the `ethereum/tests` (and EEST) JSON fixtures reth
consumes are produced/blessed by go-ethereum's `t8n` state-transition tool, so a fixture
encodes geth-authoritative expected roots. reth is *not* the fixture author — it is the
**Rust reference-test harness + `proptest` property-test harness**: the third language
variant of "how a client consumes the shared conformance fixtures," after geth's Go
`tests/` runner and besu/nethermind's JVM/.NET consumers. For fukuii, reth is the model
for the Rust-idiomatic half (proptest ≈ ScalaCheck; state-root-as-oracle) while core-geth
remains the ETC/PoW authority for the fixture *content* fukuii must match.

## Gotchas / anti-patterns / things they later changed

- **The `+1` genesis off-by-one is a recurring trap.** Fixtures list blocks *excluding*
  genesis, so block index 0 in the JSON is chain block number 1. Both `run_case`
  (line 232) and `decode_blocks` (line 327) recompute `block_number = index + 1` and
  explicitly **do not trust `block.number`** — invalid-block fixtures may carry a wrong
  header number on purpose. Any consumer that keys off the header's own number will
  mis-attribute failures.
- **`should_skip` is a maintenance liability by design.** It's a hardcoded filename
  list; fixtures renamed upstream silently stop being skipped. It's accepted because
  every entry is commented and cross-referenced (several cite revm's identical skip
  list), but it must be re-audited when the fixture submodule bumps.
- **Excluded forks are dropped, not failed.** `excluded_fork` (line 64) filters out
  Constantinople-era and EOF-merge fixtures before running; these simply never execute,
  so "all tests pass" does not mean "all forks covered."
- **Outdated EOF fixtures.** `stEOF`/`EIPTests` paths are skipped wholesale (line 420)
  as "haven't been updated for Cancun yet" — a live example of fixtures lagging the spec.
- **`unwrap()` in the fixture setup path.** `run_case` uses `.unwrap()` on provider
  creation/genesis recovery (lines 197, 205); a malformed fixture or provider bug panics
  the whole test process rather than producing a categorized `Error`, which is a
  deliberate "test-infra failure ≠ conformance failure, so crash loudly" choice.
