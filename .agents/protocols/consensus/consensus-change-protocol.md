# Consensus Change Protocol

Any change touching consensus-critical code requires specialist review before
implementation. This protocol prevents behavior changes hidden inside "cleanup"
or migration commits from silently altering chain state, wire behavior, or
cryptographic correctness.

Used by: ALL agents (hard stop — non-negotiable)
Referenced by: `fukuii/CLAUDE.md`, loom.md, wraith.md, mithril.md, banksy.md

**Vocabulary counterpart:** the naming trap this protocol's `OlympiaOpCodes` mentions
allude to (ETH's Cancun path reusing ETC's own fork name — see `PARITY-02`,
`.claude/sprints/QUEUE.md`) is a code-symbol instance of a broader naming discipline —
see `nomenclature.md` for the general rule: neutral ecosystem vocabulary at the shared
level, network fork/event names as family-local instance labels only.

---

## What counts as consensus-critical

**FORGE scope — ETC (mainnet) / Mordor (testnet) and any future PoW-family network:**
- `consensus/pow/` — Ethash, PoW block validation, mining
- `consensus/validators/pow/` — PoW-specific validator executors
- Anything touching: ECIP fork blocks, ECIP-1017 rewards, Ethash, ETChash, `forBlock()`, `OlympiaOpCodes`

**BEACON scope — ETH (mainnet) / Sepolia (testnet) and any future PoS-family network:**
- `consensus/engine/` — Engine API domain, EngineApiController, EngineApiService
- PoS consensus, timestamp fork dispatch, EIP execution
- Withdrawals, blob transactions (EIP-4844), execution payload encoding
- `forTimestamp()`, `OsakaOpCodes`

**FORGE + BEACON jointly — shared execution-layer (applies to all supported networks):**
- `consensus/validators/std/` — standard gas/block validation runs on every network's EL
- `vm/` — EVM opcode execution, gas computation
- `crypto/` — hashing, signing, key recovery
- `domain/` — Block, BlockHeader, Transaction, Account, Receipt types
- `network/p2p/messages/` — RLP wire encoding/decoding
- `db/storage/` — state trie, receipt storage, block persistence

**P2P / wire protocol (route to HERALD):**
- `network/rlpx/` — RLPx handshake, Snappy, framing
- `network/p2p/` — devp2p protocol, peer management
- ETH68/69/70/71 message types, ForkId computation
- SNAP protocol messages and encoding
- DNS discovery, ENR records

**Storage layer (route to VAULT):**
- `db/` — RocksDB config, WAL, batch writes, LRU cache
- EphemDataSource, DataSource contracts
- State trie reads/writes that aren't purely domain-level

**Client-layer policy — NOT consensus (route to BANKSY):**
- `transactions/PendingTransactionsManager.scala`,
  `transactions/SignedTransactionsFilterActor.scala` — mempool/txpool admission
  gates (ECIP-1122 `MIN_MINER_TIP` floor and similar)
- `consensus/blocks/BlockGeneratorSkeleton.scala` — block-production transaction
  selection/ordering (the production-side redundant tip-floor enforcement)
- `utils/BlockchainConfig.scala` fields `minTip`, `spiralGasTarget`,
  `olympiaGasTarget` and the `min-tip`/`*-gas-target` keys in
  `conf/base/chains/*-chain.conf` — operator-tunable client parameters, not
  consensus rules
- `consensus/mess/ArtificialFinality.scala`, `consensus/mess/MESSConfig.scala`,
  and the reorg-decision path in `ledger/BranchResolution.scala` — MESS /
  ECIP-1100 subjective fork-choice scoring (see co-review note below)

---

## The state-root litmus — forge/beacon vs. banksy

Before routing a client-layer-looking change to forge, beacon, or banksy, apply
this test: **does the change alter the state root?**

- **YES** — it's forge (PoW) or beacon (PoS) territory, even if the change
  looks like "just a config parameter." Balances, storage, emission, and
  treasury credits are consensus regardless of how the value is sourced.
