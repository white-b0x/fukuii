# erigon — accounts-signer
_Commit/branch documented: f1d79d699e / upstream. Documented 2026-07-13._

## Architecture summary

Erigon **dropped geth's entire account-management stack**. The top-level
`accounts/` directory that geth ships (`accounts/keystore`, `accounts/external`
clef bridge, `accounts/scwallet`, `accounts/usbwallet`, `accounts/abi/bind`
wallet plumbing) is **empty** in erigon — zero `.go` files. Erigon holds **no
user keystore, no wallet, no local signer, and no external-signer bridge**. It
is a pure node/infra client: it does not custody user keys and does not sign
user transactions.

What *does* remain is narrowly scoped and role-specific:

1. **Node identity key** — the devp2p `nodekey` (secp256k1 ECDSA), managed by a
   tiny `p2p.NodeKeyConfig` helper that loads / parses-from-hex / generates+saves
   a single file under the datadir. This is the only key erigon persists by
   default, and it is a network-identity key, not an account key.
2. **The account-signing RPCs are deprecated stubs.** `eth_accounts`,
   `eth_sign`, `eth_signTransaction` all return "the method has been
   deprecated" errors — there is no key material behind them.
3. **Consensus/validator signing** for the PoA-family engines it retains (Bor
   for Polygon, AuRa for Gnosis) is expressed only as a **`SignerFn` callback**
   (`func(signer, mimeType, message) ([]byte, error)`) injected via an
   `Authorize(...)` method. Erigon defines the seam but wires it **only in
   tests** on this branch — the production caller for Bor block-*production*
   signing is not present in the node path here (erigon typically runs Polygon
   as a syncing/RPC node, with block production external).
4. The word "account" survives mainly as the **state account** (the trie
   record: nonce/balance/storageRoot/codeHash) in
   `execution/types/accounts` — semantically unrelated to wallet accounts.

## Key types / interfaces / files

- `accounts/` — **empty directory** (0 `.go` files). geth's keystore / clef /
  hardware-wallet / bind stack is entirely removed. This is the headline fact.
- `p2p/node_key_config.go:27` — `type NodeKeyConfig struct{}`, the sole persistent
  key manager. `DefaultPath` = `<datadir>/nodekey`.
  - `:31` `generateKey()` → `crypto.GenerateKey()` (secp256k1)
  - `:47` `parseHex(hex)` → `crypto.HexToECDSA`
  - `:53` `load(keyfile)` → `crypto.LoadECDSA`
  - `:61` `save(keyfile, key)` → `crypto.SaveECDSA`, `MkdirAll(0755)`
  - `:71` `LoadOrGenerateAndSave` — load if present, else generate+persist
  - `:88` `LoadOrParseOrGenerateAndSave(file, hex, datadir)` — file/hex are
    mutually exclusive; else default to load-or-generate at the datadir path
- `cmd/utils/flags.go:1202` — `setNodeKey(...)` wires the `--nodekey` (file) /
  `--nodekeyhex` CLI flags through `NodeKeyConfig` into `cfg.PrivateKey`. This is
  the entire "key management" story for a normal erigon node.
- `rpc/jsonrpc/eth_deprecated.go:27` — `Accounts()` (`eth_accounts`) returns
  `[]common.Address{}` + deprecated error.
  - `:35` `Sign()` (`eth_sign`) → deprecated error, no signing.
  - `:40` `SignTransaction()` (`eth_signTransaction`) → deprecated error.
- `rpc/jsonrpc/error_messages.go:26` — `const NotAvailableDeprecated = "the
  method has been deprecated: %s"` — the shared "no wallet here" message.
- `polygon/bor/bor.go:107` — `type SignerFn func(signer accounts.Address,
  mimeType string, message []byte) ([]byte, error)` — the validator-signing seam
  (callback, so the private key lives outside the engine).
  - `:917` `func (c *Bor) Authorize(currentSigner, signFn SignerFn)` — injects the
    signer address + callback into the engine.
  - `:972` `signFn(signer, accounts.MimetypeBor, BorRLP(header, c.config))` — the
    header seal is delegated to the injected callback, not to any internal key.
- `polygon/bor/bor_test.go:289` — the **only** caller of `bor.Authorize(...)` on
  this branch; production block-production wiring is absent from the node path.
- `execution/protocol/rules/aura/aura.go:893` — `func (c *AuRa) Authorize(signer,
  signFn SignerFn)` — the identical callback seam for the Gnosis AuRa PoA engine.
- `execution/types/accounts/account.go:33` — `type Account struct { Nonce,
  Balance, Root, CodeHash, Incarnation, ... }` — the **state** account (trie
  record), not a wallet. `:44` defines the `Mimetype*` signing-domain constants
  (`MimetypeBor`, `MimetypeTypedData`, `MimetypeDataWithValidator`,
  `MimetypeTextPlain`).
