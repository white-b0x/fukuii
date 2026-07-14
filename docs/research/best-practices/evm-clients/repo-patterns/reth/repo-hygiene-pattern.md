# Reth — Repo Hygiene & Security Patterns

Source: `.claude/repo-references/clients/reth/` (vendored full clone, verified genuine —
`.git/` is populated, `git log --oneline -3` shows real history ending at
`3d76b93c2 fix(engine): use execution version header for SSZ routes (#25925)`, and
`git remote -v` resolves `origin` to `white-b0x/reth` with `upstream` tracking
`paradigmxyz/reth` — this is a real fork-and-clone, not a summary or partial checkout).
Every claim below cites a specific file, and a line number where one could be pinned
precisely; where a whole short file is quoted, the citation is the file path with its
total line count instead of manufacturing line numbers for a block quote.

Reth is Paradigm's modular Rust execution client, maintained by a small, named
engineering team (visible in full in `.github/CODEOWNERS`) under an open, high-volume
contribution model — its repo-hygiene layer optimizes for **routing review load across
~35 independently-owned crates** and for **onboarding first-time Rust contributors
gently**, which is a different problem shape from Nethermind's (mature, security-scanner-
heavy, C#/.NET) or Erigon's. This document catalogs that layer exhaustively and
cross-references it against fukuii's current state so a maintainer can see, at a glance,
what to port, what needs redesign, and what genuinely doesn't apply.

---

## SECURITY.md — minimal

**File:** `.claude/repo-references/clients/reth/SECURITY.md`, 6 lines, full text below —
confirmed via direct read, no truncation:

```markdown
# Security Policy

## Report a Vulnerability

Contact [security@tempo.xyz](mailto:security@tempo.xyz).
```

That is the entire file. No timeline commitment (contrast Nethermind's "acknowledge
your report within 24 hours"), no scope statement (in-scope vs. out-of-scope
components), no PGP key or encrypted-contact option, no bug-bounty program reference,
no GitHub Security Advisories draft-advisory link. It is a single mailto: link behind a
two-line heading structure — the minimum viable security policy that still satisfies
GitHub's automatic "Security policy" repo-health-check badge (GitHub only requires a
`SECURITY.md` to exist and be non-empty to light up that indicator; it does not
validate content).

**The domain is notable.** `security@tempo.xyz`, not `security@paradigm.xyz` or
`security@reth.rs` — Tempo is a separate Paradigm-affiliated entity/brand. This is worth
flagging as an observation (the contact routes to a different organizational identity
than the one branding the README and crate names) rather than a hygiene defect; it is
plausible this is a deliberate shared security-intake address across multiple Paradigm
projects, but that inference isn't verifiable from this file alone.

**Fukuii verdict — already planned, and Reth's version argues for staying even leaner.**
fukuii has no `SECURITY.md` today (confirmed: `ls SECURITY.md` from repo root returns
"No such file or directory"). A lean one is already planned for this session, using
GitHub Security Advisories' private draft-advisory flow as the primary path (per the
Nethermind sibling doc's `SECURITY.md` finding) rather than inventing a dedicated
security-team email fukuii doesn't have. Reth's file is useful evidence for the *floor*:
a single-sentence, single-contact policy is an accepted, GitHub-badge-satisfying pattern
in this ecosystem — fukuii's version can be exactly this short (GitHub Security
Advisories link + one maintainer contact, no invented SLA, no invented email alias)
without looking incomplete by comparison to a top-tier client.

---

## CODEOWNERS — per-crate granularity

**File:** `.github/CODEOWNERS`, 50 lines (`wc -l` confirmed), full text read.

Reth's CODEOWNERS is organized as **one line per `crates/*` subdirectory** (not per
top-level module, not per team), giving PR-review auto-assignment at the granularity of
an individual crate in a workspace of ~35+ crates. This is a finer unit of ownership
than Nethermind's per-C#-project lines or Erigon's package-level routing — Rust's crate
boundary is already the natural compilation/publishing unit, so CODEOWNERS simply
mirrors `Cargo.toml`'s existing module graph rather than inventing a new grouping.

**Catch-all owner, quoted verbatim** (`.github/CODEOWNERS:1`):

```
*                           @gakonst
```

