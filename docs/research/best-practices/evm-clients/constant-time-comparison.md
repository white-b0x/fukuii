# Constant-Time Comparison at Auth / MAC Sites

Finding 3 of the reference-client cross-check (`reference-client-crosscheck.md`). Ratified
across all three language families. The canonical worked example of a **conditional**
standard: the condition ("at security-critical comparisons") is part of the rule, not an
asterisk on it.

---

## Invariant

**Byte comparisons of secrets, MACs, and authentication tags MUST use a constant-time
comparison primitive.** Timing-variable comparison (`==`, `Arrays.equals`, early-exit
`memcmp`) at these sites leaks the position of the first differing byte and enables a
timing oracle. This is required, not preferred.

## Evidence table

| Sub-claim | Client (weight) | Evidence (`file:line`) | Verdict |
|-----------|-----------------|------------------------|---------|
| ECIES message-tag / MAC compared constant-time | go-ethereum | `crypto/ecies/ecies.go:325` — `subtle.ConstantTimeCompare` (and `hmac.Equal` for HMAC) | SUPPORTED |
| ECIES handshake tag compared constant-time | besu (**JVM analog — weighted**) | `ethereum/p2p/.../handshake/ecies/ECIESEncryptionEngine.java:276` — `Arrays.constantTimeAreEqual(T1, T2)` | SUPPORTED |
| Engine-API JWT signature compared constant-time | nethermind (**JWT/auth reference**) | `Nethermind.Core/Authentication/JwtAuthentication.cs:251` — `CryptographicOperations.FixedTimeEquals` | SUPPORTED |

**Verdict: SUPPORTED** — and unanimously across Go / Java / C#. The JVM-weighted witness
(besu) confirms the JVM has a first-class primitive (`Arrays.constantTimeAreEqual`, Bouncy
Castle) that our Scala code should call at the equivalent sites.

## The nuance — it is applied *at* security-critical sites, not universally

The same clients use **plain, timing-variable comparison for non-secret data**, on purpose:

| Site | Client | Evidence (`file:line`) | Comparison used |
|------|--------|------------------------|-----------------|
| Per-frame RLPx MAC (non-secret, integrity-only) | besu | `ethereum/p2p/.../framing/Framer.java:321` — `Arrays.equals(expectedMac, candidateMac)` | plain `Arrays.equals` |

This is the load-bearing detail: besu deliberately uses constant-time compare for the ECIES
handshake tag (`ECIESEncryptionEngine.java:276`) and plain `Arrays.equals` for the
per-frame MAC (`Framer.java:321`) **in the same subsystem**. The distinction is whether a
timing leak yields an attacker anything — the handshake tag gates authentication; the
per-frame MAC is an integrity check on already-authenticated framing.

## Standard

- **Use a constant-time primitive for:** MAC/auth-tag verification, JWT/HMAC signature
  checks, secret/key equality, password-or-token equality. In Scala on the JVM, call Bouncy
  Castle's `Arrays.constantTimeAreEqual` (or `MessageDigest.isEqual` for digests) — never
  `sameElements`, `==`, or `java.util.Arrays.equals` at these sites.
- **Plain comparison is correct for** non-secret integrity checks on already-authenticated
  data (matching besu's per-frame-MAC choice). Do not blanket-convert every byte comparison
  to constant-time — that obscures which comparisons are actually security-critical and is
  not what the reference clients do.
- A standard derived from this finding must carry the condition. "Use constant-time
  comparison" without "at auth/MAC/secret sites" is an over-broad AMEND, not the ratified
  rule.
