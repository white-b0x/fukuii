# Fork Divergence

This document explains how fukuii diverges from its own upstream lineage (IOHK's
Mantis) and from single-network sibling clients, using the same three-section
shape as core-geth's `docs/core/index.md` (see
`docs/research/best-practices/evm-clients/repo-patterns/core-geth/repo-hygiene-pattern.md`):
what fukuii adds beyond that lineage, the architectural mechanism that makes it
possible (with a concrete before/after code comparison), and an honest list of
what fukuii deliberately does not attempt to match.

## Additional Features

fukuii is a fork of IOHK's Mantis client, repackaged under `com.chipprbots` and
generalized well beyond Mantis's original single-chain (Ethereum Classic) scope:

- **Two independent consensus families in one codebase.** fukuii runs
  Proof-of-Work networks (currently Ethereum Classic — mainnet and Mordor) with
  block-number fork dispatch, and Proof-of-Stake networks (currently Ethereum
  mainnet and Sepolia) with timestamp fork dispatch, from the same
  `EvmConfig`/`BlockchainConfig` machinery — see the Divergent Design section
  below. Mantis, and ETC-only sibling clients such as core-geth, only ever had
  to resolve one dispatch axis; fukuii resolves two, in the same binary.
- **Pekko Classic → Typed migration.** fukuii is mid-migration from Pekko
  Classic actors (the Akka-lineage model Mantis was built on) to Pekko's Typed
  API — command ADTs, `replyTo` patterns, explicit timers — tracked per-actor
  via the `loom` subagent and `.claude/agent-protocols/pre-migration-checklist.md`.
  This is an ongoing structural rewrite Mantis's actor layer never underwent.
