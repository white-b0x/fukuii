# core-geth — accounts-signer
_Commit/branch documented: 4185df450 / upstream (deprecated ETC byte-authority). Documented 2026-07-13._

## Architecture summary

core-geth inherits go-ethereum's account/signer stack **wholesale and unchanged
in shape**: the two-level `accounts.Backend` (wallet provider) / `accounts.Wallet`
(sign + enumerate) abstraction aggregated by `accounts.Manager`, the four concrete
backends (`keystore`, `usbwallet`, `scwallet`, `external`), the web3 secret-storage
v3 keyfile format (scrypt/pbkdf2 + AES-128-CTR + Keccak-MAC), and the `AuthNeededError`
sentinel. See the go-ethereum `accounts-signer` doc for the full design — it applies
verbatim here. Unlike the *trimmed* vendored geth copy, core-geth's tree carries the
**full clef daemon** (`signer/core/api.go`, `signer/rules/rules.go`, `cliui.go`/
`stdioui.go`, `signer/core/apitypes/`, `signer/fourbyte/`, `signer/storage/`).

**ETC divergences are two, both narrow:**

1. **HD-derivation coin type (SLIP-44 61).** core-geth adds `accounts/hd_cg.go`
   (the `_cg` = core-geth), a global-config layer over geth's fixed HD root path.
   Hardware wallets derive addresses at BIP-44 path `m/44'/<coinType>'/0'/0` — geth
   hard-codes `coinType = 60` (Ether). core-geth makes it configurable and sets it
   to **61 (`0x3d`, SLIP-44 "Ether Classic")** when the ETC network is selected, so
   Ledger/Trezor addresses match other ETC tooling.
2. **EIP-155 replay protection uses the ETC chain-id.** Transaction signing is
   chain-agnostic geth code that reads `config.GetChainID()` — for ETC that resolves
   to **61 (mainnet) / 63 (Mordor)**, so `NewEIP155Signer(61)` is what protects an
   ETC signature from being replayed on ETH (chain-id 1) and vice-versa. Same code,
   different config value; the replay-protection *divergence from ETH is entirely in
   the chain-id constant*.

Everything else — keystore crypto, clef isolation model, `Manager.Find`, timed
unlock — is byte-identical to geth.

## Key types / interfaces / files

- `accounts/hd_cg.go:20-22` — `BIP0044CoinTypeEther = 0x3c` (60),
  `BIP0044CoinTypeEtherClassic = 0x3d` (61), `BIP0044CoinTypeTestnet = 0x1` — the
  SLIP-44 coin-type constants. **core-geth-specific file.**
- `accounts/hd_cg.go:28` `SetCoinTypeConfiguration(coinType)` — rewrites the four
  package globals (`BIP0044CoinType`, `DefaultRootDerivationPath`,
  `DefaultBaseDerivationPath`, `LegacyLedgerBaseDerivationPath`) to
  `m/44'/coinType'/0'/0[/0]`. `init()` (`:38`) defaults to Ether (60).
- `cmd/utils/flags.go:1640` — `accounts.SetCoinTypeConfiguration(BIP0044CoinTypeEtherClassic)`
  on ETC network selection (`:1628` testnet → coin type 1). This is the wiring that
  makes an ETC node derive HW-wallet addresses at coin type 61.
- `accounts/hd.go:31` — `DefaultRootDerivationPath = {44', 60', 0', 0}` — geth's
  fixed default that `hd_cg.go` overrides. Inherited unchanged; only mutated via the
  setter above.
- `core/types/transaction_signing.go:44-50` — `MakeSigner`: the same cascade geth
  uses (`Cancun → EIP1559 → EIP2930 → EIP155 → Homestead/Frontier`) selecting the
  signer by fork, each constructed with `config.GetChainID()`. For ETC this yields
  the chain-61 EIP-155 signer. Inherited; ETC-ness is in the config value, not the code.
- `signer/core/api.go`, `signer/rules/rules.go` — the **full clef daemon** (present
  here, trimmed out of the vendored geth copy): `account_*` RPC + JS rule engine.
  Inherited unchanged.
- `accounts/keystore/`, `accounts/external/`, `accounts/manager.go`,
  `accounts/accounts.go` — inherited verbatim; see the geth doc for the type map.

## Design decisions & rationale

- **HD coin type as a mutable global set at network-select time, not a per-wallet
  parameter.** core-geth chose to reconfigure the package-level derivation paths once
  (in `init()` / the CLI network flag) rather than thread a coin type through the
  `usbwallet` backend. Rationale: a node runs one network, so the derivation path is
  effectively a process-wide constant; a global setter is the smallest diff over
  geth's hard-coded path. The cost is that it is process-global mutable state (see
  gotchas).
- **Reuse geth's chain-id-parameterized EIP-155 signer rather than fork the signer.**
  Replay protection is fully expressed by the chain-id constant, so ETC needs no
  signer code of its own — only correct `GetChainID()` config (61/63). This keeps
  core-geth's `core/types` signing path a clean upstream inherit.
- **Keep the whole clef daemon.** Where the trimmed geth vendored copy dropped
  `cmd/clef`/`signer/rules`, core-geth retains the full signer subsystem, so ETC
  users get the same out-of-process signing/approval story unchanged.

## Notable patterns (the reusable idea)

**Network identity as a thin config overlay on an inherited signing stack.** The
entire ETC-vs-ETH account divergence reduces to two constants — SLIP-44 coin type
61 and EIP-155 chain-id 61/63 — injected at network-select time into otherwise
untouched go-ethereum code. The reusable lesson for fukuii: a multi-network client
does **not** need per-network account/keystore/signer code; it needs a single seam
(here, `GetChainID()` + `SetCoinTypeConfiguration`) where the network's numeric
identity is bound, and everything downstream (keyfile format, HD derivation, replay
protection) inherits from the shared implementation.

## Authority note

The **keystore format and clef pattern are chain-agnostic**, so **go-ethereum
remains the canonical authority** for account management, keyfile crypto, and the
external-signer design — core-geth inherits them unchanged. core-geth is the
authority only for the two ETC bindings: the **SLIP-44 coin type 61** HD-derivation
default and the **EIP-155 chain-id 61/63** used in ETC transaction signing (the
replay-protection divergence). fukuii already carries the geth-compatible keystore
(`keystore/EncryptedKey*.scala`, `KeyStore.scala`, `Wallet.scala`); the only
ETC-authoritative facts to mirror from core-geth are those two constants.

## Gotchas / anti-patterns / things they later changed

- **`SetCoinTypeConfiguration` mutates process-global state.** A single node fixed to
  one network is fine, but any code path that assumes geth's fixed `m/44'/60'/0'/0`
  root will derive the *wrong* HW-wallet addresses on an ETC node (coin type 61). A
  reimplementation must bind the coin type per-network, not assume 60.
- **Coin type 61 vs chain-id 61 are unrelated coincidences.** SLIP-44 coin type for
  ETC (`0x3d` = 61) and EIP-155 chain-id for ETC mainnet (61) happen to share the
  value 61 but come from different registries; Mordor is chain-id 63 but still coin
  type 61. Don't conflate them.
- **All the go-ethereum gotchas still apply** (in-node unlock deprecated in spirit,
  `ensureInt` float64 KDF-param trap, `Manager.Find` O(n) per sign, external signer
  can't `Open`/unlock, v1 legacy keyfile format) — core-geth inherited them verbatim.
- **This is a 2025-01 frozen fork.** Any later geth account/signer change (post-Jan
  2025) is absent here; treat this as the deprecated ETC byte-authority snapshot, not
  a live upstream.
