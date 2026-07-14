# Observations — accounts-signer
_Phase-2 synthesis 2026-07-13. Sources: 6 {client}/accounts-signer.md._

## Comparison table
| Design dimension | go-ethereum | core-geth | besu | erigon | nethermind | reth | Authoritative |
|------------------|-------------|-----------|------|--------|------------|------|---------------|
| Keystore (geth-v3 format?) | Yes — canonical Web3 Secret Storage v3 (scrypt/pbkdf2 + AES-128-CTR + Keccak-MAC, `UTC--…--<addr>`) | Inherited verbatim from geth | No user keystore; node `key` is plaintext hex, not v3 | No — `accounts/` dir empty, keystore deleted | Yes — `FileKeyStore` faithful geth-v3 re-impl (scrypt N=262144/r=8/p=1, `aes-128-ctr`) | No keystore at all | **geth** (defines the v3 format) |
| User wallet | Yes — `accounts.Wallet`/`Backend`/`Manager` aggregating keystore/USB/smartcard/external | Inherited unchanged | No — "a node is not a wallet" | No — user signing deferred to external tooling | Yes — `IWallet` w/ `DevWallet`/`ProtectedKeyStoreWallet`/`NullWallet` | No — dev-only in-memory signers under `--dev`, empty otherwise | **nethermind** (fullest) / geth |
| External signer (clef-compat / remote) | Yes — `accounts/external` RPC client to clef (`account_signTransaction`); keys never enter node | Full clef daemon retained in-tree | Via external service (Web3Signer), not in-tree | No external-signer bridge | Yes — `ClefWallet`/`ClefSigner` plugin, Clef-JSON-RPC over HTTP | No (an `EthSigner` trait exists; no remote impl shipped) | **geth** (clef pattern) / **nethermind** (in-tree Clef client) |
| HSM / SecurityModule | Via clef external process | Via clef external process | Yes — pluggable `SecurityModule` seam + `SecurityModuleService` registry; `--security-module=<name>` | No | Via external Clef/HSM behind `ClefSigner` | No (trait could back one; none shipped) | **besu** (SecurityModule/HSM) |
| Validator remote-signing | Via clef (Clique `SignerFn`) | Via clef | Node key = validator key; delegate to HSM via SecurityModule | `SignerFn`/`Authorize` callback seam (wired only in tests) | Yes — `ClefSigner` stands in for block-author/validator key (header+message sign) | No | **nethermind** (`ClefSigner` validator remote-sign) / besu |
| Node / network-identity key | devp2p key inside keystore/backend model | Same as geth | The one key besu holds; triple duty (RLPx/discovery/BFT seal) behind `SecurityModule` | Only persistent key — `nodekey` via `p2p.NodeKeyConfig`, plaintext | Node key handled separately from account wallet | `discovery-secret` plaintext hex, code path fully separate from RPC signers | **reth**/erigon (strict identity-vs-account separation) |
| Signer seam abstraction | `Backend`/`Wallet` + `AuthNeededError` sentinel | Inherited | `SecurityModule` (`sign`/`getPublicKey`/ECDH) | `Authorize(addr, SignerFn)` callback | `ISigner`/`ISignerStore` (read/write split) + `IWallet` | `EthSigner` one-method trait, empty default | **nethermind** (`ISigner`/`ISignerStore` narrowest DI seam) / geth |

