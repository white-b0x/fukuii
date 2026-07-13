# core-geth — build-deps
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary
Fork of go-ethereum. The build/dependency slot **inherits geth's entire build
machinery** — the `go run build/ci.go` orchestrator, `Makefile`, `Dockerfile`,
Launchpad/PPA Debian packaging, NSIS Windows installer, and the go-modules
toolchain are all structurally geth's. **The fork does NOT rename the module
path**: `go.mod` still declares `module github.com/ethereum/go-ethereum`
(`go.mod:1`), so every internal import path is geth-canonical. Divergence is
confined to three additive things: (1) an ETC-specific `test-coregeth-*`
Makefile block, (2) retained/re-enabled **EVMC** external-interpreter support
that upstream geth later dropped, and (3) two **ETC Labs OpenRPC** dependencies
for RPC service-discovery. This is a 2025-01 vintage of geth (`go 1.21`) — do
not line-diff it against today's geth HEAD (`go 1.24`); frame everything as
"additions core-geth carries," not "how far behind geth it is."

## Key types / interfaces / files
- `go.mod:1` — `module github.com/ethereum/go-ethereum` — **module path retained,
  not renamed** to a coregeth path. Identical convention to geth.
- `go.mod:3` — `go 1.21` (geth baseline at this vintage; current geth HEAD is
  `go 1.24.0`).
- `go.mod` — `github.com/etclabscore/go-openrpc-reflect v0.0.37` + indirect
  `github.com/etclabscore/go-jsonschema-walk v0.0.6` — **coregeth-only deps**
  (absent from geth's go.mod). Drive OpenRPC document generation for JSON-RPC
  discovery; ETC Labs tooling.
- `go.mod` — `github.com/ethereum/evmc/v7 v7.5.0` — EVMC binding, **retained by
  core-geth** where upstream geth removed EVMC. Pairs with the EVMC build scripts.
- `Makefile:34-37` — `test-coregeth`, `test-coregeth-features`,
  `test-coregeth-consensus`, `test-coregeth-regression-condensed` — the sole
  ETC-specific Makefile target block; geth's Makefile has **zero** `coregeth`
  targets (verified against baseline).
- `Makefile` (`hera:`, `evmone:`, `test-evmc:` targets) + `build/hera.sh`,
  `build/evmone.sh`, `build/evmc-example_vm.so.sh` — EVMC external-interpreter
  test wiring (Hera/EWASM, evmone). Runs `go test ./tests -run TestState
  -evmc.ewasm=...` against externally-built shared objects.
- `build/ci.go:227` — inline `TODO(meowsbits)`: the `-trimpath` flag is
  commented out **because it breaks openrpc discovery** — a concrete coregeth
  divergence from geth's ci.go install flags, forced by the OpenRPC feature.
- `build/ci.go:823` — `dest := sshUser + "@ppa.launchpad.net"` — Debian/PPA
  release path inherited wholesale from geth (still Launchpad, not re-branded).
- `build/`, `Dockerfile`, `Dockerfile.alltools`, `appveyor.yml`, `circle.yml`,
  `Jenkinsfile`, `.travis.yml` — CI/packaging scaffolding, all geth-inherited.

## Design decisions & rationale
- **Keep the module path `github.com/ethereum/go-ethereum`.** A soft-fork that
  never renames the module keeps rebasing on upstream geth cheap and keeps every
  `import` line identical to geth — the whole point of a "core" geth that tracks
  upstream. (Contrast: fukuii itself repackaged under `com.chipprbots`; core-geth
  deliberately did not.)
- **Retain EVMC.** Geth dropped its EVMC C-interpreter binding; core-geth kept
  `evmc/v7` plus Hera (EWASM) and evmone wiring so ETC-flavoured builds can run
  the state tests against external interpreters — an ETC-community capability
  choice, not a consensus change.
- **OpenRPC via ETC Labs libraries.** ETC Labs authored `go-openrpc-reflect`;
  core-geth wires it in for machine-readable JSON-RPC service descriptions, which
  is why `-trimpath` had to be disabled in the install path.

## Notable patterns (the reusable idea)
The reusable idea for fukuii: **a fork can add ecosystem-specific capability
(EVMC, OpenRPC) and test surface (`test-coregeth-*`) as pure additions layered
on top of an unrenamed upstream build, without forking the build system itself.**
Divergence is isolated to a handful of extra dependencies, a Makefile block, and
one flag (`-trimpath`) — the CI orchestrator, packaging, and module identity stay
byte-for-byte geth.

## Authority note
**Not authoritative — inherits geth.** core-geth is the ETC byte-authority for
*consensus/rewards/ECIP*, but the build/dependency slot is geth-canonical. For
fukuii, treat geth's build conventions as the reference here; the only
ETC-attributable signal is the additive set (OpenRPC deps, retained EVMC,
`test-coregeth-*` targets). None of it is consensus-relevant.

## Gotchas / anti-patterns / things they later changed
- **Vintage skew:** this is `go 1.21` / 2025-01. Its dep pins (pebble, gnark,
  c-kzg-4844 v0.4.0, goja) are a year behind current geth. Do not treat any
  version here as "current" — fukuii's own stack is independent.
- **`-trimpath` disabled** (`build/ci.go:227`) is a real reproducible-build
  regression forced by OpenRPC discovery. A downstream consumer wanting trimmed
  paths would have to choose between OpenRPC and `-trimpath`.
- **EVMC is optional external tooling**: the `hera`/`evmone` targets depend on
  externally-built `.so` objects (`build/_workspace/...`) that are not vendored —
  the EVMC test path is not hermetic.
- The `sync-parity-chainspecs` Makefile target is explicitly marked DEPRECATED
  ("No attempt will be made after the Istanbul fork to maintain Parity
  configuration support") — dead build surface carried forward.
