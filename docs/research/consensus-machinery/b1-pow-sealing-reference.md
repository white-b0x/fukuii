# B1 — PoW Sealing Reference Map (Ethash + ETChash / ECIP-1099)

**Purpose:** Durable external reference for what fukuii's PoW seal-verify and mining
paths MUST do, per the ETC/PoW authority. This is a *reference map*, not a gap
analysis — no fukuii-path claims, no conformance verdicts. The later Batch 6 diff
step rides on this.

**Authority:** core-geth is the sole PoW/ETC authority (Ethash + ETChash). All
citations below are to the vendored core-geth tree:
`/media/dev/2tb/dev/fukuii/.claude/repo-references/clients/core-geth/consensus/ethash/`
(mirrored at `/media/dev/2tb/dev/reference-clients-evm/core-geth/`). Config-block
citations are to `.../core-geth/params/`.

Line numbers are as of the vendored snapshot (2026-07-02). go-ethereum's upstream
ethash was frozen at The Merge and carries no ETChash; **do not** treat go-ethereum
as the PoW authority here — only core-geth (ex-multi-geth) implements ECIP-1099.

---

## 1. Constants (the fixed parameters)

`algorithm.go:36-50`:

| Constant | Value | Meaning |
|----------|-------|---------|
| `datasetInitBytes` | `1 << 30` (1 GiB) | Dataset (DAG) size at genesis epoch |
| `datasetGrowthBytes` | `1 << 23` (8 MiB) | Dataset growth per epoch |
| `cacheInitBytes` | `1 << 24` (16 MiB) | Verification cache size at genesis epoch |
| `cacheGrowthBytes` | `1 << 17` (128 KiB) | Cache growth per epoch |
| `epochLengthDefault` | `30000` | Blocks per epoch (classic Ethash) |
| `epochLengthECIP1099` | `60000` | Blocks per epoch once ECIP-1099 (ETChash) is active |
| `mixBytes` | `128` | Width of the mix |
| `hashBytes` | `64` | Hash length (one dataset/cache row) |
| `hashWords` | `16` | 32-bit ints per hash (`64/4`) |
| `datasetParents` | `256` | Parents combined per dataset item |
| `cacheRounds` | `3` | RandMemoHash rounds during cache production |
| `loopAccesses` | `64` | Dataset accesses in the hashimoto loop |
| `maxEpoch` | `2048` | Size of the precomputed size lookup tables |

`two256 = 2^256` — `ethash.go:50`. FNV prime `0x01000193` — `algorithm.go:243,249`.

---

## 2. ETChash divergence from Ethash — the ECIP-1099 hinge

ETChash **is** Ethash with a single parameter change: at the `ecip1099FBlock`
activation block the epoch length doubles from 30000 → 60000. Everything downstream
(cache size, dataset size, seed) keys off `epochLength`, so this one switch changes
DAG growth cadence without changing the hash function itself. This halves DAG growth
rate, addressing the DAG-size problem for 3–4 GB GPUs.

**The switch — `algorithm.go:52-60`:**
```go
func calcEpochLength(block uint64, ecip1099FBlock *uint64) uint64 {
    if ecip1099FBlock != nil {
        if block >= *ecip1099FBlock {
            return epochLengthECIP1099   // 60000
        }
    }
    return epochLengthDefault            // 30000
}
```

`ecip1099FBlock` is a `*uint64` config field (`ethash.go:564` `ECIP1099Block *uint64`);
`nil` means "never activate" (plain Ethash, e.g. ETH mainnet). Every size/seed
function threads `epochLength` explicitly rather than assuming 30000.

**Epoch mapping (post-switch):** `calcEpoch(block, epochLength) = block / epochLength`
(`algorithm.go:63-66`); `calcEpochBlock(epoch, epochLength) = epoch*epochLength + 1`
(`algorithm.go:69-71`). So after activation, epoch numbers restart on the 60000
cadence — the same block number maps to a *different, smaller* epoch than it would
have under 30000.

**Activation blocks (must land on an epoch boundary):**
| Network | `ECIP1099FBlock` | Source |
|---------|------------------|--------|
| ETC mainnet | `11_700_000` | `params/config_classic.go:92` |
| Mordor testnet | `2_520_000` | `params/config_mordor.go:81` |
| MintMe | `nil` (Etchash disabled) | `params/config_mintme.go:75` |

Both activation blocks are exact multiples of 30000 (11,700,000 = 390×30000;
2,520,000 = 84×30000). The `lru.get` "future item" logic (`ethash.go:255-264`)
hard-requires this: `nextEpochBlock == *ecip1099FBlock && epochLength ==
epochLengthDefault` — the activation block **must** sit at the start of an epoch, or
the future-cache prefetch mis-predicts.

