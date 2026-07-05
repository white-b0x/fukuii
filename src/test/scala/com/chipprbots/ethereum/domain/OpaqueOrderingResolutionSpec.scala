package com.chipprbots.ethereum.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Regression test for a BigInt-opaque-type footgun: naively writing `given Ordering[X] = Ordering.by(_.value)` for
  * `opaque type X = BigInt` can resolve the implicit `Ordering[BigInt]` argument back to the `Ordering[X]` given
  * currently being defined (since `X =:= BigInt` inside its own defining scope) — a self-referential initialization
  * that surfaces as a `StackOverflowError`/`NullPointerException` the first time the ordering is actually used, not at
  * compile time.
  *
  * 10 of these 12 opaque numeric domain types already pin the fix (`Ordering.by[X, BigInt](_.value)(using
  * scala.math.Ordering.BigInt)`). This spec locks that pattern in so a future edit can't silently drop the pin, and
  * exercises the two remaining types (`ChainId`, `Timestamp`) to confirm they resolve safely too.
  */
class OpaqueOrderingResolutionSpec extends AnyWordSpec with Matchers:

  "Ordering[Nonce]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[Nonce]].compare(Nonce(1), Nonce(2)) should be < 0
    }
  }

  "Ordering[Wei]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[Wei]].compare(Wei(1), Wei(2)) should be < 0
    }
  }

  "Ordering[GasPrice]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[GasPrice]].compare(GasPrice(1), GasPrice(2)) should be < 0
    }
  }

  "Ordering[TotalDifficulty]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[TotalDifficulty]].compare(TotalDifficulty(1), TotalDifficulty(2)) should be < 0
    }
  }

  "Ordering[GasAmount]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[GasAmount]].compare(GasAmount(1), GasAmount(2)) should be < 0
    }
  }

  "Ordering[BlockNumber]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[BlockNumber]].compare(BlockNumber(1), BlockNumber(2)) should be < 0
    }
  }

  "Ordering[Difficulty]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[Difficulty]].compare(Difficulty(1), Difficulty(2)) should be < 0
    }
  }

  "Ordering[BaseFeePerGas]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[BaseFeePerGas]].compare(BaseFeePerGas(1), BaseFeePerGas(2)) should be < 0
    }
  }

  "Ordering[MaxFeePerGas]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[MaxFeePerGas]].compare(MaxFeePerGas(1), MaxFeePerGas(2)) should be < 0
    }
  }

  "Ordering[PriorityFeePerGas]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[PriorityFeePerGas]].compare(PriorityFeePerGas(1), PriorityFeePerGas(2)) should be < 0
    }
  }

  "Ordering[ChainId]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[ChainId]].compare(ChainId(1), ChainId(2)) should be < 0
    }
  }

  "Ordering[Timestamp]" should {
    "resolve and compare without recursion" taggedAs UnitTest in {
      summon[Ordering[Timestamp]].compare(Timestamp(1), Timestamp(2)) should be < 0
    }
  }
