# go-ethereum — build-deps
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

## Architecture summary
geth's build layer is a thin Makefile over a self-contained Go program, `build/ci.go`,
which is the single orchestrator for compile, cross-compile, test, lint, code-gen check,
archive/package (deb, NSIS, Docker), and release upload. Dependency management is stock Go
modules — one root `go.mod`/`go.sum` plus a second `./cmd/keeper` module, with all
production versions pinned in `go.mod` and optional *build-tool* versions pinned separately
in `build/checksums.txt` via a `# version:<tool>` comment convention read at runtime. There
is no vendor directory; the Go toolchain itself can be bootstrap-downloaded and checksum-
verified (`-dlgo`) so CI builds against a chosen Go independent of the host. Code generation
is push-based (`//go:generate` directives, 32 files) and enforced in CI by a hash-diff gate
rather than being a build step.

## Key types / interfaces / files
- `go.mod:3` — `go 1.24.0`, module `github.com/ethereum/go-ethereum`; no separate
  `toolchain` directive (host Go, or `-dlgo`, governs).
- `go.mod:175` — `tool ( … )` block (Go 1.24 tool-dependency feature): `gencodec`,
  `stringer`, `protoc-gen-go` are tracked as module tools, not ad-hoc `go install`.
- `go.sum` (551 lines), 84 `// indirect` entries in `go.mod` — flat, vendorless dependency graph.
- `Makefile:8-11` — `GORUN = go run`; every real target is `go run build/ci.go <cmd>`. The
  Makefile is explicitly "for people who don't work with Go"; ci.go is the real entrypoint.
- `Makefile:48-55` — `devtools` target: `go install` of stringer/gencodec/protoc-gen-go +
  solc/protoc presence check — the manual counterpart to the `tool` block.
- `build/ci.go:20-44` — doc/usage header: the command surface (`install`, `test`, `lint`,
  `check_generate`, `check_baddeps`, `archive`, `debsrc`, `nsis`, `purge`, `keeper*`).
- `build/ci.go:205-234` — `main` switch dispatching subcommands; single binary, no framework.
- `build/ci.go:239-284` — `doInstall`: builds all `main` packages under `./cmd/...` by
  default; `-os/-arch/-cc` cross-build; `-dlgo` bootstraps Go; `-static` static link.
- `build/ci.go:331-375` — `buildFlags`: reproducible-build ldflags (`--buildid=none`,
  `--build-id=none,--strip-all`, `-trimpath`), version stamped via `-X …version.gitCommit`,
  8 MB stack-size enforcement on linux, `ckzg` cgo build tag.
- `build/ci.go:468-519` — `doCheckGenerate`: runs `go generate ./...` + `go mod tidy -diff`,
  hashes the tree before/after (`build.HashFolder`), fails if any generated file drifts.
- `build/ci.go:524-549` — `doCheckBadDeps`: `go list -deps` allow-list gate forbidding
  named package→package edges (e.g. `core/rawdb` must not import `ethdb/leveldb`).
- `build/checksums.txt:7-8,52,122,136` — `# version:<tool>` pins (golang 1.25.10, golangci
  2.10.1, protoc 27.1, protoc-gen-go 1.34.2, spec-tests v5.1.0) + sha256 of every artifact.
- `internal/build/gotool.go:96` — `DownloadGo`: reads the `golang` version from checksums,
  skips if the active Go already matches, else downloads+verifies from golang.org/dl.
- `internal/download/*.go:242` — `ChecksumDB.FindVersion` parses the `# version:` comments;
  `:88` shows the parser. Checksums double as a version registry.
- `version/version.go:20-23` — hand-edited `Major/Minor/Patch/Meta` constants
  (1.17.5 / "unstable"); `internal/version/version.go:32-63` derives Semantic/WithMeta and
  splices in git commit/date at build time.
- `build/ci.go:120-159` — deb packaging tables: `debExecutables`, `debDistros` (xenial→noble
  Ubuntu LTS list with inline EOL dates), driving `doDebianSource` → Launchpad PPA.

## Design decisions & rationale
- **Build logic in Go, not shell/Make.** `build/ci.go` is `//go:build none`-tagged
  (`build/ci.go:17`) so it never links into any binary yet compiles/runs via `go run`. This
  keeps CI logic cross-platform (no bash on Windows), testable, and dependency-free beyond
  the repo itself — the Makefile is a courtesy shim (`Makefile:1-3`).
- **Two-tier version pinning.** Runtime/library deps live in `go.mod` (reproducible via
  `go.sum`); *build-time tools* (the Go compiler itself, linters, protoc) live in
  `build/checksums.txt` with both a version and a sha256, decoupling toolchain currency from
  the module graph and letting CI build against a newer Go (1.25.10) than the `go.mod`
  language level (1.24.0). Trade-off: two places to look for "what version is pinned".
