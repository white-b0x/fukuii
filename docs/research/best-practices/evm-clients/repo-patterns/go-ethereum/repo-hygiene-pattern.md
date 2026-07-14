# go-ethereum — Security & Governance Patterns

Source: `.claude/repo-references/clients/go-ethereum/` (vendored full clone, verified
genuine — real `git clone` with a populated `.git/`, `origin` pointing at
`white-b0x/go-ethereum.git` and `upstream` pointing at `ethereum/go-ethereum.git`, HEAD at
`59e89e81e57814a96c429c5cdcaa6ca2e0d6b143`, dated 2026-07-01). Every claim below cites a
specific file; where an exact line number could not be confirmed by direct read, the
citation says "see `<file>`" rather than inventing one.

go-ethereum ("geth") is the original, canonical Go implementation of the Ethereum
protocol and the most-used execution client in the ecosystem — it predates every other
client surveyed in this series (Nethermind, Erigon) by years, and its repo-hygiene layer
reflects that maturity differently than a growth-stage client's does: fewer automated CI
security scanners, but a genuinely unique artifact none of the other clients have —
a public, narrative post-mortem of an actual consensus-splitting security incident, written
by the team that shipped the bug and coordinated the fix. This document catalogs geth's
security/governance layer exhaustively and cross-references it against fukuii's current
state so a maintainer can see, at a glance, what to port, what needs redesign, and what
genuinely doesn't apply.

---

## SECURITY.md — mature, with published audit history

**File:** `.claude/repo-references/clients/go-ethereum/SECURITY.md`, 26 lines of visible
content (the vendored file totals ~96 lines once the embedded PGP key block is counted),
full text below.

```markdown
# Security Policy

## Supported Versions

Please see [Releases](https://github.com/ethereum/go-ethereum/releases). We recommend using the [most recently released version](https://github.com/ethereum/go-ethereum/releases/latest).

## Audit reports

Audit reports are published in the `docs` folder: https://github.com/ethereum/go-ethereum/tree/master/docs/audits

| Scope | Date | Report Link |
| ------- | ------- | ----------- |
| `geth` | 20170425 | [pdf](https://github.com/ethereum/go-ethereum/blob/master/docs/audits/2017-04-25_Geth-audit_Truesec.pdf) |
| `Discv5` | 20191015 | [pdf](https://github.com/ethereum/go-ethereum/blob/master/docs/audits/2019-10-15_Discv5_audit_LeastAuthority.pdf) |
| `Discv5` | 20200124 | [pdf](https://github.com/ethereum/go-ethereum/blob/master/docs/audits/2020-01-24_DiscV5_audit_Cure53.pdf) |

## Reporting a Vulnerability

**Please do not file a public ticket** mentioning the vulnerability.

To find out how to disclose a vulnerability in Ethereum visit [https://bounty.ethereum.org](https://bounty.ethereum.org) or email bounty@ethereum.org. Please read the [disclosure page](https://github.com/ethereum/go-ethereum/security/advisories?state=published) for more information about publicly disclosed security vulnerabilities.


The following key may be used to communicate sensitive information to developers.

Fingerprint: `AE96 ED96 9E47 9B00 84F3 E17F E88D 3334 FA5F 6A0A`

[... PGP public key block, RSA, uid "Ethereum Foundation Bug Bounty <bounty@ethereum.org>"
     and "Ethereum Foundation Security Team <security@ethereum.org>" ...]
```

**Supported-versions policy** (`SECURITY.md:3-5`) — deliberately does not enumerate a
version matrix (no "1.13.x: supported, 1.12.x: EOL" table like many projects use). It
instead points at the GitHub Releases page and recommends "most recently released" —
geth's practice is effectively N-of-1: only the latest tagged release is a security-support
target, consistent with the disclosure-page evidence below showing patches ship as new
point releases rather than backports.

**Audit table — confirmed, but incomplete relative to disk.** `docs/audits/` on disk
contains **four** PDFs:

```
2017-04-25_Geth-audit_Truesec.pdf          (124,427 bytes)
2018-09-14_Clef-audit_NCC.pdf              (755,237 bytes)
2019-10-15_Discv5_audit_LeastAuthority.pdf (332,622 bytes)
2020-01-24_DiscV5_audit_Cure53.pdf         ( 86,510 bytes)
```

