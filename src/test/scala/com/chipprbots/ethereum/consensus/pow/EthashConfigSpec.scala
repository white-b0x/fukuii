package com.chipprbots.ethereum.consensus.pow

import java.nio.file.Files

import scala.compiletime.uninitialized

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** EthashConfig.resolveEthashDir — datadir-path alignment (2026-07-12): the `ethash-dir` default moved from `~/.ethash`
  * to `<datadir>/ethash`, with a fallback to the legacy path so existing miners aren't forced through a needless
  * multi-GB DAG regeneration. Exercises only directory resolution — no DAG computation is involved.
  */
class EthashConfigSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private var savedUserHome: String = uninitialized
  private var fakeHome: java.nio.file.Path = uninitialized

  override def beforeEach(): Unit =
    savedUserHome = System.getProperty("user.home")
    fakeHome = Files.createTempDirectory("ethash-config-spec-home")
    System.setProperty("user.home", fakeHome.toString)

  override def afterEach(): Unit =
    System.setProperty("user.home", savedUserHome)
    deleteRecursively(fakeHome.toFile)

  private def deleteRecursively(f: java.io.File): Unit =
    if f.isDirectory then Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  private def legacyDir: java.io.File = new java.io.File(fakeHome.toFile, ".ethash")

  private def touchDagFile(dir: java.io.File): Unit =
    dir.mkdirs()
    val f = new java.io.File(dir, "full-R23-deadbeef")
    f.createNewFile()
    ()

  "resolveEthashDir" should "use the configured directory when it already has DAG files" taggedAs UnitTest in {
    val configuredDir = Files.createTempDirectory("ethash-config-spec-configured").toFile
    touchDagFile(configuredDir)
    touchDagFile(legacyDir) // legacy also populated — configured dir should still win

    EthashConfig.resolveEthashDir(configuredDir.getAbsolutePath) shouldBe configuredDir.getAbsolutePath
  }

  it should "fall back to the legacy ~/.ethash directory when the configured directory is missing" taggedAs UnitTest in {
    val configuredDir = Files.createTempDirectory("ethash-config-spec-configured").toFile
    configuredDir.delete() // simulate a fresh datadir — <datadir>/ethash does not exist yet
    touchDagFile(legacyDir)

    EthashConfig.resolveEthashDir(configuredDir.getAbsolutePath) shouldBe legacyDir.getAbsolutePath
  }

  it should "fall back to the legacy ~/.ethash directory when the configured directory is empty" taggedAs UnitTest in {
    val configuredDir = Files.createTempDirectory("ethash-config-spec-configured").toFile
    touchDagFile(legacyDir)

    EthashConfig.resolveEthashDir(configuredDir.getAbsolutePath) shouldBe legacyDir.getAbsolutePath
  }

  it should "use the configured directory when neither it nor the legacy directory has DAG files" taggedAs UnitTest in {
    val configuredDir = Files.createTempDirectory("ethash-config-spec-configured").toFile

    EthashConfig.resolveEthashDir(configuredDir.getAbsolutePath) shouldBe configuredDir.getAbsolutePath
  }