## Approach catalog (use-case-aware)
| Approach | Clients using it | Good for (use-case/node-role) | Verdict | Why |
|----------|------------------|-------------------------------|---------|-----|
| Encrypted geth-v3 keystore | geth, core-geth, nethermind | End-user / dev nodes that custody accounts locally | **DEFAULT** | Cross-client interchange format (a keyfile opens in geth/MetaMind/nethermind/fukuii); fukuii already ships it |
| Wallet/Backend pluggable account sources | geth (core-geth inherits) | Nodes aggregating many sources (keystore + USB HW + smartcard + external) at runtime | **OPTIONAL(end-user/multi-source)** | Hot-pluggable backends via `WalletEvent`; overkill for a single-keystore node |
| Clef-compatible external signer | geth clef, nethermind `ClefWallet` | Custody / keys-out-of-node — signing + human/rule approval in a separate hardened process | **OPTIONAL(custody)** | Node compromise ≠ key compromise; the enterprise/custody posture |
| SecurityModule / HSM delegation | besu | Custody / enterprise — key lives in HSM/vault, never in the JVM heap | **OPTIONAL(custody/enterprise)** | Narrow capability interface; HSM integration ships as a plugin jar, core stays vendor-SDK-free |
| Validator remote-signing | nethermind `ClefSigner` | Staking / validator custody — block-author/sealer key external | **OPTIONAL(validator)** | Read/write signer split hard-refuses local-key injection; secures sealing without exposing the key |
| Node-key-only / no user wallet | besu, erigon, reth | Pure infra / archive / RPC / sync node that never custodies user funds | **OPTIONAL(pure-node)** | Smallest blast radius for an internet-facing node; user signing pushed client-side |
| `ISigner`/`ISignerStore` narrow signer seam | nethermind | Any deployment wanting "key in memory vs keystore-unlocked vs external" as a DI choice | **DEFAULT (as the seam)** | Consensus/sealing depends only on `ISigner`; swapping key location is a plugin decision, zero block-production change |
| Permanent in-node account unlock | geth `personal_*` / `--allow-insecure-unlock` (deprecated in spirit) | — | **OBSOLETE** | Discouraged upstream; clef is the intended path — don't model fukuii on permanent in-node unlock |
| Plaintext hex node-key file | besu, erigon, reth | Network identity only (not value) | **OPTIONAL(node-identity)** — never for accounts | Fine for enode/ENR identity; filesystem perms are the only protection — never store account keys this way |

## Best-practice synthesis
The six clients span a clear spectrum of account-management richness:

**nethermind (FULLEST)** — keystore-v3 + `IWallet` trio + Clef-compatible external signer + validator remote-signing, all in-tree behind `IKeyStore`/`IWallet`/`ISigner`(+`ISignerStore`). → **geth** — canonical keystore-v3 + clef external signer (clef as a separate tool). → **besu** — `SecurityModule`/HSM seam and a node-isn't-a-wallet stance (no user wallet at all; user signing external via Web3Signer). → **erigon / reth (MINIMAL)** — no keystore, no HSM abstraction; one node-identity key plus consensus signing as an injectable callback (erigon `SignerFn`) or an empty-by-default signer trait (reth `EthSigner`), with user signing entirely external.

fukuii already sits toward the rich end: it ships a geth-compatible keystore (`keystore/EncryptedKey*.scala`, `KeyStore.scala`, `Wallet.scala`) plus a `fukuii-key-management` skill wrapping the `fukuii cli` key subcommands.

- **DEFAULT:** Keep the geth-v3 keystore (fukuii already has it — the cross-client interchange format), AND adopt a narrow **`ISigner`-style seam** (nethermind) so that "key in memory vs keystore-unlocked vs external Clef/HSM" becomes a DI choice with **zero block-production change**. Split read (`ISigner`) from write (`ISignerStore`) so a remote signer can honestly implement "sign this" while making "here is the private key" a compile-visible unsupported operation.
- **OPTIONAL(custody/enterprise):** external-signer / HSM delegation — besu `SecurityModule` registry (`--security-module=<name>`) and/or a clef-compatible client — so account/validator keys can live outside the process.
- **OPTIONAL(validator):** validator remote-signing (nethermind `ClefSigner` shape) for the validator-software component, where the block-author key is external.
- **Strict network-identity-key vs account-key separation (reth/erigon):** the node's P2P identity key must never be reachable as a signable account, and account-signing RPCs should fail closed (`NoAccount`) when no account is configured rather than silently degrade.

## fukuii implications (forward-ref to Phase 3–4, do NOT act here)
- fukuii's keystore maps to the **SR-11 basket (keystore)** — already present and geth-v3-compatible; the transferable next step is not the keystore but the **signer seam**.
- Adopt the **`ISigner`-style DI seam** (nethermind) plus **external-signer / HSM delegation** (besu `SecurityModule` / clef) for the custody/enterprise use-case, routing sealer/validator signing through a pluggable callback (erigon `Authorize(SignerFn)` shape) so an HSM/remote signer drops in without touching consensus code.
- **Validator remote-signing** (nethermind `ClefSigner`) ties to **CL-RESEARCH-EMBED-01** (validator-software) — the block-author key belongs external of the node.
- Enforce **strict node-identity vs account-key separation** (reth) as a seam even while keeping the richer keystore.

These are seeds for Phase 3–4 scoping, not verdicts.
