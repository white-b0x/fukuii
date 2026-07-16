package com.chipprbots.fukuii.evm

/** The per-fork opcode bundles and the dense `IArray[OpCode]` dispatch table builder.
  *
  * **Naming (nomenclature.md, mirroring the [[GasCalculator]] spine).** The **shared** opcode-set bases are EIP-keyed /
  * neutral and carry **no network fork codename** — the ETH and ETC families share these opcode deltas byte-for-byte,
  * the divergence being only the activation height. Network fork names appear **only** on the family-local leaves
  * ([[EthCancunOpCodes]]/[[EthOsakaOpCodes]] on the ETH side, [[EtcOlympiaOpCodes]] on the ETC side). A leaf never
  * references the other family's leaf, and no shared base carries a fork codename — the `scala3-style.md` `Etc*`/`Eth*`
  * ratchet by construction. (`Frontier`/`Homestead` are neutral historical protocol-era names shared by both chains,
  * exactly as the gas spine uses them.)
  *
  * **Byte facts (forge co-signs the ETC/Olympia set):**
  *   - `BLOBHASH 0x49` / `BLOBBASEFEE 0x4a` are **ETH-only** (EIP-4844/7516) — present in [[EthCancunOpCodes]], absent
  *     from [[EtcOlympiaOpCodes]]; the dense-table pre-fill leaves those ETC slots [[InvalidOp]].
  *   - **Osaka = Prague + CLZ** (EIP-7939); Prague adds no EVM opcode over Cancun.
  *   - EIP-6780 (SELFDESTRUCT same-tx) is **not a new opcode** — it is a semantic gated by `EvmConfig.eip6780Enabled`;
  *     it does not appear in any bundle.
  */
