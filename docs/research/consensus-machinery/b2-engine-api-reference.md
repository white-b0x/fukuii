# B2 — Engine API Reference Map (EL-side, CL↔EL interop)

**Purpose:** Durable external reference for the full EL-side Engine API surface a
post-merge EL must implement, with per-fork version gating, payload/blob validation
rules, error codes, and JWT auth. Reference map only — no fukuii-path claims, no
conformance verdicts. Batch 6's fukuii-vs-reference diff rides on this.

**Authorities:**
- **Spec:** `ethereum/execution-apis`, vendored at
  `/media/dev/2tb/dev/fukuii/.claude/repo-references/ethereum/execution-apis/src/engine/`
  (per-fork files: `paris.md`, `shanghai.md`, `cancun.md`, `prague.md`, `osaka.md`,
  `amsterdam.md`, `common.md`, `authentication.md`). Snapshot dated 2026-06-17.
- **Reference impl:** go-ethereum `eth/catalyst/api.go`, vendored at
  `/media/dev/2tb/dev/fukuii/.claude/repo-references/clients/go-ethereum/eth/catalyst/api.go`
  (snapshot 2026-07-01). go-ethereum is the canonical EL Engine API implementation.

> **2026-current caveat (uncertainty flag):** This assistant's knowledge cutoff is
> Jan 2026. **Osaka (Fusaka, EIP-7594 PeerDAS)** and everything after it — the
> **BPO1–BPO5** (Blob-Parameter-Only) forks and **Amsterdam (Glamsterdam)** — are
> 2026 items. The method surface for them below is transcribed *directly from the
> vendored spec/go-ethereum snapshots*, which are the authority, but treat the
> Amsterdam methods (`newPayloadV5`, `getPayloadV6`, `forkchoiceUpdatedV4`,
> `getBlobsV4`, `getPayloadBodies*V2`) as **draft, subject to change** — Glamsterdam
> was not finalized as of these snapshots. Verify against the live spec before the
> diff step lands.

---

## 1. Per-fork version-gating matrix (the load-bearing table)

Version selection is **timestamp-driven** (PoS): the EL enforces that the payload's
`timestamp` falls in the fork range the called method version serves. go-ethereum
enforces this via `checkFork(timestamp, allowedForks...)` (`api.go`, cited per row).
Wrong-version-for-fork → `-38005 Unsupported fork`; wrong param shape → `-32602
Invalid params` / `-38003`.

| Method | Introduced | Valid at forks (go-ethereum gate) | Gate cite |
|--------|-----------|-----------------------------------|-----------|
| `engine_newPayloadV1` | Paris | Paris only; `Withdrawals` must be nil | `api.go:720-724` |
| `engine_newPayloadV2` | Shanghai | Paris & Shanghai; withdrawals nil pre-/non-nil post-Shanghai; blob fields nil | `api.go:727-745` |
| `engine_newPayloadV3` | Cancun | **Cancun only**; requires withdrawals, excessBlobGas, blobGasUsed, versionedHashes, beaconRoot | `api.go:748-764` |
| `engine_newPayloadV4` | Prague | Prague, Osaka, BPO1–5; adds `executionRequests` | `api.go:767-789` |
| `engine_newPayloadV5` | Amsterdam ⚠ | Amsterdam; adds `slotNumber` | `api.go:792-816` |
| `engine_forkchoiceUpdatedV1` | Paris | Paris & Shanghai | `api.go:168-177` |
| `engine_forkchoiceUpdatedV2` | Shanghai | Paris & Shanghai; withdrawals gating in `PayloadAttributes` | `api.go:182-195` |
| `engine_forkchoiceUpdatedV3` | Cancun | Cancun, Prague, Osaka, BPO1–5 | `api.go:200-215` |
| `engine_forkchoiceUpdatedV4` | Amsterdam ⚠ | Amsterdam | `api.go:220-237` |
| `engine_getPayloadV1` | Paris | Paris | `api.go:437` |
| `engine_getPayloadV2` | Shanghai | Paris & Shanghai | `api.go:451-457` |
| `engine_getPayloadV3` | Cancun | Cancun | `api.go:462-467` |
| `engine_getPayloadV4` | Prague | Prague | `api.go:473-478` |
| `engine_getPayloadV5` | Osaka | Osaka | `api.go:487-493` |
| `engine_getPayloadV6` | Amsterdam ⚠ | Amsterdam | `api.go:504` |
| `engine_getBlobsV1` | Cancun | **Cancun & Prague only** — rejected once Osaka active | `api.go:555-575` |
| `engine_getBlobsV2` | Osaka | Osaka+ (all-or-nothing; `BlobsBundleV2`/cell proofs) | `api.go:621-627` |
| `engine_getBlobsV3` | Osaka | Osaka+ (partial responses allowed) | `api.go:632-638` |
| `engine_getBlobsV4` | Amsterdam ⚠ | Amsterdam (cell-level partial, `BlobCellsAndProofsV1`) | `osaka.md`/`amsterdam.md:250-275` |
| `engine_getPayloadBodiesByHashV1` | Shanghai | any (bodies by hash) | `shanghai.md:168-194` |
| `engine_getPayloadBodiesByRangeV1` | Shanghai | any (bodies by range) | `shanghai.md:198-231` |
| `engine_getPayloadBodiesByHashV2` | Amsterdam ⚠ | Amsterdam | `amsterdam.md:155-177` |
| `engine_getPayloadBodiesByRangeV2` | Amsterdam ⚠ | Amsterdam | `amsterdam.md:179-202` |
| `engine_exchangeTransitionConfigurationV1` | Paris | **deprecated at Cancun** (`cancun.md:34`) | `paris.md:269-297` |
| `engine_exchangeCapabilities` | Paris | always; MUST be supported by EL | `common.md:142-178` |
| `engine_getClientVersionV1` | Cancun | always (client identification) | `api.go:1151` |

