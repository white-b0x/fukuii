# nethermind — accounts-signer
_Commit/branch documented: 0d09a09ed / upstream. Documented 2026-07-13._

## Architecture summary

Nethermind ships the fullest account/signing stack of any client surveyed: three
independently-addressable layers behind two small interfaces (`IKeyStore`, `IWallet`) plus
a consensus-facing signer contract (`ISigner`/`ISignerStore`).

1. **KeyStore** (`Nethermind.KeyStore`) — the on-disk encrypted key vault. `FileKeyStore`
   reads/writes geth-style Web3 Secret Storage v3 JSON files (scrypt/pbkdf2 KDF, AES cipher,
   Keccak MAC). This layer owns bytes-at-rest: encrypt, decrypt, generate, delete, list.
2. **Wallet** (`Nethermind.Wallet`) — the account/session layer on top of the keystore.
   `IWallet` adds account lifecycle (import/new/unlock/lock, unlocked-state events) and
   `TrySign*` message/transaction signing. Three implementations serve three audiences:
   `DevWallet` (deterministic dev accounts, in-memory), `ProtectedKeyStoreWallet`
   (production — keystore-backed with a 5-min LRU of DPAPI/`ProtectedPrivateKey`-wrapped
   unlocked keys), and `NullWallet`.
3. **ExternalSigner plugin** (`Nethermind.ExternalSigner.Plugin`) — a **Clef-compatible**
   remote signer. `ClefWallet` is an `IWallet` that forwards every signing call to an
   external Clef (or any Clef-JSON-RPC-speaking) process over HTTP JSON-RPC; the private key
   never enters the Nethermind process. `ClefSigner` adapts that wallet to the consensus
   `ISigner`/`IHeaderSigner` contract so a **validator/block-author key can live entirely in
   an external signer/HSM**.

The seam that unifies them: the consensus layer (Clique/AuRa block sealing, merge engine)
depends only on `ISigner`. The engine signer is either the local `Signer` (holds a
`PrivateKey`, signs in-process) or a `ClefSigner` (delegates out). The plugin swaps
`_nethermindApi.Wallet` and `_nethermindApi.EngineSigner` at init time — no consensus code
knows which is wired.

## Key types / interfaces / files

- `Nethermind.KeyStore/IKeyStore.cs:11` — the keystore contract: `GetKey`/`GetKeyBytes`/
  `GetProtectedKey`, `GenerateKey`/`GenerateProtectedKey`, `StoreKey` (3 overloads),
  `DeleteKey`, `GetKeyAddresses`, `Verify`, plus `Version`/`CryptoVersion` properties.
- `Nethermind.KeyStore/FileKeyStore.cs:50` — the geth-v3 file implementation. `Version => 3`,
  `CryptoVersion => 1`. Decrypt path at `:97` (`GetKeyBytes`); encrypt/persist at `:220`.
- `Nethermind.KeyStore/KeyStoreItem.cs:6` / `Crypto.cs:8` / `KdfParams.cs:8` — the JSON DTO
  for the v3 file (`version`, `id`, `address`, `crypto{ciphertext,cipherparams,cipher,kdf,
  kdfparams,mac}`). JSON property names are lowercased via `[JsonPropertyName]` to match the
  geth/Web3 wire format.
- `Nethermind.KeyStore/Config/KeyStoreConfig.cs:16` — defaults: `Kdf="scrypt"`,
  `Cipher="aes-128-ctr"`, `KdfparamsN=262144, R=8, P=1, Dklen=32`, `IVSize=16` — the geth v3
  scrypt defaults exactly.
- `Nethermind.KeyStore/PrivateKeyStoreIOSettingsProvider.cs:19` — file naming:
  `UTC--{yyyy-MM-dd}T{HH-mm-ss.ffffff}000Z--{address}` — the geth `UTC--…--<addr>` convention.
- `Nethermind.Wallet/IWallet.cs:13` — account/session contract. Note the **default interface
  methods** `TrySignMessage` (EIP-191 hashing at `:28`) and `TrySignTransaction` (RLP-encode,
  sign, apply EIP-155/typed-tx V at `:41`) — shared logic lives on the interface, not copied
  into each impl.
