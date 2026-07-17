# Test corpus hosting — where fukuii's multi-network fixture corpora live

_Complements [`testing.md`](testing.md), which owns the test **engine** (the R10 ratchets, the
`Case`/`Suite` scaffold, the DoD methodology). This doc owns the orthogonal concern that engine
doc deliberately does not: **provenance, hosting, and lifecycle** — which repo a fixture corpus's
bytes live in, who is allowed to change them, and how they sync. Grounded in the
`authoring-vs-oracle-vectors` principle (author only where no external oracle exists; use the
oracle everywhere one does), `reference-client-authority.md`, and Ratchets
2/5/7 of `testing.md` §3, which this doc gives a concrete home to. Owner: `warden`
(build-infrastructure; consensus-critical corpus *content* — Olympia generation, ETC fixture
correctness — stays `forge`-owned per `testing.md` §8)._

## 0. Scope & role

`testing.md` §3 Ratchets 2, 5, and 7 each assume a fixture corpus *exists somewhere reachable* —
nonzero-count assertion (R2), Olympia generation output (R5), and a content-hash manifest (R7)
all presuppose a hosting decision. This doc is that decision. It does not touch the harness code
that consumes a corpus (that stays per-layer: BlockchainTests/GeneralStateTests at L4, hive at
L5/L9, DifficultyTests/PoW at L5 — see `testing.md` §5). It is build-infrastructure — a
`.gitmodules` + repo-layout decision, repointable at any time without touching layer code — and
gates no layer's close-out.

## 1. The core reframe — two lifecycles, not one monorepo

fukuii's test sources split into two categories with opposite maintenance needs, and conflating
them into one corpus produces the wrong sync policy for at least one side:

| | TRACKED upstream | AUTHORED / PRESERVED (ours) |
|---|---|---|
| Examples | `ethereum/tests`, `hive` | Olympia vectors, ETC PoW/difficulty/Etchash/MESS, ethash fixtures upstream deletes |
| Who changes it | external maintainers, actively | **only fukuii** |
| We want to | pull new tests deliberately, SHA-pinned | freeze + guarantee survival |
| Right vehicle | submodule → upstream | real content in a repo we control |
| Size | huge, fast-moving | small, high-value |

A corpus's row in this table is decided once, at addition time, and stays fixed — a fixture does
not migrate rows except via the one documented transition in §3 (upstream deletion → preserved).

## 2. Why fukuii owns a corpus repo at all

