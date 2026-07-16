package com.chipprbots.fukuii.evm

import org.scalatest.funsuite.AnyFunSuite

/** Pins the intent-named getters (`eip2929Enabled`, `eip6780Enabled`, …) onto the *resolved fold result*, not just the
  * structural set membership (forge's P3 hardening). The interpreter reads these neutral EIP-keyed getters, never a
  * fork name, so they must read `true` on the folded [[EvmConfig]] at the forks that activate them and `false` before —
  * proving the getter genuinely reflects the fold and is not vacuously constant.
  *
  * ETC Olympia (forge) and the ETH Cancun/Osaka equivalents (beacon) both covered.
  */
class FoldResultIntentSpec extends AnyFunSuite:

  import EvmProposals.*

  test("ETC Olympia fold enables eip6780 and eip2929"):
    val cfg = EvmConfig.deriveEvmConfigAt(etcOlympiaSet)
    assert(cfg.eip6780Enabled && cfg.eip2929Enabled)

  test("ETH Cancun fold enables eip6780 and eip2929"):
    val cfg = EvmConfig.deriveEvmConfigAt(ethCancunSet)
    assert(cfg.eip6780Enabled && cfg.eip2929Enabled)

  test("ETH Osaka fold enables eip6780 and eip2929"):
    val cfg = EvmConfig.deriveEvmConfigAt(ethOsakaSet)
    assert(cfg.eip6780Enabled && cfg.eip2929Enabled)

  test("pre-EIP-6780 fold (Berlin) does not enable eip6780, though eip2929 is already active"):
    val cfg = EvmConfig.deriveEvmConfigAt(berlinSet)
    assert(!cfg.eip6780Enabled && cfg.eip2929Enabled)

  test("pre-EIP-2929 fold (Istanbul) enables neither eip2929 nor eip6780"):
    val cfg = EvmConfig.deriveEvmConfigAt(istanbulSet)
    assert(!cfg.eip2929Enabled && !cfg.eip6780Enabled)
