package com.chipprbots.fukuii.execution.blockchaintest

import java.io.File

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256

/** An `ethereum/tests` **BlockchainTest** case, parsed from its JSON fixture — the geth `tests/block_test_util.go`
  * `btJSON` / besu `BlockchainReferenceTestCaseSpec` schema. Each JSON file holds one or more cases (one per fork —
  * `…::Name_Cancun`, `…::Name_Prague`); every case is **single-fork** (its `network` field names the ruleset).
  *
  * The harness ([[BlockchainTestHarness]]) drives the L4 pipeline against these: build the `pre` genesis alloc into an
  * `InMemoryWorldState`, resolve `network` → a fukuii `ProtocolSpec`, decode each `blocks[].rlp` into an L1 `Block`,
  * run `BlockProcessor.processBlock`, and compare the resulting canonical head to `lastblockhash` **byte-for-byte**
  * (the header commitment ties in `stateRoot`/`receiptsRoot`, so a green import is a byte-exact state proof).
  *
  * @param name
  *   the fully-qualified case key (`path::Name_Fork`).
  * @param network
  *   the fork ruleset (`Frontier`…`Cancun`/`Prague`/`Osaka`) — single-fork per case.
  * @param sealEngine
  *   the seal engine (`NoProof`/`Ethash`); the harness ignores PoW-seal verification (an L5 concern), so `NoProof` and
  *   `Ethash` cases are driven identically at the execution layer.
  * @param chainId
  *   `config.chainid` (mainnet ETH fixtures: `0x01`).
  * @param pre
  *   the genesis state alloc (`address → account`).
  * @param genesisStateRoot
  *   the expected `genesisBlockHeader.stateRoot` — the harness's first checkpoint (world-build + trie correctness).
  * @param genesisHash
  *   `genesisBlockHeader.hash` — the initial canonical head (`BLOCKHASH(0)`).
  * @param genesisNumber
  *   `genesisBlockHeader.number` (normally 0).
  * @param blocks
  *   the block sequence to import, in order; a `Some(expectException)` block is expected to be **rejected**.
  * @param lastBlockHash
  *   the expected canonical head hash after importing all blocks (invalid blocks skipped).
  */
final case class BlockchainTestCase(
    name: String,
    network: String,
    sealEngine: Option[String],
    chainId: BigInt,
    pre: Map[Address, PreAccount],
    genesisStateRoot: ByteString,
    genesisHash: Hash,
    genesisNumber: BigInt,
    blocks: List[ExpectedBlock],
    lastBlockHash: Hash
):
  /** The short case name (drops the file path prefix) — for readable test labels. */
  def shortName: String = name.substring(name.indexOf("::") + 2).nn

/** A genesis-alloc account from the `pre` (or `postState`) map: balance, nonce, code, and the storage slot overrides.
  */
final case class PreAccount(
    balance: BigInt,
    nonce: BigInt,
    code: ByteString,
    storage: Map[UInt256, BigInt]
)

/** One block in a case's `blocks[]` sequence: its full consensus RLP and, for an `InvalidBlocks` fixture, the
  * `expectException` string naming the reason the block must be rejected (the harness asserts rejection, not the exact
  * reason — fukuii's `BlockExecutionError` taxonomy is not the test's exception vocabulary).
  */
final case class ExpectedBlock(rlp: ByteString, expectException: Option[String])

object BlockchainTestFixture:

  private val mapper: ObjectMapper = new ObjectMapper()

  /** Parse every case in a BlockchainTest JSON `file`. */
  def parse(file: File): List[BlockchainTestCase] =
    val root = mapper.readTree(file).nn
    root.fields().nn.asScala.toList.map { entry =>
      parseCase(entry.getKey().nn, entry.getValue().nn)
    }

  private def parseCase(name: String, node: JsonNode): BlockchainTestCase =
    val genesis = node.get("genesisBlockHeader").nn
    BlockchainTestCase(
      name = name,
      network = node.get("network").nn.asText().nn,
      sealEngine = Option(node.get("sealEngine")).map(_.asText().nn),
      chainId = Option(node.get("config"))
        .flatMap(c => Option(c.get("chainid")))
        .map(n => quantity(n.asText().nn))
        .getOrElse(BigInt(1)),
      pre = parseAccounts(node.get("pre").nn),
      genesisStateRoot = bytes(genesis.get("stateRoot").nn.asText().nn),
      genesisHash = Hash(bytes(genesis.get("hash").nn.asText().nn)),
      genesisNumber = quantity(genesis.get("number").nn.asText().nn),
      blocks = parseBlocks(node.get("blocks").nn),
      lastBlockHash = Hash(bytes(node.get("lastblockhash").nn.asText().nn))
    )

  private def parseBlocks(node: JsonNode): List[ExpectedBlock] =
    node.elements().nn.asScala.toList.map { b =>
      ExpectedBlock(
        rlp = bytes(b.get("rlp").nn.asText().nn),
        expectException = Option(b.get("expectException")).map(_.asText().nn)
      )
    }

  private def parseAccounts(node: JsonNode): Map[Address, PreAccount] =
    node
      .fields()
      .nn
      .asScala
      .map { entry =>
        val a = entry.getValue().nn
        val storage = Option(a.get("storage")) match
          case Some(s) =>
            s.fields()
              .nn
              .asScala
              .map { slot =>
                UInt256(quantity(slot.getKey().nn)) -> quantity(slot.getValue().nn.asText().nn)
              }
              .toMap
          case None => Map.empty[UInt256, BigInt]
        Address(bytes(entry.getKey().nn)) -> PreAccount(
          balance = quantity(a.get("balance").nn.asText().nn),
          nonce = quantity(a.get("nonce").nn.asText().nn),
          code = bytes(a.get("code").nn.asText().nn),
          storage = storage
        )
      }
      .toMap

  /** A hex byte-string (`0x…`) → `ByteString`. `0x` (empty) yields an empty `ByteString`. */
  private def bytes(hex: String): ByteString =
    val h = if hex.startsWith("0x") then hex else "0x" + hex
    if h == "0x" then ByteString.empty else ByteString(Hex.decode(h))

  /** A hex (`0x…`) or decimal quantity → `BigInt`. `0x`/empty yields 0. */
  private def quantity(s: String): BigInt =
    if s.startsWith("0x") then
      val digits = s.substring(2)
      if digits.isEmpty then BigInt(0) else BigInt(digits, 16)
    else if s.isEmpty then BigInt(0)
    else BigInt(s)