`@gakonst` (Georgios Konstantopoulos, Paradigm's CTO) owns everything not matched by a
more specific rule below it — standard CODEOWNERS last-match-wins semantics mean this
line is effectively "default owner," silently overridden by every one of the ~48 more
specific lines beneath it.

**Representative per-crate entries, quoted verbatim** (column alignment preserved from
source, including two lines with trailing whitespace after the last listed owner —
`crates/config/` and `crates/metrics/` — an artifact of hand-editing, not semantically
meaningful):

```
crates/consensus/            @mattsse @Rjected
crates/engine/                @mattsse @Rjected @mediocregopher @yongkangc
crates/evm/                   @mattsse @Rjected @klkvr
crates/net/                   @mattsse @Rjected
crates/net/downloaders/       @Rjected
crates/primitives/             @Rjected @mattsse @klkvr
crates/revm/                   @mattsse
crates/rpc/                    @mattsse @Rjected
crates/stages/                 @shekhirin @mediocregopher
crates/storage/db/              @joshieDo
crates/storage/provider/        @joshieDo @shekhirin @yongkangc
crates/tasks/                  @mattsse @DaniPopes
crates/trie/                    @Rjected @shekhirin @mediocregopher @yongkangc
```

Plus two non-`crates/` entries and one repo-infra entry, quoted verbatim
(`.github/CODEOWNERS:47-50`):

```
bin/reth/                     @mattsse @shekhirin @Rjected
bin/reth-bench-compare/       @mediocregopher @shekhirin @yongkangc
etc/                           @Rjected @shekhirin
.github/                       @gakonst @DaniPopes
```

**Structure takeaway — crate-per-line with 1-4 named owners, deepest match wins.** Every
`crates/*` line lists between one (`crates/tokio-util/ @mattsse`) and four
(`crates/engine/`, `crates/trie/`) named individuals; no line names more than four
people, and the file never uses a team handle (`@paradigmxyz/some-team`) — every owner
is an individual GitHub username. Sub-crate nesting is used where a workspace crate has
independently-owned sub-crates, e.g. `crates/net/` (`@mattsse @Rjected`) vs. the more
specific `crates/net/downloaders/` (`@Rjected` alone) directly beneath it — the deeper,
more specific path wins for files under `downloaders/`, while everything else under
`crates/net/` falls to the shallower rule.

**A stale entry, confirmed by direct check.** `bin/reth-bench-compare/` is listed
(`.github/CODEOWNERS:49`) but does **not exist** in this vendored clone —
`ls bin/` shows only `reth` and `reth-bb`. The directory was evidently renamed
(`reth-bench-compare` → `reth-bb`) without updating CODEOWNERS. This is a real,
observable imperfection in even a well-maintained top-tier client's hygiene layer, not a
fukuii-specific concern — worth noting because it's a concrete illustration of the
general failure mode any CODEOWNERS file is exposed to (path renames silently orphaning
ownership rules) rather than evidence Reth's file is poorly kept.

**Fukuii verdict — reference for later, not a template to port now.** fukuii has no
CODEOWNERS today (confirmed: `.github/CODEOWNERS` does not exist). A **lightweight**
CODEOWNERS is planned this session: a root catch-all `* @realcodywburns @chris-mercer`,
reflecting fukuii's real two-maintainer structure (Cody Burns / Chippr Robotics LLC,
Christopher Mercer / White B0x Inc.) — nothing more granular yet. Reth's per-crate model
is the right reference to return to **if fukuii's contributor count grows** to justify
finer routing (fukuii's module boundaries — `vm/`, `crypto/`, `domain/`, `db/`,
`jsonrpc/`, per the specialist-subagent split already documented in `CLAUDE.md` — map
reasonably well onto a future per-directory CODEOWNERS the way `crates/*` does here), but
a ~50-line, per-module file where every line still resolves to the same one or two names
adds mandatory-review-request overhead without adding the routing signal it exists to
provide. Also worth carrying forward as a lesson, independent of file size: **whatever
CODEOWNERS fukuii eventually writes needs a periodic staleness check** against actual
directory structure, since Reth's own `bin/reth-bench-compare/` entry shows this drifts
even in an actively maintained repo.

---

## CONTRIBUTING.md — reviewer-facing section (the standout gap)

**File:** `CONTRIBUTING.md`, 242 lines (`wc -l` confirmed), full text read.

### Contributor-facing sections (first ~160 lines)