All four are genuine PDFs (confirmed via `file`: PDF 1.4/1.5, zip-deflate encoded, the
Cure53 one reporting "8 page(s)" — not empty placeholders or Git-LFS pointer stubs).
**`SECURITY.md`'s own table lists only three of the four** — the 2018-09-14 NCC audit of
Clef (geth's standalone signing/key-management daemon) exists on disk but is not linked
from the security policy's own audit table. This is a small but real documentation-drift
finding in the reference client itself: the audit happened, the PDF is committed, but
`SECURITY.md` was never updated to list it. Worth citing precisely because it demonstrates
that even a mature security policy needs periodic reconciliation against what's actually in
the audits folder — a policy file and its target directory can silently diverge.

**Disclosure mechanism — a two-tier, EF-operated bug bounty, not GitHub-native.** Unlike
Nethermind (private GitHub Security Advisories draft flow) or a typical single-maintainer
project, geth's reporting instructions point reporters at **`https://bounty.ethereum.org`**
or **`bounty@ethereum.org`** (`SECURITY.md:17-19`) — the Ethereum Foundation's own
protocol-wide bug bounty program, not a repo-scoped GitHub mechanism. This matters
structurally: geth is one participant in an ecosystem-wide bounty program that also covers
the Ethereum protocol/specs, other execution/consensus clients, and smart-contract
infrastructure, so a report routed through `bounty.ethereum.org` may get triaged by EF
security staff before it ever reaches a geth-specific maintainer. The policy additionally
points at a **disclosure page**
(`https://github.com/ethereum/go-ethereum/security/advisories?state=published`,
`SECURITY.md:19`) — GitHub's own published-advisories list — as the place prior disclosures
are catalogued once resolved, meaning geth *does* also use GitHub Security Advisories as the
public record-keeping layer even though intake happens through the EF bounty program rather
than GitHub's own private-draft-advisory intake form.

**Explicit anti-public-disclosure instruction** (`SECURITY.md:15`): "**Please do not file a
public ticket** mentioning the vulnerability" — bolded in the source, the strongest and
most explicit wording of this instruction seen across the three clients surveyed in this
series so far.

**PGP key** — fingerprint `AE96 ED96 9E47 9B00 84F3 E17F E88D 3334 FA5F 6A0A`
(`SECURITY.md:23`, confirmed present as an ASCII-armored RSA public-key block spanning
`SECURITY.md:26-93`). The key carries two UIDs baked into the key material itself: "Ethereum
Foundation Bug Bounty <bounty@ethereum.org>" and "Ethereum Foundation Security Team
<security@ethereum.org>" — i.e., the same key is used both for the bounty-intake alias and
the EF security team's general alias, letting a reporter encrypt sensitive vulnerability
details (e.g., a working exploit PoC) to a single, verifiable key regardless of which of
the two addresses they mail. This is meaningfully more infrastructure than either
Nethermind's or Erigon's security policy offers — neither ships an embedded PGP key for
encrypted vulnerability submission.

**Fukuii verdict — port the shape, not the mechanism.** Fukuii's planned `SECURITY.md` (this
session) is GitHub-Security-Advisories-based, matching Nethermind's pattern rather than
geth's EF-bounty pattern — fukuii is not a participant in the Ethereum Foundation's bounty
program (it's a separate ETC/ETH client, not the reference geth codebase the EF bounty
covers), so pointing reporters at `bounty.ethereum.org` would be actively wrong. What *is*
worth porting: (1) the explicit, bolded "do not file a public ticket" instruction — cheap,
unambiguous, and geth's phrasing is better than a softer "we encourage you to report
responsibly"; (2) an audits-table pattern *if and when* fukuii ever commissions a third-party
audit — geth's table (scope / date / link) is a clean, minimal format worth reusing verbatim
even though fukuii has no audits to list yet; (3) **do not** add a PGP key unless a real
encrypted-intake need exists — for a two-maintainer repo, a plain security-contact email (as
already planned) is proportionate, and an unused PGP key that nobody checks the inbox for
routinely is worse than no PGP key (false assurance of a monitored channel).

---

## docs/postmortems/2021-08-22-split-postmortem.md — the standout artifact

**File:** `.claude/repo-references/clients/go-ethereum/docs/postmortems/2021-08-22-split-postmortem.md`,
267 lines, read in full. This is the single most valuable artifact found in this pass across
all three clients surveyed so far (Nethermind, Erigon, go-ethereum): a first-person,
timestamped, technically precise public account of an actual consensus-splitting bug in
production — reported, triaged, silently patched, and *then* fully disclosed months later
with the real technical root cause, the diff that fixed it, and a reproducing state test.
Nothing comparable exists in the Nethermind or Erigon vendored clones (neither ships a
`docs/postmortems/`-equivalent directory at all).

### Why this incident, and why this researcher, matters

The bug was reported by **Guido Vranken** (`split-postmortem.md:8,19`) — a security
researcher whose primary public work is a differential/coverage-guided EVM fuzzer that
runs multiple execution clients side-by-side (geth, besu, nethermind, erigon, evmone, etc.)
and diffs their outputs on the same crafted bytecode, looking for exactly this class of
bug: places where clients silently disagree on state root without either crashing.
That fuzzing methodology is the reason a `RETURNDATA`-corruption bug this subtle was ever
found *before* it caused a chain split via honest disagreement — and it is directly
relevant to fukuii because it demonstrates the value of differential fuzzing against a
byte-perfect reference implementation, the same discipline `forge`/`beacon`'s "byte-perfect
validation against the reference client" protocol in fukuii's own `AGENTS.md` is built
around. The irony the postmortem itself surfaces is that even with the bug found and
patched in advance, the chain split anyway six days later when an attacker independently
(re)discovered and exploited the same defect before the ecosystem had universally upgraded.

### Timeline (`split-postmortem.md:5-11`, quoted)

