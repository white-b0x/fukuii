package com.chipprbots.ethereum.network

import org.apache.pekko.actor.testkit.typed.scaladsl.LoggingTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import org.scalatest.flatspec.AnyFlatSpecLike

import com.chipprbots.ethereum.testing.Tags.*

/** Smoke coverage for [[CriticalActorAlerter]] (RS08-REMAINDER-01 P3) — the STOP-AND-ALERT death-watch has no prior
  * direct spec. See `.claude/agent-protocols/alert-wrapper-protocol.md`.
  */
class CriticalActorAlerterSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike:

  "CriticalActorAlerter" should "log a CRITICAL error and stop itself when the watched actor terminates" taggedAs (
    UnitTest
  ) in:
    val critical: TypedActorRef[String] = testKit.spawn(Behaviors.empty[String])
    val alerter: TypedActorRef[Nothing] = testKit.spawn(CriticalActorAlerter(critical, "test-name"))
    val alerterProbe = testKit.createTestProbe[Nothing]()

    LoggingTestKit.error("CRITICAL actor stopped unexpectedly").expect {
      testKit.stop(critical)
    }
    alerterProbe.expectTerminated(alerter)