**Fork order** (timestamp-forks): Paris → Shanghai → Cancun → Prague → Osaka →
BPO1…BPO5 → Amsterdam. BPO forks are blob-parameter-only (target/max blob count bumps)
between Osaka and Amsterdam — they reuse the V4/V3 method shapes (`api.go:207,782`),
they do **not** add new method versions.

---

## 2. Core structures, by fork increment

- **`ExecutionPayloadV1`** (Paris, `paris.md:41-58`): parentHash, feeRecipient,
  stateRoot, receiptsRoot, logsBloom(256B), prevRandao, blockNumber, gasLimit,
  gasUsed, timestamp, extraData(0–32B), baseFeePerGas(256b), blockHash, transactions[].
- **`ExecutionPayloadV2`** (Shanghai): V1 + `withdrawals[]` (EIP-4895).
- **`ExecutionPayloadV3`** (Cancun, `cancun.md:43-61`): V2 + `blobGasUsed`,
  `excessBlobGas` (EIP-4844).
- **`ForkchoiceStateV1`** (`paris.md:60-68`): headBlockHash, safeBlockHash,
  finalizedBlockHash. safe/finalized MAY be zero until a transition block is finalized;
  safe MUST equal or be an ancestor of head.
- **`PayloadAttributesV1/V2/V3`**: V1 = timestamp, prevRandao, suggestedFeeRecipient;
  V2 adds `withdrawals[]` (Shanghai); V3 adds `parentBeaconBlockRoot` (Cancun).
- **`PayloadStatusV1`** (`paris.md:78-84`): `status ∈ {VALID, INVALID, SYNCING,
  ACCEPTED, INVALID_BLOCK_HASH}`, `latestValidHash`, `validationError`.
- **`BlobsBundleV1`** (Cancun, `cancun.md:63-71`): `commitments[]`, `proofs[]`,
  `blobs[]` (each blob 131072B = 4096×32).
- **`BlobsBundleV2`** (Osaka, `osaka.md:35-43`): proofs are **cell** KZG proofs
  (EIP-7594) — `len(proofs) == CELLS_PER_EXT_BLOB * len(blobs)`; `blobs`/`commitments`
  equal length.
- **`BlobAndProofV1`** (Cancun) / **`BlobAndProofV2`** (Osaka) / **`BlobCellsAndProofsV1`**
  (Amsterdam ⚠, `amsterdam.md:96-101`).
- Amsterdam ⚠ payload adds `slotNumber` (`api.go:807`).

---

## 3. Validation rules that must be enforced

### newPayload (all versions)
1. Every entry of `transactions` MUST be non-zero length (`paris.md:164`); else
   `{INVALID, latestValidHash: null}`.
2. `blockHash` MUST equal `Keccak256(RLP(ExecutionBlockHeader))` reconstructed from
   payload fields per EIP-3675 + EIP-4399 (`paris.md:166`); mismatch →
   `INVALID_BLOCK_HASH`.
3. **Param-shape gating** (go-ethereum `api.go:721-816`): each version rejects payloads
   carrying fields from a later fork and rejects nil fields required by its fork —
   V1 no withdrawals; V2 withdrawals-presence tied to Shanghai, blob fields nil; V3
   requires withdrawals+excessBlobGas+blobGasUsed+versionedHashes+beaconRoot; V4 adds
   required `executionRequests` (then `validateRequests`, `api.go:786`); V5 adds
   required `slotNumber`.
4. **Blob-hash cross-check (V3+, `cancun.md:114-118`):** concatenate
   `tx.blob_versioned_hashes` across all blob txs in inclusion order and assert it
   equals the `expectedBlobVersionedHashes` param (empty `[]` if no blob txs).
5. Full payload validation per the block header + execution rule set (EIP-3675 Block
   Validity), returning `{VALID, latestValidHash: blockHash}` or `{INVALID,
   latestValidHash: <deepest valid ancestor>}` (`paris.md:103-112`). `ACCEPTED` when
   the payload is a valid-but-non-canonical extension not yet fully validated
   (`paris.md:180-185`). Validation MUST be idempotent (`paris.md:114`).

