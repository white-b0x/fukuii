package com.chipprbots.fukuii.crypto

import org.scalatest.funsuite.AnyFunSuite

class ConstantTimeSpec extends AnyFunSuite:

  test("equal arrays compare true"):
    assert(constantTimeEquals(Array[Byte](1, 2, 3, 4), Array[Byte](1, 2, 3, 4)))

  test("unequal arrays of the same length compare false"):
    assert(!constantTimeEquals(Array[Byte](1, 2, 3, 4), Array[Byte](1, 2, 3, 5)))

  test("arrays of different length compare false"):
    assert(!constantTimeEquals(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3, 4)))

  test("two empty arrays compare true"):
    assert(constantTimeEquals(Array.emptyByteArray, Array.emptyByteArray))

  // --- L0-F1 (RX-L0-16 / L0.md:179): the constant-time *enforcement lint* -----------------------------------
  // A repo-wide regression guard sanctioning `constantTimeEquals` as the ONLY approved symbol for a secret/
  // MAC/tag byte comparison. Fails if a crypto main source introduces a non-constant-time byte-content compare
  // (`Arrays.equals`, `.sameElements`, a raw `Arrays.constantTimeAreEqual`, or `==` on a secret/MAC line) —
  // a timing-oracle risk. Bites once L8 keystore-MAC + L9 JWT/auth land; a source-grep test (the sanctioned
  // fallback per the task) since it runs under `testOnly *ConstantTime*` and the lint stack has no in-suite hook.
  test("L0-F1: crypto secret/MAC comparisons go through constantTimeEquals only (R11 enforcement lint)"):
    val root = List(
      "modules/crypto/src/main/scala/com/chipprbots/fukuii/crypto", // repo-root CWD
      "src/main/scala/com/chipprbots/fukuii/crypto" // module-baseDirectory CWD
    ).map(new java.io.File(_))
      .find(_.isDirectory)
      .getOrElse(fail(s"cannot locate crypto main sources (cwd=${new java.io.File(".").getCanonicalPath})"))

    def scalaFiles(d: java.io.File): List[java.io.File] =
      val entries = Option(d.listFiles).fold(List.empty[java.io.File])(_.toList)
      entries.filter(f => f.isFile && f.getName.endsWith(".scala")) :::
        entries.filter(_.isDirectory).flatMap(scalaFiles)

    def linesOf(f: java.io.File): List[String] =
      val src = scala.io.Source.fromFile(f, "UTF-8")
      try src.getLines().toList
      finally src.close()

    // secret/MAC identifiers specific enough to avoid false positives (skip bare "mac"/"tag")
    val secretIdent = List("authentication", "hmac", "checksum", "passphrase")

    val violations =
      for
        f <- scalaFiles(root)
        (line, idx) <- linesOf(f).zipWithIndex
        trimmed = line.trim
        if !(trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*"))
        lower = line.toLowerCase
        // Rule A — non-constant-time byte-content equality primitives
        ruleA = line.contains("Arrays.equals") || line.contains(".sameElements") ||
          line.contains("Arrays.constantTimeAreEqual")
        // Rule B — value-equality `==` on a secret/MAC line
        ruleB = line.contains("==") && secretIdent.exists(lower.contains)
        if ruleA || ruleB
        // Allowlist the two sanctioned plain-compare sites:
        //   ConstantTime.scala      — the constantTimeEquals wrapper itself (it calls the BC primitive).
        //   ECDSASignature.scala    — `calculateV` compares a RECOVERED pubkey against the signer's OWN
        //                             public key: both are non-secret (derived from data the signer holds),
        //                             so a plain `Arrays.equals` is correct, not a timing oracle.
        if !(f.getName == "ConstantTime.scala" ||
          (f.getName == "ECDSASignature.scala" && line.contains("Arrays.equals") && line.contains("pubKey")))
      yield s"${f.getName}:${idx + 1}: $trimmed"

    assert(
      violations.isEmpty,
      "L0-F1 (R11): a secret/MAC/tag byte comparison must use `constantTimeEquals` — not " +
        "`==`/`sameElements`/`Arrays.equals`/raw `Arrays.constantTimeAreEqual` (timing-oracle risk). " +
        s"Offending crypto main site(s):\n  ${violations.mkString("\n  ")}"
    )