**The seedHash subtlety — `algorithm.go:139-155`:** the seed loop always iterates
`block / epochLengthDefault` (30000) times, *not* `block / epochLength`:
```go
func seedHash(epoch uint64, epochLength uint64) []byte {
    block := calcEpochBlock(epoch, epochLength)  // uses the ACTIVE epochLength
    ...
    for i := 0; i < int(block/epochLengthDefault); i++ {   // but divides by 30000
        keccak256(seed, seed)
    }
}
```
This keeps seed continuity across the 30000→60000 transition: the seed is a function
of the *block height* (via /30000), independent of which epoch length is active, so
DAGs stay consistent across the fork. A conforming implementation MUST reproduce this
"active epochLength for the epoch-block, fixed 30000 divisor for the seed rounds"
split exactly — getting it wrong desyncs every post-1099 DAG.

---

## 3. Cache generation (light-client verification cache)

`generateCache(dest, epoch, epochLength, seed)` — `algorithm.go:163-229`.

1. **Size:** `cacheSize(epoch)` — table lookup for `epoch < maxEpoch` (`cacheSizes`
   table at `algorithm.go:837`), else `calcCacheSize` (`algorithm.go:85-91`):
   `size = cacheInitBytes + cacheGrowthBytes*epoch - hashBytes`, then step down by
   `2*hashBytes` until `size/hashBytes` is prime.
2. **Sequential fill:** `keccak512(cache[0], seed)`, then each subsequent 64-byte row
   = `keccak512` of the previous row (`algorithm.go:204-208`).
3. **RandMemoHash:** `cacheRounds` (3) passes of Sergio Lerner's RandMemoHash — for
   each row `j`: `xorOff = LE_uint32(cache[dstOff]) % rows`, then
   `cache[j] = keccak512(cache[(j-1+rows)%rows] XOR cache[xorOff])`
   (`algorithm.go:212-224`).
4. **Endianness:** swap to LE on big-endian hosts (`algorithm.go:226-228`).

Cache is `[]uint32` in machine byte order; row = `hashBytes` (64B) = `hashWords` (16)
uint32s.

---

## 4. Dataset (DAG) generation

`generateDatasetItem(cache, index, keccak512)` — `algorithm.go:255-284`:
- `rows = len(cache)/hashWords`.
- Init mix = `cache[(index%rows)*hashWords]` with `mix[0] ^= index`, keccak512'd.
- FNV-mix `datasetParents` (256) parents:
  `parent = fnv(index^i, intMix[i%16]) % rows`, then `fnvHash(intMix, cache[parent...])`
  (`algorithm.go:274-277`).
- Final keccak512 → one 64-byte dataset item.

`generateDataset(dest, epoch, epochLength, cache)` — `algorithm.go:288-348`: parallel
over `runtime.NumCPU()` threads, each generating a contiguous item range; result in
machine byte order, swapped on big-endian. Dataset size = `datasetSize(epoch)`
(`datasetSizes` table `algorithm.go:423`, else `calcDatasetSize` `algorithm.go:105-111`:
`size = datasetInitBytes + datasetGrowthBytes*epoch - mixBytes`, step down by
`2*mixBytes` until `size/mixBytes` prime).

`fnv(a,b) = a*0x01000193 ^ b` (`algorithm.go:242-244`); `fnvHash` applies it
element-wise (`algorithm.go:247-251`).

---

## 5. The hashimoto seal-verify loop (the core)

`hashimoto(hash, nonce, size, lookup)` — `algorithm.go:352-390`. This is the exact
function the seal check must reproduce:

1. `rows = size / mixBytes` (`size` = **dataset** size, not cache size).
2. `seed = Keccak512(header_hash[32] || LE_uint64(nonce))` — 40-byte input
   (`algorithm.go:357-361`). `seedHead = LE_uint32(seed[0:4])`.
3. Init `mix[32]` (uint32, `mixBytes/4`) = seed replicated: `mix[i] =
   LE_uint32(seed[(i%16)*4:])` (`algorithm.go:365-368`).
4. **Loop `loopAccesses` (64) times** (`algorithm.go:372-378`):
   `parent = fnv(i ^ seedHead, mix[i % len(mix)]) % rows`; for
   `j in 0..mixBytes/hashBytes (2)`: `copy temp[j*16], lookup(2*parent + j)`; then
   `fnvHash(mix, temp)`.
5. **Compress:** fold every 4 uint32s: `mix[i/4] = fnv(fnv(fnv(mix[i],mix[i+1]),
   mix[i+2]),mix[i+3])`; truncate to `len/4` = 8 uint32 = 32 bytes (`algorithm.go:380-383`).
6. **Return** `(digest, result)`:
   - `digest` (mixDigest, 32B) = LE-serialized compressed mix (`algorithm.go:385-388`).
   - `result` = `Keccak256(seed || digest)` (`algorithm.go:389`).

