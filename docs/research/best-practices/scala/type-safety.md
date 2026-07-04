# Best Practices: Scala 3 Type Safety

Synthesized from Scala 3 official docs, virtuslab/scala-skill, Reth (Rust analog patterns),
and production anti-patterns observed in fukuii's sync layer.

---

## 1. Full-Layer Propagation: No ByteString Leaks Inside a Layer

An opaque type exists to prevent the underlying type from being accidentally confused with
semantically different values of the same raw type. If a layer receives a `TrieRoot` from
the domain but immediately stores it in a `ByteString` local variable, the guarantee is
broken: the compiler can no longer catch mixups. The `.value` extractor belongs only at
true I/O boundaries.

**Correct:**
```scala
// TrieRoot flows through the entire coordinator without unwrapping
final case class HealingState(
    pivotRoot: TrieRoot,
    pendingNodes: Seq[ByteString]   // node hashes — NOT roots — stay as ByteString
)

def refreshPivot(newRoot: TrieRoot): HealingState =
  copy(pivotRoot = newRoot)

def buildRequest(root: TrieRoot): GetTrieNodes =
  GetTrieNodes(root = root.value, ...)  // .value only at wire-encoding boundary
```

**Anti-pattern:**
```scala
// Half-typed: TrieRoot unwrapped immediately on entry, ByteString used internally
class HealingCoordinator(stateRoot: ByteString) {   // ← should be TrieRoot
  var currentRoot: ByteString = stateRoot           // type erasure inside layer
  def refreshPivot(r: TrieRoot): Unit = currentRoot = r.value  // leaks here
}
```

**Rule:** Keep the opaque type through every var, val, Map key, and message field inside a
layer. Call `.value` exactly once, at the final serialisation/storage call.

**Grep (detect leaks):**
```bash
# Find .value calls NOT at a known boundary site (adjust namespace)
grep -rn "\.value\b" src/main/scala/com/chipprbots/ethereum/blockchain/sync/ \
  --include="*.scala" | grep -v "//\|rlp\|encode\|RocksDB\|put(\|wire"
```

**Source:** Scala 3 docs — "outside of the module the type `Logarithm` is completely
encapsulated" (types-opaque-types.md). The module IS the layer. Observed violation:
`CombinedRecoveryScanner(scanRoot: ByteString)` and `AppStateStorage.putSnapSyncStateRoot(stateRoot: ByteString)` — both should accept `TrieRoot`.

---

## 2. The "Half-Typed" Anti-Pattern

Half-typed code uses the opaque type at the public API surface but abandons it inside the
implementation. It looks safe from the outside but provides no protection where bugs actually
occur — in the internal logic where values are passed between functions within the same layer.
The compiler sees `ByteString` everywhere internally and cannot catch `stateRoot = storageRoot`
transpositions.

**Correct:**
```scala
// Virtuslab pattern: opaque type flows all the way into the query parameter
object Strings:
  opaque type LowerCased <: String = String
  extension (s: String) def toLowerCased: LowerCased = s.toLowerCase(Locale.ENGLISH)

// Query uses LowerCased — the compiler enforces it at every call site
def findByEmail(email: LowerCased)(using DbTx): Option[User] =
  sql"WHERE email_lc = $email".query[User].headOption
```

**Anti-pattern:**
```scala
// Public API uses the type, but internals unwrap immediately
def findByEmail(email: LowerCased)(using DbTx): Option[User] =
  val raw: String = email  // ← unwraps; all downstream code loses the guarantee
  sql"WHERE email_lc = $raw".query[User].headOption
```

**Rule:** If you find yourself assigning an opaque type to its underlying type within 3 lines
of receiving it, that is the half-typed anti-pattern. Push the boundary to the actual I/O call.

**Grep (detect half-typed):**
```bash
# Find opaque-unwrap-to-local-variable patterns
grep -rn ": ByteString = .*\.value\b" src/main/ --include="*.scala"
grep -rn "val.*: ByteString = .*root\b" src/main/scala/com/chipprbots/ethereum/blockchain/ --include="*.scala"
```

**Source:** Virtuslab scala-skill `400-sql-persistence.md` — `LowerCased`, `Hashed`, `Id[T]`
are opaque over `String` and flow through `findByEmail`, `updatePassword`, and `findById`
signatures. No intermediate unwrap is ever shown.

---

## 3. True Boundary Definition

A "true boundary" is the exact point where a value must cross a protocol: into RLP
encoding, into a RocksDB `put` call, onto the wire, or out to a human-readable log
string. Everything before that point is internal. Everything after (the encoded bytes)
lives in a different type world that is no longer your problem.

