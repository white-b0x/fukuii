package com.chipprbots.ethereum.ethtest

import com.chipprbots.ethereum.testing.Tags.*

/** Test suite originally written to flag a Berlin-fork gas-calculation discrepancy.
  *
  * HISTORY & CURRENT STATUS (verified 2026-07-11, REPO-06-GASCALC close-out):
  *   - The gas discrepancy this spec was written to catch (add11 short by 2100 gas, addNonConst short by 900 gas, both
  *     EIP-2929-cold-access-shaped) is RESOLVED. fukuii's Berlin/Magneto gas schedule is byte-correct against
  *     core-geth: G_cold_sload=2100, G_cold_account_access=2600, G_warm_storage_read=100, access-list 2400/1900 —
  *     confirmed line-by-line by the Batch 5 Row 5.7 deep-map (etc-mordor-conformance.md §5) and a direct read of
  *     `EvmConfig.scala` MagnetoFeeSchedule. There is NO gas bug.
  *   - The `add11`/`addNonConst` cases nonetheless still FAIL, but for a HARNESS reason unrelated to gas:
  *     `ValidationBeforeExecError(HeaderPoWError)` — the fixture-import path validates the PoW seal on these blocks and
  *     rejects them BEFORE gas is ever computed. That is failure mode (a) of ETHTEST-EXEC-REGRESSIONS-01, which is why
  *     the whole spec carries `BrokenEthTest` and lives in its own `report_only` shard. Remove those flags only when
  *     ETHTEST-EXEC-REGRESSIONS-01 disables fixture PoW-seal validation, NOT before — the gas premise being resolved
  *     does not make these green.
  */