- **Scala 3 modernization.** fukuii has moved its domain layer onto Scala 3
  constructs Mantis (a Scala 2 codebase) never had access to: opaque types for
  domain primitives (`BlockNumber`, `Timestamp`, `UInt256`, etc. — see
  `EvmConfig.scala`'s imports), enums, and `given`/`using` context parameters,
  per `.claude/agent-protocols/scala3-style.md`'s S1–S11 ratchets.
- **The opaque-type domain layer.** Values that were likely raw `BigInt`/`Long`
  wrappers or untyped case-class fields in Mantis-era code are now
  distinct opaque types (`BlockNumber`, `Timestamp`) that the compiler keeps
  from being accidentally interchanged — visible directly in `EvmConfig.forBlock`'s
  signature, which takes a `BlockNumber` and, in its timestamp-aware overload, a
  separate `Timestamp`, rather than two interchangeable numeric parameters.

## Divergent Design

The mechanism underneath all of the above is `EvmConfig.forBlock` in
`src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`. Quoting the two
overloads directly (`EvmConfig.scala:29-58`):

**Before — single-axis, block-number-only dispatch** (the shape a
single-consensus-family, ETC-only client needs, and the shape this method had
before fukuii's PoS support existed):

```scala
def forBlock(blockNumber: BlockNumber, blockchainConfig: BlockchainConfig): EvmConfig =
  forBlock(blockNumber, BlockchainConfigForEvm(blockchainConfig))
```

**After — a second overload layering timestamp-based fork overrides on top,
without touching the block-number path at all:**

```scala
def forBlock(blockNumber: BlockNumber, timestamp: Timestamp, blockchainConfig: BlockchainConfig): EvmConfig =
  var config = forBlock(blockNumber, blockchainConfig)
  // Apply timestamp-based fork upgrades for ETH chains
  if blockchainConfig.isShanghaiTimestamp(timestamp) then
    config = config.copy(
      opCodeList = SpiralOpCodes, // Adds PUSH0 (EIP-3855)
      eip3651Enabled = true, // Warm COINBASE
      eip3860Enabled = true // Initcode metering
    )
  if blockchainConfig.isCancunTimestamp(timestamp) then
    config = config.copy(
      opCodeList = OlympiaOpCodes, // Adds TSTORE/TLOAD/MCOPY/BLOBHASH/BLOBBASEFEE
      feeSchedule = new FeeSchedule.OlympiaFeeSchedule,
      eip6780Enabled = true // SELFDESTRUCT restriction
    )
  if blockchainConfig.isPragueTimestamp(timestamp) then
    config = config.copy(
      feeSchedule = new FeeSchedule.PragueFeeSchedule // EIP-7623: increased calldata costs
    )
  if blockchainConfig.isOsakaTimestamp(timestamp) then
    config = config.copy(
      feeSchedule = new FeeSchedule.OsakaFeeSchedule,
      opCodeList = OsakaOpCodes // EIP-7939: CLZ opcode
    )
  config
```

The timestamp-aware overload *calls* the block-number overload first and then
layers ETH's post-merge, timestamp-gated forks (Shanghai/Cancun/Prague/Osaka) on
top of whatever `EvmConfig` the block-number resolution already produced. Callers
that only care about a PoW network (ETC/Mordor) keep using the two-argument
overload and never see a timestamp parameter at all; callers driving a PoS
network (ETH/Sepolia) call the three-argument overload. Neither caller has to
know about the other axis — this is precisely the same load-bearing idea as
core-geth's `ChainConfig.IsEnabled(feature, blockNumber)`: a single composable
dispatch point that lets two genuinely different fork-activation models
(block-number vs. timestamp) share one `EvmConfig` builder pipeline instead of
each network family requiring its own parallel implementation of fork
resolution.

**A second, ETC-specific divergence lives in the block-number overload itself**
(`EvmConfig.scala:62-97`): the `transitionBlockToConfigWithPriorityMapping` list
resolves the highest activated fork purely by block number and an explicit
priority tiebreak, and it has to account for an ETC-only fork-ordering quirk
(`EvmConfig.scala:63-67`):

```scala
// When ETC-specific forks (Spiral, Mystique) activate AFTER Olympia, the chain follows
// standard Ethereum fork schedule where London only activates EIP-1559/3529/3541.
// On ETC, Spiral < Olympia in the fork sequence, so Olympia bundles all EIPs.
val etcForksDisabled = blockchainConfig.spiralBlockNumber > blockchainConfig.olympiaBlockNumber
val olympiaBuilder = if etcForksDisabled then LondonConfigBuilder else OlympiaConfigBuilder
```

This exists because ETC's own hard-fork ordering (Spiral, Mystique) does not
match ETH's mainnet ordering relative to an Olympia-equivalent activation
height — a divergence entirely internal to the PoW family's own fork schedule,
independent of the block-number-vs-timestamp axis above. Mantis, targeting only
ETC, never had to encode a *relative* ordering check between two of its own
forks; fukuii's config resolution has to, because it now has to remain correct
under fork orderings that can vary per network within the same consensus
family.

## Limitations

Being honest about what this design does *not* do, in the same spirit as
core-geth's own Limitations section:

- **Two hardcoded consensus families, not an arbitrary pluggable set.**
  `EvmConfig.forBlock`'s two overloads hardcode exactly the PoW (block-number)
  and PoS (timestamp) dispatch axes fukuii currently supports. Adding a third,
  structurally different consensus family would mean extending this dispatch
  code directly — there is no generic "register a new fork-resolution
  strategy" seam here today. The fully pluggable, three-layer
  `fukuii-core`/`fukuii-env`/consensus-module architecture sketched in
  [`pluggable-consensus-vision.md`](pluggable-consensus-vision.md) is the
  aspirational design this could grow into; `EvmConfig`'s current two-overload
  shape is the interim mechanism actually shipping today, not that vision.
- **Fork-level, not EIP-level, composability.** core-geth's
  `ChainConfig.IsEnabled(feature, blockNumber)` decomposes every hard fork into
  independently togglable EIP-level flags, letting ETC adopt a single EIP from
  a later Ethereum hard fork without also accepting that fork's other changes
  (see the core-geth pattern doc's worked example). fukuii's
  `transitionBlockToConfigWithPriorityMapping` composes at the granularity of
  an entire named fork's `EvmConfigBuilder` — adopting one EIP in isolation
  from a fork bundle would require a manual, one-off carve-out (as the
  `etcForksDisabled` check above already is for Olympia/London), not a
  general per-EIP toggle. fukuii does not attempt core-geth's finer-grained
  decomposition.
- **No attempt to preserve Mantis-era compatibility surface.** fukuii does not
  aim to keep every JSON-RPC extension, config knob, or module boundary that
  existed in the original Mantis client working unchanged — features and
  subsystems have been added, replaced, or removed as the PoW/PoS split and the
  Scala 3 migration have progressed, and no Mantis-compatibility guarantee is
  maintained.
- **No parity claim against sibling multi-config clients' operational
  tooling.** core-geth's config-type abstraction also lets it accept both
  go-ethereum's and OpenEthereum's genesis JSON schemas through the same
  interface. fukuii does not attempt to ingest foreign genesis-config schemas
  from other clients; its own config format is the only one `EvmConfig`'s
  resolution path understands.

## Related Documentation

- [Pluggable Consensus Vision](pluggable-consensus-vision.md) — the aspirational,
  fully pluggable consensus-module architecture this document's Limitations
  section points to.
- [Architecture Overview](architecture-overview.md) — system-wide component map.
- `docs/research/best-practices/evm-clients/repo-patterns/core-geth/repo-hygiene-pattern.md` —
  the source pattern this document's three-section shape and worked
  before/after example are modeled on.
