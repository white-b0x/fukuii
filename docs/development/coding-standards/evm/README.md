# EVM domain — scope stub

**Scope:** Code-shape conventions for EVM/consensus code — opcode/fee-schedule table
structure, gas-cost table conventions, state/trie code shape, block-reward and hard-fork
dispatch structure. This is code *shape* guidance (how the code should be organized and
named to stay maintainable and byte-correct), distinct from consensus *semantics*
(what the EVM must compute) — semantics changes still go through the full Consensus-
Critical Change Protocol in `CLAUDE.md`, unaffected by this directory's existence.

**Owning specialist:** `forge` (PoW: ETC/Mordor), `beacon` (PoS: ETH/Sepolia) — both
required for any rule touching shared EVM machinery; either alone for a family-specific
rule (e.g. an ETC-only opcode table convention).

**Authority:** `.claude/repo-references/` ECIPs, EIPs, `ethereum/tests`, and reference
clients under `clients/{nethermind,erigon,...}`; `docs/research/best-practices/evm-clients/`
for the cross-client evidence base. Per the governance rule in
`.../evm-clients/reference-client-crosscheck.md`, an EVM code standard here is **ratifiable
only when grounded in reference-client evidence** — that methodology doc's coverage map
fixes which client is authoritative for what (besu weighted for JVM idioms; geth/core-geth
for EIP/Ethash fidelity; reth **not** citable for EVM/Ethash internals — its interpreter is
the un-vendored `revm` crate).

**Ratified findings that back standards in this domain:**
- Mutable EVM stack/memory/gas buffers, the Ethash mix loop, MPT node hashing, and the
  EIP-4844 `fake_exponential` loop are parity-correct in-place mutation — not debt (backs
  the `var`-in-consensus standard): `.../evm-clients/mutable-state-parity.md`.
- Unchecked consensus invariants must fail loud at the site — backs the `@unchecked`
  standard (legitimate only above a loud-throwing fall-through):
  `.../evm-clients/fail-loud-invariants.md`.

**Status:** net-new. No existing `.agents/protocols/` file covers this ground; first
content mined from `docs/research/best-practices/evm-clients/` (findings above and
`anti-patterns.md`) and the systemic-review documents, per `../README.md`'s "commit-log
mining" / "new synthesis" intake paths.
