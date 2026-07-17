# Migration runbook — `july-mod-sprint` → a clean `fukuii-rebuild` off `upstream/staging`

_The push-button move from the planning branch to the branch the rebuild is actually built on. Written
at the end of the plan phase (all 13 plan docs `READY FOR IMPLEMENTATION`, `README.md` §"Plan status").
This runbook is the **plan** for the move + the per-layer build loop; execute it only after the plan is
READY (it is) and the operator gives go._

## 0. Preconditions (all met as of 2026-07-14)

- Every `plan/*.md` is `READY FOR IMPLEMENTATION` (RX-verified, consolidated, Wave-3 re-check clean —
  `README.md` §"Plan status").
- The three operator decisions are resolved (CryptoBackend seam → build-now; security stack → approved;
  ECIP-1111 → amended).
- The RX evidence (`plan/rx/L{n}.md`) exists as the **per-item implementation spec** — build transcribes
  verified, byte-cited source, it does not re-derive.

## 1. Branch topology & why a fresh branch

| Ref | What it is |
|---|---|
| `upstream/staging` (`chippr-robotics/fukuii`, `b902e5e5…`) | the clean base the rebuild is cut from |
| `origin` (`white-b0x/fukuii`) | the operator's fork (push target) |
| `july-mod-sprint` (current) | the **planning** branch — carries `plan/` + `rx/` + `.claude/` tooling **and** a scaffolded `modules/` where only **L0 is built (sloppily)** |
| `july-fourth` | reference-only: the old IOHK-Mantis-lineage tree (`com.chipprbots.ethereum.*`) |

**Why not just continue on `july-mod-sprint`.** The sprint branch accreted a sloppy L0 build + scaffold
noise before the plan existed. The rebuild's whole thesis is *plan-first, byte-verified*; carrying the
pre-plan state forward would defeat it. Instead: **cut `fukuii-rebuild` fresh from `upstream/staging`,
and bring the good foundation over as a curated, ordered series** — every commit justified by a READY
plan, nothing carried "because it was already there."

```bash
git fetch upstream
git switch -c fukuii-rebuild upstream/staging       # clean base
# curated foundation series applied on top (§2)
```

## 2. The curated foundation series (ordered commits)

Bring over **only** what a READY plan justifies, in dependency order. Each step is one reviewable
commit; do not squash the foundation into one blob.

1. **The plan itself** — `docs/architecture/fukuii-rebuild/plan/` (+ `rx/`) and the SR it rests on
   (`docs/research/clients/`, `docs/research/best-practices/`). This is the build spec; it lands first so
   every later commit cites it.
2. **The agent tooling** — `.claude/` (agents, `.agents/protocols`, skills, hooks, looping) + `AGENTS.md`
   + `CLAUDE.md`, reconciled onto `modules/` per **`setup.md` §10 (F13)**. Agents must load a *current*
   map before they build anything (the L0-charter miss lesson).
3. **The reference repos** — `.claude/repo-references/` (the six vendored clients + `besu-etc` worktree +
   the ECIPs + `ethereum/tests` + spec-kit). The RX pass depends on these being present and pinned.
4. **The build floor** — `setup.md` realized: `project/Dependencies.scala` single-version-source, the
   supply-chain checksum gate, the CI security stack (**operator-approved**: Semgrep + `sbt-dependency-
   submission`→Dependabot + Scala Steward + Gitleaks + Trivy, SHA-pinned, sentinel-wired), the
   `check_baddeps` **enumerated-edge** ratchet + sbt cyclic-edge compile error, and the **R2
   isolation-regression grep** (`object … { var … }` + direct `Config.` + `defaultRegistry`/
   `CollectorRegistry`/`GlobalOpenTelemetry`). `sentinel` owns every dependency add; nothing lands
   un-pinned.
5. **L0 brought to standard** — `modules/{bytes,common,crypto,rlp}` reconciled to `plan/L0.md` READY,
   including the RX build-gates: expose the callable `constantTimeEquals` (RX-L0-16), **build the
   `CryptoBackend` dual-backend seam** (RX-L0-19, operator-decided), and confirm the already-caught
   consensus fixes (F-BN-1 G2 subgroup, F-RLP-1 canonical RLP, J-RLP-1) are green. `forge` co-signs the
   crypto byte-identity KATs; `eye` runs the L0 suite.

Everything from L1 up is **not** carried from `july-mod-sprint` — it is built fresh per §3 (the scaffold
dirs on the sprint branch are discarded, not migrated).

## 3. The per-layer build loop (L1 → L10)

