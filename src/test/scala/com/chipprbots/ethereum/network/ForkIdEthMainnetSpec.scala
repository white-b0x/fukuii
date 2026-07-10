package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.*

/** ForkId CRC32 accumulation tests for ETH mainnet (block-number forks through Gray Glacier, then timestamp forks from
  * Shanghai onward).
  *
  * Ground truth: go-ethereum `core/forkid/forkid_test.go` TestCreation "Mainnet test cases" (upstream branch, verified
  * 2026-07-09). Mainnet genesis hash: params/config.go MainnetGenesisHash.
  *
  * Regression guard for ETH-F3: Arrow Glacier (13773000) and Gray Glacier (15050000) are EIP-4345/ EIP-5133 bomb-delay
  * blocks with no EVM effect, but go-ethereum still checksums them into the EIP-2124 fork-id chain. Omitting them
  * shifts every checksum from London onward, so a fully-synced fukuii node would fail ForkIdValidator against a
  * fully-synced real mainnet peer (ErrLocalIncompatibleOrStale) even though both are on the correct chain.
  */
class ForkIdEthMainnetSpec extends AnyWordSpec with Matchers:

  private val ethMainnetConf = blockchains.blockchains("eth")

  private val mainnetGenesisHash =
    ByteString(Hex.decode("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3"))

  private def create(block: BigInt, ts: Long): ForkId =
    ForkId.create(mainnetGenesisHash, ethMainnetConf)(block, ts)

  "ForkId for ETH mainnet" must {

    "accumulate the first London block (12965000) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(12965000, 0) shouldBe ForkId(0xb715077dL, Some(13773000))
      create(13772999, 0) shouldBe ForkId(0xb715077dL, Some(13773000))
    }

    "accumulate the first Arrow Glacier block (13773000) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(13773000, 0) shouldBe ForkId(0x20c327fcL, Some(15050000))
      create(15049999, 0) shouldBe ForkId(0x20c327fcL, Some(15050000))
    }

    "accumulate the first Gray Glacier block (15050000) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(15050000, 0) shouldBe ForkId(0xf0afd0e3L, Some(1681338455))
      create(20000000, 1681338454) shouldBe ForkId(0xf0afd0e3L, Some(1681338455))
    }

    "accumulate Shanghai timestamp (1681338455) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(20000000, 1681338455) shouldBe ForkId(0xdce96c2dL, Some(1710338135))
      create(30000000, 1710338134) shouldBe ForkId(0xdce96c2dL, Some(1710338135))
    }

    "accumulate Cancun timestamp (1710338135) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(30000000, 1710338135) shouldBe ForkId(0x9f3d2254L, Some(1746612311))
    }

    "accumulate Prague timestamp (1746612311) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(30000000, 1746612311) shouldBe ForkId(0xc376cf8bL, Some(1764798551))
    }

    "accumulate Osaka timestamp (1764798551) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(30000000, 1764798551) shouldBe ForkId(0x5167e2a6L, Some(1765290071))
    }

    "accumulate BPO1 timestamp (1765290071) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(30000000, 1765290071) shouldBe ForkId(0xcba2a1c0L, Some(1767747671))
    }

    "accumulate BPO2 timestamp (1767747671) into checksum — tail state, next=None" taggedAs (UnitTest, NetworkTest) in {
      create(30000000, 1767747671) shouldBe ForkId(0x07c9462eL, None)
      create(50000000, 2000000000) shouldBe ForkId(0x07c9462eL, None)
    }
  }
