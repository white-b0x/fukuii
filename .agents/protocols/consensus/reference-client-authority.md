# Reference-Client Authority for Consensus Review

A consensus/byte-correctness review or co-sign is only as good as its oracle. This protocol
names the one rule that keeps a review from silently validating itself: **any `fukuii/*` branch
— `fukuii/july-fourth` or any other — is a self-reference and can never be a review oracle. Only
an external reference client or an official spec/vector is.**

Used by: `forge`, `beacon` (every consensus co-sign/review they produce), `eye` (flags a
review whose oracle is a `fukuii/*` branch as a finding, not a pass).
Referenced by: `consensus-change-protocol.md` (the hard-stop gate this rule sharpens),
`systemic-review-protocol.md` (its "Reference-client authority model" section is the
per-network/per-concern authority table this protocol's citation rule points at — read that
table for *which* client is authoritative for a given concern; this doc governs *that an
external one is cited at all*).

---

## The rule

**`fukuii/july-fourth` (`com.chipprbots.ethereum.*`) is a `fukuii` branch — fukuii's OWN prior
code, presumed buggy until externally verified, never a review authority.** So is any other
`fukuii/*` branch a review might reach for. It is a structural/transcription guide only: useful
for "where did this logic live, what did it look like" — never for "is this value/height/set
correct." Validating fukuii's rebuild code against `fukuii/july-fourth` is fukuii validating
fukuii — circular by construction, no external client involved, regardless of how the check is
worded.

Every consensus/byte-correctness review or co-sign MUST cite an **external** oracle:

- A reference client, byte-cited as `<client>/<path-from-repo-root>:<line>` against the vendored
  clone under `.claude/repo-references/clients/<client>/` — using the per-network/per-concern
  authority named in `systemic-review-protocol.md`'s "Reference-client authority model" (e.g.
  core-geth for frozen ETC values, go-ethereum for ETH-family values, go-ethereum+besu together
  for shared EVM/RLP/crypto behavior).
- An official spec or vector: the ECIP/EIP text itself, or `ethereum/tests` KATs.

**Fork activation heights and fork-membership sets are not exempt — if anything they are the
sharpest case.** A claim of the shape "block N activates fork F" or "opcode/EIP X is a member of
fork F's set" must byte-cite the reference client's own fork-schedule source, not
`fukuii/july-fourth` or any other fukuii-derived config or named bundle:

- ETC: core-geth `params/config_classic.go` (mainnet) / `params/config_mordor.go` (Mordor).
- ETH: go-ethereum `params/config.go`.

## "AS-IS" is a banned review term — name the branch instead

**Do not write "AS-IS" as the label for a validation source in any review or co-sign.** "Validate
against AS-IS" reads as a neutral methodology step — it does not read as what it actually is:
"validate `fukuii`'s rebuild against `fukuii/july-fourth`," a fukuii branch checking a fukuii
branch. The abstraction is what let a self-check pass as an external gate (see the incident
below). When a transcription source must be named in a review, **name the branch explicitly**:
`fukuii/july-fourth` (or whichever `fukuii/*` branch is meant) — never the standalone word
"AS-IS." The circularity must be unmistakable on the surface of the citation itself, not
recoverable only by someone who already knows what "AS-IS" is shorthand for.

"AS-IS" remains acceptable **only** as informal prose shorthand that *also* names the branch in
the same breath ("the AS-IS, i.e. `fukuii/july-fourth`") — never as the standalone label on a
citation, a co-sign line, or a conformance spec's "expected" column.

## Circular validation is not a gate

A review is **circular** — and therefore not a correctness gate, regardless of how thorough it
reads — if its only citations are to fukuii's own artifacts:

- "fukuii's fold equals fukuii's own named fork-opcode bundle" (`EtcOlympiaOpCodes`,
  `EthOsakaOpCodes`, or any other fukuii-derived set) with no external cite for what that bundle
  *should* contain.
- "Byte-exact vs. `fukuii/july-fourth`" (however labeled — including as "AS-IS") with no external
  cite for what `fukuii/july-fourth` itself should have been.
- A conformance spec whose "expected" column was itself transcribed from `fukuii/july-fourth`
  (or any other `fukuii/*` branch) rather than derived from the reference client or the ECIP/EIP
  text.

These checks are not worthless — internal self-consistency is a real property (it catches
transcription slips between two fukuii artifacts) — but internal self-consistency is not
byte-correctness, and a co-sign must not conflate the two. A review that can only produce
citations to a `fukuii/*` branch must report **"UNVERIFIED vs. reference —
CHANGES-REQUESTED,"** not CO-SIGNED or APPROVED. State explicitly which external oracle is
missing, so the next pass knows what to go fetch.

## Co-sign citation line (required)

Any forge/beacon co-sign of a fork-schedule, activation-height, opcode-set, or byte-value claim
states the external citation inline, not as an afterthought:

```
Reference-client byte-cite (path:line): <client>/<path>:<line> — REQUIRED;
a `fukuii/*` branch citation (e.g. fukuii/july-fourth) is a self-reference, NOT valid.
```

If no external citation is available (spec not yet published, reference client doesn't cover
this concern), the co-sign says so explicitly rather than substituting a `fukuii/*` branch
citation silently.

## Worked example — the fukuii/july-fourth self-reference incident

Two consensus bugs shipped past multiple forge/beacon co-signs and an `eye` gate because those
reviews validated fukuii's rebuild against `fukuii/july-fourth` (labeled "AS-IS" in the review
prose) and against fukuii's own derived named fork-opcode sets — internally consistent,
externally wrong:

- **EIP-3860 activation height.** The initcode-size-limit fork height was mis-placed by roughly
  4.7M blocks on ETC and diverged into a fork on ETH. `fukuii/july-fourth` carried the same wrong
  height fukuii's rebuild transcribed from it, so the "byte-exact vs. AS-IS" check passed cleanly
  — the check was comparing fukuii to fukuii.
- **SELFDESTRUCT / EIP-6780 `createdInThisTx`.** fukuii's rebuild and `fukuii/july-fourth` both
  used the same wrong `!originalWorld.accountExists` proxy for "was this account created in the
  current transaction." Because both branches carried the identical bug, a diff-vs-AS-IS review
  agreed with itself and reported no discrepancy — a textbook self-reference failure: the
  oracle and the artifact under test were both `fukuii/*` branches.

The reference clients that would have caught both (`core-geth/params/config_classic.go`,
`go-ethereum/params/config.go` for the activation height; go-ethereum's/besu's actual
`createdInThisTx`/account-creation-tracking implementation for EIP-6780) were vendored and
available the entire time; no review cited them. This is the incident this protocol exists to
prevent from recurring: a review can be thorough, multi-pass, and still be worthless as a
correctness gate if every citation in it traces back to a `fukuii/*` branch — labeling that
branch "AS-IS" instead of naming it is what let the self-reference go unnoticed.