`hashimotoLight` (`algorithm.go:395-408`) supplies a `lookup` that regenerates each
dataset item on demand from the cache; `hashimotoFull` (`algorithm.go:413-419`)
indexes the full in-memory dataset (`lookup(index) = dataset[index*hashWords : +16]`,
size = `len(dataset)*4`).

---

## 6. verifySeal — the header PoW check

`verifySeal(chain, header, fulldag)` — `consensus.go:541-603`. The conformance-critical
path (fakemodes/shared-PoW branches at `:543-553` are test infra):

1. Reject `header.Difficulty.Sign() <= 0` (`consensus.go:555-557`).
2. `number = header.Number`. Choose full-DAG (`hashimotoFull`) if a dataset is
   generated, else light (`hashimotoLight`) (`consensus.go:566-593`).
3. **Light path (`consensus.go:580-593`)** — the ECIP-1099-aware sizing:
   ```go
   epochLength := calcEpochLength(number, ethash.config.ECIP1099Block)
   epoch       := calcEpoch(number, epochLength)
   size        := datasetSize(epoch)
   digest, result = hashimotoLight(size, cache.cache,
                        ethash.SealHash(header).Bytes(), header.Nonce.Uint64())
   ```
4. **Two acceptance checks (`consensus.go:594-601`) — both MUST pass:**
   - `header.MixDigest == digest` (else `errInvalidMixDigest`).
   - `result <= two256 / header.Difficulty`, i.e.
     `big.SetBytes(result) <= (2^256 / Difficulty)` (else `errInvalidPoW`).

`target = two256 / Difficulty` is the shared threshold used by both verify and mine.

**SealHash (the PoW-input header hash) — `consensus.go:653-689`:** Keccak256 of the
RLP list of these header fields, in order: `ParentHash, UncleHash, Coinbase, Root,
TxHash, ReceiptHash, Bloom, Difficulty, Number, GasLimit, GasUsed, Time, Extra`.
`BaseFee` is **appended only if non-nil** (`:672-674`) — plain ETC (no EIP-1559) omits
it; a base-fee-carrying header (e.g. an ECIP-1111 chain) includes it. `WithdrawalsHash`,
`ExcessBlobGas`, `BlobGasUsed`, `ParentBeaconRoot` being set all **panic** (`:675-687`)
— these PoS/EIP-4844 fields must never appear on an Ethash header. Note `MixDigest` and
`Nonce` are deliberately excluded from SealHash (they are the values being solved for).

---

## 7. The mining / nonce-search path

`Seal(...)` — `sealer.go:68-` spawns one `mine` goroutine per thread, each seeded with
a random start nonce (`ethash.rand.Int63()`, `sealer.go:143-146`); first thread to find
a nonce aborts the rest (`sealer.go:148-165`).

`mine(block, id, seed, abort, found)` — `sealer.go:178-230`:
- `hash = SealHash(header)`, `target = two256 / Difficulty`, `dataset =
  ethash.dataset(number, false)` (`sealer.go:181-186`).
- Loop from `nonce = seed`, incrementing (`sealer.go:196-228`): compute
  `digest, result = hashimotoFull(dataset.dataset, hash, nonce)`; **accept when
  `result <= target`** (`sealer.go:212-213`) — identical threshold to verifySeal.
- On hit: set `header.Nonce = EncodeNonce(nonce)`, `header.MixDigest =
  BytesToHash(digest)`, seal the block (`sealer.go:215-226`).

So mine and verify are exact inverses over the same `(SealHash, dataset-size, target)`
triple — mining searches nonce space for `result <= target`; verification recomputes
`(digest, result)` for the header's committed nonce and checks the same inequality plus
the mixDigest equality.

---

## 8. What a conforming seal-verify path MUST reproduce (checklist)

1. `epochLength = 30000` before `ecip1099FBlock`, `60000` at/after — and treat `nil`
   as "never" (plain Ethash).
2. Activation block sits on a 30000 boundary; epoch numbering restarts on the 60000
   cadence after it.
3. `seedHash` iterates `block/30000` keccak256 rounds regardless of active
   epochLength (seed continuity across the fork).
4. Cache size / dataset size = prime-adjusted linear growth in `epoch`, using the
   *active* epochLength's epoch number.
5. hashimoto: 40-byte `Keccak512(hash||LE(nonce))` seed; 64-iteration FNV mix over
   `size/mixBytes` rows; 4:1 compress; `result = Keccak256(seed||digest)`.
6. SealHash = Keccak256(RLP of the 13 fields, +BaseFee iff present); PoS/blob fields
   forbidden.
7. Accept iff `MixDigest == digest` **and** `result <= 2^256 / Difficulty`.

**Not covered here (separate maps):** difficulty adjustment / ECIP-1010 bomb
(`consensus.go:349-534`, `consensus_classic.go:24-32`) and ECIP-1017 block-reward
emission — these are difficulty/reward concerns, not the seal-verify path B1 targets.
