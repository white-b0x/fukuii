package com.chipprbots.ethereum.forks

/** The layer a proposal lives in — drives the validation gate and ownership (Batch 5 framework §1.6).
  *
  * The single decider is the state-root litmus: **does the change alter the state root?**
  *   - `Consensus` — state-affecting. A divergent client produces a different state root and forks the chain. forge
  *     (PoW) / beacon (PoS) own it; byte-identity + ETC state-root compliance is the gate.
  *   - `ClientPolicy` — NOT state-affecting. Changes only what a client *admits to its pool* or *chooses to produce*.
  *     banksy owns it; pool-admission / block-production behaviour is the gate; operator-tunable.
  *
  * Row 5.2 registers EVM opcode/fee deltas only — every one of which is state-affecting — so every proposal in
  * `EvmProposals` is tagged `Consensus`. `ClientPolicy` exists in the model for later rows (e.g. ECIP-1122's
  * MIN_MINER_TIP / gas-target schedule), not this one.
  */
enum ProposalLayer:
  case Consensus
  case ClientPolicy
