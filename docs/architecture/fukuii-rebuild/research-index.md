# Research-asset → layer index

_The canonical map from the SR/research library to the rebuild layers each asset informs. Created by
[`plan/coherence-pass-02.md`](plan/coherence-pass-02.md) WB-R5 after a coverage audit
(`.local/scratch/research-linkage-audit.sh`) found the library systematically **under-linked** — 6 of 10
`topics/` catalogs and nearly the entire `best-practices/` library were **ZERO-referenced** by any plan
or RX doc, which directly caused scope gaps (L5 faker/dev-seal, L10 Hoodi testnets, L6 blacklist policy).
This index makes the library first-class: **every layer plan and RX doc links here; build-time consults
its row before the layer is marked GREEN.**_

## How to use

- **Layer plan doc** (`plan/L{n}.md`): its header cites the governing SR observation(s) + the topics /
  best-practices in its row below. Consult them before finalizing a decision they inform.
- **RX doc** (`plan/rx/L{n}.md`): verify against reference-client source **and** cite the binding SR
  observation for the layer (many RX docs currently omit it — see the coverage note). The RX method
  itself is grounded in [`best-practices/evm-clients/reference-client-crosscheck.md`](../../research/best-practices/evm-clients/reference-client-crosscheck.md).
- **Paths** are repo-relative from this file (`docs/architecture/fukuii-rebuild/`): SR research lives under
  [`../../research/`](../../research/); AS-IS snapshots are LOCAL under `.local/docs/research/clients/fukuii/`.

## Per-layer research map

| Layer | Binding SR observation(s) | Topics (cross-cutting) | best-practices (build-time idiom/pattern) |
|---|---|---|---|
| **L0** bytes·common·crypto·rlp | `primitives` | — | `evm-clients/constant-time-comparison`, `scala/type-safety`, `evm-clients/anti-patterns`, `evm-clients/fail-loud-invariants` |
| **L1** domain | `primitives`, `accounts-signer` | — | `evm-clients/mutable-state-parity`, `scala/type-safety` |
| **L2** storage·trie | `state-trie`, `storage-persistence` | `snap-porting-reference` (storage-format ↔ heal coupling) | `evm-clients/fail-loud-invariants`, `evm-clients/error-recovery` |
| **L3** evm | `evm`, `primitives` | — | `evm-clients/mutable-state-parity`, `scala/type-safety`, `evm-clients/anti-patterns` |
| **L4** execution | `block-execution`, `exec-extensions` | — | `typelevel/patterns` (IO/fs2), `evm-clients/fail-loud-invariants`, `evm-clients/mutable-state-parity`, `evm-clients/error-recovery` |
| **L5** consensus | `consensus-engines`, `block-production`, `accounts-signer` (sealer) | `consensus-methods-catalog`, `consensus-pow-cpu-dev-and-deprecated` ★, `consensus-poa-and-etc-testnets` ★, `consensus-l2-rollup-sidechain`, `pos-networks-and-testnets`, `mining-protocol-evm` | `pekko/typed-patterns`, `pekko/concurrency`, `evm-clients/error-recovery` |
| **L6** network | `networking-p2p` | `wire-protocol-evolution` | `evm-clients/p2p`, `evm-clients/peer-disconnect-blacklist-policy` ★, `evm-clients/constant-time-comparison` (ECIES MAC), `pekko/typed-patterns`, `pekko/concurrency` |
| **L7** sync | `sync`, `state-trie`, `storage-persistence`, `cross-cutting-themes`, `historical-distribution` | `snap-heal-low-peers` ✓, `snap-porting-reference` ✓, `wire-protocol-evolution` | `evm-clients/snap-sync`, `evm-clients/error-recovery`, `pekko/concurrency`, `pekko/typed-patterns` |
| **L8** txpool·keystore·observability | `observability`, `txpool`, `accounts-signer` (keystore) | — | `scala-security-tooling-2026` (keystore/security), `evm-clients/fail-loud-invariants` |
| **L9** rpc·agentic·grpc-seam | `rpc-api`, `cl-engine` (Engine API) | `mining-protocol-evm` (getWork RPC) | `typelevel/patterns` (IO), `pekko/typed-patterns`, `evm-clients/constant-time-comparison` (JWT verify) |
| **L10** node·cli | `node-lifecycle`, `multi-network`, `build-deps` | `pos-networks-and-testnets` ★, `consensus-poa-and-etc-testnets` (testnet inventory) | `scala-security-tooling-2026`, `codebase-audit` |

★ = was ZERO-referenced and maps to a Workstream-B scope gap (now linked from the layer header).
✓ = added this session (Workstream A).

**Cross-cutting (all layers + all RX):** SR `cross-cutting-themes`; `best-practices/evm-clients/anti-patterns`,
`.../fail-loud-invariants`, `.../reference-client-crosscheck` (the RX methodology), `scala/type-safety`,
`codebase-audit`, `scala-security-tooling-2026` (security, sentinel-gated). Each layer also has an AS-IS
snapshot at `.local/docs/research/clients/fukuii/<slot>.md` and per-client SR docs under
`../../research/clients/{go-ethereum,besu,core-geth,nethermind,erigon,reth}/`.

## Coverage status (2026-07-14 audit)

- **Fixed this pass:** coherence-pass back-links added to all 8 layer docs; ★ topics linked from L5/L10/L6
  headers; L7 snap docs (✓) linked in Workstream A.
- **Still open (WB-R5 linkage pass):** wire the remaining `topics/` (`consensus-l2-rollup-sidechain`,
  `consensus-methods-catalog`, `mining-protocol-nonevm`) and the `best-practices/` library
  (`pekko/typed-patterns`, `pekko/concurrency`, `scala/type-safety`, `typelevel/patterns`,
  `evm-clients/anti-patterns`, `.../reference-client-crosscheck`, `codebase-audit`,
  `scala-security-tooling-2026`) into each layer's header + build-consult list per the rows above; and add
  the binding SR-observation citation to each RX doc that omits it (`block-execution`, `rpc-api`,
  `block-production`, `cl-engine`, `build-deps`, `primitives`, `testing` were `rx:0`). This index is the
  should-link source of truth for that pass.

## Full asset inventory

- **SR observations** (`../../research/clients/observations/`, 21 slots — the binding per-concern input):
  accounts-signer · block-execution · block-production · build-deps · cl-engine · consensus-engines ·
  cross-cutting-themes · evm · exec-extensions · historical-distribution · multi-network · networking-p2p ·
  node-lifecycle · observability · primitives · rpc-api · state-trie · storage-persistence · sync ·
  testing · txpool.
- **SR topics** (`../../research/clients/topics/`, cross-cutting catalogs): consensus-l2-rollup-sidechain ·
  consensus-methods-catalog · consensus-poa-and-etc-testnets · consensus-pow-cpu-dev-and-deprecated ·
  mining-protocol-evm · mining-protocol-nonevm · pos-networks-and-testnets · wire-protocol-evolution ·
  snap-heal-low-peers · snap-porting-reference.
- **best-practices** (`../../research/best-practices/`): `evm-clients/{anti-patterns, constant-time-comparison,
  error-recovery, fail-loud-invariants, mutable-state-parity, p2p, peer-disconnect-blacklist-policy,
  reference-client-crosscheck, snap-sync}` · `pekko/{concurrency, typed-patterns}` · `scala/type-safety` ·
  `typelevel/patterns` · `codebase-audit` · `scala-security-tooling-2026`.
- **fukuii AS-IS** (LOCAL, `.local/docs/research/clients/fukuii/`): one `<slot>.md` per subsystem +
  `history-pow-etc` + `_phase3-findings-rollup`.