object OpCodes:

  val LogOpCodes: List[OpCode] = List(LOG0, LOG1, LOG2, LOG3, LOG4)

  val SwapOpCodes: List[OpCode] = List(
    SWAP1,
    SWAP2,
    SWAP3,
    SWAP4,
    SWAP5,
    SWAP6,
    SWAP7,
    SWAP8,
    SWAP9,
    SWAP10,
    SWAP11,
    SWAP12,
    SWAP13,
    SWAP14,
    SWAP15,
    SWAP16
  )

  val DupOpCodes: List[OpCode] =
    List(DUP1, DUP2, DUP3, DUP4, DUP5, DUP6, DUP7, DUP8, DUP9, DUP10, DUP11, DUP12, DUP13, DUP14, DUP15, DUP16)

  val PushOpCodes: List[OpCode] = List(
    PUSH1,
    PUSH2,
    PUSH3,
    PUSH4,
    PUSH5,
    PUSH6,
    PUSH7,
    PUSH8,
    PUSH9,
    PUSH10,
    PUSH11,
    PUSH12,
    PUSH13,
    PUSH14,
    PUSH15,
    PUSH16,
    PUSH17,
    PUSH18,
    PUSH19,
    PUSH20,
    PUSH21,
    PUSH22,
    PUSH23,
    PUSH24,
    PUSH25,
    PUSH26,
    PUSH27,
    PUSH28,
    PUSH29,
    PUSH30,
    PUSH31,
    PUSH32
  )

  /** The genesis opcode set — network-neutral. */
  val FrontierOpCodes: List[OpCode] =
    LogOpCodes ++ SwapOpCodes ++ PushOpCodes ++ DupOpCodes ++ List(
      STOP,
      ADD,
      MUL,
      SUB,
      DIV,
      SDIV,
      MOD,
      SMOD,
      ADDMOD,
      MULMOD,
      EXP,
      SIGNEXTEND,
      LT,
      GT,
      SLT,
      SGT,
      EQ,
      ISZERO,
      AND,
      OR,
      XOR,
      NOT,
      BYTE,
      SHA3,
      ADDRESS,
      BALANCE,
      ORIGIN,
      CALLER,
      CALLVALUE,
      CALLDATALOAD,
      CALLDATASIZE,
      CALLDATACOPY,
      CODESIZE,
      CODECOPY,
      GASPRICE,
      EXTCODESIZE,
      EXTCODECOPY,
      BLOCKHASH,
      COINBASE,
      TIMESTAMP,
      NUMBER,
      DIFFICULTY,
      GASLIMIT,
      POP,
      MLOAD,
      MSTORE,
      MSTORE8,
      SLOAD,
      SSTORE,
      JUMP,
      JUMPI,
      PC,
      MSIZE,
      GAS,
      JUMPDEST,
      CREATE,
      CALL,
      CALLCODE,
      RETURN,
      INVALID,
      SELFDESTRUCT
    )

  /** + DELEGATECALL (EIP-7). Neutral historical name (shared by both chains). */
  val HomesteadOpCodes: List[OpCode] =
    DELEGATECALL +: FrontierOpCodes

  /** + REVERT (EIP-140), STATICCALL (EIP-214), RETURNDATASIZE/RETURNDATACOPY (EIP-211) — the shared
    * "Byzantium/Atlantis" opcode delta, EIP-keyed so no ETH fork codename leaks into a shared base.
    */
  val Eip140OpCodes: List[OpCode] =
    List(REVERT, STATICCALL, RETURNDATACOPY, RETURNDATASIZE) ++ HomesteadOpCodes

  /** + EXTCODEHASH (EIP-1052), CREATE2 (EIP-1014), SHL/SHR/SAR (EIP-145) — the shared "Constantinople/Agharta" delta.
    */
  val Eip145OpCodes: List[OpCode] =
    List(EXTCODEHASH, CREATE2, SHL, SHR, SAR) ++ Eip140OpCodes

  /** + CHAINID (EIP-1344), SELFBALANCE (EIP-1884) — the shared "Istanbul/Phoenix" delta (EIP-named, not "Phoenix"). */
  val Eip1344OpCodes: List[OpCode] =
    List(CHAINID, SELFBALANCE) ++ Eip145OpCodes

  /** + PUSH0 (EIP-3855) — the shared "Shanghai/Spiral" delta. No BASEFEE here: ETC gets BASEFEE only at Olympia, ETH at
    * London, so BASEFEE lives on the family leaves, never this shared base.
    */
  val Eip3855OpCodes: List[OpCode] =
    PUSH0 +: Eip1344OpCodes

  // -- ETH family leaves --------------------------------------------------------------------------------------------

  /** ETH London — Istanbul base + BASEFEE (EIP-3198, added with EIP-1559). No PUSH0 yet (Shanghai). **ETH-only.** */
  val EthLondonOpCodes: List[OpCode] =
    BASEFEE +: Eip1344OpCodes

  /** ETH Shanghai — London + PUSH0 (EIP-3855). **ETH-only.** */
  val EthShanghaiOpCodes: List[OpCode] =
    PUSH0 +: EthLondonOpCodes

  /** ETH Cancun — Shanghai base + BASEFEE + BLOBHASH (EIP-4844) + BLOBBASEFEE (EIP-7516) + TLOAD/TSTORE (EIP-1153) +
    * MCOPY (EIP-5656). **ETH-only** — the blob opcodes are what the ETC set must exclude. **ETH-only.**
    */
  val EthCancunOpCodes: List[OpCode] =
    List(BASEFEE, BLOBHASH, BLOBBASEFEE, TLOAD, TSTORE, MCOPY) ++ Eip3855OpCodes

  /** ETH Prague — no new EVM opcode over Cancun (EIP-7702 is a tx type, not an opcode). **ETH-only.** */
  val EthPragueOpCodes: List[OpCode] =
    EthCancunOpCodes

  /** ETH Osaka — Prague + CLZ (EIP-7939). **ETH-only.** */
  val EthOsakaOpCodes: List[OpCode] =
    CLZ :: EthCancunOpCodes

  // -- ETC family leaf ----------------------------------------------------------------------------------------------

  /** ETC Olympia (ECIP-1121) — Spiral base + BASEFEE (EIP-3198) + TLOAD/TSTORE (EIP-1153) + MCOPY (EIP-5656) + CLZ
    * (EIP-7939). **Excludes** BLOBHASH/BLOBBASEFEE (no EIP-4844/7516 on ETC — the dense-table pre-fill leaves 0x49/0x4a
    * as [[InvalidOp]]). **ETC-only.**
    */
  val EtcOlympiaOpCodes: List[OpCode] =
    CLZ :: (List(BASEFEE, TLOAD, TSTORE, MCOPY) ++ Eip3855OpCodes)

  // -- dense dispatch table -----------------------------------------------------------------------------------------

  /** All-256-slot [[InvalidOp]] table — the pre-fill baseline (besu `MainnetEVMs.java:205-207`) and the default a
    * membership-only [[EvmConfig]] carries until P3's fold supplies a real per-fork table. Executing any slot
    * loud-fails with `InvalidOpCode(byte)`.
    */
  val InvalidTable: IArray[OpCode] =
    IArray.tabulate(256)(i => InvalidOp(i.toByte))

  /** Build the dense `IArray[OpCode]` for a bundle: **pre-fill all 256 slots** with the [[InvalidOp]] sentinel (besu
    * `MainnetEVMs.java:205-207` — no null slot, no per-step `Option`), overwrite the defined ones by `op.code`, then
    * [[validate]]. Branch-free O(1) dispatch, immutable and R2-shareable (RX-L3-02/13).
    */
  def denseTable(ops: List[OpCode]): IArray[OpCode] =
    val byCode: Map[Int, OpCode] = ops.iterator.map(op => (op.code & 0xff) -> op).toMap
    validate(IArray.tabulate(256)(i => byCode.getOrElse(i, InvalidOp(i.toByte))))

  /** Build-time table validation (go-ethereum `jump_table.go:76` validates at construction; besu instead pre-fills, so
    * fukuii adopts **both** — the pre-fill above plus this pass). Asserts: (1) every slot populated (guaranteed by the
    * pre-fill — a belt-and-suspenders no-unset-op check, geth's); (2) each slot's opcode `code` matches its index (a
    * **fukuii-added** guard against a misplaced opcode); (3) `delta`/`alpha` present and non-negative (a **fukuii
    * addition**, not what geth's `validate` inspects). An undefined slot dispatches to [[InvalidOp]] and fails loud.
    */
  def validate(table: IArray[OpCode]): IArray[OpCode] =
    require(table.length == 256, s"opcode table must have 256 slots, has ${table.length}")
    var i = 0
    while i < 256 do
      val op = table(i)
      require((op.code & 0xff) == i, f"opcode at slot 0x$i%02x has mismatched code 0x${op.code & 0xff}%02x")
      require(op.delta >= 0 && op.alpha >= 0, f"opcode at slot 0x$i%02x has negative delta/alpha")
      i += 1
    table
