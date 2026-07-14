# go-ethereum — accounts-signer
_Commit/branch documented: 59e89e81e / upstream. Documented 2026-07-13._

> Note on repo scope: this vendored copy is trimmed. The full **clef** daemon
> (`signer/core/api.go`, `signer/rules/`, `cmd/clef/`) is NOT present — only the
> clef building blocks that survive as shared library code:
> `signer/core/apitypes/` (the request/response wire types), `signer/fourbyte/`
> (calldata decoding for approval UIs), and `signer/storage/` (encrypted
> credential store). The **client-side** view of clef — `accounts/external/` —
> is present in full. Clef's daemon internals are described here from those
> surviving pieces plus the stable `account_*` RPC contract they implement; cite
> upstream `cmd/clef` / `signer/core/api.go` for the daemon proper.

## Architecture summary

geth models account management as a **two-level pluggable abstraction** that is
agnostic to where private keys actually live:

- `accounts.Backend` — a "wallet provider": a source of wallets (keystore
  directory, USB HID hub, external signer endpoint). Backends are dynamic —
  they emit `WalletEvent`s (arrived/opened/dropped) so HW devices can be
  hot-plugged.
- `accounts.Wallet` — one wallet (may hold one or many accounts, e.g. an HD
  seed) that can enumerate `Account`s, derive HD paths, and **sign** (`SignTx`,
  `SignData`, `SignText`, plus `*WithPassphrase` variants).
- `accounts.Manager` — the node-facing aggregator. It merges wallets from all
  registered backends into one URL-sorted list, runs a single `update()`
  goroutine consuming every backend's event feed, and answers `Find(account) →
  Wallet`. The node holds exactly one `Manager` and never talks to a concrete
  backend directly.

The key insight: **the node never sees a private key type**. It holds an
`Account{Address, URL}` and asks the `Manager` to find the `Wallet` that
`Contains` it, then calls `Wallet.SignTx`. Whether that signs with an in-memory
decrypted key, a Ledger over USB, or a JSON-RPC call to an out-of-process clef
daemon is entirely behind the interface. The `URL.Scheme` (`keystore://`,
`ledger://`, `trezor://`, `extapi://`) is the only tell.

Four concrete backends implement this:

1. **`keystore`** — encrypted key files on disk (the web3 secret-storage format).
   The default, serves everyone.
2. **`usbwallet`** — Ledger/Trezor HW wallets over USB HID (custody).
3. **`scwallet`** — smartcard (Status Keycard) wallets.
4. **`external`** — proxy to a clef external signer over RPC (CEX/custody /
   enterprise: keys live in a separate process, never in the node).

## Key types / interfaces / files

- `accounts/accounts.go:33` — `Account{Address, URL}` — the node's opaque handle
  to an account; `URL` optionally locates it within a backend.
- `accounts/accounts.go:47` — `Wallet` interface — `Open/Close`, `Accounts`,
  `Contains`, `Derive`/`SelfDerive` (HD), and the sign methods
  (`SignData`/`SignText`/`SignTx` + `*WithPassphrase`). The single seam every
  account source implements.
- `accounts/accounts.go:158` — `Backend` interface — `Wallets()` +
  `Subscribe(chan WalletEvent)`. A wallet provider; tiny by design.
- `accounts/accounts.go:184` `TextHash` / `:197` `TextAndHash` — the
  `"\x19Ethereum Signed Message:\n"` EIP-191 personal-sign prefix, applied
  before signing so a signed message can never be replayed as a transaction.
- `accounts/manager.go:46` — `Manager` — backends indexed by reflect type,
  URL-sorted merged wallet cache, one `event.Feed`; `merge`/`drop`
  (`manager.go:242`/`:256`) keep the cache sorted as wallets come and go.
- `accounts/manager.go:220` — `Manager.Find(account) → Wallet` — the linear
  address→wallet lookup the node uses on every sign.
- `accounts/errors.go:52` — `AuthNeededError` — the "I need a password / PIN /
  approval before I can sign" sentinel a wallet returns instead of failing hard;
  callers retry via `*WithPassphrase` or by unlocking. This is what makes the
  same interface work for locked keystores, HW PIN prompts, and clef approvals.

### keystore (encrypted key files)

- `accounts/keystore/keystore.go:62` — `KeyStore` — manages a key directory:
  `storage` (encrypt/decrypt), `cache` (`accountCache` over the fs), and
  `unlocked map[Address]*unlocked` (decrypted keys held in memory).
- `accounts/keystore/keystore.go:258` `SignHash` / `:272` `SignTx` — sign
  **only if the account is in `unlocked`**, else `ErrLocked`. Plain in-memory
  ECDSA.