Once core-geth deprecates, fukuii is the primary maintained ETC client — the last line of defense
for PoW conformance vectors (`authoring-vs-oracle-vectors`: "the same role core-geth held as the
lone ETC client"). go-ethereum/`ethereum/tests` has been pruning pre-Merge content; if a
pre-Merge ethash `GeneralStateTest`/`DifficultyTest` is deleted upstream and fukuii has not
preserved it, it is gone from the practical ecosystem — no maintained client keeps a copy. A repo
fukuii controls, holding real fixture content rather than another layer of submodule
indirection, is what makes "preserve" mean something durable rather than "preserve until the
submodule we pointed at also disappears."

This is the same provenance-control thesis as `authoring-vs-oracle-vectors`' vendoring corollary:
vendor the ETC corpus as a verifiable byte-copy while core-geth is still live — that is the only
window in which the copy can be proven identical to the authoritative source.

## 3. Topology

- **`white-b0x/fukuii-tests`** — a repo fukuii controls, holding real fixture content (not a
  submodule of submodules), partitioned by network/purpose:
  - `etc/` — folds in the existing fukuii ETC mirror (currently `white-b0x/fukuii-etc-tests`,
    submoduled at `vendor/reference-tests/etc-tests`).
  - `olympia/` — fukuii-authored ECIP-1111/1112/1121/1122 vectors (Ratchet 5's generator output).
    fukuii is the sole reference implementation here; see `authoring-vs-oracle-vectors`'
    spec-anchored-never-implementation-anchored discipline — a vector is derived from the ECIP
    spec text and cross-checked against every available draft implementation, never
    reverse-engineered from fukuii's own output.
  - `hive/` — fukuii client configs + custom simulators.
  - `preserved/` — fixtures upstream has deleted that ETC/PoW still needs (§5).

- **`ethereum/tests` stays a direct pinned submodule** pointed at upstream. It is actively
  maintained for the PoS surface the ETH family tracks; mirroring it wholesale into
  `fukuii-tests` costs size and sync burden for no gain. A fixture graduates into
  `fukuii-tests/preserved/` only when upstream actually deletes something fukuii needs — never
  proactively.

## 4. Lifecycle tags (the drift-automation answer)

Every corpus source carries one lifecycle tag, and the tag — not manual judgment per sync run —
decides whether drift-automation looks upstream at all:

| Tag | Meaning | Sync behavior |
|---|---|---|
| **TRACKED** | Externally maintained, actively changing (`ethereum/tests`, `hive`) | Nightly check upstream → open a sync PR on drift |
| **FROZEN** | Externally authored but no longer maintained (core-geth ETC values) | Never syncs — inert by design; only fukuii touches its local copy |
| **AUTHORED** | fukuii-originated (Olympia vectors) | Never syncs to an upstream — only fukuii touches it |

FROZEN and AUTHORED sources are inert to drift-automation by construction — only TRACKED sources
can drift, so only they need a nightly check. This is the mechanism that keeps the two-lifecycle
split in §1 from silently blurring back into one policy applied to everything.

## 5. Near-term action — `ethereum/tests` PoW history recovery

Time-sensitive, do while the git history still has it: audit `ethereum/tests`' git history for
ethash/PoW fixtures (`GeneralStateTests`, `DifficultyTests`, `BlockchainTests` with a PoW seal)
deleted during the PoS transition, and preserve the recoverable ones into
`fukuii-tests/preserved/`. First concrete step: `git log --diff-filter=D` over the relevant
fixture directories in a local `ethereum/tests` clone, to enumerate what was removed and when,
before deciding what's recoverable.

## 6. `.gitmodules` cleanup (recorded, not fixed here)

`.gitmodules` currently carries redundant/legacy test-corpus entries:

- `ets/tests` and `vendor/reference-tests/ethereum-tests` both point at `ethereum/tests` —
  duplicate submodules of the same upstream.
- `vendor/reference-tests/etc-tests` points at the existing fukuii ETC mirror
  (`white-b0x/fukuii-etc-tests`), which folds into `fukuii-tests/etc/` per §3.

Consolidate to one canonical `ethereum/tests` submodule path as part of the `fukuii-tests`
build-out (§7) — not fixed in this doc; recorded here so it does not evaporate.

## 7. Scheduled work

- **Build out `white-b0x/fukuii-tests`** — create the repo, the `etc/`/`olympia/`/`hive/`/
  `preserved/` partition, and fold in the existing `fukuii-etc-tests` content.
- **Wire drift-automation** — the nightly TRACKED-source check (§4) against `ethereum/tests` and
  `hive`.
- **Consolidate `.gitmodules`** — remove the duplicate `ethereum/tests` submodule entry and
  repoint `vendor/reference-tests/etc-tests` at `fukuii-tests/etc/` (§6).
- **`ethereum/tests` PoW history-recovery audit** — the `git log --diff-filter=D` sweep (§5),
  time-sensitive while upstream history is still intact.

All four are warden-owned build-infrastructure; none gates a layer's close-out (§0). Olympia
vector *content* generation (Ratchet 5's generator) stays forge-owned and consensus-critical per
`testing.md` §8 — this doc only owns where the generator's output is hosted once produced.

## Layer boundaries

- **This doc owns:** corpus topology (§3), lifecycle tagging and drift-automation policy (§4),
  the PoW history-recovery action (§5), and the `.gitmodules` cleanup record (§6).
- **This doc does NOT own:** the test engine, ratchets, or DoD methodology (`testing.md` §3/§6);
  per-layer harness code (`testing.md` §5); Olympia vector *content* correctness (forge,
  consensus-critical, `testing.md` §8); the per-network/per-concern reference-client authority
  model (`reference-client-authority.md`, `systemic-review-protocol.md`).
- **No module.** Like `testing.md`, this is cross-cutting infrastructure — it builds no
  `modules/<name>` and sits at no DAG layer.
