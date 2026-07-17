package com.chipprbots.fukuii.execution.blockchaintest

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256

/** Always-run coverage for the BlockchainTest **fixture parser** ([[BlockchainTestFixture]]) — self-contained, no
  * external corpus (unlike [[BlockchainTestDriverSpec]], which is opt-in). Parses an inline minimal fixture matching
  * the geth `btJSON` schema and asserts every field decodes (hex/decimal quantities, byte-strings, the nested `pre`
  * account with storage, the `blocks[]` `expectException`).
  */
class BlockchainTestFixtureSpec extends AnyFunSuite:

  // A minimal two-case (valid + invalid) BlockchainTest, hand-written to the geth `btJSON` schema. Quantities mix hex
  // (`balance`, `chainid`) and decimal (`nonce`) to exercise both parse paths.
  private val inlineJson =
    """{
      |  "sample.json::sample_Cancun": {
      |    "network": "Cancun",
      |    "sealEngine": "NoProof",
      |    "config": { "chainid": "0x01" },
      |    "pre": {
      |      "0x1111111111111111111111111111111111111111": {
      |        "balance": "0x0de0b6b3a7640000",
      |        "nonce": "3",
      |        "code": "0x6001",
      |        "storage": { "0x00": "0x2a", "0x01": "0xff" }
      |      },
      |      "0x2222222222222222222222222222222222222222": {
      |        "balance": "0x00", "nonce": "0", "code": "0x", "storage": {}
      |      }
      |    },
      |    "genesisBlockHeader": {
      |      "stateRoot": "0xaabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
      |      "hash": "0x1122334455667788990011223344556677889900112233445566778899001122",
      |      "number": "0x00"
      |    },
      |    "blocks": [
      |      { "rlp": "0xdeadbeef" },
      |      { "rlp": "0xc0ffee", "expectException": "TransactionException.INTRINSIC_GAS_TOO_LOW" }
      |    ],
      |    "lastblockhash": "0x99887766554433221100998877665544332211009988776655443322110099aa"
      |  }
      |}""".stripMargin

  private def parseInline(): List[BlockchainTestCase] =
    val tmp = File.createTempFile("bt-sample", ".json")
    tmp.deleteOnExit()
    Files.write(tmp.toPath, inlineJson.getBytes(StandardCharsets.UTF_8))
    BlockchainTestFixture.parse(tmp)

  test("parse — case metadata (network, sealEngine, chainId, name)"):
    val tc = parseInline().head
    assert(
      tc.name == "sample.json::sample_Cancun" &&
        tc.shortName == "sample_Cancun" &&
        tc.network == "Cancun" &&
        tc.sealEngine.contains("NoProof") &&
        tc.chainId == BigInt(1)
    )

  test("parse — a pre account decodes balance (hex), nonce (decimal), code, and storage slots"):
    val tc = parseInline().head
    val acct = tc.pre(Address(Hex.decode("0x1111111111111111111111111111111111111111")))
    assert(
      acct.balance == BigInt("1000000000000000000") && // 0x0de0b6b3a7640000
        acct.nonce == BigInt(3) && // decimal "3"
        acct.code == ByteString(Hex.decode("0x6001")) &&
        acct.storage(UInt256(0)) == BigInt(0x2a) &&
        acct.storage(UInt256(1)) == BigInt(0xff)
    )

  test("parse — an empty-code / empty-storage account decodes to empties"):
    val tc = parseInline().head
    val acct = tc.pre(Address(Hex.decode("0x2222222222222222222222222222222222222222")))
    assert(acct.balance == BigInt(0) && acct.code.isEmpty && acct.storage.isEmpty)

  test("parse — genesis fields and the block sequence (rlp + expectException)"):
    val tc = parseInline().head
    assert(
      tc.genesisStateRoot == ByteString(
        Hex.decode("0xaabbccddeeff00112233445566778899aabbccddeeff00112233445566778899")
      ) &&
        tc.genesisNumber == BigInt(0) &&
        tc.blocks.length == 2 &&
        tc.blocks(0).rlp == ByteString(Hex.decode("0xdeadbeef")) &&
        tc.blocks(0).expectException.isEmpty &&
        tc.blocks(1).expectException.contains("TransactionException.INTRINSIC_GAS_TOO_LOW") &&
        tc.lastBlockHash.bytes ==
        ByteString(Hex.decode("0x99887766554433221100998877665544332211009988776655443322110099aa"))
    )