For each layer in DAG order (`L1 domain → L2 storage,trie → L3 evm → L4 execution → L5 consensus →
L6 network → L7 chain,sync → L8 txpool,keystore,observability → L9 rpc,grpc-seam → L10 node,cli`), run
the **per-layer lifecycle** (`README.md`):

0. **RESEARCH & REVIEW** — re-read `plan/L{n}.md` + its `rx/L{n}.md` evidence + its SR slots. The RX doc
   is the impl spec; do not re-derive.
1. **PLAN** — already READY; confirm nothing drifted.
2. **BUILD** — to the plan, byte-for-byte to the cited authorities (two ETC authorities where they
   exist), Scala 3 idiom, besu's Java alongside geth's Go. **Consensus-critical layers (L4/L5, + the
   consensus-adjacent bits of L3/L7/L8) route through the Consensus-Critical Change Protocol** —
   `forge` (PoW/ETC) / `beacon` (PoS/ETH) / `banksy` (client-policy) gate *before* implementation. This
   is where the recorded build-gate items resolve: Olympia EIP-set (ECIP-1121), EIP-7825 cap, ECIP-1017
   canonical reward form + custom-network divisibility, MESS JVM-first scrutiny.
3. **DETAILED AUDIT** — ≥3 lenses, none the builder; the layer's `rx/L{n}.md` findings register must be
   empty (every finding resolved-in-layer or routed-with-a-home). `eye` runs the test tiers.
4. **RECORD** — the as-built `../NN-L{n}-*.md`; build-status → `../README.md` index only.
5. **ALIGN AGENTS** — reorient the layer's charters/protocols/skills to the built state so the next
   layer isn't built blind.
6. **COMMENT HYGIENE** — a code-comment-quality pass over the layer's new comments (a **standing**
   close-out step). Both directions: *remove* dev-narrative / internal dev-vocabulary (`AS-IS`,
   `fukuii/july-fourth`, "the old code did X", migration storytelling) **and** *add* the genuinely-missing
   why / high-level orientation / consensus-spec citations. Concise, default-to-no-comment for
   self-evident code, comment-only + gated (compile + scalafmt/scalafix), per
   [`.agents/protocols/code-style/comments.md`](../../../../.agents/protocols/code-style/comments.md).
   It feeds the **agent-improvement loop**: what the sweep keeps catching tightens `comments.md` + the
   charters, so later layers write fewer bad comments. (The L0–L4 backlog that predates this step is
   cleared as a one-time now-sweep — see [`../README.md`](../README.md).)

**Verification cadence** (`AGENTS.md`): `sbt compile-all` after every file; targeted `testOnly` after
each logic phase; `testEssential` (background, `sbt-run.sh`) as the pre-push gate; the CI ratchets
(check_baddeps + R2 grep + SAST) green before merge.

## 4. Gates that must be green before a layer is "done"

- `sbt compile-all` clean; the module DAG compiles (an upward edge = compile error).
- The layer's reference vectors pass (`rx/L{n}.md` DoD; `ethereum/tests` both fork schedules for
  consensus layers; hive for L5/L9).
- Consensus-critical sign-off recorded (forge/beacon/banksy per the protocol).
- The `rx/L{n}.md` findings register is empty; the ledger + requirements cells the layer owns are
  satisfied.
- **Comment hygiene done** (step 6): no `AS-IS`/`july-fourth`/dev-narrative in the layer's source
  comments; genuinely-missing why/orientation/citations added — concise, code-specific
  (`.agents/protocols/code-style/comments.md`).
- CI: `formatCheck` + `check_baddeps` + R2 isolation grep + Semgrep/Trivy + auto-doc drift — all green.

## 5. Safety & rollback

- The foundation series is small, ordered commits — a bad step is `git revert`-able without unwinding a
  layer build.
- `july-mod-sprint` and `july-fourth` are preserved untouched as the planning branch and the
  reference-implementation branch; the rebuild never force-pushes over them.
- Consensus code is a one-way door — the Consensus-Critical Change Protocol (plan before edit, forge/
  beacon gate, byte-perfect validation) is mandatory, not optional, at build.
- Dependency changes are sentinel-gated end-to-end; the `resolution-age` / Dependabot cooldown gate the
  supply chain even mid-build.

## 6. Definition of done (the whole migration)

`fukuii-rebuild` builds L0→L10 clean, every layer gated on its READY plan and its consensus sign-offs,
the CI ratchets green, and the as-built records (`../NN-*.md`) written — a from-scratch, byte-verified,
plan-first client with no pre-plan residue. Then the branch is the new trunk.
