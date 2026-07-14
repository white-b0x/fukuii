# core-geth — cl-engine
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary

**This slot is effectively N/A for the ETC PoW family.** ETC has no merge, no
Terminal Total Difficulty, no Consensus Layer, and no Engine API in operation. ETC
selects its own head via PoW total-difficulty with **block-number fork dispatch**
(Atlantis → … → Thanos → Magneto → Mystique → Olympia), never a timestamp/TTD merge
transition.

core-geth nonetheless **compiles and registers geth's engine driver** — the
`eth/catalyst` `ConsensusAPI` (`newPayload` / `forkchoiceUpdated` / `getPayload`)
and the `beacon/engine` payload types — because it is a go-ethereum fork and the
code is not stripped. But for ETC these paths are **inert dead weight**: the ETC
chain configs (`params/config_classic.go`, `params/config_mordor.go`) never set a
`TerminalTotalDifficulty` and never enable the withdrawal/beacon forks (EIP-4895 is
present only as a commented-out `// EIP4895FBlock: nil // Beacon chain push
withdrawals`), so the merge transition never fires and no CL ever drives the head.
`catalyst.Register(stack, eth)` is wired unconditionally in `cmd/geth/config.go:247`,
but on an ETC network the Engine API is a registered-yet-never-exercised surface.

Two further points distinguish core-geth from the documented geth baseline:

- **No `beacon/blsync`.** core-geth (a 2025-01 fork) predates / omits geth's embedded
  beacon-light-client. The `beacon/` tree here is `engine/ light/ merkle/ params/
  types/` — there is **no `blsync/`** directory and thus no embedded-CL-lite driver.
  The "geth embeds a whole alternative consensus by dialing its own engine API
  in-process" pattern from the geth doc **does not exist in core-geth**.
- The engine driver is present essentially as **upstream residue for ETH-family
  compatibility**, not as an ETC feature.

## Key types / interfaces / files

- `cmd/geth/config.go:247` — `catalyst.Register(stack, eth)` — registers the Engine
  API on every node unconditionally (inherited from geth). Inert on ETC because no
  merge fork activates.
- `cmd/geth/config.go:244` — `catalyst.RegisterSimulatedBeaconAPIs(stack, simBeacon)`
  — dev-mode simulated beacon (geth's `--dev` auto-sealer); irrelevant to ETC PoW.
- `eth/catalyst/api.go` — `ConsensusAPI`: the `newPayload`/`forkchoiceUpdated`/
  `getPayload` driver. Inherited from geth unchanged; see the go-ethereum `cl-engine`
  doc for its semantics. **Never invoked on the ETC PoW path.**
- `eth/catalyst/simulated_beacon.go` — `SimulatedBeacon` dev auto-sealer. Inherited;
  dev-only.
- `beacon/engine/` — `ExecutableData` ⇄ block marshalling types. Present, inherited.
- `params/config_classic.go:104`, `params/config_mordor.go:88` —
  `// EIP4895FBlock: nil // Beacon chain push withdrawals as operations` — the
  withdrawal fork is **commented out / disabled** for ETC. No `TerminalTotalDifficulty`
  field is set anywhere in the ETC configs. This is the concrete evidence that the
  merge never activates.
- `beacon/blsync/` — **absent** (contrast go-ethereum, which has it). No embedded
  light client in core-geth.

## Design decisions & rationale

- **ETC never merges — by protocol design.** ETC deliberately retained PoW/Ethash and
  its ECIP-1017 fixed-supply emission; the Merge (EIP-3675 / TTD) is an ETH-only event.
  So the Engine API — the entire reason it exists (let a CL drive the EL's head) — has
  no counterpart on ETC, where the node picks its own head from PoW difficulty.
- **Inherited-but-unused rather than removed.** core-geth kept catalyst compiled in
  rather than surgically excising it, because it is a go-ethereum fork that also aims
  to stay buildable against upstream structure; disabling the merge via *config*
  (no TTD, no withdrawal forks) is a smaller, less error-prone diff than deleting the
  driver. The result: an ETC node ships the Engine API code but never activates it.
- **Fork dispatch by block number, not timestamp/TTD.** ETC's head/fork logic lives in
  the `params` block-number `IsEnabled(...FBlock, blockNumber)` predicates and the PoW
  consensus engine, entirely outside `eth/catalyst`. The two mechanisms don't
  interact.

## Notable patterns (the reusable idea)

**Consensus-family divergence expressed as config, not code deletion.** The single
transferable observation: a go-ethereum-derived multi-family client can keep the
merge/Engine-API machinery physically present and neutralize it for a PoW network
purely by *not configuring* a TTD or beacon forks. For fukuii this is a warning as
much as a pattern — the PoW/ETC (`forge`) path must treat the Engine-API/beacon
surface as **strictly ETH-family-only** and never let `newPayload`/`forkchoiceUpdated`
concepts, or a blsync analogue, bleed into `consensus/pow/`. fukuii's own design
(separate PoW block-number dispatch vs. PoS timestamp/Engine-API dispatch, per
`AGENTS.md`) is the correct expression of what core-geth achieves by leaving catalyst
inert.

## Authority note

**go-ethereum, not core-geth, is the authority for engine-driver semantics and the
blsync embedded-CL pattern** — see the go-ethereum `cl-engine` doc. core-geth adds
**nothing** to this slot except the negative fact that it is **inert for ETC** and
**lacks blsync**. For fukuii's PoS family (ETH/Sepolia, owned by `beacon`), consult
the geth doc; for the PoW family (ETC/Mordor, owned by `forge`), this slot simply
**does not apply** — ETC has no CL, no Engine API, no merge. Do not mine consensus
rules from core-geth's catalyst code for the ETC path.

## Gotchas / anti-patterns / things they later changed

- **Presence ≠ activation.** The Engine API being registered on an ETC node
  (`catalyst.Register` at config.go:247) does **not** mean ETC uses it. A reader
  grepping for `forkchoiceUpdated` in core-geth will find the full driver — it is dead
  code for ETC. Don't infer ETC merge support from its existence.
- **No TTD / no withdrawal forks in ETC config is the load-bearing fact.** The merge
  is disabled by the *absence* of `TerminalTotalDifficulty` and the commented-out
  `EIP4895FBlock`, not by any explicit "merge = off" switch. A config port that
  accidentally set a TTD would erroneously arm the merge path.
- **core-geth has no blsync** — the geth doc's embedded-light-client pattern is not
  reproducible here. Don't expect `beacon/blsync/` to exist.
- **2025-01 frozen snapshot.** Any later go-ethereum Engine-API/beacon evolution is
  absent. This is the deprecated ETC byte-authority, not a current geth mirror.
