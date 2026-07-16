package com.chipprbots.fukuii.evm

/** The interpreter seam — the abstract entry point that executes a top-level program (transaction) and returns its
  * result. [[ProgramState]] carries a reference to it so re-entrant opcodes (`CALL`/`CREATE`, P2) can invoke a
  * sub-execution.
  *
  * **P1/P4 boundary.** This is a minimal seam (the referenced-seam-trait precedent the world-state [[WorldState]] and
  * [[AccountStorage]] follow): P1 defines the type so [[ProgramState.vm]] resolves; **P4 builds the concrete `@tailrec`
  * interpreter loop** as the implementation. P4 should home its interpreter as a subtype of this seam (or, if it wants
  * the `VM` name for the concrete loop, this seam is renamed at that point) — flagged as a P1→P4 handoff.
  */
import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.UInt256

trait VM[W <: WorldState[W, S], S <: AccountStorage[S]]:
  def run(context: ProgramContext[W, S]): ProgramResult[W, S]

  /** Re-entrant sub-call seam — the `CALL`/`CALLCODE`/`DELEGATECALL`/`STATICCALL` opcode bodies drive a sub-execution
    * through this. `ownerAddr` is the account whose *code* runs (the callee for `CALL`/`STATICCALL`; the caller's own
    * address for `CALLCODE`/`DELEGATECALL`). **Abstract here (P2); the concrete `@tailrec` interpreter fills it in P4**
    * — the opcode objects are built now, the loop that satisfies this seam is P4.
    */
  def call(context: ProgramContext[W, S], ownerAddr: Address): ProgramResult[W, S]

  /** Re-entrant contract-creation seam — the `CREATE`/`CREATE2` opcode bodies drive the init-code sub-execution through
    * this, returning the result **and** the derived new-contract address (`create2Address` when `salt` is present, else
    * `createAddress`). Abstract here (P2); filled by P4's interpreter.
    */
  def create(context: ProgramContext[W, S], salt: Option[UInt256] = None): (ProgramResult[W, S], Address)
