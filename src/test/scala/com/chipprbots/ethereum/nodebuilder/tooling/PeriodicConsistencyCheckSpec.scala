package com.chipprbots.ethereum.nodebuilder.tooling

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.nodebuilder.tooling.PeriodicConsistencyCheck.ConsistencyCheck
import com.chipprbots.ethereum.nodebuilder.tooling.PeriodicConsistencyCheck.Tick
import com.chipprbots.ethereum.testing.Tags.*

/** Thin smoke coverage for [[PeriodicConsistencyCheck]] (RS08-REMAINDER-01 P3) — no prior direct spec exists. Covers
  * only that a `Tick` is handled without throwing, and that each of the three skip-condition branches (SNAP done, SNAP
  * in progress, Engine API enabled) is reachable without throwing. The underlying consistency-check algorithm itself is
  * [[StorageConsistencyChecker]]'s concern, which is intentionally out of scope here.
  */
class PeriodicConsistencyCheckSpec extends AnyFlatSpec with Matchers:

  "PeriodicConsistencyCheck" should "process a Tick without throwing when neither SNAP nor Engine API applies" taggedAs (
    UnitTest
  ) in new TestSetup():
    noException should be thrownBy kit.run(Tick)

  it should "skip the check without throwing when SNAP sync is done" taggedAs UnitTest in new TestSetup():
    appStateStorage.snapSyncDone().commit()
    noException should be thrownBy kit.run(Tick)

  it should "skip the check without throwing when SNAP sync is in progress" taggedAs UnitTest in new TestSetup():
    appStateStorage.putSnapSyncPivotBlock(BigInt(1)).commit()
    noException should be thrownBy kit.run(Tick)

  it should "skip the check without throwing when Engine API is enabled" taggedAs UnitTest in new TestSetup(
    engineApiEnabled = true
  ):
    noException should be thrownBy kit.run(Tick)

  class TestSetup(engineApiEnabled: Boolean = false) extends EphemBlockchainTestSetup:
    val storages = getNewStorages.storages
    val appStateStorage: AppStateStorage = storages.appStateStorage
    val blockNumberMappingStorage: BlockNumberMappingStorage = storages.blockNumberMappingStorage
    val blockHeadersStorage: BlockHeadersStorage = storages.blockHeadersStorage

    val kit: BehaviorTestKit[ConsistencyCheck] = BehaviorTestKit(
      PeriodicConsistencyCheck.start(
        appStateStorage,
        blockNumberMappingStorage,
        blockHeadersStorage,
        shutdown = () => (),
        engineApiEnabled = engineApiEnabled
      )
    )