- **Reproducible builds are a first-class goal**, not incidental: `-trimpath`,
  `--buildid=none`, stripped build-ids, fixed stack size (`build/ci.go:333-373`).
- **Vendorless.** Relies on the module proxy + `go.sum` integrity instead of a checked-in
  `vendor/`; smaller repo, but builds need network (mitigated by module cache).
- **Code-gen correctness enforced, not trusted.** Rather than regenerate on every build,
  CI *checks* that committed generated files match a fresh `go generate` (`doCheckGenerate`),
  so generated code is reviewable in diffs while drift is impossible to merge.
- **Architectural boundaries enforced in CI** via `doCheckBadDeps` — a lightweight,
  incrementally-grown "fitness function" preventing layering regressions from refactors.

## Notable patterns (the reusable idea)
- **`ci.go` as a single self-hosted build orchestrator** — one language, one binary, all of
  compile/test/lint/package/release, invoked identically locally and in CI. GOOD FOR:
  multi-network / multi-platform release engineering (the deb+NSIS+Docker+archive matrix
  lives in one file) and enterprise reproducibility. fukuii's sbt already fills the
  orchestrator role; the transferable idea is *consolidating release/packaging/cross-build
  logic into the build tool itself* rather than scattering across GitHub Actions YAML.
- **`# version:` comment registry + checksummed toolchain download** (`checksums.txt` +
  `DownloadGo`/`FindVersion`). A plaintext, greppable, single source of truth for every
  optional build dependency *including the compiler*, each with a sha256. GOOD FOR:
  enterprise/CEX supply-chain posture (every downloaded build input is version-pinned and
  hash-verified) and CI hermeticity. **Most transferable to fukuii**: mirrors the global
  supply-chain rules (exact pins, verify-before-use) — a checksums-style pinned+hashed
  registry for non-Maven build inputs (plugins, native libs, downloaded tools) would be a
  strong DEFAULT.
- **`check_baddeps` layering gate** — dependency-direction assertions as a CI test. GOOD
  FOR: multi-network/omni-client codebases where keeping consensus-family code paths from
  leaking into each other matters (directly analogous to fukuii's `Eth*`/`Etc*`
  no-cross-reference ratchet). Offer as an adoptable pattern for fukuii's build layer.
- **`check_generate` hash-diff gate** — generated artifacts committed but proven fresh.
  GOOD FOR: archival/RPC/tooling code with lots of codec/marshaling boilerplate.

## Authority note
geth is the **canonical ETH baseline for build-tooling within the Go/EVM world**, and its
`ci.go`+checksums pattern is widely copied by Go-based clients (erigon, core-geth inherit the
same lineage). It is authoritative *for Go-ecosystem build conventions only* — its patterns
(Go modules, `go generate`, `ci.go`) do not translate 1:1 to fukuii's JVM/sbt world. For
build-tool authority in fukuii's actual stack (Scala 3 / sbt / Pekko) geth is **not** the
reference; the transferable layer is the *policy* (reproducible builds, pinned+hashed build
inputs, layering gates, generated-code freshness checks), not the mechanism. core-geth
remains the PoW/ETC authority for *config/consensus*, but for build orchestration core-geth
merely tracks upstream geth here.

## Gotchas / anti-patterns / things they later changed
- **Two dependency-version sources of truth** (`go.mod` vs `build/checksums.txt`) — easy to
  bump one and forget the other; the `go.mod` language level (1.24.0) intentionally lags the
  CI Go (1.25.10), which surprises people.
- **`go.mod:175` `tool (` block is Go-1.24-only** — the modern replacement for the older
  `//go:build tools` + blank-import `tools.go` idiom and the `Makefile:48-55` `devtools`
  `go install`; both older forms still linger (Makefile), so there are now three ways tools
  are declared.
- **Vendorless builds require network** for a cold module cache — a hermeticity gap for
  fully air-gapped enterprise builds (contrast with a checked-in `vendor/`).
- **`build/ci.go` carries legacy release plumbing**: Travis-era Debian/Launchpad notes
  (`build/ci-notes.md`) and `travis_keepalive.sh` remain despite the move to GitHub-based CI;
  the `debDistros` list (`build/ci.go:153-159`) still includes EOL `xenial`. Dead-ish
  packaging paths accumulate here.
- **`version/version.go` constants are hand-edited** (`Major/Minor/Patch/Meta`) — release
  version bumps are a manual source edit, not derived from git tags.
