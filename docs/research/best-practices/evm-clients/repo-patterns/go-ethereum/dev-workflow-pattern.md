# go-ethereum — Fuzzing & Dependency-Hygiene Patterns

Source: `.claude/repo-references/clients/go-ethereum/` (vendored full clone, verified
genuine — `origin` points at a fork, `https://github.com/white-b0x/go-ethereum.git`,
`upstream` at the canonical `https://github.com/ethereum/go-ethereum.git`; checked-out
branch is `upstream`, tracking `origin/upstream` cleanly at `HEAD` `59e89e81e` dated
2026-07-01. The fork carries a cosmetic `geth`→`keeper` rename in parts of `build/ci.go`
— e.g. `doInstallKeeper`, the `keeper`/`keeper-archive` CLI subcommands — that does not
affect any of the mechanisms documented below; every file cited here is otherwise
byte-identical to canonical upstream, and the `check_generate`/`check_baddeps`/fuzzer/
`internal/build` code paths are untouched by the rename.)

Every claim below is traceable to a file:line in the vendored clone. `core/types/rlp_fuzzer_test.go`
(147 lines) and `build/ci.go` (1,356 lines) were each read in full; `oss-fuzz.sh` (231
lines) and `tests/fuzzers/README.md` (45 lines) were read in full; all four
`internal/build/` helper files (`archive.go` 296 lines, `azure.go` 120 lines, `pgp.go` 71
lines, `gotool.go` 141 lines) were read in full.

---

## Fuzzing infrastructure — the centerpiece

