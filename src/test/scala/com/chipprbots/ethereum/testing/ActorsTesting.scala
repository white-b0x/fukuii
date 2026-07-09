package com.chipprbots.ethereum.testing
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol

object ActorsTesting:

  /** Typed-probe equivalent of Classic `TestProbe.expectMsgPF`: receive exactly the next message within `max` and apply
    * `pf` to it, failing loudly (instead of silently retrying) if it doesn't match — mirrors Classic's single-shot,
    * non-retrying semantics rather than `fishForMessage`'s retry-until-match behaviour.
    */
  extension [M](probe: TypedTestProbe[M])
    def expectMessagePF[T](max: FiniteDuration = probe.remainingOrDefault)(pf: PartialFunction[M, T]): T =
      val msg = probe.receiveMessage(max)
      pf.applyOrElse(msg, (m: M) => throw new AssertionError(s"expectMessagePF: unexpected message $m"))

    /** Typed-probe equivalent of Classic `TestProbe.fishForSpecificMessage`: skip messages `pf` isn't defined for
      * (within `max`), then apply `pf` to the first one it matches and return the extracted value.
      */
    def fishForSpecificMessage[T](max: FiniteDuration = probe.remainingOrDefault)(pf: PartialFunction[M, T]): T =
      val messages = probe.fishForMessagePF(max) {
        case m if pf.isDefinedAt(m) => FishingOutcomes.complete
        case _                      => FishingOutcomes.continueAndIgnore
      }
      pf(messages.head)

  /** Typed-actor equivalent of a Classic AutoPilot reply, for specs whose `SyncController` stub is a real
    * `ActorRef[SyncController.Command]` (`testKit.createTestProbe` can't run an `AutoPilot` — Pekko Typed's `TestProbe`
    * has no such hook).
    */
  def syncStatusBehavior(status: SyncProtocol.Status): Behavior[SyncController.Command] =
    Behaviors.receiveMessage {
      case SyncController.WrappedSyncProtocol(gs: SyncProtocol.GetStatus) =>
        gs.replyTo ! status
        Behaviors.same
      case _ => Behaviors.same
    }