> - 2021-08-17: Guido Vranken submitted a bounty report. Investigation started, root cause
>   identified, patch variations discussed.
> - 2021-08-18: Made public announcement over twitter about upcoming security release
>   upcoming Tuesday. Downstream projects were also notified about the upcoming
>   patch-release.
> - 2021-08-24: Released [v1.10.8](https://github.com/ethereum/go-ethereum/releases/tag/v1.10.8)
>   containing the fix on Tuesday morning (CET). Erigon released
>   [v2021.08.04](https://github.com/ledgerwatch/erigon/releases/tag/v2021.08.04).
> - 2021-08-27: At 12:50:07 UTC, issue exploited. Analysis started roughly 30m later,

Six-day gap between the bounty report (Aug 17) and the coordinated patch release (Aug 24),
then a further three-day gap before exploitation in the wild (Aug 27) — the fix was public
and shipping for three full days before anyone actually triggered the underlying bug on
mainnet, meaning the patch window worked for the majority of the network but not for every
node/miner.

### Technical root cause — `RETURNDATA` aliasing via unshared backing arrays (`split-postmortem.md:17-50`)

The bug: geth's `CALL`-family opcodes deliberately avoid copying the `input` slice passed
to a call (a prior fix, itself made to defend against a different DoS reported by Hubert
Ritzdorf, per `split-postmortem.md:25`, "to avoid copying data a lot on repeated `CALL`s").
The `dataCopy` precompile likewise returns the same backing slice it was given rather than
a fresh copy. Individually these are safe optimizations — the problem emerges only when the
precompile's *input* and *output* memory ranges **overlap but are shifted**, and the
interpreter then copies the return value (`ret`) into the destination memory region
*after* execution. Because `ret` and the destination range shared the same underlying
byte array, that final copy mutates `ret` in place — and `ret` is exactly what the EVM
uses as `RETURNDATA`, so a subsequent `RETURNDATACOPY` observes corrupted bytes. Quoted
in full, the report's own worked example (`split-postmortem.md:30-45`):

```
1. Calling datacopy

  memory: [0, 1, 2, 3, 4]
  in (mem[0:4]) : [0,1,2,3]
  out (mem[1:5]): [1,2,3,4]

2. dataCopy returns

  returndata (==in, mem[0:4]): [0,1,2,3]

3. Copy in -> out

  => memory: [0,0,1,2,3]
  => returndata: [0,0,1,2]
```

Consequence, stated directly (`split-postmortem.md:50`): "A memory-corruption bug within
the EVM can cause a consensus error, where vulnerable nodes obtain a different `stateRoot`
when processing a maliciously crafted transaction. This, in turn, would lead to the chain
being split." This is the textbook definition of a consensus-critical bug in an execution
client: not a crash, not an obviously-wrong result — a *silent*, deterministic-per-client
divergence that only manifests as a fork when enough of the network disagrees on the same
transaction.

### Handling decision — patch now vs. disclose-and-patch-later (`split-postmortem.md:52-67`)

Before shipping, the team verified the same crafted transaction against **openethereum,
nethermind, and besu** and confirmed none were affected by the identical defect
(`split-postmortem.md:54`) — i.e., they ran a cross-client differential check as part of
triage, the same practice fukuii's `forge`/`beacon` reference-client-validation protocol
formalizes. The team then made a deliberate, reasoned tradeoff, quoted in full
(`split-postmortem.md:56-66`):

> It was decided that in this specific instance, it would be possible to make a public
> announcement and a patch release:
> - The fix can be made pretty 'generically', e.g. always copying data on input to
>   precompiles.
> - The flaw is pretty difficult to find, given a generic fix in the call. The attacker
>   needs to figure out that it concerns the precompiles, specifically the datacopy, and
>   that it concerns the `RETURNDATA` buffer rather than the regular memory, and lastly the
>   special circumstances to trigger it (overlapping but shifted input/output).
>
> Since we had merged the removal of `ETH65`, if the entire network were to upgrade, then
> nodes which have not yet implemented `ETH66` would be cut off from the network. After
> further discussions, we decided to:
> - Announce an upcoming security release on Tuesday (August 24th), via Twitter and
>   official channels, plus reach out to downstream projects.
> - Temporarily revert the `ETH65`-removal.
> - Place the fix into the PR optimizing the jumpdest analysis [#23381].
> - After 4-8 weeks, release details about the vulnerability.

Two things worth flagging: (1) the fix was deliberately **camouflaged inside an unrelated
PR** (jumpdest-analysis optimization, PR #23381) rather than shipped as an obviously-titled
security patch — security-through-obscurity for the *disclosure timing*, not the fix
itself, buying time for the network to upgrade before an attacker could reverse-engineer
which diff actually mattered; (2) they simultaneously **reverted an unrelated protocol
change** (`ETH65` removal) purely to avoid a *different* problem (splitting off
not-yet-`ETH66`-capable nodes) compounding the same release — evidence that a
security-release decision has to account for the entire set of in-flight protocol changes,
not just the one bug being fixed.

### Exploit (`split-postmortem.md:69-83`)

At block **13107518**, mined 2021-08-27 12:50:07 UTC, a minority chain split occurred —
"minority split" meaning the *majority* of miners stayed on the correct chain and only a
minority forked off, i.e., most of the network had already upgraded past the vulnerable
window. Detection was community-driven, not tooling-driven: a Discord user (@AlexSSD7)
flagged the `allcoredevs` channel roughly 19 minutes after the block was mined
(`split-postmortem.md:71`); confirmation that a specific transaction
(`0x1cb6fb36633d270edefc04d048145b4298e67b8aa82a9e5ec4aa1435dd770ce4`) triggered the bug
came about an hour after the block (`split-postmortem.md:74`). Two more details worth
citing: the same exploit was independently run against **BSC** roughly 12 minutes *earlier*
(`split-postmortem.md:80`, cross-linking a BscScan tx) — the attacker (or a coordinated
group) targeted more than one EVM-compatible chain sharing the same vulnerable code lineage
in the same session; and the attacking account was funded from **Tornado Cash**
(`split-postmortem.md:78`), noted plainly without further comment.

### Lessons learned — disclosure policy, disclosure path, fork monitoring (`split-postmortem.md:85-127`)

**Disclosure decision, reconsidered in hindsight.** The postmortem cites geth's own
published [vulnerability-disclosure policy](https://geth.ethereum.org/docs/developers/geth-developer/disclosures)
and quotes its stated goal (`split-postmortem.md:92`): "The primary goal for the Geth team
is the health of the Ethereum network as a whole, and the decision whether or not to publish
details about a serious vulnerability boils down to minimizing the risk and/or impact of
discovery and exploitation." Then, remarkably, the postmortem second-guesses its own team's
decision in writing (`split-postmortem.md:94`): "In hindsight, this was a dangerous
decision, and it's unlikely that the same decision would be reached were a similar incident
to happen again." This is a rare, genuinely candid piece of institutional self-critique in
a public engineering document — most postmortems stop at "here's what we did," not "and in
retrospect we think that specific call was wrong."

**Disclosure path — who was told, who was missed** (`split-postmortem.md:97-118`).
Notified in advance: Polygon/Matic, MEV(-related infra), Avalanche, **Erigon**, BSC, EWF
(Energy Web Foundation), Quorum, **ETC**, xDAI. Notably *not* reached in time: Optimism,
Summa, Harmony — "some were 'lost', and only notified later" (`split-postmortem.md:111`).
The concrete remediation the team committed to and later delivered
(`split-postmortem.md:117-118`): create a low-volume `geth-announce@ethereum.org` mailing
list specifically so downstream/dependent projects have a durable subscription channel for
security and release announcements, rather than relying on the team's ad hoc outreach list
remembering every downstream consumer. **ETC is explicitly named** in the original
notified-projects list — direct evidence that ETC (fukuii's own PoW network family) was a
real recipient of a prior geth security disclosure, underscoring why fukuii tracking
upstream geth/core-geth security advisories matters operationally, not just abstractly.

**Fork monitoring** (`split-postmortem.md:120-126`): the team's own fork-monitoring tool
("forkmon") "behaved 'ok' during the incident, but had to be restarted during the evening"
and "is currently not performing great when many nodes are connected" — two concrete,
humble action items (improve forkmon resiliency; add push-based alerts to speed up fork
detection) rather than a vague "we'll do better" gesture.

### The patch and reproduction artifact (`split-postmortem.md:153-265`)

The full unified diff is embedded directly in the document — four call-site fixes in
`core/vm/instructions.go` (`opCall`, `opCallCode`, `opDelegateCall`, `opStaticCall`, each
gaining a `ret = common.CopyBytes(ret)` before the return value is written into memory)
plus a one-line *removal* of an existing defensive copy in `core/vm/interpreter.go`
(`in.returnData = common.CopyBytes(res)` → `in.returnData = res`) — i.e., the actual fix
moved *where* the copy happens rather than simply adding a copy everywhere, consistent with
the "always copying data on input to precompiles" generic-fix framing quoted above. The
postmortem also embeds a **complete JSON state test** reproducing the exact vulnerable
sequence (`split-postmortem.md:209-264`) — pre-state, transaction, and expected post-state
hash for the Berlin fork — meaning the incident write-up doubles as a permanent, runnable
regression test specification, not just prose.

### Why this matters for fukuii

This is the concrete demonstration of what a mature reference client's incident-response
documentation looks like once a *real* consensus bug has occurred: timestamped timeline,
named reporter and researcher methodology, full technical root-cause narrative with a
worked byte-level example, the actual patch diff, a runnable reproduction test, an honest
retrospective on the team's own disclosure-timing decision, and a concrete list of
process improvements the team committed to. Fukuii has never had a comparable incident (no
`docs/postmortems/`-equivalent content exists), but the *template* this establishes — write
one of these the moment a real consensus-affecting bug is found and fixed, regardless of
whether it was ever exploited — is exactly the kind of durable, high-signal documentation
worth adopting as a standing convention now, before an incident forces it to be invented
under pressure.

---

## CODEOWNERS — fine-grained, named-individual ownership

**File:** `.github/CODEOWNERS`, 34 lines (`wc -l` confirmed), full text read.

Structured identically in spirit to Nethermind's per-project CODEOWNERS (named
individuals, not teams) but scoped to *package-level Go directories* rather than
`.csproj`-equivalent project boundaries — Go has no project-file concept, so ownership
naturally maps onto the directory tree the `go build` toolchain already treats as the unit
of compilation.

**Representative entries, quoted verbatim (`CODEOWNERS:4-34`):**

```
accounts/usbwallet/             @gballet
accounts/scwallet/              @gballet
accounts/abi/                   @gballet @MariusVanDerWijden
beacon/engine/                  @MariusVanDerWijden @lightclient @fjl
beacon/light/                   @zsfelfoldi
beacon/merkle/                  @zsfelfoldi
beacon/types/                   @zsfelfoldi @fjl
beacon/params/                  @zsfelfoldi @fjl
cmd/evm/                        @MariusVanDerWijden @lightclient
cmd/keeper/                     @gballet
core/state/                     @rjl493456442
crypto/                         @gballet @jwasinger @fjl
core/                           @rjl493456442
eth/                            @rjl493456442
eth/catalyst/                   @MariusVanDerWijden @lightclient @fjl @jwasinger
eth/tracers/                    @s1na
ethclient/                      @fjl
ethdb/                          @rjl493456442
event/                          @fjl
trie/                           @rjl493456442 @gballet
triedb/                         @rjl493456442
core/tracing/                   @s1na
graphql/                        @s1na
internal/ethapi/                @fjl @s1na @lightclient
internal/era/                   @lightclient
miner/                          @MariusVanDerWijden @fjl @rjl493456442
node/                           @fjl
p2p/                            @fjl @zsfelfoldi
rlp/                            @fjl
params/                         @fjl @gballet @rjl493456442 @zsfelfoldi
rpc/                            @fjl
```

**Structural observations:**

- **No repo-wide catch-all lines at all.** Unlike Nethermind's file, which opens with
  `/.github @rubo`, `*.md @rubo @LukaszRozmej @MarekM25`, etc., geth's CODEOWNERS has
  *zero* cross-cutting glob patterns — no line matches `.github/`, `Dockerfile*`, or `*.md`.
  Every single line targets a specific package directory. Anything not covered by one of
  these 30 directory rules (docs, build scripts, top-level files like `go.mod`) simply has
  no default reviewer assigned by this mechanism.
- **Fine-grained sub-package splits within one parent tree.** `beacon/` alone has four
  separate ownership lines (`engine/`, `light/`, `merkle/`, `types/`, `params/`) each with a
  distinct (overlapping but not identical) set of owners — `beacon/light/` and
  `beacon/merkle/` are solely `@zsfelfoldi`, while `beacon/engine/` (the Engine API surface
  consumed by consensus-layer clients) needs three reviewers
  (`@MariusVanDerWijden @lightclient @fjl`), reflecting that the Engine API is a
  higher-consequence, more cross-cutting surface than the light-client sync internals.
- **A one-person, narrowly-scoped tooling directory:** `cmd/keeper/` → `@gballet` alone
  (`CODEOWNERS:13`) — a single named owner for a single `cmd/` subdirectory, no fallback
  reviewer, illustrating that even in a repo with several core maintainers, some surfaces
  are genuinely one-person-owned rather than committee-reviewed.
- **`core/tracing/` and `eth/tracers/` and `graphql/`** all route to the same single owner,
  `@s1na` (`CODEOWNERS:19,25,26`) — three separate directories, not adjacent in the tree,
  unified by one person's cross-cutting responsibility for observability/tracing-adjacent
  surfaces (execution tracing hooks, the tracer RPC subsystem, and the GraphQL API) rather
  than by directory proximity.
- **`internal/era/`** → `@lightclient` alone (`CODEOWNERS:28`) — the Era file-format
  (post-Merge historical chain-segment archive format) has exactly one named owner, a
  narrow, specialized surface unlikely to see broad contribution.
- **Overlapping-but-distinct ownership sets across related directories:** `core/` and
  `eth/` and `core/state/` and `ethdb/` and `trie/`/`triedb/` are all owned wholly or
  partly by `@rjl493456442` (`CODEOWNERS:14,16,17,21,23,24`), the common thread being that
  person's evident ownership of state/storage/database-adjacent subsystems across package
  boundaries — this is the closest geth's file comes to Nethermind's `I*Config.cs`
  cross-cutting glob pattern, except expressed as repetition across explicit directory
  lines rather than a single wildcard rule.

**Fukuii verdict — reinforces the "already planned, lightweight" conclusion, with one
refinement.** Nethermind's sibling document already concluded fukuii should add a
lightweight CODEOWNERS (`* @realcodywburns @chris-mercer`) sized to its two real
maintainers rather than attempting per-module density prematurely — geth's file confirms
that even a mature, many-maintainer reference client keeps its CODEOWNERS scoped to
*directories that already exist as natural module boundaries* (Go packages here, `.csproj`
projects for Nethermind) rather than inventing artificial subdivisions, and that a
single-person-owned narrow directory (`cmd/keeper/`, `internal/era/`) is a normal, accepted
pattern rather than something to avoid. The one refinement worth carrying forward: once
fukuii's contributor count grows past two, geth's pattern of "a person can own several
non-adjacent directories unified by subsystem concern" (à la `@s1na`'s tracing/graphql
ownership, `@rjl493456442`'s state/storage ownership) is a better model to grow into than
Nethermind's flatter per-project-directory density — it maps ownership to *who actually
knows the subsystem*, not merely to *which directory the code physically lives in*.

---

## docs/audits/ — four published third-party audits

Confirmed present, all four genuine PDFs (not stubs — see byte sizes and `file` output
above):

| File | Scope | Date | Auditor |
|---|---|---|---|
| `2017-04-25_Geth-audit_Truesec.pdf` | `geth` (core client) | 2017-04-25 | Truesec |
| `2018-09-14_Clef-audit_NCC.pdf` | Clef (signing daemon) | 2018-09-14 | NCC Group |
| `2019-10-15_Discv5_audit_LeastAuthority.pdf` | Discv5 (peer discovery v5) | 2019-10-15 | Least Authority |
| `2020-01-24_DiscV5_audit_Cure53.pdf` | Discv5 (peer discovery v5) | 2020-01-24 | Cure53 |

Note the **2018-09-14 Clef/NCC audit exists on disk but is not listed in `SECURITY.md`'s
own audit table** (see the SECURITY.md section above) — flagged there as a documentation-
drift finding rather than repeated as a separate item here. Also note **Discv5 was audited
twice by two independent firms roughly three months apart** (Least Authority Oct 2019, then
Cure53 Jan 2020) — a security-critical piece of shared networking infrastructure (Discv5
underpins peer discovery for essentially every modern Ethereum-family client, including
fukuii's own devp2p v5 discovery) receiving two independent audits is a meaningfully
stronger signal than a single audit would be.

**Fukuii verdict — not portable, informational only.** Fukuii has never commissioned a
third-party security audit and this document does not recommend fukuii fabricate one — but
if/when a fukuii-specific subsystem audit is ever commissioned (e.g., of the RocksDB
storage layer, or Discv5-derived peer discovery code), geth's `docs/audits/` directory
convention (raw PDF, filename encoding `date_scope_auditor.pdf`, one row per report in
`SECURITY.md`) is a clean, zero-design-cost format to imitate outright.

---

## CODE_OF_CONDUCT.md — confirmed absent

A repo-root `CODE_OF_CONDUCT.md` was searched for exhaustively (`find` for any
case-insensitive `*code*of*conduct*` match across the entire vendored clone, excluding
`.git/`) and **does not exist anywhere in the repository**. This corrects the task
framing's expectation of a "confirm presence, one paragraph" finding — unlike Nethermind
(135-line Contributor Covenant v2.1) and unlike fukuii's own gap (which also has none),
**go-ethereum itself has no CODE_OF_CONDUCT.md**, standard Covenant or otherwise. Nor is a
code-of-conduct section embedded in `README.md` or `.github/CONTRIBUTING.md` under any
other name — `CONTRIBUTING.md` (analyzed below) covers coding guidelines and PR mechanics
only, with no conduct/behavior expectations at all.

**Fukuii verdict — go-ethereum is not a model to follow here.** This is the one governance
axis on which the oldest, most mature client in this survey is *behind* Nethermind, not
ahead of it — a large, decades-adjacent OSS project with no published code of conduct is
notable but not something fukuii should treat as validating the absence. Nethermind's
Contributor Covenant v2.1 adoption remains the better reference pattern; this session's
absence-of-CODE_OF_CONDUCT.md finding for fukuii stands unchanged by geth's example, and
"port now, standard Covenant text, zero cost" (per the Nethermind sibling document) remains
the correct recommendation.

---

## License structure (GPLv3/LGPLv3 split)

**File:** `README.md`'s "License" section (`README.md:247-256`), quoted in full:

```markdown
## License

The go-ethereum library (i.e. all code outside of the `cmd` directory) is licensed under the
[GNU Lesser General Public License v3.0](https://www.gnu.org/licenses/lgpl-3.0.en.html),
also included in our repository in the `COPYING.LESSER` file.

The go-ethereum binaries (i.e. all code inside of the `cmd` directory) are licensed under the
[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.en.html), also
included in our repository in the `COPYING` file.
```

**Confirmed on disk:** both license files are present and are the genuine, full FSF license
texts, not stubs — `COPYING` (35,149 bytes) opens "GNU GENERAL PUBLIC LICENSE / Version 3,
29 June 2007" and `COPYING.LESSER` (7,651 bytes) opens "GNU LESSER GENERAL PUBLIC LICENSE
/ Version 3, 29 June 2007", each with the standard FSF copyright/redistribution preamble.

**The split's rationale, inferred from the license choice itself:** LGPLv3 for the
importable library packages (everything outside `cmd/`) permits closed-source or
differently-licensed applications to *import* geth's Go packages (e.g., `ethclient`,
`core/types`, `crypto`) as a dependency without inheriting a copyleft obligation on the
consuming application's own code — the LGPL's key distinguishing feature versus the GPL is
exactly this "may be linked/imported by non-GPL code" carve-out. The *binaries* in `cmd/`
(the actual `geth`, `devp2p`, `abigen`, `evm`, `rlpdump` executables users run) remain full
GPLv3, meaning nobody can take the compiled `geth` binary itself, modify it, and distribute
a closed-source derivative — but they *can* build a proprietary Go application that
imports `go-ethereum`'s library packages as a dependency. This dual-license structure is
specifically designed to make geth simultaneously permissive-enough-to-embed as a library
(driving ecosystem adoption — most Go-based Ethereum tooling imports geth packages
directly) and protective-enough-to-prevent-forking as a distributed binary.

**Fukuii verdict — likely not applicable, and this document does not recommend adopting
it.** Fukuii ships as a single application (`fukuii` the node binary) built from one Scala
codebase under a single root `LICENSE` (Apache 2.0, per repo badges) — there is no separate
"library" artifact fukuii publishes independently of the node binary for third parties to
`import`/depend on the way Go or JVM consumers import `go-ethereum`'s packages via
`go get`. Introducing a GPLv3/LGPLv3-style binary/library split would require fukuii to
first decide it wants to ship and maintain a genuinely separate, versioned library artifact
(e.g., a published JVM artifact of just the RLP/crypto/domain-types layer) — that is a
build/packaging and product decision, not a licensing-hygiene fix, and nothing in this
survey suggests fukuii currently has (or needs) such a split. Flagged as **likely N/A**
rather than a gap.

---

## Templates & governance

### Issue templates — `.github/ISSUE_TEMPLATE/` (3 templates, no `config.yml`)

**`bug.md`** (18 lines, read in full):

```markdown
---
name: Report a bug
about: Something with go-ethereum is not working as expected
title: ''
labels: 'type:bug'
assignees: ''
---

#### System information

Geth version: `geth version`
CL client & version: e.g. lighthouse/nimbus/prysm@v1.0.0
OS & Version: Windows/Linux/OSX
Commit hash : (if `develop`)

#### Expected behaviour


#### Actual behaviour


#### Steps to reproduce the behaviour


#### Backtrace

````
[backtrace]
````

When submitting logs: please submit them as text and not screenshots.
```

The **"CL client & version" field** (`bug.md:12`) is the standout, execution-client-specific
detail — since the Merge, a geth bug report is frequently actually a Consensus-Layer-side
Engine API incompatibility (exactly the same insight Nethermind's `bug_report.md` "Desktop"
block captures via its own "Consensus Client" field), so capturing the paired CL client and
version up front avoids a triage round-trip. Geth's template additionally asks for a
"Commit hash (if `develop`)" (`bug.md:14`) — distinguishing tagged-release bug reports
(where `geth version` alone identifies the build) from unstable-branch bug reports (where
the tag alone is insufficient and the exact commit matters).

**`feature.md`** (17 lines, read in full) — a minimal two-section template ("Rationale":
why should this exist, what are the use-cases; "Implementation": do you have ideas, are you
willing to implement it) — notably shorter and less structured than Nethermind's four-section
feature-request template, and it explicitly invites the reporter to consider implementing
their own request.

**`question.md`** (10 lines, read in full) — the one template neither Nethermind nor Erigon
has an exact equivalent of: a dedicated "Ask a question" template whose entire body is a
redirect, quoted in full (`question.md:9`): "This should only be used in very rare cases
e.g. if you are not 100% sure if something is a bug or asking a question that leads to
improving the documentation. For general questions please use discord ... or the Ethereum
stack exchange." Its explicit purpose is to *discourage* using GitHub Issues for general
Q&A by making the escape hatch (Discord, Ethereum Stack Exchange) the first thing a
would-be question-asker sees, while still leaving a narrow legitimate use (a question that,
if answered, should become a documentation fix) as an accepted issue type.

**No `.github/ISSUE_TEMPLATE/config.yml` exists** — geth does not disable GitHub's default
blank-issue option or add external contact links via the config-based mechanism; the
`question.md` template's own body text is the entirety of the "go ask elsewhere" redirect
mechanism.

### Pull request template — confirmed absent

`find` for any case-insensitive `*pull_request_template*` across the entire vendored clone
returns nothing. Geth accepts PRs with no structured template at all — no checkbox-driven
"Types of changes" section (Nethermind), no template-driven auto-labeling mechanism tied to
PR body content. What geth has *instead* is a **PR-title-format enforcement bot**
(`.github/workflows/validate_pr.yml`, 62 lines, read in full) — worth noting as a distinct
governance artifact even though the task's required structure doesn't explicitly call for a
CI-workflow survey the way the Nethermind/Erigon siblings do. It runs on every
`pull_request: [opened, edited, synchronize]` and does two things via inline
`actions/github-script`: (1) rejects PR titles matching a Conventional-Commits-style prefix
(`^(feat|chore|fix)(\(.*\))?\s*:`) as spam, closing the PR immediately with an explanatory
comment (`validate_pr.yml:11-30`) — a direct countermeasure against the well-known abuse
pattern of drive-by bot/spam PRs mimicking a plausible-looking commit-convention title to
farm contribution-count metrics on high-visibility repos; (2) separately validates that a
*legitimate* title matches geth's own convention (`directory, ...: description`,
`CONTRIBUTING.md:25-26` below) and that every named directory in the title actually exists
in the tree (`validate_pr.yml:50-63`), failing the check with a specific missing-directory
list if not. This substitutes for a PR template's structure-enforcement role entirely
through a title-format contract rather than a body-content contract.

### CONTRIBUTING.md — thin root file, points to an external dev-guide site

**File:** `.github/CONTRIBUTING.md`, 41 lines, read in full.

```markdown
# Contributing

Thank you for considering to help out with the source code! We welcome
contributions from anyone on the internet, and are grateful for even the
smallest of fixes!

If you'd like to contribute to go-ethereum, please fork, fix, commit and send a
pull request for the maintainers to review and merge into the main code base. If
you wish to submit more complex changes though, please check up with the core
devs first on [our gitter channel](https://gitter.im/ethereum/go-ethereum) to
ensure those changes are in line with the general philosophy of the project
and/or get some early feedback which can make both your efforts much lighter as
well as our review and merge procedures quick and simple.

## Coding guidelines

Please make sure your contributions adhere to our coding guidelines:

 * Code must adhere to the official Go
[formatting](https://golang.org/doc/effective_go.html#formatting) guidelines
(i.e. uses [gofmt](https://golang.org/cmd/gofmt/)).
 * Code must be documented adhering to the official Go
[commentary](https://golang.org/doc/effective_go.html#commentary) guidelines.
 * Pull requests need to be based on and opened against the `master` branch.
 * Commit messages should be prefixed with the package(s) they modify.
   * E.g. "eth, rpc: make trace configs optional"

## Can I have feature X

Before you submit a feature request, please check and make sure that it isn't
possible through some other means. The JavaScript-enabled console is a powerful
feature in the right hands. Please check our
[Geth documentation page](https://geth.ethereum.org/docs/) for more info
and help.

## Configuration, dependencies, and tests

Please see the [Developers' Guide](https://geth.ethereum.org/docs/developers/geth-developer/dev-guide)
for more details on configuring your environment, managing project dependencies
and testing procedures.
```

**Confirmed: the real developer guide lives entirely outside the repository**, at
`https://geth.ethereum.org/docs/developers/geth-developer/dev-guide` (`CONTRIBUTING.md:38`)
— a page on the externally-hosted `geth.ethereum.org` documentation site, itself built from
a separate `website` git branch of the same repository (referenced in `README.md:243-245`:
"For contributions to the go-ethereum website, please checkout and raise pull requests
against the `website` branch"). `CONTRIBUTING.md`'s own content is **near-verbatim
duplicated inside `README.md`'s own "Contribution" section** (`README.md:215-239`) — the
two files repeat the same four coding-guideline bullets and the same "check up with the
core devs first" framing almost word for word, with only the community-chat link updated
(`CONTRIBUTING.md` still references the now-largely-inactive Gitter channel,
`CONTRIBUTING.md:10`, while `README.md`'s copy of the same paragraph has been updated to
reference Discord instead, `README.md:222`) — a small, concrete piece of evidence that the
duplication between the two files has already drifted out of sync rather than being
mechanically kept identical.

**The commit-message convention** (`CONTRIBUTING.md:25-26`) — "Commit messages should be
prefixed with the package(s) they modify," example `"eth, rpc: make trace configs
optional"` — is the same convention `validate_pr.yml`'s title-format regex enforces at the
PR-title level (`directory, ...: description`), meaning geth has a single naming convention
applied consistently to both commit messages and PR titles, with the PR-title half of it
mechanically enforced by CI and the commit-message half left as an unenforced style
guideline.

### `docs/developers/geth-developer/dev-guide` — confirmed not present as a repo path

No such path exists inside the vendored clone (`docs/` on disk contains only `audits/` and
`postmortems/`, confirmed by the earlier directory listing) — this fully confirms
`CONTRIBUTING.md`'s own claim that the developer guide is hosted externally on
`geth.ethereum.org` and is not a file checked into this repository at all, unlike (for
example) Erigon's `CI-GUIDELINES.md`, which is an in-repo file the CI-security section of
Erigon's sibling document analyzes directly.

**Fukuii verdict — mixed, mostly "port the idea, adapt the specifics."**

- **CODE_OF_CONDUCT.md:** go-ethereum has none — see the dedicated section above; this does
  not change fukuii's plan to port Nethermind's Covenant text.
- **Issue templates:** fukuii already has a generic template plus a Gorgoroth field-report
  template — **already ahead of geth here**, since geth ships only three generic templates
  with no equivalent of a network-specific field-report template. The one concrete idea
  worth checking: does fukuii's existing bug template capture a CL-client-equivalent field
  for cross-client interop bugs — e.g., for ETH/Sepolia work, which execution *or* consensus
  layer peer a bug was observed against? If not, that's a small, concrete gap to close,
  mirroring geth's "CL client & version" field.
- **Question template:** a genuinely reusable, cheap idea fukuii doesn't have — a template
  whose entire purpose is redirecting general questions away from GitHub Issues toward a
  lower-friction channel (fukuii doesn't currently have a Discord/community-chat presence
  documented anywhere in this survey, so this may not be actionable yet, but the *pattern*
  — an issue template that is itself a polite redirect — is worth remembering once such a
  channel exists).
- **PR template:** confirmed absent in geth, same as confirmed for Erigon in its sibling
  document — **fukuii is already ahead** here (it has a real `PULL_REQUEST_TEMPLATE.md`).
- **PR-title-format enforcement bot (`validate_pr.yml`):** **needs design, not a direct
  port** — the underlying problem (drive-by spam PRs gaming contribution-count metrics via
  plausible-looking conventional-commit titles) is a real, documented abuse pattern on
  high-visibility repos; fukuii is not (yet) a high-visibility target for this specific
  abuse, so this is a low-priority, defer-until-needed item rather than an immediate port —
  but the mechanism (inline `github-script` step regex-matching the PR title, auto-closing
  with an explanatory comment) is cheap to implement whenever it becomes relevant.
- **Thin root CONTRIBUTING.md + external dev-guide site:** **not portable as a structural
  model** — fukuii's equivalent split (`AGENTS.md`'s "Full contributor workflow" pointer to
  `docs/development/contributing.md`, an **in-repo** file) is arguably a *better* pattern
  than geth's external-website split for a project without a public marketing/docs site to
  host such content on; note the observed content-drift risk (Gitter vs. Discord link
  mismatch between `CONTRIBUTING.md` and `README.md`) as a caution about maintaining any
  duplicated-content-across-two-files pattern, which is exactly the trap fukuii's own
  `CLAUDE.md`/`AGENTS.md` split (see `docs/agentic-tooling/agents-md-decision-2026.md`)
  was designed to avoid via one-directional `@import` rather than copy-paste duplication.

---

## Docker

**`Dockerfile`** (26 lines, read in full) — two-stage build: `golang:1.26-alpine` builder
stage runs `go run build/ci.go install -static ./cmd/geth` (a static build via the project's
own `build/ci.go` build-orchestration tool, not a bare `go build`), then copies just the
`geth` binary into a bare `alpine:latest` runtime stage alongside `ca-certificates`. Exposes
`8545 8546 30303 30303/udp` and sets `ENTRYPOINT ["geth"]`. No non-root `USER` directive,
no `HEALTHCHECK` — a notably leaner Dockerfile than either Nethermind's four-variant set or
fukuii's own seven-Dockerfile set.

**`Dockerfile.alltools`** (32 lines, read in full) — identical builder stage, but runs both
`go run build/ci.go install -static ./cmd/geth` *and* a second, broader
`go run build/ci.go install -static` (no package argument — builds every `cmd/*` binary:
`devp2p`, `abigen`, `evm`, `rlpdump`, etc.), then copies `/go-ethereum/build/bin/*`
(everything) into the runtime stage rather than just `geth`. The file comments explain the
redundant first build step exists purely for Docker layer-cache reuse with the plain
`Dockerfile` (`Dockerfile.alltools:16-19`, quoted: "This is not strictly necessary, but it
matches the 'Dockerfile' steps, thus makes it so that under certain circumstances, the
docker layer can be cached, and the builder can jump to the next (build all) command, with
the go cache fully loaded").

**`.dockerignore`** (5 lines, read in full) — excludes `**/*_test.go`, `build/_workspace`,
`build/_bin`, `tests/testdata` (the latter being the `git submodule`-vendored
`ethereum/tests` state-test corpus, correctly excluded from the build context since it's
multi-hundred-megabytes and never needed to *build* the binary).

**No docker-compose file exists anywhere in the vendored clone** (confirmed via repo-wide
search) — same finding as both Nethermind and Erigon: none of the three reference clients
ship multi-container orchestration alongside their core Dockerfiles.

**Fukuii verdict — not portable as a set; fukuii's own Dockerfile inventory already
covers this ground more thoroughly.** Geth's two-Dockerfile set (single-binary vs.
all-binaries) solves a narrower problem than fukuii's own seven-Dockerfile inventory
(base/dev/bootnode/distroless/mainnet/mordor + default) already addresses. The one detail
worth deliberately noting rather than silently matching: geth's production `Dockerfile` has
no `HEALTHCHECK` and runs as root by default — this is **not** a pattern to imitate;
fukuii's `docker-deployment.md` rule (non-root `USER`, `HEALTHCHECK` on all services)
remains the correct standard, and geth's leaner Dockerfile here should be read as "the
reference client accepts a security tradeoff fukuii's own house rules deliberately reject,"
not as counter-evidence against those rules.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable / Already ahead / Already planned | Reasoning |
|---|---|---|
| `SECURITY.md` overall shape | **Already planned (this session)** — port the "no public ticket" phrasing and audit-table format only | Fukuii's planned SECURITY.md is GitHub-Advisories-based (Nethermind pattern); geth's EF-bounty routing (`bounty.ethereum.org`) is specific to geth's role in the Ethereum Foundation's protocol-wide bounty program and does not apply to fukuii |
| Embedded PGP key for encrypted vulnerability intake | **Not portable** | Disproportionate infrastructure for a two-maintainer repo; an unmonitored PGP key is worse than none |
| `SECURITY.md`'s audit table omitting the 2018 Clef/NCC PDF that exists on disk | **Correction, not a finding for fukuii** | Documents doc/directory drift in the reference client itself — a caution for any future fukuii audits-table maintenance, not an action item today |
| `docs/postmortems/2021-08-22-split-postmortem.md` | **Already planned (new template convention, this session)** | The single best artifact in this survey — adopt the *template* (timeline, technical root cause, patch diff, disclosure decisions with retrospective honesty, reproduction test) as a standing convention for fukuii's own future consensus incidents; do not retrofit `docs/historical/reviews/*.md` (those are frozen archival docs) — cross-link them as prior art instead |
| CODEOWNERS (34-line, per-Go-package density) | **Not portable at this density** | Fukuii's two-maintainer scale doesn't justify per-package granularity yet, same conclusion as the Nethermind sibling document reached independently |
| CODEOWNERS (lightweight, cross-cutting version) | **Already planned** | Confirms rather than changes the Nethermind-sibling-document conclusion: `* @realcodywburns @chris-mercer` plus a handful of consensus-path lines is right-sized today |
| CODEOWNERS growth model (subsystem-based ownership across non-adjacent directories, e.g. `@s1na`'s tracing+graphql) | **Needs design (future)** | A better model to grow into than flat per-directory density once fukuii's contributor count increases — note for later, not actionable now |
| `docs/audits/` directory + `SECURITY.md` audit-table format | **Not portable now, informational** | No fukuii audit has ever been commissioned; format is worth imitating verbatim if/when one is |
| `CODE_OF_CONDUCT.md` | **Confirmed absent in go-ethereum — does not change fukuii's plan** | Nethermind's Contributor Covenant v2.1 remains the correct reference; port that, not geth's (nonexistent) example |
| GPLv3 (`cmd/`) / LGPLv3 (library) license split | **Not portable / likely N/A** | Fukuii ships one application under one root Apache-2.0 license with no separately-published library artifact; adopting this split would require a packaging decision fukuii hasn't made, not a licensing-hygiene fix |
| Issue templates (`bug.md`, `feature.md`, `question.md`) | **Already ahead** | Fukuii's generic + Gorgoroth field-report templates already exceed geth's three generic templates; check for a CL-client-equivalent cross-reference field as the one concrete gap worth closing |
| `question.md`'s "go ask elsewhere" redirect pattern | **Needs design (future)** | Reusable idea once fukuii has a documented community-chat channel to redirect to; not actionable today |
| `PULL_REQUEST_TEMPLATE.md` | **Already ahead** | Confirmed absent in go-ethereum (same as Erigon); fukuii already has one |
| `validate_pr.yml` PR-title spam/format enforcement bot | **Needs design, low priority** | Cheap `github-script` mechanism, real abuse pattern on high-visibility repos, but fukuii isn't currently a target for it — defer until it becomes relevant |
| Thin root `CONTRIBUTING.md` duplicated in README, real guide hosted externally | **Not portable as a model** | Fukuii's in-repo `docs/development/contributing.md` + one-directional `CLAUDE.md`→`AGENTS.md` import already avoids the content-drift risk this pattern exhibits (observed Gitter/Discord link mismatch between the two geth copies) |
| Dockerfile set (2 files: single-binary + all-tools) | **Not portable as a set** | Fukuii's existing 7-Dockerfile inventory already covers this ground; no gap |
| No non-root `USER`, no `HEALTHCHECK` in geth's production Dockerfile | **Not a pattern to imitate** | Fukuii's own `docker-deployment.md` house rules (non-root, healthchecked) remain correct; geth accepting this tradeoff is not counter-evidence |
| No docker-compose alongside core Dockerfiles | **Already matches fukuii** | Same finding as Nethermind and Erigon — none of the three reference clients ship compose files at this layer |
