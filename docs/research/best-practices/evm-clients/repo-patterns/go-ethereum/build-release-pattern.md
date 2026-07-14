# go-ethereum — Build, Release & Packaging Patterns

Source: `.claude/repo-references/clients/go-ethereum/` (vendored full clone, verified genuine
git checkout — `.git/` present, `origin` a fork at `white-b0x/go-ethereum`, `upstream` set to
`ethereum/go-ethereum`, currently checked out on the `upstream` branch at commit
`59e89e81e57814a96c429c5cdcaa6ca2e0d6b143`, 2026-07-01). Every claim below is traceable to a
file in the vendored clone; where a line number isn't pinned to one statement, the citation
names the file/section instead of inventing a number.

go-ethereum is a Go execution-layer client, so — like Erigon — its build tooling is Make, not
a JVM build tool. What sets it apart from Erigon's Makefile is architectural: go-ethereum's
`Makefile` is explicitly a thin, apologetic wrapper aimed at non-Go contributors, while the
*real* task runner is a ~1,350-line Go program, `build/ci.go`, invoked as `go run build/ci.go
<command>`. This is the single most important structural fact in this document — nearly every
build, test, lint, archive, Docker, Debian, and Windows-installer concern in go-ethereum
funnels through that one Go file rather than through Make targets themselves.

---

## Makefile — thin wrapper over build/ci.go

The Makefile opens with the comment this task explicitly asked to be quoted verbatim
(`Makefile:1-3`):

```make
# This Makefile is meant to be used by people that do not usually work
# with Go source code. If you know what GOPATH is then you probably
# don't need to bother with make.
```

This is not boilerplate throat-clearing — it is a literal statement of audience: someone who
already knows Go tooling (`go build`, `go test`, `GOPATH`/`GOBIN`) doesn't need `make` at all
and can invoke `build/ci.go` directly. The Makefile exists purely as a friendlier, memorable
surface (`make geth`, `make test`) for contributors who don't want to learn the `ci.go`
subcommand vocabulary.

Every real target in the 64-line Makefile does exactly one thing: shell out to
`$(GORUN) build/ci.go <cmd>` (where `GORUN = go run`, `Makefile:9`) and then print a friendly
follow-up message. None of them contain build logic of their own:

| Target | Body | Notes |
|---|---|---|
| `geth` (`Makefile:11-15`) | `$(GORUN) build/ci.go install ./cmd/geth` | Builds only the `geth` binary; prints `Run "$(GOBIN)/geth" to launch geth.` |
| `evm` (`Makefile:17-21`) | `$(GORUN) build/ci.go install ./cmd/evm` | Builds only the standalone EVM bytecode-execution utility |
| `all` (`Makefile:23-25`) | `$(GORUN) build/ci.go install` | No package argument — `doInstall` (see below) defaults to *every* `main` package under `./cmd/...` |
| `test` (`Makefile:27-29`) | depends on `all`, then `$(GORUN) build/ci.go test` | Full local test run — note this genuinely rebuilds all binaries first via the `all` dependency, unlike CI's `go.yml`, which calls `ci.go test` directly without an install step |
| `lint` (`Makefile:31-33`) | `$(GORUN) build/ci.go lint` | Downloads/runs `golangci-lint` — see `doLint` below |
| `fmt` (`Makefile:35-37`) | `gofmt -s -w $(shell find . -name "*.go")` | The **one** target that does not call `ci.go` — plain `gofmt` over every `.go` file in the tree |
| `clean` (`Makefile:39-42`) | `go clean -cache; rm -fr build/_workspace/pkg/ $(GOBIN)/*` | Also bypasses `ci.go` — pure filesystem/cache cleanup |
| `devtools` (`Makefile:44-54`) | `go install`s `stringer`, `gencodec`, `protoc-gen-go`, `./cmd/abigen`; checks for `solc`/`protoc` on `PATH` | Installs code-generation tools needed for `go generate`, not `ci.go`-mediated |
| `help` (`Makefile:56-63`) | `sed -n 's/^#?//p' $< \| column -t -s ':' \| sort` | Same self-documenting convention Erigon uses (`## comment` → generated help), here spelled `#?` instead of `##` |

`.PHONY: geth evm all test lint fmt clean devtools help` (`Makefile:5`) declares all eight
real targets non-file targets, as expected for a pure task-dispatch Makefile.

**The practical consequence for anyone reading go-ethereum's build system**: reading the
Makefile alone tells you almost nothing about *how* a binary is actually built — cross-
compilation flags, static linking, CKZG tags, ldflags for embedding commit/date, toolchain
downloading. All of that logic lives in `build/ci.go`, not here. This is the opposite
structural choice from Erigon, whose 670-line Makefile *is* the real build logic (pattern
rules, fixture downloads, sharded EEST targets, Docker/Kurtosis wrappers all inline in Make
syntax itself) — go-ethereum instead uses Make as a thin alias layer over a general-purpose
Go program that does the same job with real control flow, structs, and error handling instead
of Make's macro/shell-substitution model.

---

## build/ci.go — the real task runner

`build/ci.go` (1,356 lines) is a `//go:build none` Go source file — it is never compiled as
part of the module; it is only ever run via `go run build/ci.go <command>` (confirmed by the
build-ignore tags at `ci.go:17-18` and the doc comment at `ci.go:20-44` that spells out the
full command surface: `lint`, `check_generate`, `check_baddeps`, `install`, `test`, `keeper`,
`keeper-archive`, `archive`, `importkeys`, `debsrc`, `nsis`, `purge`, plus a global `-n`
dry-run flag mentioned in the doc comment that is not actually wired to any command in the
current `main()` dispatch — the doc comment for `-n` appears aspirational/stale relative to
the `main()` switch at `ci.go:205-234`, which has no shared dry-run flag implementation).