Standard shape: a welcoming preamble ("No contribution is too small and all
contributions are valued," `CONTRIBUTING.md:8`), a Code of Conduct pointer to the
upstream [Rust Code of Conduct][rust-coc] with a report contact of
`georgios@paradigm.xyz` (`CONTRIBUTING.md:20-24`) — notably a *different* address than
`SECURITY.md`'s `security@tempo.xyz`, i.e., conduct reports and vulnerability reports are
deliberately routed to different people/inboxes — and the three canonical ways to
contribute (open an issue, add context to an existing one, resolve one via PR,
`CONTRIBUTING.md:30-37`).

**"No spelling/grammar-only PRs" policy, quoted verbatim** (`CONTRIBUTING.md:42-45`):

> ### Contributions Related to Spelling and Grammar
>
> At this time, we will not be accepting contributions that only fix spelling or
> grammatical errors in documentation, code or elsewhere.

This is a deliberate low-signal-PR filter — a large, high-traffic OSS project (unlike
fukuii's current scale) needs this specifically to avoid "drive-by typo PR" farming for
contribution-graph padding, a known pattern on high-star repos.

**`make pr` as the pre-submit gate** (`CONTRIBUTING.md:96-100`):

```sh
make pr
```

is the single command a contributor is told to run before opening a PR — no enumerated
list of individual `cargo fmt`/`cargo clippy`/`cargo test` invocations in this file; the
Makefile target is the contract, and its actual contents (not read in full for this
document; out of scope) are the source of truth for what "passes CI-equivalent locally"
means.

**VSCode rust-analyzer settings, quoted verbatim** (`CONTRIBUTING.md:105-111`):

```json
"editor.formatOnSave": true,
"rust-analyzer.rustfmt.extraArgs": ["+nightly"],
"[rust]": {
"editor.defaultFormatter": "rust-lang.rust-analyzer"
}
```

The `rustfmt.extraArgs": ["+nightly"]` detail matters: it means `rustfmt` itself must run
under the **nightly** toolchain even though the crate builds on stable — a common Rust
pattern since several `rustfmt.toml` options (Reth's own file uses `imports_granularity`,
`wrap_comments`, `format_code_in_doc_comments` — see the tooling section below) are
nightly-only formatter features not yet stabilized.

**Commit-squashing guidance** (`CONTRIBUTING.md:139-146`): no hard limit on commit count
per PR ("many contributors find it easier to review changes that are split across
multiple commits"), but "checkpoint" commits that don't represent a single logical
change should be squashed together before review — guidance framed as a readability
recommendation, not a bot-enforced rule.

### The standout section — reviewing pull requests (`CONTRIBUTING.md:163-227`, ~65 lines)

This is the section absent from every sibling client's `CONTRIBUTING.md` reviewed so
far (Nethermind's 84-line file and Erigon's equivalent are entirely contributor-facing).
Reth's file dedicates roughly a quarter of its total length to **coaching reviewers**,
under the explicit premise "Any Reth community member is welcome to review any pull
request" (`CONTRIBUTING.md:165`) — i.e., review is not restricted to CODEOWNERS-listed
maintainers, so first-time or occasional reviewers need the same onboarding a
first-time contributor gets.

**Framing** (`CONTRIBUTING.md:167-172`, quoted):

> All contributors who choose to review and provide feedback on pull requests have a
> responsibility to both the project and individual making the contribution. Reviews and
> feedback must be helpful, insightful, and geared towards improving the contribution as
> opposed to simply blocking it. If there are reasons why you feel the PR should not be
> merged, explain what those are. Do not expect to be able to block a PR from advancing
> simply because you say "no" without giving an explanation.

**"Review a bit at a time"** (`CONTRIBUTING.md:181-209`) — an explicit, ordered
priority list for what a reviewer should look at *first*, given verbatim
(`CONTRIBUTING.md:190-193`):

1. Does this change make sense for Reth?
2. Does this change make Reth better, even if only incrementally?
3. Are there clear bugs or larger scale issues that need attending?
4. Are the commit messages readable and correct? If it contains a breaking change, is
   it clear enough?

Followed by an explicit warning against scope creep in review demands
(`CONTRIBUTING.md:195`, quoted): "Note that only **incremental** improvement is needed
to land a PR. This means that the PR does not need to be perfect, only better than the
status quo." — and a request-vs-demand distinction (`CONTRIBUTING.md:198-199`, quoted):
"When changes are necessary, *request* them, do not *demand* them, and **do not assume
that the submitter already knows how to add a test or run a benchmark**." A concrete nit-
labeling convention is even specified (`CONTRIBUTING.md:208-209`, quoted): `` Nit: change
foo() to bar(). But this is not blocking ``.

**"Be aware of the person behind the code"** (`CONTRIBUTING.md:214-218`) — the section
header itself is the thesis; the body states plainly that a technically-correct-but-
harshly-delivered review can permanently drive away a contributor even if the specific
change lands (`CONTRIBUTING.md:216-218`, paraphrased closely): merging a change that
improves Reth is a loss if the individual who wrote it never wants to contribute again —
"the goal is not just having good code."

**Abandoned/stale PR handling, quoted verbatim** (`CONTRIBUTING.md:220-226`):

> If a pull request appears to be abandoned or stalled, it is polite to first check with
> the contributor to see if they intend to continue the work before checking if they
> would mind if you took it over (especially if it just has nits left). When doing so, it
> is courteous to give the original contributor credit for the work they started, either
> by preserving their name and e-mail address in the commit log, or by using the
> `Author:` or `Co-authored-by:` metadata tag in the commits.

This is the most concretely actionable git-mechanics guidance in the whole file: it
names the exact two ways (author-field preservation vs. `Co-authored-by:` trailer) a
maintainer can take over someone else's abandoned work while keeping their contribution
attributed in the permanent commit history — a specific enough instruction that it
reads as institutional memory of a real incident, not generic etiquette.

**Provenance note** (`CONTRIBUTING.md:228`): the entire reviewer section is explicitly
marked "_Adapted from the [Foundry contributing guide][foundry-contributing]_" — Reth
did not originate this content; it's a deliberate cross-pollination from a sibling
Paradigm-ecosystem project (Foundry), suggesting this reviewer-coaching pattern is
viewed internally as portable boilerplate worth reusing across projects, which is
itself evidence it's a low-cost, high-value addition for any project facing the same
"many occasional reviewers, not just a fixed maintainer roster" shape.

**Fukuii verdict — real gap, correctly lower priority pending a multi-reviewer
community.** fukuii's contributing documentation
(`docs/development/contributing.md`, referenced from `AGENTS.md`'s "Full contributor
workflow" section) is contributor-facing only — there is no equivalent
reviewer-coaching section, and no reviewer section exists anywhere else in fukuii's
docs tree either. This is a genuine gap relative to Reth's pattern, but the underlying
premise Reth's section addresses ("any community member may review") does not yet apply
to fukuii, which has two named maintainers, not an open reviewer pool. Writing a
Reth-style reviewer section today would be coaching an audience that doesn't exist yet.
The correct sequencing is: (1) note the gap now so it isn't rediscovered from scratch
later, (2) write the section once fukuii has more than the two current maintainers
doing review, at which point Reth's four-point "review a bit at a time" priority list
and the `Co-authored-by:`-based abandoned-PR-takeover mechanic are both worth porting
close to verbatim — neither is Reth-specific content, and the `Co-authored-by:`
mechanic in particular is exactly the git primitive fukuii's own commit-message
convention (`Co-Authored-By: Claude <noreply@anthropic.com>` trailers, per the global
commit-workflow rules) already demonstrates familiarity with.

---

## Issue templates / PR template absence

### Issue templates — `.github/ISSUE_TEMPLATE/` (4 files, all form-based, all read in
full)

All four templates use GitHub's YAML issue-forms format (not legacy Markdown templates),
each with a `labels:` array that pre-applies both a category label and a triage-state
label on creation:

| Template | Lines | Labels applied | Structure |
|---|---|---|---|
| `bug.yml` | 124 | `C-bug`, `S-needs-triage` | 10 fields: free-text description (with an inline pointer to the security contact for vulnerability reports, `bug.yml:10`), reproduction steps, node logs (with exact log-path hints for Linux/`~/.cache/reth/logs` and macOS/`~/Library/Caches/reth/logs`, `bug.yml:43-45`), a multi-select platform dropdown, a multi-select container-type dropdown (Docker/Kubernetes/LXC/LXD/Other/none), client version (`reth --version`), **database version** (`reth db version`), network/chain argument, node type (Archive/Full/Pruned), prune config, build command, and a required Code-of-Conduct checkbox |
| `feature.yml` | 22 | `C-enhancement`, `S-needs-triage` | 2 free-text fields: description (with a specific ask for "a proposed API surface" if the feature is crate-level, `feature.yml:15`) and additional context |
| `docs.yml` | 19 | `C-docs`, `S-needs-triage` | 2 free-text fields: description (asks whether the doc change targets end-users or contributors, `docs.yml:13`) and additional context |
| `config.yml` | 5 | n/a (meta-config) | `blank_issues_enabled: false` plus one `contact_links` entry redirecting free-form questions to GitHub Discussions |

**The bug-report template's database-version field is the standout, client-specific
detail** (`bug.yml:81-87`): `reth db version` is a dedicated CLI subcommand purely for
surfacing on-disk database schema version in bug reports — a full execution client's
bug reports frequently hinge on whether a corruption/panic is a data-migration issue
versus a code bug, and asking for this up front (alongside the node-type/prune-config
fields, which similarly narrow "is this an archive-node bug or a pruned-node bug")
front-loads exactly the triage information an EL client maintainer needs before even
opening the reproduction steps.

**`blank_issues_enabled: false`, confirmed** (`config.yml:1`) — GitHub's setting that
removes the "Open a blank issue" option from the issue-creation picker entirely, forcing
every issue through one of the three form templates above (or redirecting to
Discussions per the `contact_links` entry). Combined with all three content templates
using the YAML `issue_forms` schema (required fields, dropdowns, checkboxes) rather than
freeform Markdown, this is a fully form-gated issue tracker — there is no path to file
an issue that skips structured intake.

### Confirmed absence: no PR template

**Search performed:** `find . -iname "*pull_request_template*" -o -iname
"*PULL_REQUEST*"` across the entire vendored clone returns zero results. There is no
`.github/pull_request_template.md`, `.github/PULL_REQUEST_TEMPLATE.md`, or
`.github/PULL_REQUEST_TEMPLATE/` directory anywhere in this checkout.

This is a real discrepancy against `CONTRIBUTING.md`'s own text, which says
(`CONTRIBUTING.md:150-151`, quoted): "From within GitHub, opening a new pull request
will present you with a template that should be filled out." No such template exists in
this repository's `.github/` directory to be presented. The most plausible explanation
— consistent with how GitHub Actions org-level defaults work — is that
`paradigmxyz`/`gakonst`'s GitHub organization has an **org-level default PR template**
configured in a `.github`-named repository at the organization root (GitHub falls back
to `github.com/<org>/.github/.github/pull_request_template.md` when no
repo-local template exists), which would not be present in a clone of the `reth`
repository itself. This document does not assert that org-level file exists (it is
outside what a clone of `reth` can show), only that `CONTRIBUTING.md`'s claim and this
repository's actual file listing are inconsistent, and the most likely reconciliation
is an inherited org default rather than a documentation bug.

**Fukuii verdict — fukuii is ahead here.** fukuii already has
`.github/PULL_REQUEST_TEMPLATE.md` (confirmed present) — a real advantage over this
particular vendored Reth checkout, which has no repo-local PR template to point to
regardless of what its own contributing guide implies. Nothing to port; if anything,
this is a data point that fukuii's own `PULL_REQUEST_TEMPLATE.md` +
`.github/labeler.yml` combination (documented in `.agents/protocols/github-workflows.md`)
is already at or above the bar this specific file demonstrates.

---

## Docker & observability stack

### Dockerfile inventory

| File | Lines | Purpose |
|---|---|---|
| `Dockerfile` | 67 | Production image. `cargo-chef` multi-stage build on `lukemathwalker/cargo-chef:latest-rust-1.95-trixie` — `chef` → `planner` (runs `cargo chef prepare` to produce a dependency-only `recipe.json`) → `builder` (runs `cargo chef cook` against that recipe to build and cache dependencies *before* copying full source, then `cargo build --profile $BUILD_PROFILE --features "$FEATURES" --locked --bin reth`) → `runtime` (`ubuntu:24.04`, copies only the built binary + `LICENSE-*`/`LICENSES`/`README.md`). Default `BUILD_PROFILE=maxperf` (`Dockerfile:23`) — a custom, more-aggressive-than-`release` Cargo profile. Platform-specific `RUSTFLAGS` (`Dockerfile:43-47`): on `linux/amd64` specifically, sets `-C target-cpu=x86-64-v3 -C target-feature=+pclmulqdq` unless the caller already supplied `RUSTFLAGS` — `x86-64-v3` targets Haswell-and-newer CPUs (AVX2 baseline) and `+pclmulqdq` enables a carry-less-multiply CPU instruction RocksDB-adjacent/CRC code paths can use; this is a real portability tradeoff (the resulting binary will `SIGILL` on pre-Haswell hardware) made deliberately for performance on the common case |
| `Dockerfile.depot` | 94 | A parallel production Dockerfile purpose-built for [Depot](https://depot.dev)'s remote-cache build infrastructure — swaps `cargo-chef` for `sccache` (`RUSTC_WRAPPER=sccache`, `SCCACHE_WEBDAV_ENDPOINT=https://cache.depot.dev`, `Dockerfile.depot:18-21`), takes `BINARY`/`MANIFEST_PATH` as build args so the same file can build `reth` or other future binaries from the workspace, threads `VERGEN_GIT_SHA`/`VERGEN_GIT_DESCRIBE`/`VERGEN_GIT_DIRTY` build args through as env vars specifically because `.git` is excluded from the Docker build context (`COPY --exclude=.git`, `Dockerfile.depot:51`) so the `vergen` crate can't read git metadata at build time the normal way, and mounts a Depot auth token as a build secret (`--mount=type=secret,id=DEPOT_TOKEN`, `Dockerfile.depot:52`) |
| `Dockerfile.reproducible` | 28 | Bit-for-bit reproducible build image — pins an exact Rust toolchain via `ARG RUST_TOOLCHAIN=1.89.0` (older than the other two Dockerfiles' `1.95-trixie`, presumably pinned at the point reproducibility was locked in), switches APT to a snapshot repository by rewriting `sources.list.d` entries (`Dockerfile.reproducible:7`) so dependency versions are pinned at the OS-package level too, builds via `make build-reth-reproducible` with `mold` as the linker (`RUSTFLAGS_REPRODUCIBLE_EXTRA="-Clink-arg=-fuse-ld=mold"`), and lands on a **pinned-by-digest** `gcr.io/distroless/cc-debian13:nonroot-<sha>` final image (`Dockerfile.reproducible:23`) — the digest pin means the runtime base image itself cannot silently drift between builds, which matters for reproducibility in a way a floating `:nonroot` tag would not |
| `Dockerfile.depot` / `docker-bake.hcl` combination | — | See below — `docker-bake.hcl` is the orchestration layer that actually invokes `Dockerfile.depot` with per-target arguments |

**`docker-bake.hcl`** (117 lines, read in full) — a Docker Buildx Bake file defining
build *targets*, not a Dockerfile itself. Two groups: `default` (just `ethereum`) and
`nightly` (`ethereum` + `ethereum-profiling`). The `ethereum` target
(`docker-bake.hcl:64-72`) builds `reth` from `bin/reth` at the default `maxperf-symbols`
profile; `ethereum-profiling` (`docker-bake.hcl:74-83`) overrides to the `profiling`
build profile with the `jemalloc-prof` feature enabled, single-platform
(`linux/amd64` only, via `_base_profiling`'s override), tagged `:nightly-profiling` —
i.e., a separate, deliberately non-default image exists purely to ship a
memory-profiling-instrumented build for troubleshooting, without polluting the normal
release tag. Two more targets, `hive` and `kurtosis`
(`docker-bake.hcl:98-116`), build single-platform, `hivetests`-profile images and emit
them as local `.tar` files (`output = ["type=docker,dest=.../reth_image.tar"]`) rather
than pushing to a registry — these feed Hive/Kurtosis multi-client test harnesses
directly from CI-local build output.

**`Cross.toml`** (30 lines) — configuration for the `cross` cargo subcommand (QEMU-based
cross-compilation), not Docker-image-building per se, but Docker-adjacent since `cross`
runs builds inside Docker containers per target. Notable: a dedicated
`[target.riscv64gc-unknown-linux-gnu]` section (`Cross.toml:18-27`) installs a RISC-V
GCC cross-toolchain and sets `CARGO_TARGET_RISCV64GC_UNKNOWN_LINUX_GNU_LINKER` — RISC-V
support is real enough to warrant its own cross-compilation target, beyond the
x86_64/ARM64 pair the other Dockerfiles target.

### `etc/docker-compose.yml` — read in full (110 lines)

**Correction to the base assumption going in:** `etc/docker-compose.yml` on its own does
**not** define the "complete" stack (reth + prometheus + grafana + loki + promtail +
ethereum-metrics-exporter) — it defines exactly **five services**: `reth`, `prometheus`,
`loki`, `promtail`, `grafana`. `ethereum-metrics-exporter` and a consensus-layer client
are deliberately split into a **second, overlay compose file**,
`etc/lighthouse.yml`, and the two are meant to be composed together via `docker compose
-f etc/docker-compose.yml -f etc/lighthouse.yml up -d` (documented explicitly in
`docs/vocs/docs/pages/installation/docker.mdx:96-99`). This is confirmed by three
independent pieces of evidence: (1) `etc/docker-compose.yml` itself has no
`metrics-exporter` or `lighthouse` service definition; (2) `etc/prometheus/prometheus.yml`
already contains a scrape job targeting `metrics-exporter:9091` (`prometheus.yml:6-9`)
that would 404/fail with only the base file running, since nothing named
`metrics-exporter` exists in that compose project without the overlay; (3)
`docker.mdx:106-116` explicitly documents the base file as producing "three containers:
Reth, Prometheus, Grafana" and the *optional* `lighthouse.yml` overlay as adding "two
containers: Lighthouse, ethereum-metrics-exporter." Loki/Promtail are real services in
the base file but are not mentioned in that same doc passage at all — the docs
themselves are slightly behind the compose file (Loki/Promtail were evidently added to
`docker-compose.yml` after that doc paragraph was last updated), a second small,
concrete instance of documentation drift in this repository worth noting for the same
reason the stale CODEOWNERS entry is worth noting: even a well-run project accumulates
small doc/reality gaps.

**Full service breakdown, `etc/docker-compose.yml`:**

- **`reth`** (`:4-33`) — image `ghcr.io/paradigmxyz/reth`, four ports exposed (`9001`
  metrics, `30303` peering, `8545` RPC, `8551` Engine API), four named volumes (one per
  network: `mainnet_data`, `sepolia_data`, `holesky_data`, `hoodi_data`, each mounted at
  `/root/.local/share/reth/<network>`), plus a `logs` volume and a read-only bind mount
  of `./jwttoken` for the Engine API JWT secret. **`pid: host`** (`:20`) is set with an
  inline comment citing Reth's own troubleshooting docs
  (`.../troubleshooting.html#concurrent-database-access-error-using-containersdocker`)
  — sharing the host PID namespace is a documented workaround for a container-specific
  MDBX database-locking false-positive, not a general security posture choice.
- **`prometheus`** (`:35-47`) — vanilla `prom/prometheus`, depends on `reth`, bind-mounts
  a local `./prometheus/` config directory plus a named `prometheus_data` volume.
- **`loki`** (`:49-57`) — `grafana/loki:latest` (floating tag, not pinned), bind-mounts
  `./loki/` config, named `loki_data` volume.
- **`promtail`** (`:59-67`) — `grafana/promtail:latest`, depends on `loki`, bind-mounts
  `./promtail/` config **and** `/var/run/docker.sock:/var/run/docker.sock:ro` — the
  read-only Docker socket mount is how Promtail auto-discovers and tails other
  containers' logs without per-container log-path configuration.
- **`grafana`** (`:69-92`) — `grafana/grafana:latest`, depends on `reth` + `prometheus`
  + `loki`, one environment override (`PROMETHEUS_URL`, defaulted via `${...:-...}`
  shell-style interpolation), and a genuinely clever **entrypoint override**
  (`:87-92`) that shells out a three-step provisioning fixup at container start: (1)
  copy dashboards from a `_temp` staging mount into the real provisioning path (so the
  next steps don't mutate the host's original dashboard JSON files bind-mounted
  read-only), (2)/(3) `sed`-rewrite Grafana template-variable placeholders
  (`${DS_PROMETHEUS}`, `${datasource}`, `${VAR_INSTANCE_LABEL}`) to concrete values
  (`Prometheus`, `Prometheus`, `instance`) directly inside the dashboard JSON files,
  before finally invoking the base image's own `/run.sh`. This solves the well-known
  Grafana-dashboard-provisioning problem where exported dashboard JSON references a
  datasource *variable* (as it must, to be portable across environments) but
  file-based provisioning has no equivalent of the UI's "select a datasource" prompt —
  the fixup patches the variable to a hardcoded name matching the datasource Grafana
  provisions from `./grafana/datasources` at the same time.

**`etc/lighthouse.yml`** (overlay file, read in full) — adds two services:
`lighthouse` (`sigp/lighthouse:v8.1.3`, pinned version tag; five ports for RPC/metrics/
P2P; a `lighthouse_data` volume; a checkpoint-sync command against
`https://sync-mainnet.beaconcha.in` with a 300s timeout) and `metrics-exporter`
(`ethpandaops/ethereum-metrics-exporter:debian-latest`, depends on both `reth` and
`lighthouse`, bind-mounts `./ethereum-metrics-exporter/config.yaml`). Inline comments in
the `lighthouse` service (present for mainnet/Sepolia/Holesky/Hoodi) show the exact
substitutions needed to point the whole stack at any of Ethereum's four networks —
`--network` flag plus a network-specific checkpoint-sync URL per network.

**`etc/ethereum-metrics-exporter/config.yaml`** (14 lines) — the exporter's own config,
pointing at `http://lighthouse:5052` (consensus) and `http://reth:8545` (execution,
`eth`/`net`/`web3` modules only) by service name, relying on Compose's default bridge
network DNS.

### `etc/grafana/dashboards/` — versioned dashboard JSON, confirmed

Six files, confirmed present via directory listing: `dashboard.yml` (the Grafana
provisioning-config wrapper, not a dashboard itself), `metrics-exporter.json`,
`overview.json`, `reth-discovery.json`, `reth-mempool.json`, `reth-persistence.json`,
`reth-state-growth.json`. Five real, purpose-specific dashboards (discovery/P2P,
mempool/tx-pool, persistence/storage-engine, state-growth, plus a general overview) —
checked into the repository as versioned JSON rather than distributed only as
externally-hosted Grafana.com dashboard IDs, meaning a `git clone` of this repo alone is
sufficient to reproduce the full observability picture with no external dashboard
downloads.

**Fukuii verdict — the pattern is directly applicable; fukuii already has more raw
material than it might appear.** fukuii is not starting from zero: `ops/barad-dur/`
already provides a heavier compose stack (Kong API-gateway layer, Postgres, Prometheus,
Grafana with **15 dashboard JSON files** across 5 folders —
`ops/grafana/{Archive,ETC Node,Network,Sepolia Consensus,Sync}/*.json`, confirmed via
directory listing — auto-organized into Grafana folders via
`foldersFromFilesStructure: true` in `ops/barad-dur/grafana/provisioning/dashboards/
dashboards.yml`) and its own multi-network Dockerfile set (`docker/Dockerfile-base`,
`-dev`, `.bootnode`, `.distroless`, `.mainnet`, `.mordor`, plus a root `Dockerfile` and
`hive/fukuii/Dockerfile` — 7 total). The concrete gaps against Reth's `etc/` pattern are
narrower than "no observability stack": (1) **no Loki/Promtail log-aggregation tier** —
confirmed via `grep -rli "loki\|promtail" ops/barad-dur` returning nothing — fukuii's
stack has metrics (Prometheus/Grafana) but no centralized log search; (2) **no
lightweight, Kong-free entry point** comparable to `etc/docker-compose.yml`'s five-service
minimal stack — `ops/barad-dur`'s compose files are all Kong+Postgres-inclusive,
meaning there is no fukuii-equivalent of "just run the node plus metrics/dashboards,
nothing else" for a maintainer who wants observability without the full API-gateway
layer. Both gaps are additive (new compose file + two new services), not a rebuild —
the Grafana entrypoint-fixup trick (copy-then-`sed`-rewrite datasource placeholders from
a read-only bind mount) is directly portable regardless of which gap is closed first,
since it solves a generic Grafana-provisioning problem, not a Reth-specific one.

---

## Third-party audit

**File:** `audit/sigma_prime_audit_v2.pdf`, confirmed present at 668,280 bytes (`ls -la
audit/` — the file is the sole entry in that directory). Sigma Prime is a known
blockchain-security auditing firm (also a maintainer of Lighthouse, the consensus client
referenced in `etc/lighthouse.yml` above — an interesting ecosystem overlap). This
document does not read the PDF's content (out of scope per the task), only confirms:
the audit is checked directly into the source repository (not merely linked from a
README or hosted externally), it is versioned (`_v2` in the filename implies at least
one prior version existed), and its presence in `audit/` at the repo root signals the
project treats a completed third-party security audit as durable project artifact
worth version-controlling alongside the code it covers, not just a one-time PR/blog-post
announcement.

**Fukuii verdict — not portable today; note as a future milestone, not a gap to close
now.** fukuii has no third-party security audit and no `audit/` directory — this is
expected at fukuii's current stage (pre-audit, actively under consensus-migration work)
rather than a hygiene omission comparable to a missing `SECURITY.md`. The concrete
takeaway to carry forward is the *placement convention* — a checked-in `audit/` directory
at repo root, versioned filenames (`_v2`, `_v3`, ...) — for whenever fukuii does
commission a third-party audit; there is nothing actionable before that point.

---

## Rust-workspace tooling (brief)

One line each, for completeness — none of these are ported as-is (Scala/sbt has no
direct equivalent tool for several), but each names a concern fukuii's own tooling
should have *some* answer to:

- **`deny.toml`** (103 lines) — `cargo-deny` policy: advisory handling (`yanked =
  "warn"`, four explicitly-`ignore`d RUSTSEC IDs each with an inline justification
  comment, e.g. `RUSTSEC-2025-0141` bincode-unmaintained "need to transition all deps to
  wincode first"), a license allowlist (`MIT`, `Apache-2.0`, `BSD-2/3-Clause`, `MPL-2.0`,
  etc., plus one per-crate exception for `gmp-mpfr-sys`'s `LGPL-3.0-or-later`), a `[bans]`
  section that explicitly `deny`s `openssl` (Rust's ecosystem convention of `rustls`
  over OpenSSL) and forces single-version `reqwest`, and an `allow-git` list of ~12
  Paradigm/ecosystem repos permitted as git (non-crates.io) dependencies.
- **`.config/zepter.yaml`** (41 lines) — configuration for the `zepter` CLI, which
  checks Cargo *feature-flag propagation* correctness across the workspace (does crate
  `A`, depending on crate `B`, correctly re-expose/propagate `B`'s Cargo features it
  activates) — a Rust-workspace-specific correctness class with no Scala/sbt analogue,
  since sbt has no equivalent "feature flag" mechanism to propagate.
- **`clippy.toml`** (17 lines) — lint-config tuning: a `too-large-for-stack = 128` byte
  threshold and a `doc-valid-idents` allowlist (`P2P`, `ExEx`, `IPv4`, `WAL`,
  `MessagePack`, etc. — acronyms Clippy's doc-comment spellchecker would otherwise flag).
- **`rustfmt.toml`** (12 lines) — formatter config, notably `style_edition = "2021"` and
  several nightly-only options (`imports_granularity = "Crate"`, `wrap_comments = true`,
  `format_code_in_doc_comments = true`) — which is why `CONTRIBUTING.md` instructs
  contributors to run `rustfmt` under `+nightly` even on an otherwise-stable toolchain.
- **`flake.nix`** (142 lines) — a Nix flake providing a reproducible `devShell` via
  `crane` + `fenix` (pinned Rust toolchains, both stable and nightly — the nightly one
  supplies `rust-analyzer`/`rustfmt` for the same nightly-rustfmt reason above), for
  contributors who use Nix for dev-environment reproducibility instead of `rustup`.

**Fukuii verdict — background parity check only, no action items.** fukuii's build is
sbt/Scala, not Cargo/Rust, so none of these five files transliterate directly. The
closest fukuii equivalents already exist in different forms: dependency/license
scanning is covered by the global supply-chain-security rules (`~/.claude/...
supply-chain-security.md`'s pnpm-side `resolution-age` gate has no sbt/Maven-ecosystem
counterpart currently configured for fukuii — this is a real, if separate, gap worth
raising independently rather than folding into this Reth-specific document) and
formatting/lint config is `scalafmt`/`scalafix` (`.scalafmt.conf`, present in fukuii
today). Nothing here changes that picture; listed for completeness per the task
requirements only.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable / Already planned | Reasoning |
|---|---|---|
| `SECURITY.md` (6-line, single mailto: contact) | **Already planned** | A lean `SECURITY.md` is planned this session using GitHub Security Advisories as the primary path + one maintainer contact; Reth's file is useful evidence that a single-sentence policy is an accepted floor in this ecosystem, not a reason to add more |
| CODEOWNERS (~50-line, per-crate density) | **Not portable at this density** | fukuii's two-maintainer structure doesn't justify per-module routing yet — every line would resolve to the same one or two names |
| CODEOWNERS (lightweight, root catch-all) | **Already planned** | Root catch-all `* @realcodywburns @chris-mercer` is the right-sized scope now; Reth's per-crate model (crate boundary = natural ownership unit) is the concrete reference to return to if/when fukuii's module boundaries (`vm/`, `crypto/`, `domain/`, `db/`, `jsonrpc/`) need finer-grained routing |
| CODEOWNERS staleness (the `bin/reth-bench-compare/` → `bin/reth-bb` rename left unfixed) | **Needs design (process note)** | Not a Reth-specific defect — a general lesson that whatever CODEOWNERS fukuii writes needs a periodic path-staleness check against real directory structure |
| CONTRIBUTING.md contributor-facing sections (spelling/grammar-PR policy, `make pr`-equivalent single-gate framing, commit-squash guidance) | **Port the ideas, adapt wording** | Low-cost, high-clarity; fukuii's `docs/development/contributing.md` should state an equivalent "no drive-by cosmetic PRs" policy and point at a single pre-PR gate command (`sbt pp`) the way `make pr` does here |
| CONTRIBUTING.md reviewer-facing section (review-a-bit-at-a-time, "be aware of the person behind the code," abandoned-PR `Co-authored-by:` handling) | **Needs design (real gap, correctly deferred)** | fukuii's contributing doc has no reviewer-facing content at all; but the premise this section addresses (many occasional, non-maintainer reviewers) doesn't apply yet with only two maintainers — write this once fukuii has a broader reviewer community, not before |
| Issue templates (form-based YAML, `blank_issues_enabled: false`, client-specific triage fields like `reth db version`) | **Port the pattern (verify field coverage)** | fukuii should confirm its own issue templates capture equivalent client-specific triage fields (network/chain selection, sync mode, datadir/db state) the way `bug.yml`'s node-type/prune-config/db-version fields do — check existing templates for gaps rather than replacing them wholesale |
| PR template | **Already ahead — no action** | fukuii already has `.github/PULL_REQUEST_TEMPLATE.md`; this vendored Reth checkout has none despite `CONTRIBUTING.md` implying one exists (likely an org-level `.github`-repo default not visible in this clone) |
| `Dockerfile` (cargo-chef, `maxperf` profile, platform-specific RUSTFLAGS) | **Not portable** | Cargo/Rust-specific build tooling (cargo-chef dependency caching, Cargo build profiles) has no Scala/sbt equivalent; fukuii's own Dockerfile set already serves the analogous "production image" role |
| `Dockerfile.depot` / `docker-bake.hcl` (remote-cache CI build orchestration) | **Not portable** | Depot-specific remote build caching; not a gap for fukuii unless fukuii adopts a similar remote-cache CI vendor |
| `Dockerfile.reproducible` (digest-pinned base image, snapshot APT repo, `mold` linker) | **Needs design (pattern, not literal port)** | The *idea* (digest-pin the final runtime base image so it can't silently drift) is portable to any of fukuii's own Dockerfiles independent of language; worth a follow-up check of whether fukuii's Docker base images are tag-pinned vs. digest-pinned today |
| `etc/docker-compose.yml` (reth + prometheus + loki + promtail + grafana, 5 services) | **Port now (additive)** | fukuii's `ops/barad-dur/` stack has metrics/dashboards but confirmed no Loki/Promtail log-aggregation tier and no Kong-free lightweight entry point — both gaps are additive, not a rebuild |
| `etc/lighthouse.yml` overlay pattern (base compose + optional CL-client overlay) | **Needs design** | The two-file overlay pattern (`-f base.yml -f optional.yml`) is a clean, reusable Compose idiom worth adopting for fukuii's own optional components (e.g., a CL client for ETH/Sepolia) rather than folding everything into one monolithic compose file |
| Grafana entrypoint dashboard-provisioning fixup (`sed`-rewrite `${DS_PROMETHEUS}` placeholders from a read-only bind mount) | **Port now** | Solves a generic Grafana-provisioning problem fukuii's own `ops/barad-dur/grafana` setup likely also faces; directly reusable regardless of which other gap is closed first |
| `etc/grafana/dashboards/` versioned JSON (5 dashboards + config) | **Already matches fukuii** | fukuii's `ops/grafana/` already checks in 15 dashboard JSON files across 5 folders — ahead of Reth's 5-dashboard set in raw count, though scope/content differs |
| `audit/sigma_prime_audit_v2.pdf` (checked-in third-party audit) | **Not portable today** | fukuii has no third-party audit — expected at fukuii's current pre-audit stage, not a hygiene gap; note the checked-in-`audit/`-directory convention for whenever an audit is commissioned |
| `deny.toml` (cargo-deny license/advisory/ban policy) | **Not portable (no direct tool); parity gap is real but separate** | No Cargo-deny equivalent for sbt; fukuii's supply-chain-security rules currently target the pnpm ecosystem only — an sbt/Maven-ecosystem dependency-advisory gate is a genuine, separate gap worth its own investigation, not folded into this document |
| `.config/zepter.yaml` (Cargo feature-flag propagation checker) | **Not portable** | Rust Cargo-features-specific problem class; sbt has no feature-flag propagation mechanism to check |
| `clippy.toml` / `rustfmt.toml` (lint/format tuning) | **Not portable** | Cargo/rustfmt-specific config syntax; fukuii's `scalafmt`/`scalafix` config already serves the equivalent role |
| `flake.nix` (Nix devShell via crane+fenix) | **Not portable** | Nix-specific reproducible-devenv tooling; fukuii has no Nix tooling today and no stated need for one |
