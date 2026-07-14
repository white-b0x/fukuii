# besu — accounts-signer
_Commit/branch documented: 3fd233a4f9 / upstream. Documented 2026-07-13._

## Architecture summary

besu is deliberately **node-focused**: it manages exactly one private key — the
node's own **identity/signing key** — and ships **no user-account keystore or
wallet** at all. There is no `personal_*` RPC namespace with signing methods in
the main source (the `PERSONAL` enum name survives in `RpcApis`, but zero
`personal_*` method classes exist under `api/jsonrpc/.../methods`, and `personal`
is not in the default or "all valid" RPC API list). User transactions are
expected to be signed **externally** (a wallet, EthSigner/Web3Signer, or a dApp)
and submitted pre-signed via `eth_sendRawTransaction`. besu itself never holds
user funds' keys.

The one key besu does hold is wrapped behind a **`SecurityModule`** abstraction —
a pluggable seam that hides the private key from the rest of the application and
exposes only `sign`, `getPublicKey`, and ECDH-agreement operations. The default
implementation (`KeyPairSecurityModule`) keeps the key on disk; a plugin can
register an alternative (HSM/vault/remote signer) that never materializes the key
in-process. This node key does triple duty: RLPx/devp2p identity + handshake
(ECDH), discovery (DiscV5), and — for BFT consensus (IBFT/QBFT) and Clique — the
**validator signing key** that produces proposal/commit **seals**.

## Key types / interfaces / files

- `plugin-api/.../plugin/services/securitymodule/SecurityModule.java:30` — the
  pluggable signing seam. "Wrap/hide a cryptographic private key … without
  releasing the content of the private key." Methods: `sign(Bytes32)`,
  `getPublicKey()`, `calculateECDHKeyAgreement(...)`, and a defaulted
  `calculateECDHKeyAgreementCompressed(...)` (DiscV5). Marked `@Unstable`.
- `plugin-api/.../plugin/services/SecurityModuleService.java:28` — the plugin
  registry: `register(name, Supplier<SecurityModule>)` +
  `getByName(name)`. A plugin registers an HSM-backed module under a name; the
  CLI selects it by name.
- `app/.../services/SecurityModuleServiceImpl.java:27` — trivial
  `ConcurrentHashMap<String, Supplier<SecurityModule>>` registry impl.
- `crypto/services/.../cryptoservices/NodeKey.java:30` — the node's signing
  handle. Holds a `SecurityModule` (not a raw key) and delegates `sign` to it,
  then `normaliseSignature(...)` to produce a canonical `SECPSignature`. This is
  what consensus/networking code is handed — they sign through the module, never
  touching key bytes.
- `crypto/services/.../cryptoservices/KeyPairSecurityModule.java:38` — the
  **default** module: wraps a `SECP256K1.KeyPair`, performs sign/ECDH via the
  `SignatureAlgorithm`. This is the only in-tree module (HSM/vault modules live
  in plugins, out of tree).
- `crypto/algorithms/.../crypto/KeyPairUtil.java:83,121` — node-key persistence.
  `loadKeyPair(File)`: if a `key` file exists, load it; else **generate and
  store** a new one. `storeKeyPair(...)` writes the **raw private key as a plain
  hex string** to `<datadir>/key` (atomic temp-file + move). Note: this is a
  bare hex file, **not** a geth-style encrypted (scrypt/JSON) keystore.