- `execution/abi/bind/auth.go:37` — `ErrNotAuthorized` from the bind transactor
  auth path (contract-deployment tooling helper), not node key custody.
- `plugins/auth/signer_eoa.go:27` — `EOASigner` **verifies** ERC-191
  `personal_sign` signatures for UCAN auth tokens (`did:pkh:eip155:`). This is
  request-authentication, not blockchain transaction signing — it never holds a
  private key, only recovers/verifies.

## Design decisions & rationale

- **Node ≠ wallet.** Erigon's product position is a fast archive/sync/RPC node.
  Custodying user keys is out of scope, so the keystore/wallet/clef stack was
  deleted outright rather than maintained. Users sign transactions in an
  external wallet and submit via `eth_sendRawTransaction`.
- **Deprecate, don't fake.** Rather than silently omit the account RPCs, erigon
  keeps the method surface but returns explicit "deprecated" errors, so callers
  fail loudly instead of getting empty-but-successful results.
- **Signing as an injected callback.** For the PoA engines it inherited (Bor,
  AuRa), the key never lives inside the consensus engine — it is a `SignerFn`
  supplied by whoever runs the producer. This keeps key handling decoupled from
  consensus logic and lets the actual key source be anything.
- **One persistent key, and it's an identity key.** The only key erigon writes
  by default is the devp2p `nodekey`, which establishes peer identity/ENR — not
  spendable value.

## Notable patterns (the reusable idea)

- **The `Authorize(addr, SignerFn)` callback seam.** Consensus-signing engines
  take an injected `func(signer, mimeType, message) ([]byte, error)` instead of
  owning a private key. The key source (in-memory, file, or a future external
  signer) is fully pluggable behind one function type, and the engine stays pure.
  This is the same shape geth uses for Clique/`SignerFn`, preserved here.
- **Mimetype-tagged signing domains** (`accounts.MimetypeBor`, etc.) keep the
  callback generic while letting the signer distinguish payload kinds.
- **Explicit deprecation stubs** as a migration/compat pattern: keep the RPC
  method registered but return a typed "not available" error.

## Authority note

geth = keystore + clef external-signer authority (full local wallet, encrypted
keystore, hardware wallets, USB, clef bridge). besu = SecurityModule / HSM
abstraction (pluggable external signer, no built-in soft wallet by default).

**Erigon sits at the far *minimal* end of the spectrum — further than besu.**
It offers **neither** a local keystore/wallet (geth) **nor** an
external-signer/HSM abstraction (besu SecurityModule). It manages exactly one
key — the devp2p nodekey — and exposes consensus signing only as an
inject-your-own-callback (`SignerFn`) seam, wired only in tests here. For
account/user signing it defers entirely to external tooling. So on the
geth ↔ besu ↔ "external-only" axis: **geth (rich soft wallet) → besu (HSM
abstraction) → erigon (no account custody at all, bring-your-own signer)**.

## Gotchas / anti-patterns / things they later changed

- **Do not expect `accounts/` to contain anything.** The directory exists but is
  empty; the surviving `accounts` *package* is `execution/types/accounts` (state
  account + mimetypes), which is easy to mistake for a wallet package. Two
  different meanings of "account" — state record vs. wallet — and only the state
  one exists.
- **`eth_sign` / `eth_accounts` / `eth_signTransaction` do not work** — they
  return "deprecated" errors. Any integration that assumed node-side signing
  must move signing client-side.
- **`Bor.Authorize` / `AuRa.Authorize` are seams, not wired producers here.** On
  this branch the only caller is `bor_test.go`. Reading the presence of
  `SignerFn`/`Authorize` as "erigon signs Polygon blocks in production" would be
  wrong for the node path documented — production Bor block production is
  external; erigon primarily syncs/serves Polygon.
- **`plugins/auth/signer_eoa.go` is not a wallet.** Despite "Signer" in the
  name, it only *verifies* ERC-191 signatures for API auth tokens; it holds no
  key and signs nothing on-chain. Do not conflate it with transaction signing.

## fukuii takeaway

fukuii offers **both** a local keystore/wallet (geth-style) **and** external
signing — it is on the rich-custody end. Erigon is the opposite extreme: no
account custody, no HSM abstraction, one nodekey, consensus signing only as an
injectable `SignerFn` callback. The reusable idea worth carrying is the clean
`Authorize(addr, SignerFn)` seam that keeps the private key *outside* the
consensus engine — fukuii can keep its full wallet while still routing
validator/sealer signing through a pluggable callback so an external signer/HSM
can be dropped in without touching consensus code.
