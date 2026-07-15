package com.chipprbots.fukuii.crypto.zksnark

import com.chipprbots.fukuii.crypto.zksnark.BN128.Point
import org.scalatest.funsuite.AnyFunSuite

/** BN128 curve arithmetic over `Fp` — the group law behind EIP-196 `ECADD`/`ECMUL`. Points from old fukuii's
  * `BN128FpSpec` (each a valid on-curve `Fp` point).
  */
class BN128FpSpec extends AnyFunSuite:

  private val points: Seq[Point[Fp]] = Seq(
    Point(Fp(1), Fp(2), Fp(1)),
    Point(
      Fp(BigInt("1368015179489954701390400359078579693043519447331113978918064868415326638035")),
      Fp(BigInt("9918110051302171585080402603319702774565515993150576347155970296011118125764")),
      Fp(1)
    ),
    Point(
      Fp(BigInt("11169198337205317385038692134282557493133418158128574038999810944352461077961")),
      Fp(BigInt("2820885980102468247213289930888494165190958823101043243711917453290081841766")),
      Fp(1)
    ),
    Point(
      Fp(BigInt("3510227910005969626168871163796842095937160976810256674232777209574668193517")),
      Fp(BigInt("2885800476071299445182650755020278501280179672256593791439003311512581969879")),
      Fp(1)
    ),
    Point(
      Fp(BigInt("15497584038690294240042153688304417339506091937513459124271972833238779664131")),
      Fp(BigInt("21762456842531143558012592863461237297422391564814111359902381816272400009493")),
      Fp(1)
    )
  )

  test("P + P == 2 * P"):
    points.foreach { p =>
      assert(BN128Fp.isOnCurve(p) && p.isValid)
      assert(BN128Fp.add(p, p) == BN128Fp.mul(p, 2))
    }

  test("P + P + P == 3 * P, and the sum stays on-curve"):
    points.foreach { p =>
      val added = BN128Fp.add(BN128Fp.add(p, p), p)
      val multiplied = BN128Fp.mul(p, 3)
      assert(added == multiplied)
      assert(BN128Fp.isOnCurve(added) && BN128Fp.isOnCurve(multiplied))
      assert(BN128Fp.isOnCurve(BN128Fp.toEthNotation(added)))
    }

  test("createPoint rejects an off-curve point"):
    // (1, 3) is not on Y² = X³ + 3.
    import org.apache.pekko.util.ByteString
    import com.chipprbots.fukuii.bytes.ByteUtils
    def bs(n: Int): ByteString = ByteString(ByteUtils.bigIntToBytes(BigInt(n), 32))
    assert(BN128Fp.createPoint(bs(1), bs(3)).isEmpty)
    assert(BN128Fp.createPoint(bs(1), bs(2)).isDefined)
