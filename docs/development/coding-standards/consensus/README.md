# Consensus domain — scope stub

**Scope:** Code-shape conventions for consensus-critical paths (`consensus/`, `vm/`,
`crypto/`, `domain/`) that are NOT the consensus rules themselves — determinism discipline,
byte-exact encoding patterns, block-number vs. timestamp fork-dispatch structure, and the
shape of EIP/ECIP implementations. The consensus *rules* (what a fork changes) live in the
project constitution and are gated by
`.agents/protocols/consensus/consensus-change-protocol.md` (operational, stays a protocol);
this domain covers how that code is *written*, not what it computes. Distinct from
`../evm/` (EVM execution layer: opcodes, gas, interpreter).

**Owning specialist:** `forge` (PoW), `beacon` (PoS).

**Authority:** the vendored reference clients under `.claude/repo-references/clients/`
(core-geth, go-ethereum, besu, erigon, reth, nethermind) plus `.claude/repo-references/EIPs`
and `.claude/repo-references/ECIPs`. Per the governance rule in
`docs/research/best-practices/evm-clients/reference-client-crosscheck.md`, a consensus code
standard here is **ratifiable only when grounded in reference-client evidence** — that
research directory holds the evidence tables and the client-authority weighting (besu for
JVM idioms, geth/core-geth for EIP/Ethash fidelity).

**Ratified standards in this domain:**
- **`mutable-state.md`** — the `var`-in-consensus rule: three sanctioned categories
  (A perf/fidelity hot-loop, B stateful field incl. guarded Engine-API state, C security
  constant-time), the FIX category, the hot-path criterion, and the two-part grep ratchet.

**Ratified findings that back those standards:**
- Mutable hot-path buffers + imperative counters, and guarded cross-request Engine-API
  state, are parity-correct — not debt (backs `mutable-state.md`):
  `docs/research/best-practices/evm-clients/mutable-state-parity.md`.
- Constant-time comparison required at auth/MAC sites, plain comparison elsewhere
  (conditional standard): `.../evm-clients/constant-time-comparison.md`.
- Unchecked consensus invariants must fail loud at the site — backs the `@unchecked`
  standard (legitimate only above a loud-throwing fall-through):
  `.../evm-clients/fail-loud-invariants.md`.

**Status:** net-new — no existing protocol file covers consensus code *style* (the
consensus-change-protocol is routing, not style). First content mined from
`docs/research/best-practices/evm-clients/` (findings above) and the systemic-review
documents.