- **NO, and the parameter is operator-tunable without a hard fork** — it's
  banksy's: mempool/txpool admission, transaction-selection ordering, tip/gas
  floors, gas-target schedules, subjective fork-choice (MESS). ECIP-1122 itself
  chooses chain configuration over consensus for exactly this reason
  ("operator-configurable minimums allow the network to adapt ... without
  requiring a hard fork").

**Worked example — the canonical case for this litmus**: ECIP-1122's
`MIN_MINER_TIP` (1 gwei) and ECIP-1111's `MIN_BASE_FEE` (1 gwei) are the same
numeric floor from the same Olympia fee model, but opposite ownership —
`MIN_BASE_FEE` is redirected to the ECIP-1112 Treasury (a balance change, state
root, forge's), `MIN_MINER_TIP` is an admission/selection gate that changes
which transactions are considered, not any account balance (banksy's).

### Bidirectional co-ownership (banksy ↔ forge)

A proposal's *concerns* can split across the litmus, producing co-review in
both directions — this is not a one-way handoff:

- **banksy owns, forge co-signs**: MESS / ECIP-1100. It is subjective
  (score depends on local observation timing, not chain data a third party can
  verify) and does not touch any state root, so it's banksy's to edit — but its
  purpose is reorg/51%-attack resistance, so **no MESS change lands without a
  forge co-review**, regardless of how small the diff looks.
- **forge owns, banksy is a required consult**: ECIP-1017 emission
  (`BlockRewardCalculator.scala`) and ECIP-1111 base-fee floor/Treasury routing
  (`BlockPreparator.scala`). These are state-affecting and forge's to implement,
  but they define the network security-budget economics that banksy's
  ECIP-1122 tip floor is sized against — banksy must be in the room before
  either lands, even though banksy does not edit the code.

The shared overlap zone across both directions is *network security economics*
(ECIP-1017 emission ↔ ECIP-1122 tip floor ↔ MESS reorg resistance) — forge and
banksy approach it from opposite sides.

---

## The hard stop rule

**Before editing any file in the paths above:**

1. STOP — do not write a single line of implementation
2. Identify which specialist applies (FORGE / BEACON / HERALD / VAULT)
3. Produce an impact analysis: what changes, what invariants must hold, what tests cover it
4. Get specialist review or hand off entirely

**This applies even for "obvious" changes:**
- Renaming a variable in `consensus/` → FORGE review
- Reformatting a method in `vm/` → FORGE review  
- Adding a log line in `network/p2p/messages/` → HERALD review
- "It's just a cleanup" is not an exception

**A batch's own `Gate: none` status does not extend to a file discovered mid-implementation
that wasn't in its original scope.** If, while implementing a gate-free batch, you find you
need to fix something in a path from the list above that the batch's KNOWN FILE LIST never
named, that discovery is a new, separate consensus-critical edit — stop and apply the hard-stop
rule to it specifically, even though the rest of the batch never needed a gate. Don't let the
batch's overall gate-free framing carry over to scope it never covered.

**Incident:** Batch 1.5 (2026-07-05, `Gate: none`, non-consensus cleanup) had mithril write a
requested regression test that hung the JVM, exposing a real deadlock in `domain/ChainId.scala`
and `domain/Timestamp.scala` — both `domain/`, both FORGE+BEACON joint scope. The fix was
applied immediately, in the same session, without pausing for this protocol's gate — reasonable
in the moment (the fix looked obviously behavior-preserving, matching an already-established
pattern in 10 sibling files) but exactly the situation this protocol exists to catch regardless
of how confident anyone is. FORGE and BEACON reviewed retroactively before commit and confirmed
no issue, but the review should have happened before the fix, not after.

---

## Routing table

| Symptom / Change | Agent |
|-----------------|-------|
| PoW fork activation, block rewards, Ethash, ETChash, `forBlock()` | FORGE |
| Engine API, PoS, timestamp forks, `forTimestamp()` | BEACON |
| EVM opcodes, gas computation, `vm/` | FORGE + BEACON |
| Shared execution-layer validation, `consensus/validators/std/` | FORGE + BEACON |
| Domain types: Block, BlockHeader, Transaction, Receipt | FORGE + BEACON |
| devp2p handshake, wire messages | HERALD |
| SNAP protocol, ETH68/69/70/71 | HERALD |
| RocksDB, WAL, state persistence | VAULT |
| RLP encoding of domain types | FORGE + BEACON + HERALD |
| New PoW-family network added | FORGE |
| New PoS-family network added | BEACON |
| Mempool/txpool admission gates, tip/price floors | BANKSY |
| Block-production transaction selection/ordering | BANKSY |
| Gas-target schedule (production ceiling, not header validation) | BANKSY |
| MESS / ECIP-1100 subjective fork-choice scoring | BANKSY (owns) + FORGE (co-signs) |
| ECIP-1017 emission, ECIP-1111 base-fee floor/Treasury routing | FORGE (owns) + BANKSY (required consult) |

---

## What agents CAN do without specialist review

In consensus-adjacent files (not consensus-critical):
- Read and analyze — always safe
- Add logging with no logic change — flag it but proceed
- Fix `log.warning` → `log.warn` — safe (API rename, no logic)
- Fix unused imports — safe

If uncertain: treat it as consensus-critical and route to specialist. The cost
of an unnecessary review is low. The cost of a silent consensus bug is a chain fork.

---

## Inline cleanup in consensus files

The inline-cleanup protocol (`inline-cleanup.md`) applies EVERYWHERE except
consensus-critical paths. In those paths: flag patterns for specialist review,
do not fix opportunistically. Write the flag in the continuation file or
surface it to the user.

---

## Post-implementation caller verification

After any consensus-boundary change, before closing the task, grep for callers
of the changed types in non-consensus files:

```bash
grep -rn "ChangedType\|ChangedMessage\|newMethodName" src/main/ --include="*.scala" \
  | grep -v "consensus/\|vm/\|crypto/\|domain/\|network/p2p/messages/"
```

For each caller not in scope for this task:
- If the caller uses Classic bridge patterns (`.toClassic`, `ctx.toClassic.sender()`) against the
  updated API → add a Chase entry (`type: CLASSIC`) to `.claude/sprints/QUEUE.md` so the bridge is not forgotten
- If the caller has a stale method signature after the change → surface to user for triage

Consensus changes often require coordinated updates in the surrounding non-consensus call sites.
Skipping this step leaves Classic bridge debt that compiles fine but accumulates silently.
