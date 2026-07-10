package com.chipprbots.ethereum.vm

/** Shared byte-identity helper for the EVM fee schedule (Row 5.3b). Two `FeeSchedule` instances are field-identical iff
  * their `fields` sequences are equal — the representation-independent equivalent of the old
  * `isInstanceOf[XxxFeeSchedule]` assertions. Used by `ForBlockFoldIdentitySpec` and the regression specs after
  * `EvmConfig.forBlock` was switched to the fold (which yields a field-identical `EvmProposals.FeeScheduleValues`, not
  * the hand-written subclass).
  */
object FeeScheduleFields:

  /** The 41 declared `FeeSchedule` fields in declaration order. */
  def fields(fs: FeeSchedule): Seq[BigInt] = Seq(
    fs.G_zero,
    fs.G_base,
    fs.G_verylow,
    fs.G_low,
    fs.G_mid,
    fs.G_high,
    fs.G_balance,
    fs.G_sload,
    fs.G_jumpdest,
    fs.G_sset,
    fs.G_sreset,
    fs.R_sclear,
    fs.R_selfdestruct,
    fs.G_selfdestruct,
    fs.G_create,
    fs.G_codedeposit,
    fs.G_call,
    fs.G_callvalue,
    fs.G_callstipend,
    fs.G_newaccount,
    fs.G_exp,
    fs.G_expbyte,
    fs.G_memory,
    fs.G_txcreate,
    fs.G_txdatazero,
    fs.G_txdatanonzero,
    fs.G_transaction,
    fs.G_log,
    fs.G_logdata,
    fs.G_logtopic,
    fs.G_sha3,
    fs.G_sha3word,
    fs.G_copy,
    fs.G_blockhash,
    fs.G_extcode,
    fs.G_cold_sload,
    fs.G_cold_account_access,
    fs.G_warm_storage_read,
    fs.G_access_list_address,
    fs.G_access_list_storage,
    fs.G_initcode_word
  )