### forkchoiceUpdated (all versions)
- `status` restricted to `{VALID, INVALID, SYNCING}` here (`paris.md:202-206`).
- Returns `payloadId` (8B) when `payloadAttributes` is present and valid and head is
  VALID → begins a build process (`paris.md:227-231`); else `payloadId: null`.
- Errors: `-38002 Invalid forkchoice state` when finalized/safe not in head's chain
  (`paris.md:219`); `-38003 Invalid payload attributes` when attrs invalid (e.g.
  `attributes.timestamp <= head.timestamp`, `paris.md:229`); `-38006 Too deep reorg`
  (`paris.md:221`). A forkchoice update MUST NOT roll back on an attributes failure
  (`paris.md:233`).

### getPayload
- Returns the most recent build for `payloadId` (`paris.md:263`); `-38001 Unknown
  payload` if the build process doesn't exist (`paris.md:265`).
- V2+ wraps payload in an envelope with `blockValue`; V3+ adds `blobsBundle`
  (`BlobsBundleV1`) + `shouldOverrideBuilder`; V5 switches to `BlobsBundleV2` (cell
  proofs) and MUST satisfy `verify_cell_kzg_proof_batch` (`osaka.md:56-92`); V4 keeps
  `BlobsBundleV1` but for Prague requests.

### getBlobs
- Response order MUST match request order, `null` for missing (`cancun.md:206-208`).
- MUST support ≥128 versioned hashes; larger → `-38004 Too large request`
  (`cancun.md:210`). MAY return all-`null` when syncing (`cancun.md:212`). Callers
  must tolerate pruned blobs.
- **V1 is Cancun/Prague-only and is rejected once Osaka activates** (`api.go:568-572`,
  per `osaka.md#cancun-api`) — CLs switch to V2/V3 at Osaka. V2 is all-or-nothing
  (`null` if *any* requested blob missing, `osaka.md:114-120`); V3 permits partial
  (`osaka.md:122-124`).

---

## 4. Capabilities & client identification
- `engine_exchangeCapabilities(list)` → EL returns its supported `engine_*` method
  names; MUST be supported by EL, MUST NOT include `exchangeCapabilities` itself in
  the response (`common.md:144-178`). go-ethereum builds the list reflectively from
  its `ConsensusAPI` methods (`api.go:1138-1149`).
- `engine_getClientVersionV1(ClientVersionV1)` → EL client code/name/version/commit
  (`api.go:1151-`).

## 5. Error codes (`common.md:91-102`)
| Code | Name |
|------|------|
| -32700 | Parse error |
| -32600 | Invalid Request |
| -32601 | Method not found |
| -32602 | Invalid params |
| -32603 | Internal error |
| -32000 | Server error (carries `data.err`) |
| -38001 | Unknown payload |
| -38002 | Invalid forkchoice state |
| -38003 | Invalid payload attributes |
| -38004 | Too large request |
| -38005 | Unsupported fork |
| -38006 | Too deep reorg |

## 6. JWT authentication (`authentication.md`)
- Engine API MUST be on a **port independent** of the public JSON-RPC; default
  **8551**, `engine` namespace (`authentication.md:26-27`).
- **HS256 (HMAC+SHA256) MUST be supported; `alg: none` MUST be rejected**
  (`authentication.md:28-29`).
- HTTP: every request individually JWT-authenticated via HTTP header. WebSocket: only
  the upgrade handshake is authenticated. Local IPC: no auth required
  (`authentication.md:17-21`).
- Secret: 256-bit hex in a `jwt-secret` file; if absent, generate and persist
  `jwt.hex` (`authentication.md:34-40`).
- Claims: **`iat` required, accepted only within ±60s of current time**; optional `id`,
  `clv`; unknown claims MUST be ignored (`authentication.md:42-50`).

---

## 7. What a conforming EL Engine API MUST provide (checklist)
1. All `newPayloadV1..V5`, `forkchoiceUpdatedV1..V4`, `getPayloadV1..V6`,
   `getBlobsV1..V4`, `getPayloadBodiesBy{Hash,Range}V1/V2`, `exchangeCapabilities`,
   `getClientVersionV1` — with the exact per-fork timestamp gating in §1.
2. Reject wrong-version-for-fork with `-38005`; reject wrong param shape (missing/extra
   fork fields) with `-32602`/`-38003`.
3. Enforce the tx-non-empty, blockHash-recompute, and blob-versioned-hash cross-checks
   on newPayload; return the correct `{status, latestValidHash}` taxonomy incl.
   `ACCEPTED`/`SYNCING`/`INVALID_BLOCK_HASH`.
4. fcU build-process semantics: `payloadId` iff valid attributes on a VALID head; the
   three fcU-specific errors.
5. Blob bundle KZG semantics per fork (V1 blob proofs Cancun/Prague; V2 cell proofs
   Osaka+); getBlobsV1 rejection at Osaka.
6. Full error-code table; JWT/HS256 on port 8551, `iat` ±60s, IPC exempt.

**Not covered here:** the CL side, `eth`-namespace JSON-RPC, and payload *execution*
semantics (state transition) — those are separate surfaces from the Engine API
handshake B2 targets.
