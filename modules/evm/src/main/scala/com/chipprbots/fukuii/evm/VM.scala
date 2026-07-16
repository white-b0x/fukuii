package com.chipprbots.fukuii.evm

/** The interpreter seam — the abstract entry point that executes a top-level program (transaction) and returns its
  * result. [[ProgramState]] carries a reference to it so re-entrant opcodes (`CALL`/`CREATE`, P2) can invoke a
  * sub-execution.
  *
  * **P1/P4 boundary.** This is a minimal seam (the referenced-seam-trait precedent the world-state [[WorldStateProxy]]
  * and [[Storage]] follow): P1 defines the type so [[ProgramState.vm]] resolves; **P4 builds the concrete `@tailrec`
  * interpreter loop** as the implementation. P4 should home its interpreter as a subtype of this seam (or, if it wants
  * the `VM` name for the concrete loop, this seam is renamed at that point) — flagged as a P1→P4 handoff.
  */
trait VM[W <: WorldStateProxy[W, S], S <: Storage[S]]:
  def run(context: ProgramContext[W, S]): ProgramResult[W, S]
