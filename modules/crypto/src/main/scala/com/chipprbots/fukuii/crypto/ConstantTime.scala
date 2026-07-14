package com.chipprbots.fukuii.crypto

import org.bouncycastle.util.Arrays

/** The single audited constant-time comparison surface for secret material (R11).
  *
  * Wraps BouncyCastle `Arrays.constantTimeAreEqual` — the same primitive besu calls at its ECIES MAC site
  * (`ECIESEncryptionEngine.java:276`) and the API-shape nethermind unifies three consumers behind
  * (`CryptographicOperations.FixedTimeEquals`, used by JWT auth, the keystore MAC, and IES). Every secret-equality
  * check in this codebase (ECIES MAC, and — once built — the L8 keystore MAC and L9 JWT/auth-gate compares) routes
  * through this one symbol, so a lint can target it and no consumer re-imports BouncyCastle ad hoc.
  *
  * Do not use for non-secret integrity compares (e.g. a per-frame wire MAC where timing leaks no secret) — plain
  * `==`/`Arrays.equals` stays correct and faster there.
  */
def constantTimeEquals(a: Array[Byte], b: Array[Byte]): Boolean =
  Arrays.constantTimeAreEqual(a, b)
