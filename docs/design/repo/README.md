# Fukuii — Repository & Documentation Organization

This is the map for **where non-code files live** in the Fukuii repository, and the home for
**repo-design docs** (documents about how the repository itself is structured). Use it to:

1. **Land a new document** in the right place the first time.
2. **Migrate a keep-file** to its destination during cleanup.

> **Scope:** *non-code* organization. Source code is rebuilt and organized separately under
> [`../../../modules/`](../../../modules/) (the code) and
> [`../../architecture/fukuii-rebuild/`](../../architecture/fukuii-rebuild/) (the live rebuild plan).
> This document does not govern those.

> **This document is living.** It is the first of the repo-design docs under `docs/design/repo/`.
> Every time we move, delete, or rewrite a doc, that operation *tests* this guide — see
> [How this document evolves](#how-this-document-evolves).

---

## Organizing principles

1. **Audience decides placement, not topic.** `docs/` is **contributor documentation by default**
   (for people *building* Fukuii). User/operator-facing docs are a distinct, curated set (the
   published site). A doc's *reader* determines where it lives — not its subject.
2. **Git history is the archive.** Superseded docs are **deleted**, not moved to an in-tree
   `archive/`. Recover from history if a later phase needs one. There is no `docs/archive/`.
3. **Timeless, present-tense.** Durable docs describe the designed end-state as if complete.
   Completion status lives in exactly one place — the rebuild `implementation-reports/` index —
   never scattered as "planned / in-progress" notes.
4. **One canonical file, no wrappers.** A marquee file (`README`, `SECURITY`, `CONTRIBUTING`) is
   the real thing, not a redirect.
5. **Explicit names over vague ones.** Directory names should be self-documenting — `design/repo`
   and `design/client`, not a bare `repo` or `design` that could mean anything.

### Two kinds of "design" — one umbrella, kept apart

All design docs live under `docs/design/`, split by *what* is being designed:

| Directory | Designs… | Holds |
|-----------|----------|-------|
| **`docs/design/client/`** | the **software** | subsystem architecture, consensus, EVM, storage, the multi-network vision; the rebuild graduates here |
| **`docs/design/repo/`** | the **repository** | file organization (this guide), CI conventions, the codebase map, labels/release conventions |

---

## The repository at a glance (non-code)

```
fukuii/
├── README.md · AGENTS.md · CLAUDE.md · CONTRIBUTING.md   # marquee (root, stationary)
├── LICENSE · NOTICE · SECURITY.md                        # legal/marquee (root)
├── docs/                 # ← all documentation (this tree)
├── .agents/              # canonical, tool-agnostic agent protocols + skills (source of truth)
├── .claude/              # Claude Code tooling (agents, skills symlinks, hooks, looping, sprints)
├── .github/              # CI workflows, issue/PR templates, copilot config
├── ops/                  # deployment stacks (barad-dûr, cirith-ungol, gorgoroth, grafana)
├── scripts/              # dev/ops helper scripts (incl. agent-tooling/)
├── docker/ · debian/     # container + package builds
├── hive/                 # ethereum/hive test adapter
├── ets/ · vendor/        # reference-test corpora (see note below — reconcile)
├── specs/                # Spec Kit feature specs (specs/<NNN-feature>/)
└── site/                 # generated docs-site output (gitignored — never edit by hand)
```

---

## Root files (marquee — stationary)

| File | What it is | Rule |
|------|-----------|------|
| `README.md` | Project front door | Timeless; describes the designed client |
| `AGENTS.md` | Portable, tool-agnostic agent context | Domain facts + commands |
| `CLAUDE.md` | Claude Code orchestration (imports `@AGENTS.md`) | Claude-specific only |
| `CONTRIBUTING.md` | Contributor guide | Canonical guide currently at `docs/development/contributing.md`; promotes to root at the docs restructure |
| `LICENSE` · `NOTICE` | Apache-2.0 + lineage/attribution | NOTICE records ETCDEV / IOHK / Fukuii lineage |
| `SECURITY.md` | Vulnerability disclosure policy | Lean, current |

---

## `docs/` — the documentation tree

### Contributor docs (in-repo, for building Fukuii)

| Directory | What lands here |
|-----------|-----------------|
| **`design/client/`** | **Client design** — subsystem design records, fork-divergence, the pluggable-consensus / multi-network vision, consensus references (e.g. MESS). *The rebuild's as-built records graduate here.* |
| **`design/repo/`** | **Repo design** — this file-organization guide, CI conventions, the codebase map, labels/release conventions |
| **`development/`** | Contributor workflow — the contributing guide, `coding-standards/`, CI/CD, testing strategy & KPI methodology, build, branch-protection |
| **`adr/`** | Architecture Decision Records (the decision log) |
| **`research/`** | Reference-client Systemic Review corpus + best-practices library (**PROTECT** — never delete) |
| **`agentic-tooling/`** | Meta-docs about the agent-tooling decisions |

### Published docs (user/operator-facing → the site)

**Target: aggregate under `docs/site/`** (the reth `docs/vocs/` pattern) so the auto-built website
lives in one curated subtree with `mkdocs.yml`'s `docs_dir` pointed at it — a scheduled **docs
website modernization** sprint, not a piecemeal move. Current locations, all destined for `docs/site/`:

`getting-started/` · `runbooks/` · `operations/` · `deployment/` · `api/` · `guides/` · `tools/` ·
`for-developers/` · `for-node-operators/` · `for-operators/` · `index.md` · `MCP.md` ·
`images/` · `stylesheets/` · `hooks/`

The persona nav sections (For Node Operators / For Operators-SRE / For Developers) are retained as
the site's navigation.

### Landing decision guide

| Your new doc is about… | It goes in |
|------------------------|-----------|
| How the **repository** is structured / a repo convention | `design/repo/` |
| How a **client subsystem** is designed / why | `design/client/` |
| A design/architecture **decision** (with alternatives) | `adr/` |
| How to contribute / build / test / CI | `development/` |
| How another client works / a best-practice pattern | `research/` |
| How to install / run / operate a node (user-facing) | the published site (→ `docs/site/`) |
| An RPC/API method reference | `api/` (→ `docs/site/`) |
| A point-in-time investigation / one-off fix write-up | **nowhere in `docs/` — that's ephemeral; keep it in `.local/` or a PR** |

---

## Migration map (current → target)

The tree currently sprawls across ~27 `docs/` subdirs. Consolidate **incrementally** — land new docs
correctly now; migrate keep-files as touched.

| Current | Disposition |
|---------|-------------|
| `architecture/fukuii-rebuild/` | **Stays put during the rebuild** (structural authority); graduates into `design/client/` when complete |
| `architecture/*.md` (FORK_DIVERGENCE, pluggable-consensus-vision, architecture-overview, console-ui) | Migrate → `design/client/` (architecture-overview needs a timeless rewrite first) |
| `analysis/` (keep-backs: EIP-2124, RLPX_HANDSHAKE, CORE_GETH_SNAP_GENESIS) | Migrate the reference-client analyses → `research/`; retire the dir |
| `reports/` (keep-back: MESS_IMPLEMENTATION_SUMMARY) | Migrate → `design/client/` (consensus reference); retire the dir |
| `troubleshooting/` (keep-back: GAS_CALCULATION_ISSUES) | Migrate → `design/client/` or the published site; retire the dir |
| `testing/` (keep-backs: KPI methodology, interop runbooks) | Migrate → `development/`; retire the dir |
| `specifications/` (EVM-compat) | Migrate → `design/client/`; retire the RLP stub |
| published dirs (`getting-started/`, `runbooks/`, `operations/`, `deployment/`, `api/`, `guides/`, `tools/`, `for-*/`, assets) | Aggregate → `docs/site/` (docs website modernization sprint) |
| `releases/` | Optional — GitHub Releases is the changelog |
| **`_config.yml`** | **Delete** — dead Jekyll config; MkDocs (`mkdocs.yml`) is the live generator (verified: `gh-pages.yml` runs `mkdocs build`) |

---

## Non-code outside `docs/`

| Area | What it holds | Rule |
|------|---------------|------|
| `.agents/` | **Canonical** tool-agnostic protocols + skills | Source of truth; `.claude/` symlinks into it |
| `.claude/` | Claude Code tooling — `agents/`, `skills/` (symlinks), `hooks/`, `looping/`, `sprints/` | `sprints/` + operator-local files untracked (separate review) |
| `.github/` | CI workflows, issue/PR templates, copilot config | — |
| `ops/` | Deployment stacks + Grafana dashboards | Live infra; spent campaign *records* belong in git history |
| `scripts/` | Dev/ops helpers; `scripts/agent-tooling/` is **PROTECT** | — |
| `docker/` · `debian/` | Container + Debian package builds | — |
| `hive/` | Hive test adapter | — |
| `ets/` (old) · `vendor/reference-tests/` (new) | Reference-test corpora. `vendor/reference-tests/` = SHA-pinned `ethereum-tests` + the `white-b0x/fukuii-etc-tests` fork (commit `d2c258cf1`); `ets/` = the older retesteth setup it supersedes | **Active rebuild work** (`fukuii-rebuild/plan/test-corpus-hosting.md`) — reconciliation deferred to the rebuild; don't touch either |
| `specs/` | Spec Kit feature specs (`specs/<NNN-feature>/`) | Net-new features only |
| `site/` | Generated docs-site output | Gitignored — never edit by hand |

---

## Deferred to the post-rebuild docs restructure

Target-state moves that wait until the rebuild lands, to avoid churning the tree you're building out of:

- **`architecture/fukuii-rebuild/` → `design/client/`** graduation.
- **`CONTRIBUTING.md` → root** promotion (restores GitHub's PR/issue auto-link).
- **Published-subtree split** — aggregate the site under `docs/site/`, point `mkdocs.yml` there (the
  "docs website modernization" sprint; the [`fukuii-website`](https://github.com/chippr-robotics) repo is the design inspiration).
- **`ets/` → `vendor/reference-tests/`** test-corpus migration — active rebuild work
  (`plan/test-corpus-hosting.md`); the rebuild picks the canonical home.

Until then: land new docs per the tables above, and migrate keep-files opportunistically.

---

## How this document evolves

This guide is a **living spec, refined through use**. The rule:

- Every doc **move / delete / rewrite** is a test of this guide. If the guide already told you where
  the doc goes, it passed. If you had to *guess* — that ambiguity is a **documentation gap**, and
  closing it (adding the rule here) is part of the task, not a follow-up.
- Repo cleanup and this guide **co-evolve**: sorting the repo sharpens the guide; a sharper guide
  makes the next sort unambiguous. The goal is a file tree with **zero placement ambiguity**.
- When a deferred item lands (the restructure moves above), update the tables from "target" to
  "current" in the same change.