- `accounts/keystore/keystore.go:334` — `TimedUnlock(account, pass, timeout)` —
  decrypt and hold the key in memory for `timeout` (0 = until exit); a goroutine
  `expire`s it. `Unlock` = `TimedUnlock(…, 0)`; `Lock` (`:317`) zeroes it early.
- `accounts/keystore/keystore.go:403` `NewAccount` / `:432` `Import` / `:452`
  `ImportECDSA` / `:476` `Update` — lifecycle: create, import a foreign JSON
  key, import a raw ECDSA key, re-encrypt with a new passphrase.
- `accounts/keystore/key.go:41` — `Key{Id (UUIDv4), Address, PrivateKey}` —
  in-memory plaintext key; `key.go:66` `encryptedKeyJSONV3` +
  `key.go:80` `CryptoJSON` — the **on-disk v3 JSON schema**
  (`cipher`/`ciphertext`/`cipherparams`/`kdf`/`kdfparams`/`mac`).
- `accounts/keystore/key.go:222` — `keyFileName` — the
  `UTC--<ISO8601>--<addresshex>` filename convention.
- `accounts/keystore/passphrase.go:140` `EncryptDataV3` / `:200` `DecryptKey` —
  the actual crypto: **scrypt** (default, `passphrase.go:50` `keyHeaderKDF`) or
  **pbkdf2** KDF (`passphrase.go:333` `getKDFKey`) → derived key → **AES-128-CTR**
  of the private key, authenticated by **Keccak256(derivedKey[16:32] ‖
  ciphertext)** as the MAC. `StandardScryptN=1<<18` vs `LightScryptN=1<<12`
  (`:54`/`:62`) trade CPU cost for security.
- `accounts/keystore/keystore.go:506` — `zeroKey` — wipe the private key from
  memory after use (called via `defer` in every `*WithPassphrase` path).
- `accounts/keystore/wallet.go:36` — `keystoreWallet` — thin `Wallet` adapter:
  one key file = one single-account wallet; delegates every sign back to the
  parent `KeyStore`.

### external signer (clef client side)

- `accounts/external/backend.go:36` `ExternalBackend` / `:64` `ExternalSigner` —
  a `Backend`+`Wallet` pair that is just an **RPC client** to a clef endpoint.
- `accounts/external/backend.go:157` `SignData` → `account_signData`,
  `:200` `SignTx` → `account_signTransaction`, `:267` `listAccounts` →
  `account_list`, `:275` `pingVersion` → `account_version`. Signing is a network
  call; **no key material ever enters the node process**.
- `accounts/external/backend.go:101`/`:105` — `Open`/`Close` return "operation
  not supported": you cannot unlock an external signer from the node — approval
  happens at the clef side, by design.

### clef daemon building blocks (present) + daemon proper (upstream)

- `signer/core/apitypes/types.go:86` — `SendTxArgs` — clef's canonical
  transaction-to-sign representation (typed, EIP-1559/4844 aware via
  `validateTxSidecar` `:220`); `:137` `ToTransaction`. This is the wire type
  `external` marshals into `account_signTransaction`.
- `signer/core/apitypes/types.go:310` `SigFormat` / `:340` `TypedData` — EIP-712
  typed-data structures clef renders for human approval.
