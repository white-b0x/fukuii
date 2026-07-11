package com.chipprbots.ethereum.consensus

import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockValidator
import com.chipprbots.ethereum.consensus.validators.SignedTransactionValidator

/** Implements validators that adhere to the PoW-specific [[com.chipprbots.ethereum.consensus.ValidatorsExecutor]]
  * interface.
  */
final class StdValidatorsExecutor private[consensus] (
    val blockValidator: BlockValidator,
    val blockHeaderValidator: BlockHeaderValidator,
    val signedTransactionValidator: SignedTransactionValidator,
    val ommersValidator: OmmersValidator
) extends ValidatorsExecutor