**Count, confirmed by direct grep:** `grep -rn "^func Fuzz" --include="*.go" .` against
the vendored clone's root returns **44 matches** across 24 files — matching the task
brief's expected count exactly. This is a mix of native Go 1.18+ fuzz targets
(`func FuzzX(f *testing.F)`, the majority) and a handful of go-fuzz-tool–style targets
using a bare `func Fuzz(data []byte) int` or `func Fuzz(f *testing.F)` with a generic
name (the `tests/fuzzers/` subpackages, see below). Notable concentrations: 15 of the 44
are in `tests/fuzzers/bls12381/bls12381_test.go` alone (a cross-implementation
differential-fuzzing suite, one `Fuzz*` function per BLS12-381 operation), 5 in
`eth/protocols/snap/handler_fuzzing_test.go`, 5 in `tests/fuzzers/bn256/bn256_test.go`.
The remaining ~19 are single- or double-target files scattered across `accounts/`,
`common/bitutil/`, `core/state/`, `core/types/`, `core/vm/`, `crypto/`, `eth/protocols/eth/`,
`internal/era/`, `rlp/`, and `trie/` — i.e. fuzz targets sit directly next to the
production code they exercise as ordinary `_test.go` files, not segregated into one
fuzzing-only directory (with the sole exception of the six `tests/fuzzers/` subpackages
below, which exist specifically *because* their targets need a differential/cross-library
harness that doesn't belong inline with the package under test).

### `FuzzRLP` — the minimal, no-infra pilot example, quoted verbatim

`core/types/rlp_fuzzer_test.go:45-147` (the full file, reproduced exactly):

```go
func decodeEncode(input []byte, val interface{}) error {
	if err := rlp.DecodeBytes(input, val); err != nil {
		// not valid rlp, nothing to do
		return nil
	}
	// If it _were_ valid rlp, we can encode it again
	output, err := rlp.EncodeToBytes(val)
	if err != nil {
		return err
	}
	if !bytes.Equal(input, output) {
		return fmt.Errorf("encode-decode is not equal, \ninput : %x\noutput: %x", input, output)
	}
	return nil
}

func FuzzRLP(f *testing.F) {
	f.Fuzz(fuzzRlp)
}

func fuzzRlp(t *testing.T, input []byte) {
	if len(input) == 0 || len(input) > 500*1024 {
		return
	}
	rlp.Split(input)
	if elems, _, err := rlp.SplitList(input); err == nil {
		rlp.CountValues(elems)
	}
	rlp.NewStream(bytes.NewReader(input), 0).Decode(new(interface{}))
	if err := decodeEncode(input, new(interface{})); err != nil {
		t.Fatal(err)
	}
	{
		var v struct {
			Int    uint
			String string
			Bytes  []byte
		}
		if err := decodeEncode(input, &v); err != nil {
			t.Fatal(err)
		}
	}
	{
		type Types struct {
			Bool  bool
			Raw   rlp.RawValue
			Slice []*Types
			Iface []interface{}
		}
		var v Types
		if err := decodeEncode(input, &v); err != nil {
			t.Fatal(err)
		}
	}
	{
		type AllTypes struct {
			Int    uint
			String string
			Bytes  []byte
			Bool   bool
			Raw    rlp.RawValue
			Slice  []*AllTypes
			Array  [3]*AllTypes
			Iface  []interface{}
		}
		var v AllTypes
		if err := decodeEncode(input, &v); err != nil {
			t.Fatal(err)
		}
	}
	{
		if err := decodeEncode(input, [10]byte{}); err != nil {
			t.Fatal(err)
		}
	}
	{
		var v struct {
			Byte [10]byte
			Rool [10]bool
		}
		if err := decodeEncode(input, &v); err != nil {
			t.Fatal(err)
		}
	}
	{
		var h Header
		if err := decodeEncode(input, &h); err != nil {
			t.Fatal(err)
		}
		var b Block
		if err := decodeEncode(input, &b); err != nil {
			t.Fatal(err)
		}
		var tx Transaction
		if err := decodeEncode(input, &tx); err != nil {
			t.Fatal(err)
		}
		var txs Transactions
		if err := decodeEncode(input, &txs); err != nil {
			t.Fatal(err)
		}
		var rs Receipts
		if err := decodeEncode(input, &rs); err != nil {
			t.Fatal(err)
		}
	}
	{
		var v struct {
			AnIntPtr  *big.Int
			AnInt     big.Int
			AnU256Ptr *uint256.Int
			AnU256    uint256.Int
			NotAnU256 [4]uint64
		}
		if err := decodeEncode(input, &v); err != nil {
			t.Fatal(err)
		}
	}
}
```

This is the single cheapest fuzz target in the whole tree to understand and to port, and
that is exactly why it is the pilot candidate below. Three properties make it minimal:

1. **No corpus is required to start.** `f.Fuzz(fuzzRlp)` (`rlp_fuzzer_test.go:46`) works
   immediately with `go test -fuzz=FuzzRLP ./core/types/` — Go's native fuzzing engine
   auto-generates the initial corpus from the type signature (`[]byte`) and any seed
   corpus under `testdata/fuzz/FuzzRLP/` is optional, purely additive.
2. **No external harness or build step.** It is an ordinary Go test function using the
   standard `testing.F` API introduced in Go 1.18 — `go test` runs it as a normal unit
   test when invoked without `-fuzz` (`f.Fuzz` degenerates to iterating any seed corpus
   once), and as a genuine coverage-guided fuzzer when invoked with `-fuzz=FuzzRLP
   -fuzztime=60s`. There is no `go-fuzz-build`, no `.a`/binary artifact, no libFuzzer
   linkage — that machinery belongs to the *older* convention (see the `go-fuzz` distinction
   below), not to `FuzzRLP` itself.
3. **The property under test is trivial to state and universal:** decode arbitrary bytes
   into a target type; if decoding succeeds, re-encoding must reproduce the exact
   original bytes (`decodeEncode`, `rlp_fuzzer_test.go:29-43`). `fuzzRlp` applies this one
   property to eight increasingly complex target shapes in sequence — `interface{}`, a
   struct with `uint`/`string`/`[]byte` fields, a struct with a `Bool`/`RawValue`/self-
   referential `Slice`/`Iface []interface{}` combination, a struct combining all of the
   above plus a fixed `Array`, a bare `[10]byte`, a struct with fixed-size byte/bool
   arrays, the five core chain types (`Header`, `Block`, `Transaction`, `Transactions`,
   `Receipts`), and finally a struct mixing `*big.Int`/`big.Int`/`*uint256.Int`/`uint256.Int`
   — deliberately covering both pointer and value forms of the two big-integer types RLP
   must handle identically. A single input length guard (`len(input) == 0 || len(input) >
   500*1024`, line 50) is the only precondition; every other line either calls the low-level
   `rlp.Split`/`rlp.SplitList`/`rlp.CountValues`/`rlp.NewStream(...).Decode` entry points
   directly (exercising the parser's structural layer even when the typed decode fails)
   or runs the `decodeEncode` round-trip property against one target shape.

### `tests/fuzzers/` — the older go-fuzz-tool convention, and how it differs from `FuzzRLP`

`tests/fuzzers/README.md` (45 lines, read in full) documents a **distinct, older**
fuzzing convention from the one `FuzzRLP` uses: it instructs the reader to install
[go-fuzz](https://github.com/dvyukov/go-fuzz) (`dvyukov/go-fuzz`, the pre-Go-1.18
external fuzzing engine), build a package-specific fuzz binary via `go-fuzz-build`
(`cd ./rlp && CGO_ENABLED=0 go-fuzz-build .`, README.md:8), and run the resulting
`.zip` bundle through the standalone `go-fuzz` binary — a completely separate toolchain
from `go test -fuzz`. The README's own troubleshooting note is telling: it warns that
go-fuzz's `suppressions` folder can mask distinct bugs behind one crash signature unless
each panic message embeds a differentiator (its own worked example is the *very* pattern
`FuzzRLP`'s `decodeEncode` no longer needs, because native fuzzing's `t.Fatal` doesn't
route through go-fuzz's suppression mechanism at all, README.md:36-44).

Six subpackages live under `tests/fuzzers/` — a directory that exists specifically to
host multi-file/differential-comparison fuzz harnesses that don't fit inline next to the
package they exercise:

| Subpackage | What it fuzzes |
|---|---|
| `bls12381/` | Differential fuzzing of the BLS12-381 precompile: cross-checks fukuii's own G1/G2 add, multi-exp, pairing, map-to-curve, and subgroup-check operations against themselves and against a second implementation (15 `Fuzz*` targets, the largest single file in the fuzz corpus by target count) |
| `bn256/` | Differential fuzzing of BN256 (alt_bn128) curve operations across three independent implementations — `cloudflare`, `google`, and `gnark` (`bn256_fuzz.go:24-26`) — add/mul/pair/unmarshal on G1/G2, checking all three agree |
| `difficulty/` | Fuzzes Ethash's classic difficulty-adjustment formula (`consensus/ethash` + `core/types`, `difficulty-fuzz.go:24-25`) against arbitrary byte-derived block header fields |
| `rangeproof/` | Fuzzes Merkle-Patricia-trie range proofs (`core/rawdb`, `ethdb/memorydb`, `trie`, `triedb`, `rangeproof-fuzzer.go:23-27`) — proof generation/verification against arbitrary trie shapes |
| `secp256k1/` | Differential fuzzing of secp256k1 elliptic-curve point addition between fukuii's own `crypto/secp256k1` implementation and `decred/dcrd`'s independent implementation (`secp_test.go:38-53`) — asserts both curves agree on `ScalarBaseMult` + `Add` for arbitrary scalar inputs |
| `txfetcher/` | Fuzzes the transaction-announcement fetcher state machine (`eth/fetcher`, `txfetcher_fuzzer.go`) — a protocol/state-machine fuzz target rather than a pure-function one, using a deterministic seeded RNG (`rand.NewSource(0x3a29)`, `txfetcher_fuzzer.go:36`) to keep runs reproducible |

Four of the six (`bls12381`, `bn256`, `difficulty`, `rangeproof`) additionally define a
native `func FuzzX(f *testing.F)` wrapper alongside (or instead of) the legacy bare
`func Fuzz(data []byte) int`/`func Fuzz(f *testing.F)` signature — confirming the
codebase is mid-migration from the go-fuzz-tool convention to native fuzzing rather than
cleanly on one or the other; `secp256k1` and `txfetcher` still expose the bare
`func Fuzz(f *testing.F) { f.Fuzz(func(t *testing.T, ...) { fuzz(...) }) }` shape
(`secp_test.go:32-36`, `txfetcher_test.go:21-25`) — a thin native-fuzzing shim wrapping an
unchanged legacy `fuzz(...)` body, which is the actual migration pattern in use: keep the
old differential/property function untouched, add a `testing.F`-based entry point on top
of it.

### OSS-Fuzz wiring — `FuzzRLP` specifically

`oss-fuzz.sh` (231 lines, read in full) is Google OSS-Fuzz's build script for continuous
fuzzing — it is fetched and executed by OSS-Fuzz's own external infrastructure, not by
go-ethereum's own CI (confirmed: `grep -rln "oss-fuzz\|clusterfuzz" .github/workflows/`
returns no matches — fuzzing runs entirely outside the repo's own GitHub Actions,
asynchronously and continuously, decoupled from any PR gate). `FuzzRLP` is wired in via
its own `compile_fuzzer` invocation:

```bash
compile_fuzzer github.com/ethereum/go-ethereum/core/types \
  FuzzRLP fuzzRlp \
  $repo/core/types/rlp_fuzzer_test.go
```

(`oss-fuzz.sh:105-107`) — the three positional arguments to `compile_fuzzer`
(`oss-fuzz.sh:51-81`) are the Go import path (`core/types`), the target function name
(`FuzzRLP`), the OSS-Fuzz output binary name (`fuzzRlp`), and the source file(s)
containing it. Internally, `compile_fuzzer` runs `go mod tidy`, then either a coverage
build (`coverbuild`, when `$SANITIZER` includes `coverage`) that rewrites a template
`ossfuzz_coverage_runner.go` with the target's name via `sed` (lines 32-35) and shells
out `go test -run Test${function}Corpus ... -coverpkg`, or the normal path: `gofuzz-shim
--func $function --package $package -f $file -o $fuzzer.a` (line 69) followed by linking
that `.a` against `$LIB_FUZZING_ENGINE` with the host `$CXX` (line 70) — i.e.
`gofuzz-shim` (a separate `holiman/gofuzz-shim` tool, installed at line 83) is the bridge
that lets OSS-Fuzz's C++-oriented libFuzzer driver call into a native Go `testing.F`
fuzz function, regardless of whether that function itself uses the old or new fuzzing
convention. 30 of the file's `compile_fuzzer` calls target the `tests/fuzzers/*`
subpackages plus a handful of inline targets (`FuzzABI`, `FuzzEncoder`/`FuzzDecoder`,
`FuzzVmRuntime`, `FuzzPrecompiledContracts`, `FuzzRLP`, blake2b's `Fuzz`, `FuzzPassword`,
`FuzzTrie`/`FuzzStackTrie`, the four `snap` handler fuzzers, `FuzzEthProtocolHandlers`) —
notably *not* all 44 `Fuzz*` functions in the tree are wired into `oss-fuzz.sh`; several
(`FuzzJournal`, `FuzzUnpackIntoDeposit`, `FuzzIteratorCount`, `FuzzCodec`,
`FuzzCrossPairing` and its `bls12381` siblings beyond the 9 wired individually) exist as
plain `go test -fuzz`-runnable local fuzz targets without a matching OSS-Fuzz entry —
continuous coverage-guided fuzzing and "has a native fuzz target at all" are two
independent facts about a given function, not one.

### Porting to fukuii: a minimal RLP round-trip pilot

fukuii is not proposing to build OSS-Fuzz integration, a corpus-management pipeline, or
even Go-style native fuzzing — none of that exists on the JVM in an equivalent form, and
building it is a multi-week infrastructure project, consistent with how prior
reference-client passes have correctly framed "fuzzing" as mid-term/all-or-nothing
infrastructure work. **The nuance this pass adds:** `FuzzRLP` itself, independent of
OSS-Fuzz/corpus/CI, is *not* infrastructure — it is a 20-line property (`decodeEncode`)
applied to eight type shapes, runnable with zero setup beyond `go test`. That same
minimal shape has a direct ScalaCheck analog fukuii can add *today*, with no new CI job,
no corpus directory, and no dependency beyond one already in `project/Dependencies.scala`.

**fukuii already has the exact dependency and the exact per-type pattern — just not the
adversarial-bytes direction.** `project/Dependencies.scala:69` pins
`"org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % "test"` (plus `scalacheck` itself
at line 72, `it,test` scope), and
`rlp/src/test/scala/com/chipprbots/ethereum/rlp/RLPSuite.scala` already mixes in
`ScalaCheckPropertyChecks`/`ScalaCheckDrivenPropertyChecks` (imports at lines 9-10) with
nine existing `forAll(Gen...)` property tests — e.g. `forAll(Gen.choose[Int](Int.MinValue,
Int.MaxValue)) { anInt => ... }` (`RLPSuite.scala:404-409`), `forAll(Arbitrary.arbitrary[BigInt])`
(line 437). **But every one of these generates a valid domain value first** (an `Int`, a
`BigInt`, a `List[Long]`), encodes it, decodes it back, and asserts equality — this
proves `decode(encode(x)) == x` for well-formed domain values. It never generates
arbitrary byte sequences and asks "does the decoder crash, loop, or silently accept
malformed input" — which is precisely the direction `FuzzRLP`'s `decodeEncode` tests
(`decode(bytes)` first, on *raw* bytes that may or may not be valid RLP, then re-encode
only if decoding succeeded). This is the actual gap, not "fukuii has no property testing"
— fukuii has good encode-side property tests and zero decoder-hardening property tests.

**Concrete pilot design — `fukuii-rlp-roundtrip`:**

Add one new `test` in `RLPSuite.scala` (or a sibling `RLPRoundTripSpec.scala` in the same
module) mirroring `fuzzRlp`'s direction exactly:

```scala
test("Arbitrary bytes: rawDecode-then-encode round-trips when decoding succeeds", UnitTest, RLPTest) {
  forAll(Gen.listOf(Arbitrary.arbitrary[Byte]).map(_.toArray)) { (input: Array[Byte]) =>
    scala.util.Try(RLP.rawDecode(input)) match {
      case scala.util.Failure(_: RLPException) => // not valid RLP — nothing to check, matches
                                                    // go-ethereum's "not valid rlp, nothing to do"
      case scala.util.Failure(e)               => fail(s"rawDecode threw a non-RLPException: $e")
      case scala.util.Success(decoded)         =>
        assert(RLP.encode(decoded).sameElements(input))
    }
  }
}
```

This uses exactly the primitives already present: `RLP.rawDecode(data: Array[Byte]):
RLPEncodeable` (`rlp/src/main/scala/.../RLP.scala:81`, `private[rlp]` — reachable from
`RLPSuite` since it lives in the same `com.chipprbots.ethereum.rlp` package) is fukuii's
structural decode, the direct analog of `rlp.Split`/`rlp.NewStream(...).Decode(new(interface{}))`;
`decode[T](data: Array[Byte])(implicit dec: RLPDecoder[T]): T` (`package.scala:99`) throws
`RLPException` (`package.scala:17-18`, the package object's own exception type) on malformed
input, giving a precise "not valid RLP" signal to catch — the same role `err != nil` from
`rlp.DecodeBytes` plays in `decodeEncode` (`rlp_fuzzer_test.go:30-32`). The property —
"decode arbitrary bytes; if it succeeds, re-encoding must reproduce the exact input" — is
identical to `decodeEncode`, applied first to the untyped `RLPEncodeable` shape (the
`interface{}` case in `fuzzRlp`). A second, near-identical property test targeting
`decode[SomeExistingCaseClass]` (mirroring `fuzzRlp`'s struct/`Header`/`Block`/`Transaction`
cases) is the natural follow-up once the untyped version is green — but the untyped
version alone already exercises the parser's length-prefix/nesting logic against
adversarial input, which is where a real remote peer's malformed RLP would land first.

**Why this is a strictly better decoder-hardening test than what exists today, at zero
new infrastructure cost:** ScalaCheck's shrinking will minimize any failing input to the
smallest byte sequence that breaks the round-trip or crashes the decoder — genuinely
useful for finding malformed-input bugs in code that parses attacker-controlled bytes off
the wire (RLPx/devp2p messages), which is exactly the threat model `FuzzRLP` defends
against in go-ethereum. This is **not** coverage-guided fuzzing (ScalaCheck has no
instrumentation feedback loop the way `go test -fuzz` or libFuzzer does — it's pure
random generation plus shrinking on failure), so it will not discover as much as true
fuzzing eventually would; but it costs nothing beyond one test method using dependencies
and patterns already in the tree, and it is a direct, unambiguous upgrade over "zero
adversarial-input testing of the RLP decoder," which is fukuii's status quo today.
Nothing about running this pilot requires a CI change, a corpus directory, or new build
tooling — `sbt "testOnly com.chipprbots.ethereum.rlp.RLPSuite"` runs it exactly like any
other ScalaCheck property already in the file.

---

## check_baddeps — dependency denylist gate

`build/ci.go:521-549` (`doCheckBadDeps`, quoted in full):

```go
// doCheckBadDeps verifies whether certain unintended dependencies between some
// packages leak into the codebase due to a refactor. This is not an exhaustive
// list, rather something we build up over time at sensitive places.
func doCheckBadDeps() {
	baddeps := [][2]string{
		// Rawdb tends to be a dumping ground for db utils, sometimes leaking the db itself
		{"github.com/ethereum/go-ethereum/core/rawdb", "github.com/ethereum/go-ethereum/ethdb/leveldb"},
		{"github.com/ethereum/go-ethereum/core/rawdb", "github.com/ethereum/go-ethereum/ethdb/pebbledb"},
	}
	tc := new(build.GoToolchain)

	var failed bool
	for _, rule := range baddeps {
		out, err := tc.Go("list", "-deps", rule[0]).CombinedOutput()
		if err != nil {
			log.Fatalf("Failed to list '%s' dependencies: %v", rule[0], err)
		}
		for _, line := range strings.Split(string(out), "\n") {
			if strings.TrimSpace(line) == rule[1] {
				log.Printf("Found bad dependency '%s' -> '%s'", rule[0], rule[1])
				failed = true
			}
		}
	}
	if failed {
		log.Fatalf("Bad dependencies detected.")
	}
	fmt.Println("No bad dependencies detected.")
}
```

**The exact current denylist is two rules, both about one package:** `core/rawdb` must
not depend on `ethdb/leveldb`, and must not depend on `ethdb/pebbledb`
(`ci.go:526-528`). The mechanism is `go list -deps <package>` (the full transitive
dependency closure of `core/rawdb`), scanning line-by-line for an exact string match
against the forbidden import path (`ci.go:538-539`) — a purely structural check with no
semantic understanding of *why* the dependency would be bad, just that it must not exist.
The doc comment is explicit about scope: "This is not an exhaustive list, rather
something we build up over time at sensitive places" (`ci.go:522-523`) — i.e. this is a
deliberately narrow, incrementally-grown allowlist-by-exclusion, not a general
architecture-layering enforcement tool. The specific rationale, also in-comment: "Rawdb
tends to be a dumping ground for db utils, sometimes leaking the db itself" (`ci.go:526`)
— `core/rawdb` provides low-level, storage-backend-agnostic accessors used throughout the
codebase, and a stray import of a *concrete* backend (LevelDB or PebbleDB) would silently
reintroduce a hard dependency on one specific storage engine into what is supposed to be
backend-agnostic code, defeating the point of having an abstraction layer there at all.
This is wired into CI at `.github/workflows/go.yml:36-40`, run inside the "Lint" job
immediately after `go run build/ci.go lint` and before any test job: `check_generate` then
`check_baddeps`, both gating merge on the same job as the linter proper — a pull request
cannot merge with either check failing.

**fukuii's narrower analog exists today, but is not CI-gated.** `.agents/skills/fukuii-dependency-audit/SKILL.md`
already contains a structurally identical single-rule check under its "BSL guard — zero
Akka imports" section (`SKILL.md:51-56`):

```bash
grep -rn "import akka\." /media/dev/2tb/dev/fukuii/src/main/scala/ --include="*.scala" 2>/dev/null || echo "✓ Zero Akka imports — BSL-clean"
grep '"com.typesafe.akka"\|"com.lightbend".*akka' /media/dev/2tb/dev/fukuii/project/Dependencies.scala && echo "CRITICAL: BSL 1.1 Akka dep found" || echo "✓ No Akka deps in Dependencies.scala"
```

This is the same *shape* as go-ethereum's `check_baddeps` — a single forbidden-import
grep, scoped to one sensitive concern (here: guaranteeing zero `com.typesafe.akka`/
`com.lightbend:akka*` imports or dependency entries, since fukuii migrated off Akka to
Pekko specifically to stay clear of Akka's BSL 1.1 license — a CRITICAL finding per
`SKILL.md:98` if one ever reappears) — but it is materially narrower in two ways
go-ethereum's isn't: (1) it checks for a banned *dependency family* anywhere in
`src/main/scala/`, not a banned *edge* between two specific named packages the way
`{core/rawdb → ethdb/leveldb}` is package-pair-scoped; and (2) it is invoked on-demand via
the `fukuii-dependency-audit` skill, not run automatically on every push/PR the way
`check_baddeps` runs inside `go.yml`'s Lint job on every push and pull request
(`go.yml:2-8`).

**Verdict: promote to a CI-gated check, keeping the existing grep.** The BSL guard is
already the right shape and already correctly scoped (it protects the single most
consequential architectural boundary in the codebase — Akka's BSL 1.1 license is exactly
the kind of "sensitive place" `check_baddeps`'s own doc comment describes building up
rules for over time) — the only gap relative to go-ethereum's pattern is that it lives in
an on-demand skill instead of a CI job that runs unconditionally on every push/PR. Porting
the *mechanism* (not the specific rule, which fukuii already has) means: extract the two
existing `grep` lines into a small script invoked from a CI workflow step (mirroring
`go.yml:36-40`'s placement immediately alongside/after formatting and lint checks), so
that a stray `akka` import can never land on `main` between one operator-run audit and
the next. `check_baddeps`'s own two-rule denylist (`core/rawdb` → concrete DB backends)
has no direct fukuii analog to add alongside the BSL rule today — fukuii's storage layer
(`db/`, per the `vault` agent's domain) does not currently show the same "abstraction
package accidentally importing a concrete backend" failure mode reported for
`core/rawdb` — but the *pattern itself* (grow a small, named list of specific
forbidden-import edges at genuinely sensitive architectural boundaries, gate it in CI) is
worth keeping in mind the next time such a boundary is identified, rather than reserving
it exclusively for the BSL case.

---

## check_generate

`build/ci.go:466-519` (`doCheckGenerate`, read in full) verifies two independent things
in one command, per its own doc comment ("ensures that re-generating generated files does
not cause any mutations in the source file tree", `ci.go:466-467`) plus the top-level
usage string's more precise description: "verifies that 'go generate' and 'go mod tidy' do
not produce changes" (`ci.go:28`):

1. **`go generate` drift.** For each Go workspace module (`goModules`, iterated at
   `ci.go:486`), it hashes every file in the module tree (excluding
   `tests/testdata`, `build/cache`, `.git`, `ci.go:481-484`) via `build.HashFolder`, runs
   `go generate ./...` for that module (with a `PATH` extended to include locally
   downloaded `protoc`/`protoc-gen-go` binaries, `ci.go:474-479, 493-494` — `go generate`
   steps in this codebase invoke protobuf codegen, hence the toolchain download), re-hashes
   the tree, and diffs the two hash sets (`build.DiffHashes`, `ci.go:502`). Any changed file
   is logged (`ci.go:504`) and the whole check fails (`log.Fatal`, `ci.go:507`) if the
   diff is non-empty. In plain terms: whatever `gen_*.go` (or equivalent) files are
   committed to the tree must be byte-identical to what running the generator produces
   right now — a committed generated file that has drifted from its generator is a hard
   CI failure, not a warning.
2. **`go mod tidy` drift**, as a second, independent loop over the same module list: `go
   mod tidy -diff` per module (`ci.go:513-516`) — the `-diff` flag makes `go mod tidy`
   report what it *would* change without writing, so a non-empty diff (rather than a
   modified `go.mod`/`go.sum` on disk) is the failure signal here, distinct from the
   file-hash-diff mechanism used for `go generate`.

This is wired into the same CI step as `check_baddeps`, immediately preceding it
(`go.yml:39-40`, both inside the Lint job).

**fukuii has no analogous problem to solve here.** fukuii's one committed-vs-generated
concern is `sbt-buildinfo` (`project/plugins.sbt:17`,
`addSbtPlugin("com.eed3si9n" %% "sbt-buildinfo" %% "0.12.0")`), which generates
`com.chipprbots.ethereum.utils.BuildInfo` (`build.sbt:288-310`) fresh on every compile —
it is never written to a source file that gets committed, and `build.sbt` explicitly
excludes the generated `BuildInfo.scala` from Scoverage/Scapegoat scanning
(`build.sbt:401`, `"com\\.chipprbots\\.ethereum\\.utils\\.BuildInfo" // BuildInfo
generated code`; `build.sbt:589`, `".*/BuildInfo\\.scala"`) precisely because it is
build-artifact code, not tracked source. There is nothing in fukuii's tree that plays the
role of go-ethereum's committed `gen_*.go` files (protobuf-generated Go source checked
into git) — `sbt-buildinfo`'s output is regenerated identically on every single `sbt
compile` invocation and never touches the working tree in a way `git status` would show,
so the entire "did the committed generated file drift from its generator" failure mode
`check_generate`'s first half defends against does not exist here. The second half
(`go mod tidy -diff` — dependency-manifest drift) also has no fukuii equivalent: sbt has
no analogous "tidy the dependency manifest and fail if it would change" command, and
`project/Dependencies.scala` is hand-maintained rather than derived, so there is nothing
for a `-diff`-style check to compare against. **No action needed** — this is a
"not applicable, no analogous problem" finding, not a gap to close.

---

## internal/build/ — release-automation library pattern

Four small, single-purpose Go files under `internal/build/` (628 lines total across the
four; the package also has `env.go`, `file.go`, `util.go`, not read for this pass since
they're outside the task's scope) form a plain internal library that `build/ci.go`'s
`doArchive` and related release commands call into directly — no interfaces beyond
`Archive`, no plugin system, just small functions grouped by concern:

- **`archive.go` (296 lines)** — a minimal `Archive` interface (`Directory`/`Header`/
  `Close`, `archive.go:32-44`) with two implementations, `ZipArchive` and
  `TarballArchive` (`archive.go:109-188`), selected by file extension in `NewArchive`
  (`.zip` vs `.tar.gz`, `archive.go:46-55`). `WriteArchive(name string, files []string)
  error` (`archive.go:78-107`) is the public entry point `doArchive` calls (`build.WriteArchive(geth,
  gethArchiveFiles(*targetOS))` etc., `ci.go:724-759`) — it creates the archive, prints
  each file as it's added (a plain progress log to stdout, `archive.go:96, 101`), and
  cleans up the half-written file on any failure (`archive.go:85-91`). The reverse
  direction, `ExtractArchive`/`extractTarball`/`extractZip`/`extractFile`
  (`archive.go:190-296`), is used elsewhere in the build tooling to unpack downloaded
  toolchains (e.g. `DownloadGo` in `gotool.go`, see below) and includes a path-traversal
  guard (`extractFile` checks the resolved target path is still inside `dest` before
  writing, `archive.go:270-273`) — a real "zip-slip" defense, not incidental.
- **`azure.go` (120 lines)** — `AzureBlobstoreUpload`/`AzureBlobstoreList`/
  `AzureBlobstoreDelete` (`azure.go:37-120`), a thin wrapper around the official
  `Azure/azure-sdk-for-go` blob SDK for shipping release archives to Azure Blob Storage.
  Both mutating functions (`Upload`, `Delete`) respect a package-level `*DryRunFlag` —
  `AzureBlobstoreUpload` prints "would upload..." and returns without touching the
  network when dry-run is set (`azure.go:43-46`), and `AzureBlobstoreDelete` does the
  equivalent per-blob ("would delete...", `azure.go:97-100`) — a real dry-run mode built
  into the library itself, not bolted on by the caller.
- **`pgp.go` (71 lines)** — `PGPSignFile`/`PGPKeyID` (`pgp.go:30-71`), wrapping
  `golang.org/x/crypto/openpgp` to detach-sign a release archive with a single armored
  private key (both functions assert exactly one key is present in the supplied keyring,
  `len(keys) != 1` is a hard error in both, `pgp.go:41-43, 67-69`) and to recover a key's
  ID from an armored public/private key string. Called from `doArchive` right after the
  archive itself is written (`build.PGPSignFile(archive, archive+".asc", string(key))`,
  `ci.go:785`), producing the `.asc` detached-signature file shipped alongside every
  release archive.
- **`gotool.go` (141 lines)** — `GoToolchain` (a small struct capturing `Root`/`GOARCH`/
  `GOOS`/`CC`, `gotool.go:31-38`) wraps every invocation of the `go` binary the rest of
  `build/ci.go` needs (`Go(command, args...)`, `gotool.go:41-70`), and `DownloadGo`
  (`gotool.go:96-141`) fetches and unpacks a pinned Go toolchain version when the active
  `go` doesn't already match (short-circuiting if it does, `gotool.go:104-107`) —
  exactly the mechanism `check_generate` and other CI commands rely on to guarantee a
  consistent toolchain version regardless of what's on the runner's `PATH`. The one line
  worth quoting verbatim, because it captures a real cross-compilation gotcha this file
  exists specifically to work around:

  ```go
  // Configure environment for cross build. Force CGO_ENABLED=1 whenever
  // either GOOS or GOARCH differs from the host: Go's default is
  // CGO_ENABLED=0 for any cross-compile, but geth's release builds rely
  // on cgo (c-kzg-4844, secp256k1) regardless of which axis is crossing.
  crossArch := g.GOARCH != "" && g.GOARCH != runtime.GOARCH
  crossOS := g.GOOS != "" && g.GOOS != runtime.GOOS
  if crossArch || crossOS {
      tool.Env = append(tool.Env, "CGO_ENABLED=1")
  }
  ```

  (`gotool.go:44-52`). Go's toolchain silently disables cgo on any cross-compile
  (differing `GOARCH` *or* `GOOS` from the host) unless told otherwise, and two of
  go-ethereum's dependencies — `c-kzg-4844` (the KZG commitment library backing EIP-4844
  blob verification) and its own `secp256k1` bindings — are cgo-backed C libraries with
  no pure-Go fallback path in this build. Without this override, a cross-compiled release
  binary would silently link a broken (cgo-disabled) build of those packages instead of
  failing loudly, which is exactly the kind of "fails at runtime on an unrelated machine
  weeks later" bug this three-line guard exists to prevent. `gotool.go` also force-appends
  `CGO_CFLAGS=-O2 -g -D__BLST_PORTABLE__` unconditionally (`gotool.go:67`) — a second,
  narrower cgo portability guard specifically for BLST (the BLS12-381 backend), ensuring
  the compiled C code doesn't assume modern-CPU instruction sets are available on
  whatever machine ends up running the release binary.

**Not urgent for fukuii, and correctly so.** fukuii has no equivalent
`scripts/agent-tooling/lib/`-shaped typed library for release automation specifically —
`scripts/agent-tooling/lib/` (per the CLAUDE.md reference index) is a shell-script helper
library scoped to *agent tooling*, not release packaging, and there is no existing
release pipeline complex enough to need an `internal/build`-equivalent yet (no
cross-compiled multi-platform archives, no Azure/S3 blob upload, no PGP-signed release
artifacts). The pattern itself — small, single-concern files (archive writing, cloud
upload, signing, toolchain invocation) composed by a single CI entry-point script rather
than one monolithic release script — is worth remembering as a *shape* for whenever
fukuii's release process does grow that complex, but building it preemptively for a
release pipeline that doesn't exist yet would be exactly the kind of speculative
infrastructure this project's own conventions warn against.

---

## Fukuii verdict summary table

| Finding | Port now / Needs design / Not portable / Not applicable (no analogous problem) | Reasoning |
|---|---|---|
| `FuzzRLP`'s minimal native-fuzzing pilot shape (20-line `decodeEncode` property, zero corpus/CI/harness required) | **Port now** — as `fukuii-rlp-roundtrip`, a ScalaCheck property, not literal Go fuzzing | fukuii already has the exact dependency (`scalacheck-1-18`, `project/Dependencies.scala:69`) and the exact per-type round-trip pattern (`RLPSuite.scala`'s nine `forAll(Gen...)` tests) — but every existing property generates a *valid domain value* first, never arbitrary bytes fed straight to the decoder. Adding one `forAll(Gen.listOf(Arbitrary.arbitrary[Byte]))` property against `RLP.rawDecode`/`decode[T]`, mirroring `decodeEncode`'s "decode; if it succeeds, re-encode and compare to the original bytes" property exactly, costs one test method and zero new infrastructure. This is a property test, not coverage-guided fuzzing (no instrumentation feedback, no corpus persistence) — a real capability gap versus true fuzzing, but a strict upgrade over today's zero adversarial-byte testing of the RLP decoder. |
| The 6 `tests/fuzzers/` differential-fuzzing subpackages (bls12381, bn256, difficulty, rangeproof, secp256k1, txfetcher) and the remaining ~29 inline native `Fuzz*` targets | **Not portable (mid-term/all-or-nothing infrastructure)** | These depend on either genuine coverage-guided fuzzing (native Go `testing.F` + `go test -fuzz`, with no JVM equivalent) or cross-implementation differential comparison against independent third-party libraries in the same language ecosystem (`cloudflare`/`google`/`gnark` for BN256, `decred/dcrd` for secp256k1) — porting these individually would each be its own multi-day investigation into whether an equivalent second JVM implementation even exists to differential-test against. Building general JVM/Scala fuzzing infrastructure (e.g. jqwik, or wiring ScalaCheck's `Prop.forAll` with a corpus-persistence layer to approximate coverage guidance) remains the correctly-scoped mid-term project prior reference-client passes have already identified — this pass does not change that framing, it only extracts the one piece (`FuzzRLP`) cheap enough to act on immediately. |
| OSS-Fuzz continuous-fuzzing wiring (`oss-fuzz.sh`, `gofuzz-shim`, decoupled entirely from go-ethereum's own CI) | **Not applicable** | Requires Google OSS-Fuzz program enrollment (external, invitation/application-based), a `gofuzz-shim`-equivalent JVM bridge (doesn't exist), and continuous external infrastructure fukuii has no path to today. Not a "port later" item — it's contingent on infrastructure fukuii doesn't control. |
| `check_baddeps` mechanism (named forbidden-import-pair grep, gated in CI's Lint job) | **Port now** (mechanism, not the specific rule — fukuii already has the rule) | fukuii's `fukuii-dependency-audit` BSL guard (`.agents/skills/fukuii-dependency-audit/SKILL.md:51-56`) is the same shape already — a grep for a specific forbidden import family (Akka, for BSL 1.1 reasons) — but it only runs on-demand via the skill, never automatically in CI the way `check_baddeps` runs on every push/PR inside `go.yml`'s Lint job. Extracting the existing two grep lines into a CI-gated script step is a small, concrete, immediately-actionable change; the go-ethereum-specific rule itself (`core/rawdb` must not import concrete DB backends) has no direct fukuii analog to add today, since fukuii's `db/` layer doesn't show the same "abstraction package leaking a concrete backend" failure mode. |
| `check_generate` (verifies `go generate` output and `go mod tidy` are both drift-free, committed) | **Not applicable (no analogous problem)** | fukuii's only generated-code concern, `sbt-buildinfo`'s `BuildInfo.scala`, is produced fresh on every `sbt compile` and never committed to the tree (and is explicitly excluded from Scoverage/Scapegoat, `build.sbt:401, 589`) — there is no committed generated file that could drift from its generator the way go-ethereum's `gen_*.go` files can. sbt also has no `go mod tidy -diff`-equivalent dependency-manifest-drift check, and `project/Dependencies.scala` is hand-maintained, not derived, so there is nothing for such a check to compare against even if one were built. |
| `internal/build/` helper library (`archive.go` tar/zip writer with zip-slip guard, `azure.go` blob upload with built-in dry-run, `pgp.go` detached-signing wrapper, `gotool.go`'s `GoToolchain` + forced-`CGO_ENABLED=1`-on-cross-compile pattern) | **Needs design (idea only, not urgent)** | No fukuii equivalent exists because no fukuii equivalent problem exists yet — no cross-compiled multi-platform release archives, no cloud blob upload, no PGP-signed release artifacts. The *shape* (small single-concern files, composed by one CI entry point, each with its own safety guard baked in — path-traversal check, dry-run mode, single-key assertion, forced-cgo-on-cross-compile) is worth keeping as a reference the day fukuii's release pipeline grows complex enough to need it; building it preemptively now would be speculative infrastructure with no current consumer. |

---

*Compiled from a direct read of every file cited above in the vendored clone at
`.claude/repo-references/clients/go-ethereum/`. `core/types/rlp_fuzzer_test.go` (147
lines), `tests/fuzzers/README.md` (45 lines), `oss-fuzz.sh` (231 lines), and all four
`internal/build/` files cited (`archive.go`, `azure.go`, `pgp.go`, `gotool.go`, 628 lines
combined) were read in full; `build/ci.go` (1,356 lines) was read in full for the
`check_generate`/`check_baddeps` sections and the `internal/build` call sites. The `Fuzz*`
function count (44) was confirmed via `grep -rn "^func Fuzz" --include="*.go" .` against
the vendored clone's root, not estimated. Line numbers refer to the vendored clone's
checkout at commit `59e89e81e` (2026-07-01), on the `upstream`-tracking branch; re-verify
against `git log` if the vendored copy is refreshed.*
