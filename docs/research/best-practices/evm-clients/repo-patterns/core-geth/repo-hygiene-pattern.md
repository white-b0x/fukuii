# core-geth — Fork-Divergence & Security-Hygiene Patterns

Source: `.claude/repo-references/clients/core-geth/` (vendored full clone; verified genuine
— a real `git clone` of `ethereumclassic/core-geth` with a populated `.git/` directory and
full commit history inherited from its `ethereum/go-ethereum` ancestry, not a summary or
partial checkout).

**Annotation caveat (verified fresh this session):** two specific files in this vendored
copy have been heavily rewritten with 2026-dated, fukuii-aware narrative content and must
be treated as narrative, not pristine upstream:

- `README.md` — carries a `> [!WARNING]` maintenance-mode banner ("CoreGeth entered
  maintenance mode in December 2024... migrating to **Fukuii**"), a CVE table naming
  6 CVEs + a GraphQL DoS, and an explicit "Cross-Client Alignment" table listing Fukuii as
  "Primary ETC client — migration target" (`README.md:7-12,63-69,108-124`).
- `docs/audits/2026-03-security-audit.md` (683 lines) — a full incident narrative naming
  "White B0x" as the remediation author, `@diega` as the unresponsive upstream release
  manager, a disclosure timeline running June 2024 → May 2026, and a pointed paragraph
  about single-maintainer unreviewed merge risk (`docs/audits/2026-03-security-audit.md:1-33`).

Every other file cited below — `docs/core/index.md`, `docs/developers/add-network.md`,
`params/types/README.md`, `docs/developers/versioning.md`,
`docs/developers/create-new-release.md`, `docs/postmortems/2021-08-22-split-postmortem.md`,
`SECURITY.md`, `.github/CODEOWNERS`, `Makefile`, `.gitmodules`, and the CI workflow set —
reads as authentic, unmodified core-geth/go-ethereum-lineage material. This was verified by
content inspection (no fukuii/2026/White B0x references anywhere outside the two files
above) rather than assumed.

core-geth (`ethereumclassic/core-geth`) is the other major ETC-focused execution client — a
fork of `ethereum/go-ethereum` maintained (formerly by ETC Labs/ETC Cooperative staff, now
in security-only maintenance mode per the annotated README) specifically to serve Ethereum
Classic. It is the client in this reference set **most directly analogous to fukuii's own
situation**: both are forks that had to build their own consensus-chain-family-specific
tooling and documentation on top of an upstream that only cares about one chain. Its real
value here is not CI maturity (there is very little) but its fork-divergence documentation
and its own security-hygiene story — one exemplary pattern worth adopting, and one
cautionary anti-pattern worth avoiding.

---

## Confirmed: near-zero agentic tooling

Verified fresh this session, not assumed from a prior pass. `find . -iname "AGENTS.md" -o
-iname "CLAUDE.md"` returns nothing. `find . -type d \( -iname ".claude" -o -iname
".agents" \)` returns nothing. A repo-wide grep across every CI config
(`appveyor.yml`, `circle.yml`, `Jenkinsfile`, `.travis.yml`, `.golangci.yml`, `Makefile`,
and all eight files under `.github/workflows/`) for `claude|copilot|chatgpt|anthropic|
gpt-3|gpt-4|codex` matched zero files. There is no MCP configuration, no AI-code-review
workflow, and no `CONTRIBUTING.md` reference to an AI review step (contrast this with
Nethermind's `claude-review/reviewed` required-status-check gate, documented in the
sibling `nethermind/repo-hygiene-pattern.md`). core-geth predates the agentic-tooling era
entirely and, being in maintenance mode since December 2024, was never going to retrofit
it — this is a dead end for fukuii to study, not a gap to flag.

---

## docs/core/index.md — the standout fork-divergence model

**File:** `docs/core/index.md`, 164 lines, read in full. This is the single most directly
reusable artifact in the entire core-geth clone, because it is structured almost exactly
like the document fukuii is planning to write (`docs/architecture/FORK_DIVERGENCE.md`).

The document has three sections, in this order:

### 1. "Additional Features" (`index.md:16-73`)

A catalogue of things core-geth added that upstream go-ethereum either can't or won't
support, organized as sub-headings: Extended RPC API (comprehensive OpenRPC-based service
discovery, `trace_block`/`trace_transaction` OpenEthereum-compatible tracing "including a
1000x performance improvement... in some cases", `debug_removePendingTransaction`),
EVMCv7 support for external EVMs, a remote freezer store (S3/Storj) for ancient chaindata,
an extended CLI flag (`--eth.protocols`), developer tooling (`--dev.pow` with configurable
Poisson block-interval mocking, acceptance of both go-ethereum's and OpenEthereum's genesis
JSON schemas), public Jenkins-driven chaindata regression testing on every push to
`master`, and — the risk-management payoff of the whole design — "myriad additional ECIP
support" (ECBP1100/MESS, ECIP1099 DAG growth limit, ECIP1014 difficulty-bomb defusal) shipped
alongside out-of-the-box network selection via `--<chain>` CLI flags (`index.md:65-73`).

### 2. "Divergent Design" (`index.md:75-149`) — the worked example

This section explains **why** the feature list above is even possible, via a single
concrete before/after code comparison. Quoted verbatim (`index.md:83-91,122-130`):

Upstream go-ethereum's feature-activation pattern:

```go
blockNumber := big.NewInt(0)
config := params.MainnetChainConfig
if config.IsByzantium(blockNumber) {
	// do a special thing for post-Byzantium chains
}
```

core-geth's composable equivalent:

```go
blockNumber := big.NewInt(0)
config := params.MainnetChainConfig
if config.IsEnabled(config.EIP658Transition, blockNumber) {
	// do a special thing for post-EIP658 chains
}
```

The document's own framing of why this matters (`index.md:93-102`): the upstream pattern
bundles an entire named hard fork ("Byzantium") — which is actually nine distinct EIPs —
behind one boolean check, raising unanswerable questions for a new contributor ("Which of
the nine distinct Byzantium upgrades is this implementing? Does feature `Byzantium.X`
depend on also having `Byzantium.Y` activated?"). go-ethereum can get away with this because
it "is only designed and intended to support one chain: Ethereum. From this perspective,
configurability presents a risk rather than a desirable feature" (`index.md:98-99`). The
doc drives this home with an extended "wiring a house" metaphor (`index.md:104-120`): in
upstream's model, the TV, kitchen lights, garbage disposal, and garage door share one
switch — fine for a single eccentric homeowner's fixed preference, absurd for anyone who
wants the parts independently controllable.

core-geth's `ChainConfig.IsEnabled(feature, blockNumber)` decomposes each bundled hard fork
into its named EIP/ECIP components, each independently checkable, independently testable
("You can test block reward modifications without also having to test difficulty
adjustments", `index.md:138-139`), and — the concrete payoff cited — this is exactly what
let Ethereum Classic adopt a receipt-RLP-encoding change from a later Ethereum hard fork
*without* also having to accept that same hard fork's difficulty-bomb and block-reward
changes: "without this decomposition, Ethereum Classic would have had to accept and
(re)implement the Difficulty Bomb and reduce block rewards in order to adopt a change to
the RLP encoding of transaction receipts" (`index.md:147-148`). The interface itself is
pointed at directly: `params/types/ctypes/configurator_iface.go` (`index.md:132-134`), and
the design additionally supports arbitrary configuration *data types* — core-geth accepts
both go-ethereum's `genesis.json` schema and OpenEthereum's JSON config schema through the
same interface (`index.md:141-145`).

### 3. "Limitations" (`index.md:151-164`) — the explicit non-support list

Immediately following the pitch, the document lists what core-geth deliberately does
**not** do, verbatim:

- No broad multi-platform build-artifact matrix: "We're vastly outnumbered by
  ethereum/go-ethereum maintainers and contributors, and ensuring proper delivery of a
  whole bunch of diverse artifacts is beyond our capacity" (`index.md:155-159`).
- `puppeth` (the interactive network-setup wizard) was [removed](https://github.com/ethereumclassic/core-geth/pull/270)
  entirely (`index.md:160-161`).
- No `-trim` build flag support: Go's path-anonymizing build flag was deliberately left
  off because "stripping file paths caused automatic service discovery features to break
  (they depend, in part, on source file path availability for build-time AST and runtime
  reflection)" (`index.md:162-164`) — i.e., core-geth's own signature feature (RPC service
  discovery) has a build-time cost that upstream doesn't pay, and the doc says so plainly
  instead of hiding the tradeoff.

**Why this is the standout pattern:** the three-section shape — what we add that upstream
won't / the architectural mechanism that makes it possible, with a real before/after code
diff / what we deliberately don't do — is a complete, self-contained template for
documenting fork divergence honestly, including the costs. It doesn't just list features;
it explains the *load-bearing design decision* underneath them, and closes with the
discipline of naming its own limitations rather than letting a reader assume feature
parity with upstream. This directly informs fukuii's own planned
`docs/architecture/FORK_DIVERGENCE.md`: fukuii forked from IOHK Mantis and diverges from
its own upstream in ways that deserve exactly this shape of write-up — what fukuii adds
that Mantis-lineage code didn't (multi-network PoW/PoS support, the Pekko Typed migration,
Scala 3 modernization), the architectural mechanism enabling it (analogous to
`ChainConfig.IsEnabled` — fukuii's own fork-dispatch abstractions for `forBlock()` vs.
`forTimestamp()`), and an honest Limitations section naming what fukuii does not attempt to
match from Mantis or from sibling clients.

---

## docs/developers/add-network.md — new-chain-family tutorial

**File:** `docs/developers/add-network.md`, 206 lines, read in full. A worked, fully
executable tutorial for adding an entirely new chain to core-geth, keyed to a real,
diffable branch rather than prose alone: `docs/_tutorial-add-network`, with a stated diff
target of `v1.11.22` and a working example chain named "AlphaBeta Coin (ABC)" —
PoW/Ethash issuance, a single pre-mined address, Istanbul/Phoenix-equivalent features
active from genesis (`add-network.md:8-14`).

**Named files touched**, listed as the actual `git diff --name-only` output
(`add-network.md:27-34`):

```
params/bootnodes_abc.go
params/config_abc.go
params/config_abc_test.go
params/example_abc_test.go
params/genesis_abc.go
```

**Two supported activation paths** are named explicitly (`add-network.md:41-46`): (1) JSON
genesis-config initialization via `geth init` against a custom datadir, or (2) exposing the
network as a first-class CLI flag (`--abc`) the way `--classic` works for ETC — the
tutorial covers only path (1) in depth and states plainly that path (2) "won't cover... yet."

The rest of the document is a literal, copy-pasteable shell transcript: building `geth`,
authoring a JSON genesis (a full worked example with every EIP-block field explicitly set
to `0`, mirroring how a new ETC-lineage chain activates every feature from genesis rather
than at staggered block heights), `geth init` against a custom `--datadir`, starting the
node with an explicit `--bootnodes` flag (since a brand-new network has no baked-in
defaults), setting up a dedicated bootnode and confirming its self-reported `enode` via
`admin.nodeInfo.enode` on the JS console, then starting three more nodes pointed at that
bootnode (`add-network.md:48-205`) — a complete, runnable "stand up a private network"
recipe, not just a conceptual explainer.

**Relevance to fukuii:** this is the kind of tutorial fukuii's `fukuii-custom-networks`
skill already automates operationally (genesis/bootnode/static-node wiring without
modifying source), but core-geth's version is notable for being keyed to a real,
independently diffable branch (`compare/v1.11.22...docs/_tutorial-add-network`) rather than
prose that can silently drift from the actual code. That's a documentation-hygiene idea
worth considering if fukuii ever wants a similarly concrete "add a new network family"
walkthrough beyond the skill's operational scope: point at a real tagged diff, not just
narrative steps.

---

## params/types/README.md — hand-maintained gencodec drift, documented honestly

**File:** `params/types/README.md`, 56 lines, read in full.

This tiny file documents a very specific and easy-to-miss maintenance trap: `Genesis` and
`GenesisAccount`'s JSON marshaling code (`gen_genesis.go`, `gen_genesis_account.go`) was
originally produced by the `gencodec` code generator (`gencodec -type Genesis
-field-override genesisSpecMarshaling -out gen_genesis.go`, `README.md:6-9`, tool source at
[fjl/gencodec](https://github.com/fjl/gencodec)) — but is **no longer regenerable**, because
"MultiGeth's needs for custom JSON un/marshaling have outgrown the capabilities of the
tool" (`README.md:13-15`). The file's only content beyond that admission is a pasted `git
diff` (`README.md:21-57`) showing exactly what was hand-edited into the generated file after
the fact — a chain-config-type dispatch that tries `MultiGethChainConfig` first, falls back
to `goethereum.ChainConfig` on unmarshal or validation failure, and validates via
`common0.IsValid` (`README.md:37-52`).

**Why this matters:** the honest move here is not avoiding generated-code drift (that's
often unavoidable in a fork with genuinely different requirements than upstream's
generator assumed) — it's **documenting** the drift explicitly, in the exact location a
future maintainer would look (a `README.md` sitting right next to the affected files),
with the literal diff pasted in rather than a vague "this file is hand-modified" comment.
A future `go generate` run against this package would silently clobber the hand-written
fallback logic if someone didn't know to check this file first; the README is the tripwire
that prevents that.

**Fukuii relevance:** fukuii doesn't currently generate any Scala source via an equivalent
of `gencodec` (this is a Go-ecosystem-specific problem — Scala's case-class-derived
JSON codecs, e.g. circe's `deriveCodec`, don't have an analogous "generate once, then
hand-edit forever" failure mode since the derivation runs at compile time from the live
type, not from a one-shot codegen invocation against a frozen snapshot). This pattern is
**not portable today**, but the general principle — if fukuii ever introduces any one-shot
code generation step whose output later gets hand-edited, document the drift inline, next
to the generated file, with the diff pasted in — is worth remembering as a documentation
discipline regardless of language.

---

## SECURITY.md drift vs. actual security process — a cautionary anti-pattern

**File:** `SECURITY.md`, 175 lines, read in full. This is **verbatim, unlocalized upstream
go-ethereum boilerplate** — there is nothing ETC-specific or core-geth-specific in it at
all. It documents:

- A "Supported Versions" section that points at `github.com/ethereum/go-ethereum/releases`
  — go-ethereum's own release page, not core-geth's (`SECURITY.md:3-5`).
- An audit-report table pointing at `github.com/ethereum/go-ethereum/tree/master/docs/audits`
  — four go-ethereum audits (Truesec 2017, NCC Clef 2018, Least Authority Discv5 2019, Cure53
  Discv5 2020), none of them about core-geth (`SECURITY.md:7-16`).
- A disclosure process pointing at `bounty.ethereum.org` and `bounty@ethereum.org` — the
  Ethereum Foundation's own general bug-bounty program, with no carve-out or alternate
  channel for an ETC-specific vulnerability (`SECURITY.md:18-22`).
- A `geth version-check` pointer to `vulnerabilities.json`, again hosted at
  `geth.ethereum.org` (`SECURITY.md:24`).
- A full ASCII-armored PGP public key block for `security@ethereum.org`
  (`SECURITY.md:26-175`), taking up 150 of the file's 175 lines.

**None of this reflects core-geth's actual, real security process** — which does exist and
is far more substantive, but lives entirely somewhere else: `docs/audits/2026-03-security-audit.md`
(683 lines — a full incident-response narrative naming real dates, real CVE IDs, a
disclosure timeline, named individuals, and remediation attribution) and the README's own
"Security Audit — March 2026" section (`README.md:108-124`, a CVE table plus explicit
node-operator remediation instructions like rotating the P2P nodekey after upgrading). The
file a security researcher is conventionally expected to find first —
`SECURITY.md` — describes a disclosure process (`bounty@ethereum.org`) that has nothing to
do with how core-geth's real vulnerabilities were actually found, disclosed, or fixed. A
reporter following `SECURITY.md`'s instructions today would be reporting to the Ethereum
Foundation's bounty program about an issue in a different codebase than the one they found
it in.

**This is a concrete, reportable anti-pattern: SECURITY.md drift.** The file was presumably
accurate the moment core-geth was forked from go-ethereum (identical processes, identical
maintainers-in-spirit) and was never revisited as the fork's actual security practice
diverged sharply from upstream's. The cost of that drift is not neutral — it actively
misdirects a good-faith reporter toward the wrong organization.

**Fukuii lesson:** fukuii has no `SECURITY.md` yet; one is planned. The cautionary takeaway
from core-geth's drift is not "write a SECURITY.md" (that's the baseline, covered
elsewhere, e.g. in the sibling `nethermind/repo-hygiene-pattern.md`'s "port now" verdict) —
it's **don't let it go stale once written**. Concretely: whenever fukuii's actual
disclosure process, audit history, or maintainer contact changes (which will happen — the
two-maintainer structure today is not guaranteed to be the two-maintainer structure in two
years), `SECURITY.md` needs to be treated as a living document reviewed alongside those
changes, not a one-time artifact copied in at project bootstrap and then forgotten. A
`SECURITY.md` that quietly stops matching reality is arguably worse than having none at
all, because it actively signals a false disclosure path with apparent authority.

---

## Undocumented git.diff — an anti-pattern to avoid

**File:** `git.diff` at repo root, 6,239,619 bytes, 24,839 lines (confirmed via `wc -l`/`ls
-la`). A repo-wide grep for the string `git.diff` across every `.md`, `.yml`, and
`Makefile` in the repository (excluding the file itself) returns **zero matches** — nothing
in the entire codebase references, explains, or links to this file's purpose.

Inspecting its first lines shows it is a **raw `git diff --cc` output containing unresolved
merge-conflict markers** (`<<<<<<< HEAD` / `=======` literally present in the diff body,
confirmed at the file's opening `diff --cc .travis.yml` hunk) — the kind of artifact
produced by `git diff` during an in-progress octopus/three-way merge, apparently committed
to the repository by accident or as a forgotten working artifact rather than intentionally
checked in as reference material.

**This is a clear, reportable anti-pattern**, not a portable idea: a 6.2MB, 24,839-line raw
diff with literal conflict markers, sitting at repo root with zero explanatory
documentation anywhere in the tree, is dead weight that:

- Bloats every full clone and every `git blame`/history operation touching root-level files.
- Gives a reader zero signal about whether it's safe to delete, what it was for, or
  whether anything still depends on it.
- If ever mistaken for a legitimate patch file and applied, would reintroduce unresolved
  merge conflicts into the working tree.

**Fukuii verdict:** explicitly **do not imitate this**. If fukuii ever needs to check in a
large reference diff (e.g., documenting a migration's before/after shape), it must (a) be
named descriptively, not `git.diff`, (b) be referenced from at least one doc explaining
what it is and why it's there, and (c) never contain unresolved conflict markers — those
indicate an artifact that should have been cleaned up before commit, not preserved.

---

## CODEOWNERS — empty, same non-gap-validation pattern seen elsewhere

**File:** `.github/CODEOWNERS`, confirmed **0 bytes / 0 lines** (`wc -l` and `wc -c` both
report zero). Notably, `git log -- .github/CODEOWNERS` shows real history — entries like
"github: update code owners (#19638)" and "Set gballet as codeowner of the smartcard wallet
dir" — inherited from the go-ethereum ancestry this fork carries in its git history. The
file was populated upstream at some point in go-ethereum's history and has since been
emptied out entirely in the core-geth lineage, leaving a tracked-but-content-free file.

An empty `CODEOWNERS` file has no automatic-review-assignment effect at all — GitHub simply
finds no matching pattern for any file and routes review requests exactly as it would if
the file didn't exist. This is functionally identical to having no file, just with the
file present as an inert placeholder. fukuii's own `agent-protocols/github-workflows.md`
previously documented an analogous deliberate-deferral rationale for not having
`CODEOWNERS` at all ("single-maintainer repo" — see the cross-reference in the sibling
`nethermind/repo-hygiene-pattern.md`'s CODEOWNERS section, which notes this premise is now
outdated).

**Fukuii verdict:** fukuii has no `CODEOWNERS` yet; a lightweight, two-maintainer version
(Cody Burns/Chippr Robotics LLC, Christopher Mercer/White B0x Inc.) is already planned per
the Nethermind-pattern cross-reference. core-geth's empty file offers no template to copy —
it's simply a second confirmed instance (alongside Besu's own empty `CODEOWNERS`, per the
sibling `besu/repo-hygiene-pattern.md`) of the same non-gap-validation trap: a file's mere
*presence* in the repo tree is not evidence that ownership routing is actually configured.
Any port of `CODEOWNERS` must be checked for populated content, not just existence.

---

## Versioning & release-checklist conventions

**File:** `docs/developers/versioning.md`, 20 lines, read in full. core-geth uses Semantic
Versioning with two custom suffixes layered on top: **`-stable`** for tagged releases and
**`-unstable`** for everything else — i.e., every commit on `master` between releases
self-identifies as unstable in its own version string (`versioning.md:8-11`). The doc notes
plainly that a major-version bump is effectively theoretical for this project ("The API
definition that would demand increments to the major version is basically nil; it can be
expected that a major version bump would be accompanied by an entirely new repository and
name", `versioning.md:8`) — an acknowledgment that this is a chain client, not a library
with a versioned API contract in the conventional sense.

**File:** `docs/developers/create-new-release.md`, 40 lines, read in full. A literal
Markdown checkbox runbook (`- [ ]` per step) for cutting a release:

1. Decide the version (worked example: `v1.11.16[-stable]`).
2. Confirm `master`, run `make lint` and `make test` (explicitly gates CI-generated release
   artifacts — "If linting or tests fail, the workflows will be interrupted and artifacts
   will not be generated", `create-new-release.md:11-13`).
3. Branch to `release/v1.11.16`, hand-edit `params/version.go` to the `-stable` suffix,
   commit with a signed, sign-off commit (`git commit -S -s -m "..."`), create a signed
   annotated tag (`git tag -S -a`), push the tag *before* the branch/PR ("expediting
   artifact delivery" by triggering the tagged-version CI job ahead of the branch's own CI
   run, `create-new-release.md:19-21`).
4. Bump `params/version.go` again to the next `-unstable` version, commit, push the branch
   for PR review.
5. Draft the GitHub release manually (with a note that CI will auto-draft it if a human
   doesn't get there first) and wait for all platform artifacts (Linux/OSX/Windows,
   34 assets total stated explicitly) to upload.
6. **Second-person review required before publishing** — proofreading, artifact-fingerprint
   verification, and release-notes content approval, explicitly by "one other person"
   (`create-new-release.md:36-39`) — a lightweight but real four-eyes gate before a
   release goes public.

**Fukuii relevance:** the `-stable`/`-unstable` suffix convention and the "tag before
branch, to expedite CI artifact generation" trick are both small, cheap, and portable ideas
if fukuii's own versioning scheme (`.github/VERSIONING.md`, per the Nethermind sibling
doc's cross-reference) doesn't already cover them — but fukuii's release process is already
fully automated (auto-increment on merge, per the Nethermind sibling doc's "release *shape*
is fundamentally different by design" verdict), so this checklist-style manual runbook is
mostly a historical artifact of a manual-release era core-geth never modernized away from,
not a gap fukuii needs to fill.

---

## docs/postmortems/2021-08-22-split-postmortem.md — upstream's incident, not core-geth's own

**File:** `docs/postmortems/2021-08-22-split-postmortem.md`, 266 lines, read in full.
Confirmed: this is **go-ethereum's own postmortem, carried into core-geth's docs tree
verbatim**, not an ETC-specific incident. Every identifying detail confirms upstream
authorship: the incident concerns Ethereum **mainnet** block
[13107518](https://etherscan.io/block/13107518) (Aug 27, 2021), the bounty report was
submitted to `bounty@ethereum.org` and coincided with "a geth-meetup in Berlin"
(`split-postmortem.md:19`), the affected clients cross-checked were "openethereum,
nethermind, besu" (`split-postmortem.md:54`) — no mention of core-geth itself being tested
— and the disclosure list of notified downstream projects includes "ETC" as one of nine
recipients (`split-postmortem.md:100-109`), confirming ETC/core-geth was a *downstream
notification target* of this incident, not its subject or origin. The bug itself (a
`RETURNDATA` memory-corruption issue in EVM `CALL`-variant handling, root-caused to a
missing `common.CopyBytes` on the `ret` slice after precompile execution,
`split-postmortem.md:17-50,155-205`) is a shared EVM-interpreter-level defect that would
equally have affected any go-ethereum-lineage client, including core-geth, had core-geth
not received the same patch via its fork relationship to upstream.

**The notable gap:** core-geth's own `docs/postmortems/` directory contains **only this one
file** — go-ethereum's postmortem — and nothing documenting an ETC-specific incident,
despite Ethereum Classic having its own distinct consensus history including at least one
widely known incident class specific to PoW minority-chain reorganizations that ETC's
network experienced independently of Ethereum mainnet. core-geth inherited the *postmortem
discipline* (the directory convention, the writing style, the lessons-learned structure)
from upstream, but never produced an ETC-specific instance of it.

**Fukuii relevance:** this is worth noting as a gap-pattern to watch for, not a template to
port mechanically — if fukuii ever experiences an ETC/Mordor- or ETH/Sepolia-specific
consensus incident, a postmortem in this exact shape (Timeline → Bounty/discovery report →
Technical details → Exploit → Lessons learned → Links → Appendix with the literal patch
diff and a reproducing state test) is a solid structure to reuse — but fukuii should not
assume that having *access to* upstream postmortems (via its own reference-client vendoring
of go-ethereum-lineage docs) substitutes for writing one about fukuii's or ETC's own
incidents when they occur.

---

## ECIP-specific test-fixture regeneration (Makefile pattern) — contingent

**File:** `Makefile`, targets `tests-generate-state` (`Makefile:78-87`) and
`tests-generate-difficulty` (`Makefile:89-95`), both rolled up under `tests-generate`
(`Makefile:76`).

**Mechanism, confirmed by direct read:**

```makefile
tests-generate-state: ## Generate state tests.
	@echo "Generating state tests."
	env COREGETH_TESTS_GENERATE_STATE_TESTS=on \
	env COREGETH_TESTS_CHAINCONFIG_FEATURE_EQUIVALENCE_COREGETH=on \
	go test -p 1 -v -timeout 60m ./tests -run TestGenStateAll
	rm -rf ./tests/testdata-etc/GeneralStateTests
	mv ./tests/testdata_generated/GeneralStateTests ./tests/testdata-etc/GeneralStateTests
	rm -rf ./tests/testdata-etc/LegacyTests
	mv ./tests/testdata_generated/LegacyTests ./tests/testdata-etc/LegacyTests
	rm -rf ./tests/testdata_generated

tests-generate-difficulty: ## Generate difficulty tests.
	@echo "Generating difficulty tests."
	env COREGETH_TESTS_GENERATE_DIFFICULTY_TESTS=on \
	go run build/ci.go test -v -timeout 10m ./tests -run TestDifficultyGen
	rm -rf ./tests/testdata-etc/DifficultyTests
	mv ./tests/testdata_generated/DifficultyTests ./tests/testdata-etc/DifficultyTests
	rm -rf ./tests/testdata_generated
```

Both targets follow the same shape: set an env-var flag that switches a Go test file from
*consuming* fixtures to *generating* them, run that specific test (`TestGenStateAll` /
`TestDifficultyGen`) against core-geth's own `ChainConfig`-driven fork schedules (not
upstream's), write output into a scratch `testdata_generated/` directory, then
`rm -rf`+`mv` the relevant subtrees into their permanent home under
`tests/testdata-etc/` — the ETC-specific fixture submodule (see `.gitmodules` below) —
deleting the scratch directory afterward. This is, functionally, a **fork of the upstream
`ethereum/tests` corpus, regenerated under ETC's own fork-block schedule** (ECIP-1099
epoch lengths, ECIP-1017 rewards, the full Atlantis→Spiral timeline) rather than
Ethereum mainnet's.

**Confirmed via `.gitmodules`:** three pinned test-data submodules exist
(`.gitmodules:1-14`):

```
[submodule "tests"]
	path = tests/testdata
	url = https://github.com/ethereum/tests
	shallow = true
[submodule "evm-benchmarks"]
	path = tests/evm-benchmarks
	url = https://github.com/ipsilon/evm-benchmarks
	shallow = true
[submodule "tests-etc"]
	path = tests/testdata-etc
	url = https://github.com/etclabscore/tests
	shallow = true
```

`etclabscore/tests` (the `tests-etc` submodule) is the ETC-specific counterpart to the
canonical `ethereum/tests` corpus — and the two Makefile targets above are core-geth's
tooling for keeping that ETC-specific fork populated and current whenever fork
configuration changes.

**Confirmed: this is genuinely core-geth's only ECIP-specific *test-fixture-generation*
tooling** — no other Makefile target, script, or CI workflow regenerates fork-configuration
test data; everything else in the test suite consumes fixtures as given.

**Fukuii verdict — contingent, not currently portable.** fukuii runs
`ethereum-tests-nightly.yml` directly against the upstream `ethereum/tests` fixture corpus
and does not currently fork or maintain its own regenerated copy of that corpus under
ETC's/ETH's own fork schedules the way core-geth maintains `etclabscore/tests`. This
Makefile pattern — env-var-gated generator tests, scratch-directory rm/mv handoff into a
permanent fixture submodule — is a clean, reusable mechanism *if and when* fukuii ever
needs to fork its own copy of the EF test corpus (e.g., to pin down Olympia-specific state
tests before they're accepted upstream, mirroring exactly the problem core-geth solved for
ECIP-1099/1017). Until that need exists, there is nothing to port — this is a "keep this
pattern in mind, don't implement it speculatively" finding, not a gap.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable / Contingent (not applicable yet) | Reasoning |
|---|---|---|
| `docs/core/index.md`'s 3-section fork-divergence model (Additional Features / Divergent Design / Limitations) | **Port now (design template)** | Directly informs fukuii's planned `docs/architecture/FORK_DIVERGENCE.md` — the single most reusable idea in this client; the worked `ChainConfig.IsEnabled` before/after example and the honest Limitations section are exactly the shape fukuii needs |
| `docs/developers/add-network.md`'s diffable-branch tutorial pattern | **Port the idea, not the literal content** | The "point at a real tagged diff, not just prose" discipline is reusable if fukuii writes its own add-a-network-family tutorial beyond what the `fukuii-custom-networks` skill already automates operationally |
| `params/types/README.md`'s inline generated-code-drift documentation | **Not portable** | Go-ecosystem-specific `gencodec` failure mode; Scala's compile-time-derived JSON codecs (e.g. circe) don't have an analogous one-shot-codegen-then-hand-edit trap. General principle (document drift inline, next to the file) worth remembering regardless |
| `SECURITY.md` verbatim upstream boilerplate vs. the real security process living elsewhere | **Cautionary lesson, not a port** | fukuii's planned `SECURITY.md` must be kept current as disclosure process/maintainers change — core-geth's drift (pointing at `bounty@ethereum.org` while its real CVE history lives in a 683-line audit doc) shows what happens when it isn't revisited |
| Undocumented `git.diff` (6.2MB, 24,839 lines, unresolved merge markers, zero references anywhere) | **Anti-pattern — do not imitate** | Dead weight with no explanatory doc; if fukuii ever checks in a large reference diff it must be named descriptively, referenced from a doc, and free of conflict markers |
| Empty `.github/CODEOWNERS` (0 bytes, but real git history from go-ethereum ancestry) | **Not portable as-is; lightweight version already planned** | Same non-gap-validation trap seen in Besu's empty CODEOWNERS — presence in the tree is not evidence of configured routing; fukuii's planned 2-maintainer CODEOWNERS must actually be populated |
| `-stable`/`-unstable` version-suffix convention + "tag before branch" CI-expediting trick | **Needs design (low priority)** | Cheap idea if fukuii's own `.github/VERSIONING.md` doesn't already cover an equivalent signal; fukuii's automated release-per-merge model makes core-geth's manual checkbox runbook mostly non-applicable |
| Manual release checklist (`create-new-release.md`), including the "one other person" four-eyes review before publish | **Not portable** | fukuii's release process is already fully automated (auto-increment on merge); this is a manual-release-era artifact core-geth never modernized past |
| `docs/postmortems/`'s single file being upstream's own incident, with no ETC-specific postmortem ever produced | **Gap-pattern to watch for, not a template to port mechanically** | If fukuii ever has an ETC/Mordor- or ETH/Sepolia-specific consensus incident, reuse this postmortem's structure (Timeline/Bounty report/Technical details/Exploit/Lessons learned/Appendix with patch + repro state test) — but don't assume vendoring upstream postmortems substitutes for writing fukuii's own |
| ECIP-specific test-fixture regeneration (`Makefile` `tests-generate-state`/`tests-generate-difficulty`, backed by the `etclabscore/tests` submodule) | **Contingent — not applicable yet** | fukuii runs `ethereum-tests-nightly.yml` directly against upstream `ethereum/tests` and does not currently fork its own regenerated fixture corpus under ETC/ETH-specific fork schedules; this env-var-gated generator-test + scratch-dir rm/mv pattern is worth reusing exactly if that need ever arises |
| Near-zero agentic tooling (no AGENTS.md/CLAUDE.md/.claude//.agents/, no AI mentions in any CI config) | **Confirms nothing to study** | core-geth predates the agentic-tooling era and is in maintenance mode; dead end, not a gap |