`main()` (`ci.go:196-235`) does two sanity checks before dispatch — it refuses to run unless
`build/ci.go` exists relative to the current directory (`ci.go:199-201`, "this script must be
run from the root of the repository") and requires at least one subcommand argument — then
switches on `os.Args[1]` to one of eleven handler functions. The switch's actual cases
(`ci.go:206-233`) are `install`, `test`, `lint`, `check_generate`, `check_baddeps`, `archive`,
`dockerx`, `debsrc`, `nsis`, `purge`, `sanitycheck`, `keeper`, `keeper-archive` — a superset of
what the doc comment lists (the doc comment omits `dockerx` and `sanitycheck`, and spells the
Debian target `debsrc` consistently, but lists `importkeys` as available when no such case
exists in the switch — another small doc/code drift, noted for accuracy but not load-bearing).

### `doInstall` — the actual compiler invocation behind `make geth`/`make evm`/`make all`

`doInstall` (`ci.go:239-284`) is what every Makefile build target ultimately calls. It:

- Parses `-dlgo` (download a pinned Go toolchain rather than using the host's), `-os`/`-arch`
  (cross-compilation target), `-cc` (C compiler for CGO), `-static` (static linking).
- Builds a `build.GoToolchain{GOOS, GOARCH, CC}` (`ci.go:251`) — if `-dlgo` is set, it loads
  `build/checksums.txt` and calls `build.DownloadGo(csdb)` to fetch and verify a pinned Go
  release rather than trusting whatever `go` is on `PATH` (`ci.go:252-255`).
- Always adds the `urfave_cli_no_docs` build tag to disable CLI markdown doc generation in
  release builds, and adds `ckzg` (the C-KZG-4844 cryptography backend for blob transactions)
  unless building for Ubuntu Trusty, where the CGO toolchain can't support it (`ci.go:256-262`).
- Delegates the actual per-platform linker-flag logic to `buildFlags` (`ci.go:329-375`), which
  is worth reading in full because it encodes several non-obvious, deliberately-commented
  decisions:
  - `--buildid=none` on the linker unconditionally, "See
    https://github.com/golang/go/issues/33772#issuecomment-528176001" (`ci.go:333-336`).
  - When `env.Commit != ""`, embeds the git commit and date into the binary via `-ldflags -X
    .../internal/version.gitCommit=... -X .../internal/version.gitDate=...` (`ci.go:337-340`)
    — this is how `geth version` reports a build commit without `git describe` at runtime.
  - Strips DWARF on darwin unconditionally, with an explicit "no downside, so we just keep
    doing it" comment (`ci.go:341-345`).
  - On linux, enforces an 8 MB stack size and `--build-id=none,--strip-all` for **reproducible
    builds** — the comment is explicit that this exists to remove "references to temporary
    files in C-land" and make the build-id "reproducibly absent" (`ci.go:346-353`); under
    `-static`, it additionally appends `-static` to the external linker flags and forces the
    `osusergo`/`netgo` build tags to avoid a shared-glibc dependency creeping back in
    (`ci.go:354-359`).
  - Always passes `-trimpath`, "to avoid leaking local paths into the built executables"
    (`ci.go:372-373`) — another reproducibility/privacy concern, not a performance one.
- With no explicit package arguments, defaults to every `main` package under `./cmd/...`
  (`ci.go:271-275`, via `build.FindMainPackages`) — this is why `make all` needs no argument
  list of its own; the enumeration lives in `internal/build`, not the Makefile or `ci.go`.

### `doInstallKeeper` — a second, structurally distinct multi-target build path

Alongside the conventional `cmd/geth`/`cmd/evm`/etc. binaries, the current upstream tree has a
newer `cmd/keeper` command with its own installer, `doInstallKeeper` (`ci.go:287-327`), driven
by a `keeperTargets` table (`ci.go:81-118`) of five named cross-build configurations —
`ziren` (linux/mipsle, softfloat, CGO disabled, build tag `ziren`), `womir` (wasip1/wasm, tag
`womir`), `wasm-js` (js/wasm), `wasm-wasi` (wasip1/wasm), and `example` (host OS, tag
`example`). This is a materially different shape from `doInstall`'s single-binary-per-package
model: one Go source directory (`cmd/keeper`) is built repeatedly with different
`GOOS`/`GOARCH`/build-tag/environment-variable combinations to produce five differently-named
output binaries (`keeper-ziren`, `keeper-womir`, ...). Both the public GitHub Actions CI
(`.github/workflows/go.yml:42-58`, a dedicated `keeper` job) and the private Gitea release
workflow (`.gitea/workflows/release.yml:125-144`, a dedicated `keeper` job calling `go run
build/ci.go keeper -dlgo`) build this target — it is a first-class release artifact, not an
experimental side build.

### `doTest` — CI's actual test invocation

`doTest` (`ci.go:381-450`) is what `.github/workflows/go.yml`'s `test`/`test-32bit`/`windows`
jobs call (each as `go run build/ci.go test [-arch ...] [-short] [-p N]`). Notable behavior:
it always loads `build/checksums.txt` and, unless `-short` is passed, downloads and extracts
the `execution-spec-tests` fixture tarball into `tests/spec-tests` before running anything
(`ci.go:398-401`, `downloadSpecTestFixtures` at `ci.go:452-464`) — so the EF execution-spec
test vectors are fetched as a pinned, checksum-verified tarball rather than vendored in-tree
or pulled as a git submodule. It hardcodes a 45-minute test timeout "CI needs a bit more time
for the statetests" (`ci.go:411-412`), always enables the `ckzg` and `integrationtests` build
tags (`ci.go:414-418`), and threads `-p <threads>` through so CI can control test-package
parallelism explicitly (default `1`, `ci.go:391`) rather than trusting Go's own default GOMAXPROCS-driven parallelism — CI passes `-p 8` in practice (`go.yml:81,104,145`).

### `doCheckGenerate` / `doCheckBadDeps` — drift and architecture guards, not test coverage

Two lint-adjacent commands worth distinguishing from ordinary `go test`:

- `doCheckGenerate` (`ci.go:468-519`) hashes every file in each Go module (excluding
  `tests/testdata`, `build/cache`, `.git`), re-runs `go generate ./...`, re-hashes, and fails
  if anything changed — i.e. it proves committed generated code (protobuf, `stringer`,
  `gencodec` output, etc.) is actually up to date with its generators, then separately runs
  `go mod tidy -diff` per module to catch untidy `go.mod`/`go.sum` (`ci.go:512-518`). This is
  the same "generated code must match source of truth" class of check Nethermind's
  `tools/DocGen` addresses for *documentation* rather than code — go-ethereum's version
  guards Go codegen output, not docs.
- `doCheckBadDeps` (`ci.go:524-549`) is a small, explicitly-non-exhaustive architectural
  fitness function: a hardcoded list of two forbidden import edges (`core/rawdb` must not
  import `ethdb/leveldb` or `ethdb/pebbledb`, `ci.go:526-529`) — "rawdb tends to be a dumping
  ground for db utils, sometimes leaking the db itself" — checked via `go list -deps` output
  scanning. This is a hand-maintained, additive blocklist ("something we build up over time
  at sensitive places" per its own comment, `ci.go:522-523`), not a general layering-rule
  engine.

### `doLint` — pinned, checksum-downloaded golangci-lint, not a `PATH`-resident binary

`doLint` (`ci.go:551-608`) never assumes `golangci-lint` is installed. It resolves a pinned
version from `build/checksums.txt` (`csdb.FindVersion("golangci")`), downloads the correct
OS/arch archive (with an ARM-specific `GOARM` suffix, `ci.go:596-598`), verifies its checksum,
and extracts it into `build/cache` before invoking it against `.golangci.yml`. This is the
same "download and checksum-verify the tool rather than trust `PATH`" pattern `doInstall`
applies to the Go toolchain itself via `-dlgo`, and that `doCheckGenerate` applies to
`protoc`/`protoc-gen-go` (`downloadProtoc`/`downloadProtocGenGo`, `ci.go:610-694`) — every
external CI-consumed binary in this file is checksum-pinned via one shared mechanism
(`build/checksums.txt`), not version-floated.

### `doArchive` — release archive creation, signing, and upload

`doArchive` (`ci.go:697-735`) builds two archives per platform: `geth-<platform>-<version>.zip`
(just `geth` + `COPYING`) and `geth-alltools-<platform>-<version>.zip` (`abigen`, `evm`,
`geth`, `rlpdump` + `COPYING`, per the `allToolsBinaries` list at `ci.go:78`). `maybeSkipArchive`
(`ci.go:822-831`) is a hard gate: archiving is skipped entirely for PR builds
(`env.IsPullRequest`) and for any branch/tag combination other than `master` or a tag prefixed
`v1.` — meaning **archive creation for non-release branches never happens**, regardless of
what triggered the workflow. `archiveUpload` (`ci.go:781-819`) is the shared signing/upload
routine used by `doArchive`, `doKeeperArchive`, and `doWindowsInstaller` alike: if a `-signer`
env-var name is given, it PGP-signs the archive (`build.PGPSignFile`, producing a `.asc`
detached signature); if `-signify` is given, it additionally produces a `signify`-tool `.sig`;
if `-upload` (a `<storage-account>/<container>` string) is given, it uploads the archive plus
any signature files to Azure Blob Storage via `build.AzureBlobstoreUpload`, reading the
`AZURE_BLOBSTORE_TOKEN` environment variable for auth (`ci.go:798-816`). The Azure container
name, `gethstore/builds`, appears as the literal `-upload` argument in every release workflow
job (e.g. `release.yml:33`) — this is go-ethereum's canonical, hardcoded release artifact
store.

### `doDockerBuildx` — multi-arch image build and the exact tag scheme requested

`doDockerBuildx` (`ci.go:834-902`) is invoked as `go run build/ci.go dockerx -platform
linux/amd64,linux/arm64,linux/riscv64 -upload` from the private Gitea `docker` job
(`.gitea/workflows/release.yml:202-225`) — the public `go.yml` has no docker job at all (see
the dual-CI section below). It authenticates to
Docker Hub via base64-encoded `DOCKER_HUB_USERNAME`/`DOCKER_HUB_PASSWORD` secrets
(`ci.go:847-854`), ensures a `docker buildx` multi-arch builder named `multi-arch-builder`
exists (`ci.go:872-876`), then builds and (if `-upload`) pushes both `Dockerfile` (geth-only)
and `Dockerfile.alltools` (all four tools) for a tag set computed by branch/tag context. The
tag logic, quoted directly from the source comment (`ci.go:855-863`):

```
//  - ethereum/client-go:latest                            - Pushes to the master branch, Geth only
//  - ethereum/client-go:stable                            - Version tag publish on GitHub, Geth only
//  - ethereum/client-go:alltools-latest                   - Pushes to the master branch, Geth & tools
//  - ethereum/client-go:alltools-stable                   - Version tag publish on GitHub, Geth & tools
//  - ethereum/client-go:release-<major>.<minor>           - Version tag publish on GitHub, Geth only
//  - ethereum/client-go:alltools-release-<major>.<minor>  - Version tag publish on GitHub, Geth & tools
//  - ethereum/client-go:v<major>.<minor>.<patch>          - Version tag publish on GitHub, Geth only
//  - ethereum/client-go:alltools-v<major>.<minor>.<patch> - Version tag publish on GitHub, Geth & tools
```

The actual `switch` (`ci.go:866-871`) that produces this: on `master` branch, `tags =
["latest"]`; when `env.Tag` has prefix `"v1."`, `tags = ["stable", "release-<Family>",
"v<Semantic>"]` where `Family`/`Semantic` come from `internal/version` (see Versioning
section) — i.e. **only tags matching `v1.*` trigger the release-tag set**, a legacy artifact
of go-ethereum's long-lived 1.x versioning scheme that is still literally true of the current
tree (`version/version.go` has `Major = 1`, confirmed below).

### `doDebianSource` — Launchpad PPA source package generation

`doDebianSource` (`ci.go:905-988`) is the most involved single function in the file. Per
`build/ci-notes.md` (2,254 bytes, quoted in full context below), go-ethereum publishes
installable `.deb` packages for every Canonical-supported Ubuntu release by generating a
**Debian source package** (not a prebuilt binary `.deb`) and uploading it to Launchpad's
build farm, which compiles it there. The function, for each of five distros in `debDistros`
(`ci.go:153-159`: `xenial` 16.04 EOL 04/2026, `bionic` 18.04 EOL 04/2028, `focal` 20.04 EOL
04/2030, `jammy` 22.04 EOL 04/2032, `noble` 24.04 EOL 04/2034):

1. Imports a PGP signing key from the base64-encoded `PPA_SIGNING_KEY` secret (`ci.go:920-925`).
2. Downloads three **bootstrap** Go source tarballs (`ppa-builder-1.19`, `-1.21`, `-1.23`,
   pinned in `checksums.txt`) plus the **current** Go source tarball, because Launchpad's
   build environment doesn't have a recent-enough Go preinstalled to build go-ethereum's own
   current toolchain — the package bundles the entire Go source and bootstraps it before
   building go-ethereum itself (`ci.go:926-930`, `downloadGoBootstrapSources`/`downloadGoSources`
   at `ci.go:990-1024`, confirmed by `ci-notes.md`'s prose: "we bundle the entire Go sources
   into our own source archive and start the built job by compiling Go and then using that to
   build go-ethereum").
3. Stages a `debian/` control directory per package/distro via `stageDebianSource`
   (`ci.go:1190-1214`), rendering five template files from `build/deb/ethereum/` (`deb.rules`,
   `deb.changelog`, `deb.control`, `deb.copyright`, plus per-executable `.install`/`.docs`
   files) with Go's `text/template`-based `build.Render`.
4. Runs `debuild -S -sa -us -uc -d -Zxz -nc` to produce the source package, optionally signs
   the resulting `.changes` file with `debsign`, and uploads via SFTP to
   `~<launchpad-team>/ubuntu/<ppa-name>` with up to three retries (`ppaUpload`,
   `ci.go:1026-1054`).

Package naming bakes in a stable/unstable distinction: `isUnstableBuild` (`ci.go:1077-1082`)
returns true whenever `env.Tag == ""` (i.e. not a tagged release build), and every unstable
package gets a `-unstable` suffix plus an explicit Debian `Conflicts:` relationship against
the stable package name (`ExeConflicts`, `ci.go:1173-1188`) — so a develop-branch nightly
build and the last tagged stable release physically cannot be installed side-by-side via
`apt`, "requires user intervention" to switch streams per `ci-notes.md`.

### `doWindowsInstaller` — NSIS-based installer generation

`doWindowsInstaller` (`ci.go:1217-1298`) assumes binaries were already built by a prior
`install` step ("don't mix building and packaging to keep cross compilation complexity to a
minimum", `ci.go:1267-1269`) and only assembles the installer. It partitions
`allToolsArchiveFiles("windows")` into a `geth.exe` main tool and a `devTools` list of the
remaining three tools, renders five NSIS script templates (`nsis.geth.nsi`, `nsis.install.nsh`,
`nsis.uninstall.nsh`, `nsis.pathupdate.nsh`, `nsis.envvarupdate.nsh`) into a work directory
with `build.Render`, copies the prebuilt `SimpleFC.dll` binary and the `COPYING` license file
alongside them, then shells out to `makensis`/`makensis.exe` (binary name and flag prefix
(`-D` vs `/D`) chosen based on host OS, `ci.go:1278-1285`) with `MAJORVERSION`/`MINORVERSION`/
`BUILDVERSION` defines derived by splitting `version.Semantic` and appending the short commit
hash to the patch component when building from a non-tagged commit (`ci.go:1270-1273`). The
finished installer is signed and uploaded through the same shared `archiveUpload` routine
`doArchive` uses.

### `doPurge` — scheduled Azure blob cleanup

`doPurge` (`ci.go:1302-1351`) lists every blob in the configured Azure container, filters to
only `unstable`-tagged blobs (stable/tagged-release archives are never auto-deleted), sorts by
last-modified date, and deletes every unstable blob older than a `-days` threshold (default
30; the Gitea workflow that calls this passes `-days 14`, see below). It hard-gates on
`env.IsCronJob` (`ci.go:1309-1312`) — a manual `go run build/ci.go purge` invocation outside a
recognized scheduled-job environment context is a no-op that logs and exits 0 rather than
deleting anything.

---

## Native binary packaging (NSIS, Debian, checksums)

This section inventories `build/`'s non-Go packaging assets directly — largely **N/A for
fukuii's JVM distribution model** (see verdict table), but documented in full because the
task requires an exhaustive accounting.

| File | Size | Role |
|---|---|---|
| `build/nsis.geth.nsi` | 2,189 bytes | Top-level NSIS installer script: defines the two installer sections referenced by `doWindowsInstaller`'s `templateData` (`Geth`, `DevTools`) — the main geth binary section and an optional dev-tools section |
| `build/nsis.install.nsh` | 5,360 bytes | Install-time logic: file copy steps, registry entries, per-tool inclusion driven by the `DevTools`/`Geth` template variables |
| `build/nsis.uninstall.nsh` | 1,044 bytes | Uninstall-time cleanup, templated with the full `allTools` binary-name list |
| `build/nsis.envvarupdate.nsh` | 10,273 bytes | A well-known, reusable third-party NSIS macro library (`EnvVarUpdate`) for safely appending/removing entries from the Windows `PATH` environment variable during install/uninstall |
| `build/nsis.pathupdate.nsh` | 4,331 bytes | A thin wrapper around `EnvVarUpdate` specifically for adding/removing the geth install directory from `PATH` |
| `build/nsis.simplefc.dll` | 179,712 bytes | Prebuilt binary NSIS plugin (SimpleFC — Simple Firewall Control) copied verbatim into the installer work directory (`ci.go:1261-1263`) so the installer can register a Windows Firewall exception for geth's P2P listener without requiring the end user to compile anything |
| `build/nsis.simplefc.source.zip` | 23,209 bytes | Source archive for the above DLL, kept alongside for provenance/rebuildability, not extracted or used by `ci.go` itself |
| `build/deb/ethereum/deb.rules`, `deb.changelog`, `deb.control`, `deb.copyright`, `deb.docs`, `deb.install` | — | Go `text/template` sources for the Debian `debian/` control directory, rendered per-executable/per-distro by `stageDebianSource` (`ci.go:1201-1211`) |
| `build/deb/ethereum/completions/bash/geth`, `completions/zsh/_geth` | — | Shell completion scripts bundled into the `.deb` package via `deb.install`/`deb.docs` templating |
| `build/checksums.txt` | 12,240 bytes | Pinned SHA-256 checksums for every externally-downloaded build tool: `execution-spec-tests` fixture tarball, every `golang` release tarball (all OS/arch combinations, current pin `1.25.10`), three PPA Go bootstrap versions (`1.19`/`1.21`/`1.23`), `golangci-lint`, `protoc`, `protoc-gen-go` — read by `download.MustLoadChecksums` everywhere `ci.go` downloads a tool, and cached by CI keyed on this file's hash (`go.yml:25-28`, `key: ${{ runner.os }}-build-tools-cache-${{ hashFiles('build/checksums.txt') }}`) |
| `build/ci-notes.md` | 2,254 bytes | The only prose documentation of the Debian/Launchpad release mechanism — quoted extensively above; also documents that `go run build/ci.go debsrc -workdir dist` can be run locally on Ubuntu for testing, with local packaging tool prerequisites (`build-essential golang-go devscripts debhelper python-bzrlib python-paramiko`) |
| `build/goimports.sh` | 362 bytes | Small shell helper, not called from `ci.go` or the Makefile — a standalone import-formatting convenience script |
| `build/update-license.go` | 10,273 bytes | A separate `go run`-able utility (not dispatched through `ci.go`'s subcommand switch) for rewriting per-file copyright license headers across the tree |
| `build/bot/` | — | A small subdirectory (not read in depth for this report) holding CI-bot-adjacent scripts |

**Why this is mostly N/A for fukuii.** fukuii ships JVM bytecode (JARs) and Docker images —
there is no native per-OS binary to sign, no Windows installer to build (no `.exe`, no `PATH`
registration, no firewall-exception DLL), and no Debian package to compile from source because
the JVM itself is the portable runtime, not something fukuii bundles or cross-compiles. None
of the NSIS scripts, the Debian templating, or the PPA/Launchpad upload flow have a JVM
analog — a user installs a JRE/JDK once, independent of fukuii's release cadence, and runs the
same JAR everywhere. The **one** transferable idea, not a portable mechanism: `checksums.txt`'s
pattern of pinning every externally-downloaded build tool's checksum in one file, read by a
shared loader, and cache-keyed by CI on that file's hash — fukuii's sbt/Coursier dependency
resolution already provides an equivalent guarantee (locked artifact hashes in
`~/.sbt`/`~/.ivy2`/Coursier cache, resolved from `build.sbt` version pins) via a different,
JVM-native mechanism, so this is confirmation of an existing good practice rather than a gap.

---

## Dual CI: public GitHub Actions + private Gitea signing infrastructure

go-ethereum runs **two separate, non-overlapping CI systems**, confirmed by direct inspection
of both directories:

- **`.github/workflows/`** (public GitHub Actions, 3 files): `go.yml` (the main CI gate — lint,
  test matrix across Go 1.24/1.25, 32-bit tests, Windows build+test, and a `keeper` build job,
  triggered on push-to-`master` and every pull request, with PR-run cancellation via a
  `concurrency` group, `go.yml:10-13`), `validate_pr.yml` (not read in depth for this report —
  PR-metadata validation), and `freebsd.yml` (a FreeBSD-specific build check). **None of these
  three files reference `LINUX_SIGNING_KEY`, `AZURE_BLOBSTORE_TOKEN`, or any release/signing
  secret** (confirmed by direct grep) — public CI never touches signing material or the
  release artifact store.
- **`.gitea/workflows/`** (a **separate, self-hosted Gitea instance** — not GitHub Actions
  despite the near-identical YAML syntax, 3 files): `release.yml` (triggered on push to
  `master`, on `v*` tags, or manual dispatch — builds and uploads signed archives for
  linux-intel amd64/386, linux-arm arm64/arm5/arm6/arm7, the `keeper` cross-targets, Windows
  amd64/386 with NSIS installers, and multi-arch Docker images), `release-ppa.yml` (the
  Launchpad PPA source-package upload, triggered on `v*` tags or manual dispatch — its own
  comment notes "we cannot use cron-triggered builds right now, Gitea seems to have a few bugs
  in that area. So this workflow is scheduled using an external triggering mechanism and
  workflow_dispatch", `release-ppa.yml:7-9`), and `release-azure-cleanup.yml` (the `doPurge`
  wrapper, `-days 14`, same external-trigger caveat, manual-dispatch-only in-repo).

Every Gitea release job references signing/upload secrets directly: `LINUX_SIGNING_KEY`,
`WINDOWS_SIGNING_KEY`, `AZURE_BLOBSTORE_TOKEN`, `DOCKER_HUB_USERNAME`/`PASSWORD`,
`PPA_SIGNING_KEY`, `PPA_SSH_KEY` (grepped across `release.yml`/`release-ppa.yml`). These
secrets exist **only** in the private Gitea instance's secret store — they are never
referenced by, or reachable from, the public-facing `.github/workflows/` files a random
GitHub contributor's fork or PR branch could trigger.

**Why this split exists — the instructive pattern.** A PGP/code-signing private key, an Azure
Blob Storage write token, Docker Hub push credentials, and a Launchpad PPA signing key are all
credentials that, if exfiltrated via a malicious PR (e.g. a crafted `workflow_dispatch` input,
a supply-chain-compromised Action, or a fork-PR `pull_request_target` misconfiguration), would
let an attacker publish a signed, trusted-looking malicious `geth` binary to the exact
distribution channels users are told to trust. go-ethereum's structural answer is to physically
separate *where the secrets can be reached from*: public GitHub Actions handles PR gating and
correctness CI (lint, test, 32-bit, Windows build+test) — the surface any external contributor
or fork can trigger — and holds **zero** release-signing secrets. The private, self-hosted
Gitea instance holds every signing/upload credential and only runs release-shaped triggers
(push to `master`, version tags, or an operator's manual dispatch) — surfaces an external
contributor cannot trigger by opening a PR. This is a genuinely instructive isolation pattern
independent of Gitea specifically: the general principle is *"the infrastructure that can
publish signed artifacts under the project's trusted name must not be reachable from the same
trigger surface that untrusted contributions can influence."*

---

## Versioning & internal/ privacy convention

### `version/version.go` — plain Go const block

The entire authoritative version definition is four constants in one file
(`version/version.go:19-24`):

```go
const (
	Major = 1          // Major version component of the current release
	Minor = 17         // Minor version component of the current release
	Patch = 5          // Patch version component of the current release
	Meta  = "unstable" // Version metadata to append to the version string
)
```

This is deliberately the *only* thing this file does — no changelog, no release notes, no
history. `internal/version/version.go` (a separate package, imported by `build/ci.go` as
`"github.com/ethereum/go-ethereum/internal/version"`) derives everything else from these four
constants: `Family = "<Major>.<Minor>"`, `Semantic = "<Major>.<Minor>.<Patch>"`, `WithMeta =
Semantic[+"-"+Meta if Meta != ""]`, `WithCommit(gitCommit, gitDate)` (appends the first 8
commit-hash characters, and the build date unless `Meta == "stable"`), and `Archive(gitCommit)`
(the string used for release archive filenames). Every one of `ci.go`'s Docker-tag, archive-
filename, NSIS-installer-version, and Debian-package-version computations reads through this
one derivation chain rather than re-parsing a version string anywhere.

**Confirmed: no root-level `CHANGELOG.md`.** Direct listing of the repository root shows
exactly three Markdown files — `AGENTS.md`, `README.md`, `SECURITY.md` — no changelog of any
kind. go-ethereum publishes release notes exclusively via GitHub Releases (external to the
git tree) rather than an in-repo changelog file; bumping `version/version.go`'s three integer
constants (and the `Meta` string, e.g. from `"unstable"` to `""` for a stable tag) is the only
in-repo signal that a release occurred.

### `internal/` — 21 subpackages, compiler-enforced privacy

Direct listing (`ls internal/`) shows **21** subpackages: `blocktest`, `build`, `cmdtest`,
`debug`, `download`, `era`, `ethapi`, `flags`, `guide`, `jsre`, `memlimit`, `reexec`,
`shutdowncheck`, `syncx`, `tablewriter`, `telemetry`, `testlog`, `testrand`, `utesting`,
`version`, `web3ext`. Their file counts range from 1 file (`blocktest`, `cmdtest`, `download`,
`shutdowncheck`, `syncx`, `testrand`) to 13 (`ethapi`, the largest — the internal JSON-RPC
`eth_*` namespace method implementations). Two of these are directly load-bearing for the
build system documented above:

- **`internal/build`** (7 files: `archive.go`, `azure.go`, `env.go`, `file.go`, `gotool.go`,
  `pgp.go`, `util.go`) — this is the library `build/ci.go` itself is built on: `build.Env()`
  (git/CI-environment detection — commit, branch, tag, `IsPullRequest`, `IsCronJob`),
  `build.GoToolchain` (cross-compilation toolchain abstraction, `DownloadGo`), `WriteArchive`/
  `ExtractArchive`, `AzureBlobstoreUpload`/`List`/`Delete`, `PGPSignFile`, `Render`/
  `RenderString` (the NSIS/Debian templating engine), `HashFolder`/`DiffHashes` (the
  generated-code drift detector), `FindMainPackages`, `MustRun`/`MustRunCommand`. Every
  significant capability `ci.go` exposes as a CLI subcommand is really a thin dispatch layer
  over functions in this internal package.
- **`internal/download`** (1 file) — `ChecksumDB`, `MustLoadChecksums`, `FindVersion`,
  `DownloadFileFromKnownURL`, `DownloadAndVerifyAll` — the shared checksum-pinned-download
  mechanism used for the Go toolchain, `golangci-lint`, `protoc`, `protoc-gen-go`, and the
  execution-spec-tests fixture tarball alike, all reading from the one `build/checksums.txt`.

**The privacy mechanism itself.** Go's compiler refuses to let any package outside
`github.com/ethereum/go-ethereum/...` (i.e. outside this module) import anything under an
`internal/` path anywhere in the import chain — this is a language-level rule (Go spec,
"Internal packages"), not a convention enforced by linting or code review. Concretely: an
external consumer of go-ethereum-as-a-library can import `github.com/ethereum/go-ethereum/
core`, `.../ethclient`, `.../rpc`, etc. (all public), but a compile error results from
importing `.../internal/build` or `.../internal/ethapi` from outside the module. This gives
go-ethereum a *within-repo-only* API surface for its own build tooling, RPC method internals,
JS-console runtime, and test helpers — free of any risk that an external project starts
depending on internals that were never meant to be a stable public API, without requiring any
documentation discipline ("please don't import this") to hold.

**fukuii's nearest analog.** The JVM/Scala build has no compiler-level equivalent of Go's
`internal/` import restriction — there is no path-based rule that says "anything under this
directory name can only be imported from within this module," enforced by `scalac` itself.
The two mechanisms fukuii actually has, both weaker in different ways:

1. **Package-private visibility modifiers** (`private[com.chipprbots]`, or bare `private`/
   `protected` without a qualifier) restrict a symbol's visibility to a named package scope,
   but this is opt-in per-symbol, not a directory-wide convention the compiler applies
   automatically the way `internal/<anything>` is in Go — a fukuii author must remember to
   mark each class/def `private[somepackage]`; nothing forces an entire subtree to be
   non-importable by naming it a certain way.
2. **sbt module boundaries** (`build.sbt`'s `lazy val` project graph — `bytes`, `crypto`,
   `rlp`, `Evm`, etc.) enforce visibility at the *module* (separately-compiled-and-published-
   artifact) level via `dependsOn`/classpath scoping: a module that doesn't declare a
   dependency on another module simply cannot see its public classes at compile time. This is
   closer in spirit to Go's `internal/` restriction than package-private modifiers are — it is
   directory/project-structural rather than per-symbol — but it operates at a coarser
   granularity (whole modules, not arbitrary subtrees within one module) and requires an
   actual module split to get the enforcement, whereas Go's `internal/` rule applies to any
   directory named `internal` anywhere in a single module's tree with zero build-file
   configuration.

Neither fukuii mechanism reproduces Go's specific guarantee "any directory literally named
`internal/` becomes uncompilable-against from outside this module, with no build-file
configuration required." This is a language-design difference (Go standardized this as a
spec-level convention in 1.4; the JVM/Scala ecosystem never adopted an equivalent), not a
fukuii gap to close — see the verdict table.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable (scope mismatch: native vs JVM) / Already ahead | Reasoning |
|---|---|---|
| Makefile-as-thin-wrapper over a real Go-program task runner (`build/ci.go`) | **Not portable as literal structure; principle already satisfied differently** | fukuii has no Makefile at all — `build.sbt`'s `addCommandAlias` block plays the "named, memorable task" role Make's targets play here, and sbt itself (not a wrapped external script) is already the "real" build engine, so there's no analogous need for a thin dispatch layer over a separate program. The *goal* (a friendly, memorable command surface for contributors who don't want to learn build internals) is already met by `AGENTS.md`'s build-command table plus `addCommandAlias`. |
| Reproducible-build linker flags (`--buildid=none`, `-trimpath`, stripped DWARF on darwin, static-link glibc-avoidance tags) | **Not portable (scope mismatch: native vs JVM)** | These are all native-binary/linker concerns specific to producing bit-identical Go executables across machines. The JVM has no linker step and no per-platform binary artifact to make reproducible in this sense — fukuii's reproducibility surface (if pursued) would be about deterministic JAR/Docker-layer builds via sbt/Docker build-arg discipline, not linker flags. |
| Pinned-checksum download of every external build tool (Go toolchain, `golangci-lint`, `protoc`, `protoc-gen-go`, spec-test fixtures) via one shared `checksums.txt` + `internal/download` loader | **Already ahead (different mechanism, equivalent guarantee)** | fukuii's sbt/Coursier dependency resolution already pins and verifies artifact hashes for every JVM dependency via `build.sbt` version declarations and the Coursier/Ivy cache — this is the JVM-ecosystem-native equivalent of go-ethereum's `checksums.txt` mechanism, achieved through the build tool's own dependency-resolution guarantees rather than a hand-rolled checksum file. No action needed; noted to confirm the underlying goal is already met. |
| `doCheckGenerate` — hash-before/hash-after `go generate`/`go mod tidy` drift detection | **Not portable (scope mismatch)** | fukuii has no `go generate`-equivalent codegen step (no protobuf/stringer/gencodec analogs in the Scala build) for this check to guard. Revisit only if fukuii ever adopts a source-codegen step (e.g. a Scala 3 macro-derivation or ABI-codegen tool) that could silently drift from its generator. |
| `doCheckBadDeps` — hand-maintained, additive forbidden-import-edge blocklist (`core/rawdb` must not import `ethdb/leveldb`/`ethdb/pebbledb`) | **Needs design** | The specific edges are go-ethereum-only, but the *pattern* — a small, explicitly-non-exhaustive, grep/`go list -deps`-driven list of "these two packages must never import each other," grown incrementally at sensitive places rather than designed as a general layering-rule engine — is directly reusable for fukuii's own known-sensitive boundaries (e.g. "consensus/vm/domain code must not import jsonrpc/ or db/ storage-engine internals directly"). Cheap to build: a small script using `sbt` classpath/dependency introspection or a `scalafix` custom rule, wired into an existing CI lint step. Worth scoping as a discrete, low-effort addition. |
| Pinned `golangci-lint`/`protoc` version resolution via `checksums.txt` — specifically the **download-if-missing, never trust `PATH`** discipline | **Already ahead (different mechanism)** | fukuii's tooling (scalafmt, scalafix, scapegoat) is resolved as sbt plugin dependencies via `project/plugins.sbt`, which is the JVM-native equivalent of "never trust a `PATH`-resident tool version" — plugin versions are pinned in version control and resolved reproducibly by sbt itself. No gap. |
| `doArchive`/`doDockerBuildx`/`doWindowsInstaller`/`doDebianSource` — native binary archive, Docker tag scheme, NSIS installer, Debian PPA source package | **Not portable (scope mismatch: native vs JVM)** | fukuii ships JARs and Docker images, not cross-compiled native binaries — there is no Windows installer, no firewall-exception DLL registration, and no Debian source package to build, because the JVM runtime is the portable layer rather than something fukuii itself cross-compiles per OS/arch. The Docker *tag-naming discipline* specifically (`latest` on default branch, `stable`/`release-<major>.<minor>`/`v<semantic>` on release tags) is a genuinely portable idea independent of the native-vs-JVM distinction — see the next row. |
| Docker tag scheme (`ethereum/client-go:release-<major>.<minor>`, `:stable`, `:v<semantic>`, `:latest` on default-branch pushes) | **Port now** | This specific tagging convention (float tags for "latest stable" and "latest on this minor line" alongside an immutable exact-semver tag) is a general Docker-release best practice with zero native-vs-JVM scope mismatch. Compare against fukuii's current auto-versioning release pipeline (`.github/VERSIONING.md`) to confirm the same float-tag set (`stable`, `release-<major>.<minor>`, exact semver, `latest`) is produced on every auto-release — if any of these floating tags are currently missing, this is a low-cost addition to the existing buildx/Cosign workflow. |
| Dual CI split: public GitHub Actions (PR gating, no secrets) + private, separately-hosted signing infrastructure (Gitea, holds every release/signing credential) reachable only from push-to-default-branch/tag/manual-dispatch triggers | **Not portable now; principle worth flagging** | fukuii's release process is intentionally the opposite design choice — fully automatic, public-GitHub-Actions-only releasing via Cosign + buildx provenance/SBOM on every merge (`.github/VERSIONING.md`), with no separate private signing infrastructure. This is a legitimate, different trust model (rely on OIDC-based keyless Cosign signing tied to the GitHub Actions identity, rather than a long-lived private signing key reachable only from a separate trust boundary) rather than a straightforward gap — standing up a second, privately-hosted CI system purely to isolate signing secrets would be a large, likely unwarranted infrastructure investment for fukuii's current single-maintainer scale and threat model. The instructive principle to retain, without adopting the two-CI-system mechanism: periodically re-verify that no signing-capable secret in fukuii's current single public-Actions pipeline is reachable from a fork-PR-triggered workflow (`pull_request_target` misuse, or a compromised third-party Action in a workflow that has secrets access) — the isolation *goal* remains valid even though fukuii's chosen mechanism (OIDC/Cosign keyless signing rather than long-lived signing keys) reduces, but does not eliminate, the blast radius this pattern is designed to contain. |
| `version/version.go`'s plain four-constant version block, no in-repo changelog, releases published exclusively via GitHub Releases | **Already ahead** | fukuii already keeps a root `CHANGELOG.md` in-repo, which is arguably better practice than go-ethereum's GitHub-Releases-only approach (in-repo history survives independent of the hosting platform, is diffable/greppable locally, and doesn't require leaving the working tree to see what changed between two commits). No action needed — noted as confirmation fukuii's existing choice compares favorably. |
| `internal/` — Go-compiler-enforced, directory-name-triggered import restriction (21 subpackages, no configuration required, applies automatically to any path segment named `internal`) | **Not portable (language-level scope mismatch)** | The JVM/Scala toolchain has no equivalent language rule — there is no directory name that automatically becomes uncompilable-against from outside a module with zero build-file configuration. fukuii's two closest mechanisms — `private[com.chipprbots]`-style qualified-private visibility (opt-in per-symbol) and sbt's module/`dependsOn` graph (module-level, requires an actual module split) — both require deliberate author action or build-structure investment to achieve a narrower version of the same guarantee; neither is a drop-in port of Go's automatic directory-name convention. Worth documenting as a known, permanent language-ecosystem gap rather than something to chase — see `dead-code-review.md`/module-boundary discipline as fukuii's practical substitute going forward. |
| `internal/build`/`internal/download` as the real implementation behind every `ci.go` subcommand (thin CLI dispatch over a well-factored internal library) | **Port now (as a process lesson)** | The structural lesson — keep the actual logic in ordinarily-testable, ordinarily-importable (within-module) packages, and make the CLI-facing entry point (`ci.go`'s `main()` switch) a thin dispatcher over that library rather than embedding logic directly in the dispatcher — is a pattern fukuii's own `scripts/agent-tooling/` scripts and sbt command aliases should continue to follow as they grow, independent of the internal/-privacy mechanism specifically. No new mechanism required; a discipline note for future script/tooling additions. |

---

*Compiled from a direct read of every file cited above in the vendored clone at
`.claude/repo-references/clients/go-ethereum/`. Line numbers refer to that clone's current
checkout (commit `59e89e81e5`, branch `upstream`, 2026-07-01); re-verify against `git log` if
the vendored copy is refreshed.*