- `app/.../cli/BesuCommand.java:591` — `--security-module=<NAME>` CLI option
  (default `"localfile"` / `DEFAULT_SECURITY_MODULE`). `:1304` registers the
  default supplier; `:1308 defaultSecurityModule()` loads the key file and wraps
  it in `KeyPairSecurityModule`; `:2098` builds `new NodeKey(securityModule())`;
  `:2612 securityModule()` resolves the configured name from the registry (throws
  if a plugin didn't register it).
- `app/.../cli/options/NodePrivateKeyFileOption.java:38` — `--node-private-key-file`
  overrides the key path (default `<datadir>/key`).
- `app/.../cli/subcommands/PublicKeySubCommand.java` — `public-key export` /
  `export-address` subcommands: read the node key and print its public
  key/address. This is the extent of besu's "key CLI" — node identity only, no
  account import/new/unlock.
- `crypto/algorithms/.../crypto/{SECP256K1,SECPPrivateKey,SECPPublicKey,SECPSignature,SignatureAlgorithm,SignatureAlgorithmFactory}.java`
  — the low-level curve/signature primitives (secp256k1 default, secp256r1
  available). `SignatureAlgorithmFactory` is a global singleton chosen once at
  startup.
- BFT/Clique validator signing (the node key *is* the validator key):
  `consensus/qbft-core/.../payload/MessageFactory.java:41` and
  `consensus/ibft/.../payload/MessageFactory.java` hold a `NodeKey` and sign
  proposal/prepare/commit/round-change payloads; commit **seals** are
  `SECPSignature`s collected in `BftExtraData`
  (`consensus/common/.../bft/BftExtraData.java`) and sealed via
  `BftHelpers.createSealedBlock(...)`.

## Design decisions & rationale

- **A node is not a wallet.** besu manages only its own identity key; it does not
  custody user account keys. This is a security posture: an RPC-exposed node that
  can't sign user transactions has a far smaller blast radius. Signing is pushed
  to dedicated external signers (EthSigner/Web3Signer) or client-side wallets.
- **Signing behind an interface, not a key field.** Everything that needs a
  signature is handed a `NodeKey`/`SecurityModule`, never the private key bytes.
  This makes "the key never exists in the JVM heap" a *possible* deployment: a
  plugin implements `SecurityModule` against an HSM/KMS/vault, registers it, and
  the operator selects it with `--security-module=<name>`. No consensus or
  networking code changes.
- **One key, three roles, unified.** The same node key serves P2P identity/ECDH,
  discovery, and BFT/Clique validator sealing. Delegating that one key to an HSM
  therefore secures *validator signing* for free — the enterprise/custody story.
- **Named-registry indirection** (`SecurityModuleService`) rather than a hard
  dependency lets the HSM integration live entirely in a plugin jar, keeping the
  core free of vendor SDKs.

## Notable patterns (the reusable idea)

**The `SecurityModule` seam.** Define signing as a narrow capability interface
(`sign(hash) -> Signature`, `getPublicKey()`, `ecdhAgreement()`) and thread that
interface — never the raw key — through consensus and networking. A default
on-disk implementation covers the common case; a plugin registry lets operators
swap in an HSM/vault/remote-signer implementation by name, so the private key can
live entirely outside the process. This cleanly separates *"who can produce a
signature"* from *"where the key material lives,"* which is exactly what
enterprise/custody deployments require.

## Authority note

For accounts-signer, treat besu and geth as the **two ends of the spectrum**:

- **besu = the SecurityModule/HSM-delegation + no-in-node-wallet stance.** Node/
  validator key only, hidden behind a pluggable module; user signing is external.
  This is the authority for the *external-security-module seam* and the
  *node-isn't-a-wallet* posture.
- **geth = the keystore/clef authority.** Encrypted (scrypt) JSON keystore,
  account new/import/unlock, `personal_*` and clef external-signer. This is the
  authority for the *user-account keystore/wallet* end.

fukuii already ships the geth end (a geth-compatible keystore + a key-management
skill). besu is informative precisely because it shows the *other* end fukuii
does **not** yet have: a pluggable security-module/external-signer seam so a
node/validator key can live in an HSM/vault with no key bytes in-process.

## Gotchas / anti-patterns / things they later changed

- **`SecurityModule` is `@Unstable`.** Its API (including the DiscV5 compressed-
  ECDH default method) is explicitly not a stability guarantee — plugin authors
  are warned it can change.
- **The default `key` file is plaintext hex, not encrypted.** `KeyPairUtil`
  writes the raw private key to `<datadir>/key` with no passphrase/scrypt. File-
  system permissions are the only protection; anyone reading the file has the
  node/validator key. (This is exactly the risk the HSM `SecurityModule` path is
  meant to eliminate.)
- **The `PERSONAL` RPC name is a vestige.** The enum constant exists in
  `RpcApis`, but there are no `personal_*` signing methods in the main source and
  it is absent from the default and all-valid API lists — do not assume enabling
  it gives you geth-style account signing. It does not.
- **`SignatureAlgorithmFactory` is a global singleton fixed once at startup**
  (secp256k1 vs secp256r1). It must be configured before first use;
  reconfiguring mid-run is not supported.
- **`--security-module=<name>` throws at startup if the named module was never
  registered by a plugin** (`securityModule()` `orElseThrow`) — the HSM plugin
  must be on the classpath and register under the exact name.
