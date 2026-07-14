# core-geth — testing
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary
core-geth inherits go-ethereum's `tests/` conformance harness (block tests, state tests,
transaction tests, difficulty tests, RLP tests) driven by the shared `ethereum/tests`
fixture repo. On top of that inherited harness it adds an **ETC-specific fixture source and
ETC fork configurations** so the same runners validate ETC consensus: a second submodule
`tests/testdata-etc` pointing at `etclabscore/tests`, and ETC fork config objects
(`ETC_Atlantis` … `ETC_Mystique`) built as `coregeth.CoreGethChainConfig`. The runner *code*
is geth; the *fixtures and configs* are the ETC extension. It also carries "mgen"
(multi-geth generator) helpers that generate fixtures from core-geth's own config schema.

## Key types / interfaces / files
- `.gitmodules` — two fixture submodules: `tests/testdata` → `github.com/ethereum/tests`
  (inherited) and **`tests/testdata-etc` → `github.com/etclabscore/tests`** (ETC addition).
- `tests/difficulty_test_util.go:116,177,246,…` — `Forks` map entries `"ETC_Atlantis"`,
  `"ETC_Agharta"`, `"ETC_Phoenix"`, `"ETC_Magneto"`, `"ETC_Mystique"`, each a
  `*coregeth.CoreGethChainConfig` with ETC block schedule (ECIP1017/1010/1099 fields set).
- `tests/difficulty_mgen_test.go:50-54` — difficulty-test generator wired to the ETC forks
  above (`difficultyETC_Agharta.json` etc. under `testdata_generated/`).
- `tests/state_test_util_cg.go`, `tests/init_mgen.go`, `tests/init_mgen_test.go`,
  `tests/state_mgen.go` — the `_cg` / `_mgen` suffixed files are **core-geth-specific**
  harness code (config translation + fixture generation) layered on the inherited utils.
- `tests/regression/` — core-geth regression fixture tree.
- `tests/params.go`, `tests/init.go` — inherited geth fork/config registration the ETC
  entries plug into.

## Design decisions & rationale
- **Separate ETC fixture submodule.** ETC consensus tests (difficulty bomb removal, ECIP-1017
  emission, Etchash, the ETC fork timeline) diverge from Ethereum's, so rather than patch the
  upstream `ethereum/tests` vectors, core-geth pulls a parallel `etclabscore/tests` repo. This
  keeps the inherited ETH conformance vectors pristine and isolates ETC-specific vectors.
- **Config-schema-native test configs.** ETC fork configs are expressed directly as
  `coregeth.CoreGethChainConfig` (the same multi-geth schema used in production `params/`),
  so tests exercise the real config type rather than a test-only shim — the ForkID/fork
  dispatch logic under test is the production logic.
- **mgen fixture generation.** The `_mgen` files let core-geth *generate* difficulty/state
  fixtures from its own config, needed because upstream doesn't ship ETC-fork difficulty
  vectors.

## Notable patterns (the reusable idea)
**Additive test extension without forking the harness**: keep the upstream fixture repo and
runner untouched, add a second fixture submodule and a set of chain-config entries in the
shared `Forks` registry. The same test binaries then validate a new chain family. fukuii's
equivalent is running the shared `ethereum/tests` corpus while adding ETC/ECIP fixtures
alongside, rather than maintaining a divergent runner.

## Authority note
core-geth is **not** the harness authority — `tests/*_test.go` runner code is inherited geth.
It *is* a useful reference for **which ETC fork configs and ETC fixture source** to validate
against (`ETC_*` configs + `etclabscore/tests`), which complements its role as ETC
network-config authority.

## Gotchas / anti-patterns / things they later changed
- **Two fixture submodules must both be initialized.** `tests/testdata` (ETH) and
  `tests/testdata-etc` (ETC) are independent; a shallow/uninitialized `testdata-etc` silently
  skips ETC conformance without failing loudly.
- The `_cg` / `_mgen` suffix convention is the tell for "core-geth-added vs inherited geth
  file" in `tests/` — useful when diffing against upstream geth to see what's ETC-specific.
- Fixture vectors here are a **2025 snapshot**; ETC forks after Mystique (Spiral/ECBP-1100
  deactivate at 19_250_000, and anything Olympia-era) are not represented in these frozen
  vectors — fukuii's Olympia work needs its own fixtures.
