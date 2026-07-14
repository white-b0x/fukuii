# reth — accounts-signer
_Commit/branch documented: 3d76b93c2 / upstream. Documented 2026-07-13._

## Architecture summary

reth ships **no user keystore, no wallet, and no account-management subsystem**.
This is deliberate: signing is a wallet/tooling concern, not the execution node's,
so reth delegates all key handling to external tools and the [alloy] signer crates.
The node itself holds exactly one long-lived secret — the **p2p / discovery secret
key** (the node's network identity), which is not an "account" in the user sense and
is never used to sign transactions.

Two, and only two, signing surfaces exist:

1. **Network secret key** — a `secp256k1::SecretKey` that is the node's devp2p
   identity (enode/ENR). Auto-generated on first start and persisted as a hex file;
   no encryption, no password, no keystore directory.
2. **Dev signers** (`--dev` only) — 20 in-memory `alloy_signer_local::PrivateKeySigner`
   accounts derived from a fixed test mnemonic, wired into the `eth_*` RPC signer list
   purely to make `eth_sendTransaction` / `eth_sign` work on a local dev chain. In any
   non-dev run the RPC signer list is **empty**, so `eth_sign` / `eth_signTransaction`
   / `eth_sendTransaction` return `SignError::NoAccount`.

There is no `personal_*` namespace-backed keystore, no `--keystore` flag, no
`eth_accounts` population outside dev mode.

## Key types / interfaces / files

- `crates/rpc/rpc-eth-api/src/helpers/signer.rs:14` — the `EthSigner<T, TxReq>` trait:
  the sole RPC signing abstraction (`accounts`, `is_signer_for`, `sign`,
  `sign_transaction`, `sign_typed_data`). This is an *interface*; reth ships only one
  real implementor.
- `crates/rpc/rpc/src/eth/helpers/signer.rs:14` — `DevSigner`, the only production
  `EthSigner` impl. Holds `AddressMap<PrivateKeySigner>` (alloy local signers).
- `crates/rpc/rpc/src/eth/helpers/signer.rs:22` — `DevSigner::random_signers(num)` and
  `:40` `DevSigner::from_mnemonic(mnemonic, num)` — build the dev accounts via alloy's
  `MnemonicBuilder::<English>` on the Ethereum path `m/44'/60'/0'/0/{index}`.
- `crates/rpc/rpc/src/eth/helpers/signer.rs:7` — imports
  `alloy_signer_local::{coins_bip39::English, MnemonicBuilder, PrivateKeySigner}` and
  `alloy_signer::SignerSync` — confirms all actual signing is alloy's, not reth's.
- `crates/node/builder/src/rpc.rs:1146-1150` — the **only** wiring of any signer into
  the node: `if config.dev.dev { … DevSigner::from_mnemonic(config.dev.dev_mnemonic, 20)
  … signers().write().extend(signers) }`. Gated entirely on `--dev`.
- `crates/node/core/src/args/dev.rs:8` — `DEFAULT_MNEMONIC = "test test test test test
  test test test test test test junk"` (the standard Hardhat/Foundry test mnemonic);
  `:19-20` prefunds those 20 accounts with 10 000 ETH each on the dev chain.
- `crates/rpc/rpc/src/eth/core.rs:321` — `signers` initialized to `Default::default()`
  (an empty `RwLock`); `:464` `signers()` handle. Empty unless dev mode extends it.
- `crates/cli/util/src/load_secret_key.rs:42` — `get_secret_key(path)`: the node-key
  lifecycle. If the file exists, parse hex; else generate `rng_secret_key()` and write
  it as plain hex (`:56-58`). No encryption.
- `crates/cli/util/src/load_secret_key.rs:10` / `crates/net/network/src/config.rs:38` —
  `rng_secret_key()` → `SecretKey::new(&mut rand_08::thread_rng())`.
- `crates/node/core/src/args/network.rs:278-286` — CLI: `--p2p-secret-key <PATH>` and
  `--p2p-secret-key-hex <HEX>` (mutually exclusive) to supply the node key explicitly.
- `crates/node/core/src/args/network.rs:671-683` — `secret_key(default_path)`: hex arg
  wins, else file path, else the default path.
- `crates/node/core/src/dirs.rs:330-331` — default node-key path:
  `<datadir>/discovery-secret`.
- `crates/net/network/src/config.rs:287-292` — the secret key derives the node's
  network identity (`pk2id(secret_key.public_key(...))`); it is a P2P identity, not a
  spendable account.

## Design decisions & rationale

- **Signing is out of scope for the node.** reth treats transaction signing as a
  wallet/tooling responsibility. There is no built-in keystore because the node never
  needs to custody user funds; users sign with external wallets (MetaMask, Foundry
  `cast`, Ledger, alloy-based tooling) and submit already-signed transactions via
  `eth_sendRawTransaction`.
- **The one persistent secret is the network identity.** A node still needs a stable
  enode/ENR, so the p2p secret key is auto-generated and persisted — but as an
  unencrypted hex file, because it protects peer identity, not value. Losing it only
  changes the node's enode; it is not custody.
- **Dev accounts exist only for the local dev chain.** The 20 signers are hardcoded to
  the well-known public test mnemonic and only wired in behind `--dev`, so they can
  never be mistaken for real custody. Off dev mode, the signer list is empty by
  construction, so account-based RPC signing simply fails closed with `NoAccount`.
- **Reuse over reinvention.** Rather than implement BIP-39/BIP-32 derivation and
  signing, reth pulls `alloy-signer` / `alloy-signer-local` — the same primitives the
  broader alloy tooling ecosystem uses.

## Notable patterns (the reusable idea)

- **A one-method signing trait + a single dev-only implementor.** `EthSigner` is a
  clean seam: the RPC layer depends only on the trait, and the node supplies concrete
  signers at wiring time (only in dev). A production deployment or a downstream fork
  could register its own `EthSigner` (e.g. HSM/remote-signer backed) without touching
  RPC code — but reth ships none, keeping the node stateless w.r.t. accounts.
- **Fail-closed empty signer list.** Defaulting `signers` to empty and only extending
  under `--dev` means the "no accounts" behavior is the structural default, not a
  runtime check that can be forgotten.
- **Separate the identity key from account keys.** The network secret (identity) lives
  in a completely different code path (`cli/util`, `net/network/config`) from the RPC
  signers (`rpc/eth/helpers/signer`), so there is no chance of the node identity being
  exposed as a signable account.

## Authority note

reth = **minimal** (no keystore/wallet; alloy-signer for dev/test only) — the minimal
end of the account-management spectrum together with **erigon**. go-ethereum
(keystore + `personal_*` + `clef`) and Nethermind (full keystore/wallet trio) sit at
the rich end. For **PoW/ETC** consensus questions reth is not an authority (core-geth
is); for account-management *philosophy* reth is a clean exemplar of the pure-node
stance. Do not treat reth's absence of a keystore as a spec gap — it is an intentional
architectural boundary.

## Gotchas / anti-patterns / things they later changed

- **`eth_sign` / `eth_sendTransaction` "work" only under `--dev`.** Anyone testing RPC
  signing against a normal reth node will get `SignError::NoAccount`; this is expected,
  not a bug. Account-signing RPCs are effectively unsupported in production reth.
- **The p2p secret key file is unencrypted hex.** `discovery-secret` is written in
  plaintext (`load_secret_key.rs:57-58`). Fine for a network identity, but it is a real
  secret — operators should protect the datadir; there is no password prompt.
- **Dev accounts use the public Hardhat mnemonic** (`"test test … junk"`). Never fund
  these on any real network — every reth/anvil/hardhat user has the same private keys.
- **`rand_08::thread_rng()`** — the node key uses the 0.8 rand facade
  (`config.rs:39`), a deliberate pin; not a CSPRNG concern but a versioning detail to
  note if porting the key-gen logic.

---

### fukuii contrast

fukuii **does** ship account management — a keystore plus a `fukuii cli` key-management
surface (node private keys, key-pair generation, address derivation, key encryption,
genesis-alloc snippets; see the `fukuii-key-management` skill). That places fukuii
closer to the go-ethereum end than to reth/erigon. The takeaway for fukuii: reth's
`EthSigner`-trait-with-empty-default and its hard separation of *network identity key*
from *account keys* are worth mirroring as a seam even where fukuii keeps a richer
keystore — the node's P2P identity should never be reachable as a signable account, and
account-signing RPCs should fail closed when no account is configured rather than
silently degrade.