- `signer/core/apitypes/types.go:49` `ValidationMessages` (`Crit`/`Warn`/`Info`)
  — the structured warnings clef surfaces to the approver ("this tx sends to a
  contract you've never interacted with").
- `signer/fourbyte/fourbyte.go` — embedded 4-byte selector DB (`//go:embed
  4byte.json`) so clef can decode calldata (`0xa9059cbb` → `transfer(address,
  uint256)`) into human-readable intent at the approval prompt.
- `signer/storage/aes_gcm_storage.go:37` — `AESEncryptedStorage` — AES-GCM
  per-value JSON store; clef persists its rule-engine attestations and stored
  credentials here (keys not encrypted, values are).
- _(upstream, trimmed here)_ `signer/core/api.go` — `SignerAPI` implementing the
  `account_*` methods; `signer/rules/` — the JS rule engine for automated
  approval; `cmd/clef/` — the standalone daemon binary + its UI protocol.

## Design decisions & rationale

- **Keys are addressed, not held.** The node stores `Account{Address, URL}` and
  resolves to a `Wallet` on demand. This is what lets keystore, HW, and external
  signers coexist behind one code path and be added/removed at runtime.
- **`AuthNeededError` as a first-class return.** Rather than modeling
  "unlocked" state in the type system, a wallet that can't sign yet returns a
  typed error describing what's needed (password / PIN / external approval). One
  interface serves locked keystores, HW prompts, and clef — the caller's retry
  logic is uniform.
- **web3 secret storage v3.** scrypt/pbkdf2 + AES-128-CTR + Keccak-MAC is a
  cross-client interchange format: a keyfile written by geth opens in
  MetaMask/Parity/fukuii and vice-versa. Interop was chosen over a bespoke
  format.
- **Verify-after-write.** `StoreKey` (`passphrase.go:105`) immediately reads the
  file back and re-decrypts with the same password before committing the
  `os.Rename`, refusing to leave a keyfile it can't reopen. Atomic
  temp-file-then-rename (`key.go:190`) prevents torn writes.
- **Signing isolation (clef).** The external-signer split exists so the node —
  an internet-facing, frequently-updated, large-attack-surface process — never
  holds decrypted keys. Signing (and human/rule approval) lives in a small,
  auditable, separately-permissioned daemon. This is the custody/enterprise
  posture: compromise of the node ≠ compromise of the keys.
- **Timed unlock over permanent unlock.** `TimedUnlock` defaults miners/tooling
  to a bounded in-memory window; `--allow-insecure-unlock` gating and the
  general push toward clef reflect that in-node unlocking is the discouraged
  path.

## Notable patterns (the reusable idea)

**The `Backend`/`Wallet` seam as a signer-source abstraction.** The node depends
on two tiny interfaces (`Backend` = "give me wallets + notify me of changes",
`Wallet` = "enumerate accounts + sign"), and every account source — local
encrypted files, USB hardware, or an out-of-process daemon reached over RPC —
is just an implementation. New custody models (HSM, KMS, remote MPC signer) plug
in without touching the node's transaction path. The `AuthNeededError` sentinel
is the companion pattern that keeps "not authorized yet" out of the happy-path
type while still being uniform across every backend.

## Authority note

geth is the canonical source for **both**: (1) the **web3 secret-storage
keyfile format** (scrypt/pbkdf2 + AES-128-CTR + Keccak-MAC, v3 JSON schema, the
`UTC--…--<address>` filename) that every EVM client interoperates on, and (2)
the **clef external-signer pattern** — the reference design for isolating
signing (and human/rule-based approval) out of the node process. For PoW/ETC
specifics the keystore format is chain-agnostic, so geth remains authoritative
here even though core-geth is the ETC consensus authority elsewhere.

## Gotchas / anti-patterns / things they later changed

- **In-node account unlocking is deprecated in spirit.** `personal_*` unlock
  and `--allow-insecure-unlock` exist but are discouraged; clef is the intended
  path. Don't model fukuii's key handling on permanent in-node unlock.
- **`ensureInt` float64 hack** (`passphrase.go:362`) — KDF params come back from
  JSON as `float64`; a naive `int` cast panics. Any reimplementation of the
  keyfile parser hits the same "JSON numbers are floats" trap.
- **`Manager.Find` is O(n) per sign** (`manager.go:218` comment) — accounts move
  between wallets dynamically so there's no index; fine for a handful of
  accounts, a footgun at custody scale (thousands of keys → don't route every
  sign through this).
- **External signer can't `Open`/`Close`/unlock** (`backend.go:101`) — approval
  is out-of-band at clef. Code expecting to programmatically unlock will get an
  error; that's intentional, not a bug.
- **v1 keyfiles use AES-128-CBC + a Keccak-of-derivedKey twist**
  (`passphrase.go:295` `decryptKeyV1`) — legacy presale/early format kept for
  read compatibility; only v3 is written. A from-scratch decoder must handle
  both.
- **Scrypt cost is a deployment decision, not a constant.** `StandardScryptN`
  (~256MB, ~1s) vs `LightScryptN` (~4MB, ~100ms) — light params on a
  server-side keystore weaken brute-force resistance; pick deliberately.

## fukuii alignment

fukuii already carries the geth-compatible keystore: `keystore/EncryptedKey.scala`,
`keystore/EncryptedKeyJsonCodec.scala`, `keystore/KeyStore.scala`,
`keystore/Wallet.scala` (under `src/main/scala/com/chipprbots/ethereum/`), and the
`fukuii-key-management` skill wraps the `fukuii cli` key subcommands (node keys,
key pairs, address derivation, key encryption, genesis allocs). The most
transferable next step from geth is not the keystore (already present) but the
**external-signer seam**: a `Wallet`/`Backend`-style interface so signing can be
delegated to an out-of-process signer (clef-compatible `account_signTransaction`
RPC, or an HSM/KMS), giving the enterprise/custody use-case keys-out-of-the-node
without changing the tx path.
