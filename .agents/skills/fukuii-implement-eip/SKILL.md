---
name: fukuii-implement-eip
description: >-
  Structured, spec-driven workflow for implementing a new EIP (ETH/Sepolia, PoS,
  timestamp forks → `beacon`) or ECIP (ETC/Mordor, PoW, block-number forks →
  `forge`): spec-fetch → dependent-spec mapping → prior-work check → implement →
  mandatory forge/beacon consult → wraith for compile fixes → eye for validation →
  wrap-up summary. Use when asked to "implement EIP-XXXX", "implement ECIP-XXXX",
  "add support for <fork feature>", or when starting any hard-fork/opcode/gas-cost
  change. This wraps and enforces `consensus-change-protocol.md` — it does not
  replace it, and does not skip forge/beacon/wraith/eye for speed.
disable-model-invocation: true
user-invokable: true
argument-hint: "EIP-1234|ECIP-1234"
---

# Fukuii EIP/ECIP implementation

A structured pipeline for implementing a new EIP or ECIP, wrapping fukuii's existing
Consensus-Critical Change Protocol (see `CLAUDE.md`) rather than inventing a parallel
process. Ported from Erigon's `erigon-implement-eip`, split into two flavors matching
fukuii's dual PoW/PoS architecture.

## Step 0 — identify the flavor (mandatory, do this first)

- **EIP** (Ethereum, timestamp-fork dispatch, `OsakaOpCodes`/`forTimestamp()`) → the
  PoS family → route through **`beacon`**.
- **ECIP** (Ethereum Classic, block-number-fork dispatch, `OlympiaOpCodes`/
  `forBlock()`) → the PoW family → route through **`forge`**. Check
  `/media/dev/2tb/dev/ECIPs/_specs/` for the spec **first** — ECIPs are frequently
  already mirrored locally, no need to fetch from a remote source.
- If genuinely uncertain which family a given change belongs to, stop and ask rather
  than guessing — this is exactly the kind of irreversible-if-wrong call
  AGENTS.md's "Irreversible = 10× thought" principle covers.

## Procedure

### 1. Fetch and understand the spec

- **EIP**: fetch from `eips.ethereum.org/EIPS/eip-<N>`. Map every affected
  package/module before writing any code — for fukuii that means identifying which
  of `consensus/`, `vm/`, `domain/`, `crypto/` the change touches.
- **ECIP**: read `/media/dev/2tb/dev/ECIPs/_specs/ecip-<N>.md` directly (local
  mirror, no fetch needed). If absent locally, only then fetch remotely.

### 2. Map dependent/referenced specs

Follow the same spec-fetch process for every EIP/ECIP the primary one references or
depends on. A fork is rarely a single isolated change — under-mapping dependencies
here is the most common way an implementation silently misses a required companion
change.

### 3. Fork-context check

- **EIP**: check the fork's meta-EIP (`eips.ethereum.org/EIPS/eip-<fork-meta>`) for
  whether this EIP is Considered/Scheduled/Proposed/Declined For Inclusion
  (CFI/SFI/PFI/DFI) in the target fork. Note portmanteau EL/CL fork naming where
  relevant (fukuii is EL-only; a CL-side companion change is out of scope here but
  worth flagging if one exists).
- **ECIP**: identify which named ETC hard fork (Atlantis → Agharta → Phoenix →
  Thanos → Magneto → Mystique → Olympia) this ECIP activates under, and at what
  block number — ECIP activation is block-number-gated, never timestamp-gated.

### 4. Prior-work check (mandatory, do not skip)

Before writing any code, check whether this has already been started or completed:

```bash
gh pr list --search "<EIP-or-ECIP-number>"
git log --all --grep="<EIP-or-ECIP-number>" --oneline
```

Per memory `feedback_check_prs_before_drafting_findings`: check existing PRs/git
history before treating this as new work, not just the local sprint tracker
(`.claude/sprints/QUEUE.md`). If a prior partial implementation exists, reconcile
against it rather than starting fresh.

### 5. Implement

Write the actual change. Consult `forge`/`beacon` (per Step 0's routing) **before**
implementing for impact analysis — this is the Consensus-Critical Change Protocol's
step 2, not optional here.

### 6. Mandatory forge/beacon consult

Per `CLAUDE.md`'s Consensus-Critical Change Protocol: `forge` or `beacon` (or both,
if the change somehow spans both families — rare, flag it explicitly if so) reviews
the implementation against the reference client for byte-perfect validation. Do not
skip this step because the change "looks simple."

### 7. Compile-error triage

Route any compile errors to `wraith` — fix without altering consensus semantics. If
a fix requires a semantic change, stop and go back to Step 5/6, don't let `wraith`
silently make a consensus-affecting call.

### 8. Test and validate

Run the relevant tagged sbt subset directly (`build.sbt`'s `addCommandAlias` block
is the authoritative list — confirmed subsets include `testConsensus`,
`testOlympia`, `testEthSmoke`, `testEthereum`, plus the general
`testEssential`/`testStandard` tiers), then hand off to `eye` for full validation:
tests, consensus compliance, performance.

**Question the tests, don't silently fix them.** If a protocol/EF test itself
appears wrong, write up findings and ask for review — do not quietly patch the test
to make it pass. This ethic carries over unchanged from Erigon's
`erigon-implement-eip`.

### 9. Wrap-up summary

Write a structured summary — packages/modules touched, design decisions, dependent
EIPs/ECIPs, open questions, test coverage status — to **either**:

- `.claude/sprints/log/` (for sprint-tracked modernization/cleanup-adjacent work), or
- a new `specs/<NNN-feature-name>/` Spec Kit artifact (for a net-new feature,
  following the `/speckit-specify` → `/speckit-plan` → `/speckit-tasks` →
  `/speckit-implement` pipeline)

matching fukuii's existing conventions — **not** `agentspecs/`, which is Erigon's own
gitignored-notes convention and has no equivalent meaning here.

## Verify (self-test)

This skill's steps are all existing, already-validated fukuii mechanisms
(`consensus-change-protocol.md`'s routing, the `forge`/`beacon`/`wraith`/`eye`
subagent chain, `gh pr list`/`git log --grep` for prior-work checks, `build.sbt`'s
tagged test aliases) — it composes them into one pipeline rather than introducing
new mechanics, so there is no new script/command to smoke-test independently beyond
confirming each referenced path/command exists (done above during authoring).