- `Nethermind.Wallet/DevWallet.cs:18` — deterministic dev accounts from a seeded key
  (`_keySeed`, `walletConfig.DevAccounts` count); marked `[DoNotUseInSecuredContext]`.
- `Nethermind.Wallet/ProtectedKeyStoreWallet.cs:17` — production wallet. Unlocks by loading
  from the keystore, wrapping in `ProtectedPrivateKey`, caching in a 100-entry LRU with a
  5-min default expiry (`:19`, `:26`); `TrySign` unprotects only for the signing call and
  disposes (`:84`).
- `Nethermind.Consensus/ISigner.cs:13` — the consensus signer: `ITxSigner` + `Key`,
  `Address`, `TrySign(hash)`, `CanSign`, default `Sign` throwing if unable.
- `Nethermind.Consensus/ISignerStore.cs:8` — the mutable half: `SetSigner(PrivateKey)` /
  `SetSigner(IProtectedPrivateKey)`. Splitting read (`ISigner`) from write (`ISignerStore`)
  lets a remote signer implement the read side and hard-refuse the write side.
- `Nethermind.Consensus/Signer.cs:14` — local in-process signer; holds the `PrivateKey`,
  signs via `SecP256k1.SignCompact`, applies EIP-155 chain-id V (`:56`).
- `Nethermind.ExternalSigner.Plugin/ClefWallet.cs:19` — the remote `IWallet`. `GetAccounts` →
  `account_list` (`:37`); `TrySign` → `account_signData` with `text/plain` (`:70`);
  `TrySign(BlockHeader)` → `account_signData` with `application/x-clique-header` and the full
  RLP header so Clef parses/decides what to sign (`:101`); `TrySignTransaction` →
  `account_signTransaction` (`:126`). Unlock/lock/import all throw `NotSupportedException`.