class GasCalculationIssuesSpec extends EthereumTestsSpec:

  /** Execute test and capture detailed gas calculation error information
    *
    * @param test
    *   Test to execute
    * @return
    *   Either error message with gas details, or success
    */
  def executeAndAnalyzeGasError(test: BlockchainTest): Either[String, TestExecutionResult] =
    executeTest(test) match
      case Left(error) if error.contains("invalid gas used") =>
        // Extract expected vs actual gas from error message
        val expectedGasRegex = """expected (\d+)""".r
        val actualGasRegex = """but got (\d+)""".r

        val expectedGas = expectedGasRegex.findFirstMatchIn(error).map(_.group(1).toLong)
        val actualGas = actualGasRegex.findFirstMatchIn(error).map(_.group(1).toLong)

        val diff = (expectedGas, actualGas) match
          case (Some(exp), Some(act)) => s" (difference: ${exp - act})"
          case _                      => ""

        Left(s"Gas calculation error$diff: $error")

      case result => result

  "GasCalculationIssues" should "flag add11 test gas calculation discrepancy" taggedAs (
    IntegrationTest,
    EthereumTest,
    BrokenEthTest, // whole spec premised on known-failing gas exec; masked by HeaderPoWError — ETHTEST-EXEC-REGRESSIONS-01
    SlowTest
  ) in {
    info("Testing add11 (basic ADD opcode) - should use identical gas")
    val suite = loadTestSuite("/ethereum-tests/add11.json")

    suite.tests.foreach { case (testName, test) =>
      info(s"Test: $testName, Network: ${test.network}")

      val result = executeAndAnalyzeGasError(test)
      result match
        case Left(error) =>
          info(s"  ✗ FLAGGED: $error")
          info(s"  ACTION REQUIRED: Review gas calculation for ADD opcode in ${test.network}")
          fail(s"Gas calculation mismatch detected - requires code review before proceeding")

        case Right(executionResult) =>
          info(s"  ✓ Test passed unexpectedly - gas calculation may have been fixed")
          info(s"  Gas used: ${executionResult.blocksExecuted} blocks executed successfully")
    }
  }

  // BrokenEthTest: gas exec masked by HeaderPoWError — ETHTEST-EXEC-REGRESSIONS-01
  it should "flag addNonConst test gas calculation discrepancy" taggedAs (
    IntegrationTest,
    EthereumTest,
    BrokenEthTest,
    SlowTest
  ) in {
    info("Testing addNonConst (ADD with non-constant values) - should use identical gas")
    val suite = loadTestSuite("/ethereum-tests/addNonConst.json")

    suite.tests.foreach { case (testName, test) =>
      info(s"Test: $testName, Network: ${test.network}")

      val result = executeAndAnalyzeGasError(test)
      result match
        case Left(error) =>
          info(s"  ✗ FLAGGED: $error")
          info(s"  ACTION REQUIRED: Review gas calculation for PUSH and ADD opcodes in ${test.network}")
          fail(s"Gas calculation mismatch detected - requires code review before proceeding")

        case Right(_) =>
          info(s"  ✓ Test passed unexpectedly - gas calculation may have been fixed")
    }
  }

  it should "provide detailed analysis of gas calculation patterns" taggedAs (
    IntegrationTest,
    EthereumTest,
    BrokenEthTest, // whole spec premised on known-failing gas exec; masked by HeaderPoWError — ETHTEST-EXEC-REGRESSIONS-01
    SlowTest
  ) in {
    info("Analyzing gas calculation patterns across multiple tests...")

    val testFiles = Seq(
      ("/ethereum-tests/add11.json", "Basic ADD opcode"),
      ("/ethereum-tests/addNonConst.json", "ADD with non-constant values")
    )

    var totalGasDiscrepancies = 0
    val discrepancyDetails = scala.collection.mutable.ListBuffer[String]()

    testFiles.foreach { case (testPath, description) =>
      info(s"Analyzing: $description")
      val suite = loadTestSuite(testPath)

      suite.tests.foreach { case (testName, test) =>
        executeAndAnalyzeGasError(test) match
          case Left(error) if error.contains("difference:") =>
            totalGasDiscrepancies += 1
            discrepancyDetails += s"  - $testName: $error"

          case _ => // Test passed or different error
      }
    }

    if totalGasDiscrepancies > 0 then
      info(s"Found $totalGasDiscrepancies gas calculation discrepancies:")
      discrepancyDetails.foreach(d => info(d))

      info("")
      info("INVESTIGATION REQUIRED:")
      info("1. Review EIP implementations for Berlin and Istanbul networks")
      info("2. Check opcode gas costs in VM implementation")
      info("3. Verify gas refund calculations")
      info("4. Compare against geth/nethermind gas calculations")
      info("")

      fail(s"$totalGasDiscrepancies gas calculation discrepancies detected - code review required")
    else info("✓ No gas calculation discrepancies detected")
  }

  it should "document known gas calculation issues for follow-up" taggedAs (
    IntegrationTest,
    EthereumTest,
    BrokenEthTest, // whole spec premised on known-failing gas exec; masked by HeaderPoWError — ETHTEST-EXEC-REGRESSIONS-01
    SlowTest
  ) in {
    info("Documenting gas calculation status (REPO-06-GASCALC, verified 2026-07-11)...")
    info("")
    info("RESOLVED — no live gas discrepancy:")
    info("1. add11 (Berlin): expected 43112. Historic shortfall of 2100 gas (missing EIP-2929 cold-access")
    info("   surcharge) is FIXED — G_cold_sload=2100 now applied. Matches core-geth.")
    info("2. addNonConst (Berlin): expected 23412. Historic 900-gas shortfall is FIXED. Matches core-geth.")
    info("")
    info("REMAINING BLOCKER (harness, not gas): add11/addNonConst fail on ValidationBeforeExecError(HeaderPoWError)")
    info("before gas is ever computed. Tracked as ETHTEST-EXEC-REGRESSIONS-01 mode (a); keeps this spec flagged")
    info("BrokenEthTest + report_only until the fixture-import path disables PoW-seal validation.")
    info("")

    // This test documents the issues but doesn't fail - it's for information
    succeed
  }