**Correct:**
```scala
// RLP codec for TrieRoot: .value called exactly once, inside the codec
given rlpCodec: RLPCodec[TrieRoot] =
  byteStringEncDec.xmap(TrieRoot.apply, _.value)   // ← boundary is the codec

// Storage: .value called exactly once, at the RocksDB serialiser
def valueSerializer: TrieRoot => IndexedSeq[Byte] =
  root => ArraySeq.unsafeWrapArray(root.value.toArray)  // ← boundary is the serialiser
```

**Anti-pattern:**
```scala
// "Boundary" is incorrectly identified as "when we receive it from the actor"
def receive(root: TrieRoot): Unit =
  val bytes: ByteString = root.value  // NOT a boundary — still inside the layer
  coordinator.updateRoot(bytes)       // coordinator now loses type information
```

**Boundaries in fukuii (exhaustive list where `.value` is correct):**
- `RLPCodec[T]` xmap extractor
- `DataSource` `put` serialiser
- Wire-encoding (`encode(root.value)` in `MessageCodec`)
- `toHexString` / `toArray` for logging
- RocksDB `get`/`put` key serialiser only

**Source:** Scala 3 docs — "type equality `Logarithm = Double` can be used to implement the
methods … only known in the scope where `Logarithm` is defined" (types-opaque-types.md).
Reth analog: `B256` (Rust's opaque fixed-bytes wrapper) is passed as `root_hash: B256` in
`GetAccountRangeMessage`, `GetStorageRangesMessage`, and `GetTrieNodesMessage` — never
unwrapped to `[u8; 32]` before the `RlpEncodable` derive macro fires (snap.rs).

---

## 4. Newtype Discipline: When to Create an Opaque Type

Create an opaque type whenever two values of the same raw type represent semantically
distinct concepts that must never be accidentally interchanged. Do not create one when the
raw type is already self-distinguishing (e.g., `Int` for a count vs `Int` for a port is
usually not worth it unless they appear as adjacent parameters in hot paths).

**Decision rule:**
- Same raw type + adjacent parameters → create opaque type (transposition risk)
- Same raw type + different life-cycle phase → create opaque type (TrieRoot vs node hash)
- Different raw types → no opaque type needed, the compiler already distinguishes them
- Single usage, no re-use → type alias is sufficient (no opaque needed)

**Correct:**
```scala
// TrieRoot and CodeHash are both ByteString but NEVER interchangeable
opaque type TrieRoot  = ByteString
opaque type CodeHash  = ByteString
opaque type StorageKey = ByteString

// These three appear as adjacent params — opaque types prevent accidental transposition
def verifyAccount(stateRoot: TrieRoot, codeHash: CodeHash, storageRoot: TrieRoot): Unit
```

**Anti-pattern:**
```scala
// All three are ByteString — compiler cannot catch stateRoot passed as codeHash
def verifyAccount(stateRoot: ByteString, codeHash: ByteString, storageRoot: ByteString): Unit
```

**Virtuslab pattern (direct analog):**
```scala
opaque type Id[T]      = String   // entity identity — never confused with a display name
opaque type LowerCased = String   // normalised for comparison — never raw input
opaque type Hashed     = String   // bcrypt output — never a plaintext password
```

**Source:** Virtuslab scala-skill `400-sql-persistence.md` — Strings object with `Id[T]`,
`LowerCased`, `Hashed`; and `120-type-safe-configuration.md` — `Sensitive` wrapper.
Scala 3 docs: "sound abstraction over implementation details, without imposing performance
overhead" (types-opaque-types.md).

---

## 5. Type-Safe Collections: Lift the Element Type

A collection of raw types that are semantically homogeneous but represent a specific domain
concept should use the opaque type, not the raw type. `List[ByteString]` when all elements
are trie roots is a silent bug: it allows mixing in node hashes, bytecodes, or storage roots
without a compiler error.

**Correct:**
```scala
// The coordinator knows exactly what it holds
final case class HealingTask(
    root: TrieRoot,
    pendingRoots: Seq[TrieRoot],   // all are roots — typed correctly
    pendingNodes: Seq[ByteString]  // these are keccak-hashed node keys — NOT roots
)
```

**Anti-pattern:**
```scala
// Cannot distinguish roots from node hashes at compile time
final case class HealingTask(
    root: ByteString,
    pendingRoots: Seq[ByteString],
    pendingNodes: Seq[ByteString]
)
```

**Grep (detect untyped collections in sync layer):**
```bash
grep -rn "Seq\[ByteString\]\|Vector\[ByteString\]\|List\[ByteString\]\|Map\[ByteString" \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/ --include="*.scala" \
  | grep -v "//\|test\|node\|hash\|path\|code"
# Each hit should be reviewed: is it really an untyped root or key?
```

**Reth analog:** `Vec<B256>` for `account_hashes`, `block_hashes` in snap protocol messages
(snap.rs lines 129, 238). Reth uses `B256` (not `[u8; 32]`) uniformly throughout its
`Vec` fields — the compiler ensures only valid B256 values enter these collections.

**Source:** Inferred from Scala 3 docs (opaque types as "complete encapsulation") and
observed in fukuii: `CombinedRecoveryScanActor` uses `Seq[(ByteString, ByteString)]` for
`missingStorageTries` — both elements are semantically distinct roots, but the tuple
offers no protection.

---

## 6. Subtype Opaque for Safe Implicit Widening

When the opaque type needs to be usable wherever the underlying type is expected — without
an explicit `.value` call — use the `<: UnderlyingType` bound. This is the correct pattern
for types that are refinements of the raw type (a lower-cased string IS a string; a hashed
string IS a string). It is NOT correct for types that are distinct from the raw type
(a `TrieRoot` is NOT interchangeable with an arbitrary `ByteString`).

**Correct:**
```scala
// LowerCased <: String: can be passed where String is expected (it is a String)
opaque type LowerCased <: String = String
extension (s: String) def toLowerCased: LowerCased = s.toLowerCase(Locale.ENGLISH)

def findByEmail(email: LowerCased): Option[User] = ...
val lc: LowerCased = "user@example.com".toLowerCased
findByEmail(lc)       // OK: LowerCased is a subtype of String
```

**Anti-pattern (wrong direction):**
```scala
// TrieRoot should NOT be <: ByteString — it should never silently coerce to ByteString
// because that would allow passing a TrieRoot where any ByteString is expected
opaque type TrieRoot <: ByteString = ByteString  // ← WRONG for TrieRoot
```

**Rule:** Use `<: T` only when the opaque type IS-A specialisation of the raw type that
carries an invariant (lowercase, hashed, validated). Do NOT use `<: T` for domain identity
types (`TrieRoot`, `BlockHash`, `CodeHash`) — those must never coerce implicitly.

**Source:** Virtuslab scala-skill `400-sql-persistence.md` — `opaque type LowerCased <: String`
and `opaque type Hashed <: String` with deliberate `<:` bound; vs `opaque type Id[T] = String`
(no `<:`) because `Id[T]` is an entity identifier, not a refinement of String. Scala 3
changelog 3.2.2 — "Disallow opaque type aliases of context functions" (#16041) — confirms
the compiler enforces structural constraints on opaque type definitions.

---

## 7. Codec Placement: One `given` per Opaque Type, Inside the Companion

The RLP/RocksDB/JSON codec for an opaque type belongs in the companion object of that type,
not scattered across the codebase. Centralising the codec means `.value` is called in
exactly one place, and all codec evolution happens in one file. The `xmap` / `biMap`
pattern makes the boundary explicit: encode uses `_.value`, decode uses `OpaqueType.apply`.

**Correct:**
```scala
object TrieRoot:
  given rlpCodec: RLPCodec[TrieRoot] =
    byteStringEncDec.xmap(TrieRoot.apply, _.value)  // encode: _.value; decode: apply
  // All serialisation of TrieRoot goes through this given — zero other .value calls

// Usage: codec is summoned implicitly, no manual .value needed
rlp.encode(header.stateRoot)   // stateRoot: TrieRoot — codec fired automatically
```

**Anti-pattern:**
```scala
// .value called ad-hoc at multiple call sites
rlp.encode(header.stateRoot.value)   // site 1
dataSource.put(header.stateRoot.value.toArray)  // site 2
msg.root = header.stateRoot.value               // site 3
// These multiply when the type is used widely; updating codec means hunting all 3
```

**Virtuslab analog:**
```scala
// DbCodec for Id[T], Hashed, LowerCased live in one Magnum object
given idCodec[T]: DbCodec[Id[T]] =
  DbCodec.StringCodec.biMap(_.asId[T], _.toString)
given DbCodec[Hashed] = DbCodec.StringCodec.biMap(_.asHashed, _.toString)
```

**Source:** Virtuslab scala-skill `400-sql-persistence.md` — `Magnum` object centralises all
codec `given` instances. Fukuii's `TrieRoot.rlpCodec`, `BlockHash.rlpCodec` follow this
pattern correctly; the violation is that callers sometimes bypass the codec.

---

## 8. Message Types: Opaque Types in Protocol Messages

Actor messages and protocol structs are part of the internal layer — they are not I/O
boundaries. A message carrying a state root from coordinator to sub-actor should use
`TrieRoot`, not `ByteString`. The wire-encoding step that serialises the message to bytes IS
the boundary where `.value` is called.

**Correct:**
```scala
// Pekko Typed command ADT uses the opaque type
sealed trait HealingCommand
object HealingCommand:
  final case class PivotRefreshed(newRoot: TrieRoot) extends HealingCommand
  final case class NodesReceived(nodeHashes: Seq[ByteString]) extends HealingCommand
  // nodeHashes are keccak keys, not roots — ByteString is correct here
```

**Anti-pattern:**
```scala
// Message uses ByteString — sub-actor cannot distinguish a root from a node hash
final case class PivotRefreshed(newRoot: ByteString)  // ← should be TrieRoot
```

**Reth analog:** `GetAccountRangeMessage` carries `root_hash: B256` through the entire
download pipeline — from coordinator to peer request to response handler. B256 is never
downcast to `[u8; 32]` inside the sync layer; only the RLP derive macro touches the bytes
(reth `crates/net/eth-wire-types/src/snap.rs`).

**Grep (detect wrongly-typed messages):**
```bash
grep -rn "stateRoot.*ByteString\|root.*: ByteString\|scanRoot.*ByteString" \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/ --include="*.scala" \
  | grep -v "//\|node\|hash\|path"
```

**Source:** Strong analog from reth. Observed violation: `CombinedRecoveryScanActor` —
`stateRoot: ByteString` in the `StartRecoveryScan` message and `CombinedRecoveryScanner`
constructor.

---

## 9. Storage Layer Boundary: Accept Opaque Types, Serialise Internally

Storage classes (`AppStateStorage`, `HealingFrontierStorage`, etc.) are the final recipients
of typed domain values before they hit RocksDB. They should accept the opaque type, not
`ByteString`. The `keySerializer` / `valueSerializer` lambdas are the one and only place
`.value` is called — making the serialisation boundary structurally visible.

**Correct:**
```scala
// AppStateStorage: accept TrieRoot, call .value inside the serialiser only
def putSnapSyncStateRoot(root: TrieRoot): DataSourceBatchUpdate =
  put(Keys.SnapSyncStateRoot, Hex.toHexString(root.value.toArray))  // .value at boundary

def getSnapSyncStateRoot(): Option[TrieRoot] =
  get(Keys.SnapSyncStateRoot)
    .map(v => TrieRoot(ByteString(Hex.decode(v))))  // TrieRoot.apply at decode boundary
```

**Anti-pattern:**
```scala
// AppStateStorage accepts ByteString — callers must remember to .value themselves
def putSnapSyncStateRoot(stateRoot: ByteString): DataSourceBatchUpdate = ...  // current
// Caller: appStateStorage.putSnapSyncStateRoot(header.stateRoot.value)
//                                                               ^^^^^ fragile
```

**Rule:** If a storage method accepts `ByteString` but semantically only makes sense for
a specific opaque type, the parameter type is wrong. The storage layer is part of the
internal layer, not the I/O boundary. The I/O boundary is the `keySerializer` /
`valueSerializer` function body.

**Source:** Inferred from Scala 3 docs module abstraction pattern. The "leaky abstraction"
section explicitly warns: "we have to make sure to only ever program against the abstract
interface … never directly use `LogarithmsImpl`. Directly using `LogarithmsImpl` would
make the equality `Logarithm = Double` visible" (types-opaque-types.md). Passing
`ByteString` to a storage API is the same leak.

---

## 10. Alias vs Opaque: When a Plain Type Alias Is Sufficient

A `type` alias gives a name to a type for readability but provides ZERO compile-time
protection — the alias and the underlying type are interchangeable everywhere. Use a plain
type alias only for documentation: when the value is never adjacent to a different value of
the same raw type that it could be confused with.

**Correct:**
```scala
// Type alias: Nibbles is always in a dedicated data structure, never adjacent to raw Bytes
type Nibbles = IndexedSeq[Byte]   // alias is fine — no confusion risk

// Opaque type: StateRoot and StorageRoot are BOTH ByteString and appear as adjacent params
opaque type TrieRoot = ByteString  // must be opaque — confusion risk is real
```

**Anti-pattern:**
```scala
// Using a type alias for something that appears adjacent to its raw type
type TrieRoot = ByteString  // provides zero protection — ByteString is always assignable
val roots: Map[TrieRoot, TrieRoot] = ...  // compiler accepts Map[ByteString, ByteString]
```

**Rule:** If the type alias appears in function signatures alongside its raw underlying type
— or alongside another alias of the same underlying type — make it opaque. Aliases are for
when the domain has only one use of that raw type in context.

**Grep (find plain aliases that should be opaque):**
```bash
# Find "type X = ByteString" aliases (potential opaque candidates)
grep -rn "^  *type [A-Z][a-zA-Z]* = ByteString" src/main/ --include="*.scala" \
  | grep -v "opaque"
```

**Source:** Scala 3 docs — "this abstraction is slightly leaky … Directly using
`LogarithmsImpl` would make the equality `Logarithm = Double` visible for the user, who
might accidentally use a `Double` where a logarithmic double is expected" (types-opaque-types.md).
The module trait / alias approach is the Scala 2 workaround that opaque types replace.