- `Nethermind.ExternalSigner.Plugin/ClefSigner.cs:13` — adapts `ClefWallet` to
  `IHeaderSigner, ISignerStore`. `Key` getter throws ("Cannot get private keys from remote
  signer" `:32`), `SetSigner` throws, `TrySign(Transaction)` throws
  ("Remote signing of transactions is not supported" `:49`) — but header + message signing
  work, which is what Clique/AuRa sealing needs.
- `Nethermind.ExternalSigner.Plugin/ClefSignerPlugin.cs:16` — the wiring. Enabled iff
  `miningConfig.Signer` is set (`:27`); builds a `BasicJsonRpcClient` to that URI, replaces
  `api.Wallet` with the `ClefWallet`, and (if mining) sets `api.EngineSigner` to the
  `ClefSigner` for the configured `BlockAuthorAccount` (`:46`–`:50`).

## Design decisions & rationale

- **Two interfaces, three back-ends, one consensus seam.** Consensus code depends on
  `ISigner`; account access depends on `IWallet`. Both the in-process path and the remote
  path implement the same interfaces, so the choice of "key in memory" vs "key in Clef/HSM"
  is a DI/plugin decision, not a code fork.
- **Read/write split on the signer (`ISigner` vs `ISignerStore`).** A remote signer can
  honestly implement "sign this" while making "here is the private key to sign with" a
  compile-time-visible unsupported operation. `ClefSigner.SetSigner` throwing is the whole
  point — you cannot accidentally inject a local key into a remote-signing deployment.
- **Protected-key caching for the production wallet.** Unlocked keys are never held as bare
  `PrivateKey`; they are `ProtectedPrivateKey` (OS-level DPAPI/keyed protection), cached with
  a bounded LRU + time expiry, and unprotected only for the duration of one signature.
- **Clef delegates the *decision*, not just the *math*.** Nethermind sends Clef the full RLP
  header / typed transaction and a content-type (`application/x-clique-header`,
  `text/plain`), and Clef parses and decides what/whether to sign. This preserves Clef's
  policy/audit layer (rules, confirmations) instead of treating it as a dumb signing oracle.

## Notable patterns (the reusable idea)

**The signer-provider seam.** A single narrow `ISigner` interface, with `ISignerStore` split
off for mutation, lets the entire consensus/sealing layer be agnostic to where the key
lives: in-process (`Signer`), keystore-unlocked (via `ProtectedKeyStoreWallet`), or fully
external (`ClefSigner` → `ClefWallet` → Clef over JSON-RPC). Adding external/HSM signing was
a *plugin* that swaps two API fields at init — zero changes to block production. Secondary
reusable ideas: (a) putting shared sign logic (EIP-191 hashing, EIP-155 V application,
RLP-then-sign) as **default methods on `IWallet`** so every back-end inherits it; (b) the
Clef content-type protocol that ships the full structured payload so the remote signer keeps
its own policy engine.

## Authority note

nethermind = the fullest account stack — keystore + wallet + Clef-compatible ExternalSigner
+ validator/block-author remote-signing, all behind `IKeyStore`/`IWallet`/`ISigner`. geth
ships a keystore + Clef (as a separate external tool). besu exposes a `SecurityModule`/HSM
abstraction and web3signer as a separate service. Nethermind is the only client here that
carries **all three tiers in-tree** and lets the external signer stand in for the *validator*
key, not just user accounts. For the geth-v3 keystore file format itself (Web3 Secret
Storage v3), geth is the canonical reference; nethermind's `FileKeyStore` is a faithful
re-implementation (v3, scrypt N=262144/r=8/p=1, `aes-128-ctr`, `UTC--…--<addr>` naming).

## Gotchas / anti-patterns / things they later changed

- **`FileKeyStore` carries `[DoNotUseInSecuredContext("Untested, also uses lots of unsafe
  software key generation techniques")]`** (`FileKeyStore.cs:49`). The *production* signing
  path is `ProtectedKeyStoreWallet` (protected-key cache), not raw `FileKeyStore` handing out
  bare `PrivateKey`s. Treat `GetKey`/`GetKeyBytes` as lower-level than `GetProtectedKey`.
- **Two AES-key-derivation conventions coexist.** For the geth-compatible default
  (`aes-128-ctr`, or any non-`aes-128-cbc` cipher) the AES key is the first 16 bytes of the
  scrypt output directly. Only the legacy `scrypt`+`aes-128-cbc` path applies an *extra*
  `Keccak.Compute(derivedKey[0..16])` (`FileKeyStore.cs:157`, `:235`) — that's the old
  Parity/Mantis format. A keystore reader that only implements one convention will silently
  fail MAC verification on the other. (Relevant to fukuii: Mantis-lineage keystores may be in
  the cbc form.)
- **MAC is Web3-standard** `keccak256(derivedKey[16..32] ++ ciphertext)` (`:149`, `:253`),
  compared with `CryptographicOperations.FixedTimeEquals` (constant-time) — don't hand-roll a
  `==` MAC check.
- **scrypt thread-safety workaround:** for `N > 8192` they force single-threaded
  `SCrypt.ComputeDerivedKey` (`:139`) to avoid a stack overflow in the multi-thread SMix — a
  library-specific footgun worth noting if fukuii uses a different scrypt impl.
- **Clef can't do everything a local signer can.** `ClefSigner.TrySign(Transaction)` and
  `SetSigner` throw; `ClefWallet` unlock/lock/import throw `NotSupportedException`. Header +
  message (`account_signData`) and `account_signTransaction` work, but generic
  transaction-hash signing via the `ISigner` path is unsupported — the remote path covers
  *sealing*, not arbitrary tx signing through that interface.
- **Clef quirks encoded in the client:** Clef returns recid `0/1` without the v-offset for
  headers, so `ClefWallet` reconstructs the `Signature` accordingly (`:119`); and it strips
  `GasPrice` from EIP-1559 txs before sending because "Clef rejects certain fields if they
  are serialized" (`:131`). These are the kind of interop warts fukuii would hit implementing
  a Clef client.
