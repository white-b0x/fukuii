package com.chipprbots.fukuii.execution

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.fukuii.bytes.Address
import com.chipprbots.fukuii.bytes.Hash
import com.chipprbots.fukuii.bytes.Hex
import com.chipprbots.fukuii.bytes.UInt256
import com.chipprbots.fukuii.crypto.sha256
import com.chipprbots.fukuii.domain.Account
import com.chipprbots.fukuii.domain.Block
import com.chipprbots.fukuii.domain.BlockBody
import com.chipprbots.fukuii.domain.BlockHeader
import com.chipprbots.fukuii.domain.Bloom
import com.chipprbots.fukuii.domain.ChainId
import com.chipprbots.fukuii.domain.Log
import com.chipprbots.fukuii.evm.EvmConfig
import com.chipprbots.fukuii.evm.EvmInterpreter
import com.chipprbots.fukuii.storage.EphemDataSource
import com.chipprbots.fukuii.trie.InMemoryMptStorage
import com.chipprbots.fukuii.trie.MptNode

/** L4 P5b — the EIP-7685 request phase: the [[RequestsHash]] SHA256 fold, the EIP-6110 [[DepositRequestProcessor]]
  * log-scrape, the EIP-7002/7251 [[SystemCallRequestProcessor]] queue calls, the [[RequestProcessors]] coordinator
  * ordering + `noOp` degradation, and the [[BlockProcessor]] wiring (fork gating + fail-loud `requestsHash` mismatch).
  * All ETH/PoS consensus (beacon's lane), byte-cited to go-ethereum `state_processor.go`/`block.go` + besu `requests`
  * package / `BodyValidation`; ETC binds `noOp`.
  *
  * Queue contracts are stubbed with a minimal return contract (`PUSH1 v; PUSH1 0; MSTORE; PUSH1 0x20; PUSH1 0;
  * RETURN`), which proves the framework wiring — the type-byte prefix, the world threading, the hash fold. The
  * canonical request-contract byte-vectors are a genesis-allocation + eye BlockchainTests concern (deferred to P7).
  */
class RequestProcessorSpec extends AnyFunSuite:

  private val chainId: ChainId = ChainId(1)
  private val interpreter = new EvmInterpreter[InMemoryWorldState, InMemoryAccountStorage]()
  private val systemCall = new SystemCallProcessor(interpreter)

  private def addr(b: Int): Address = Address(ByteString(Array.fill[Byte](Address.Length)(b.toByte)))

  /** `PUSH1 v; PUSH1 0; MSTORE; PUSH1 0x20; PUSH1 0; RETURN` — returns the 32-byte word `0x00…00v`. */
  private def returnByteCode(v: Int): ByteString =
    ByteString(Hex.decode(f"0x60${v}%02x60005260206000f3"))

  /** The 32-byte word a [[returnByteCode]] contract returns: 31 zero bytes then `v`. */
  private def returnWord(v: Int): ByteString =
    ByteString(Array.fill[Byte](31)(0) ++ Array(v.toByte))

  private def contractAccount: Account = Account.empty().copy(nonce = UInt256.One)

  private def world(entries: (Address, Account, ByteString)*): InMemoryWorldState =
    val base = InMemoryWorldState(
      codeStorage = new CodeStorage(EphemDataSource()),
      mptStorage = new InMemoryMptStorage,
      getBlockHashByNumber = _ => None,
      accountStartNonce = UInt256.Zero,
      stateRootHash = MptNode.EmptyRootHash,
      noEmptyAccounts = true
    )
    entries.foldLeft(base) { case (w, (a, acc, code)) => w.saveAccount(a, acc).saveCode(a, code) }

  private def header(number: BigInt = 1): BlockHeader =
    BlockHeader(
      parentHash = Hash.Zero,
      ommersHash = Hash.Zero,
      beneficiary = addr(0x33),
      stateRoot = Hash.Zero,
      transactionsRoot = Hash.Zero,
      receiptsRoot = Hash.Zero,
      logsBloom = Bloom.Empty,
      difficulty = 0,
      number = number,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = 0,
      extraData = ByteString.empty,
      mixHash = Hash.Zero,
      nonce = ByteString.empty
    )

  private def context(w: InMemoryWorldState, logs: List[Log] = Nil): RequestContext =
    RequestContext(header(), EvmConfig.EthPrague, w, chainId, logs)

  // -- RequestsHash fold (EIP-7685) — besu BodyValidation:82, go-ethereum CalcRequestsHash block.go:480 ---------------

  test("RequestsHash.Empty == sha256(\"\") — the known EmptyRequestsHash (go-ethereum hashes.go:44)"):
    // e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
    val known = Hex.decode("0xe3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    assert(RequestsHash.Empty == ByteString(known))

  test("RequestsHash — the empty request set folds to EmptyRequestsHash"):
    assert(RequestsHash.compute(Nil) == RequestsHash.Empty)

  test("RequestsHash — a single request folds to sha256(sha256(typeByte ‖ data))"):
    val req = Request(RequestType.Withdrawal, returnWord(0xab))
    val expected = sha256(sha256(req.encoded))
    assert(RequestsHash.compute(List(req)) == expected)

  test("RequestsHash — sha256(sha256(r0)‖sha256(r1)) over two requests, in order"):
    val r0 = Request(RequestType.Withdrawal, returnWord(0xab))
    val r1 = Request(RequestType.Consolidation, returnWord(0xcd))
    val expected = sha256(sha256(r0.encoded) ++ sha256(r1.encoded))
    assert(RequestsHash.compute(List(r0, r1)) == expected)

  test("RequestsHash — empty requests are EXCLUDED from the fold (besu !data.isEmpty; geth len(item) > 1)"):
    val nonEmpty = Request(RequestType.Withdrawal, returnWord(0xab))
    val empty = Request(RequestType.Deposit, ByteString.empty)
    // [empty] folds to the empty-set hash; interleaving an empty request does not change the hash.
    assert(
      RequestsHash.compute(List(empty)) == RequestsHash.Empty &&
        RequestsHash.compute(List(empty, nonEmpty)) == RequestsHash.compute(List(nonEmpty))
    )

  test("Request.encoded prefixes the type byte (besu getEncodedRequest = type ‖ data)"):
    assert(
      Request(RequestType.Deposit, returnWord(0x01)).encoded == (ByteString(0x00.toByte) ++ returnWord(0x01)) &&
        Request(RequestType.Withdrawal, ByteString.empty).encoded == ByteString(0x01.toByte) &&
        Request(RequestType.Consolidation, ByteString.empty).encoded == ByteString(0x02.toByte)
    )

  // -- EIP-6110 deposit-scrape (DepositRequestProcessor / DepositLogDecoder) -----------------------------------------

  private val depositContract: Address = addr(0x42)

  /** A canonical 576-byte deposit-log ABI payload with distinctive per-field bytes, plus the expected 192-byte flat
    * request (`pubkey48 ‖ cred32 ‖ amount8 ‖ signature96 ‖ index8`). Field values sit at `32 + fieldOffset` (both
    * clients slice past the leading ABI length word).
    */
  private def depositLog(): (ByteString, ByteString) =
    val data = Array.fill[Byte](576)(0)
    def put(start: Int, len: Int, b: Int): Unit = (start until start + len).foreach(i => data(i) = b.toByte)
    put(192, 48, 0x11) // pubkey        (32 + 160)
    put(288, 32, 0x22) // withdrawalCred (32 + 256)
    put(352, 8, 0x33) // amount          (32 + 320)
    put(416, 96, 0x44) // signature      (32 + 384)
    put(544, 8, 0x55) // index           (32 + 512)
    val flat = ByteString(
      Array.fill[Byte](48)(0x11) ++ Array.fill[Byte](32)(0x22) ++ Array.fill[Byte](8)(0x33) ++
        Array.fill[Byte](96)(0x44) ++ Array.fill[Byte](8)(0x55)
    )
    (ByteString(data), flat)

  private def depositLogEvent(data: ByteString): Log =
    Log(depositContract, List(DepositRequestProcessor.DepositEventTopic), data)

  test("EIP-6110 — a matching deposit log yields the 0x00-prefixed 192-byte flat request"):
    val (logData, flat) = depositLog()
    val processor = new DepositRequestProcessor(depositContract)
    val outcome = processor.process(context(world(), List(depositLogEvent(logData)))).toOption.get
    assert(outcome.request.requestType == RequestType.Deposit && outcome.request.data == flat)

  test("EIP-6110 — a non-matching log (wrong address / wrong topic) is ignored → empty deposit request"):
    val (logData, _) = depositLog()
    val processor = new DepositRequestProcessor(depositContract)
    val wrongAddress = Log(addr(0x99), List(DepositRequestProcessor.DepositEventTopic), logData)
    val wrongTopic = Log(depositContract, List(Hash.Zero), logData)
    val outcome = processor.process(context(world(), List(wrongAddress, wrongTopic))).toOption.get
    assert(outcome.request.data.isEmpty)

  test("EIP-6110 — two matching deposit logs concatenate into one request (384 bytes)"):
    val (logData, flat) = depositLog()
    val processor = new DepositRequestProcessor(depositContract)
    val outcome = processor
      .process(context(world(), List(depositLogEvent(logData), depositLogEvent(logData))))
      .toOption
      .get
    assert(outcome.request.data == (flat ++ flat) && outcome.request.data.length == 384)

  test("EIP-6110 — a malformed deposit log (not 576 bytes) fails LOUD (InvalidDepositLog)"):
    val processor = new DepositRequestProcessor(depositContract)
    val badLog = depositLogEvent(ByteString(Array.fill[Byte](100)(0)))
    processor.process(context(world(), List(badLog))) match
      case Left(RequestError.InvalidDepositLog(_)) => succeed
      case other                                   => fail(s"expected InvalidDepositLog, got $other")

  // -- EIP-7002 / EIP-7251 system-call requests (SystemCallRequestProcessor) -----------------------------------------

  test("EIP-7002 — a withdrawal-queue system call yields the 0x01-prefixed contract return"):
    val w = world((SystemCallRequestProcessor.WithdrawalQueueAddress, contractAccount, returnByteCode(0xab)))
    val processor =
      new SystemCallRequestProcessor(
        systemCall,
        SystemCallRequestProcessor.WithdrawalQueueAddress,
        RequestType.Withdrawal
      )
    val outcome = processor.process(context(w)).toOption.get
    assert(outcome.request == Request(RequestType.Withdrawal, returnWord(0xab)))

  test("EIP-7251 — a consolidation-queue system call yields the 0x02-prefixed contract return"):
    val w = world((SystemCallRequestProcessor.ConsolidationQueueAddress, contractAccount, returnByteCode(0xcd)))
    val processor = new SystemCallRequestProcessor(
      systemCall,
      SystemCallRequestProcessor.ConsolidationQueueAddress,
      RequestType.Consolidation
    )
    val outcome = processor.process(context(w)).toOption.get
    assert(outcome.request == Request(RequestType.Consolidation, returnWord(0xcd)))

  test("EIP-7002 — a codeless queue target fails LOUD (RequestError.SystemCall wrapping NoCodeAtAddress)"):
    val processor =
      new SystemCallRequestProcessor(
        systemCall,
        SystemCallRequestProcessor.WithdrawalQueueAddress,
        RequestType.Withdrawal
      )
    processor.process(context(world())) match
      case Left(RequestError.SystemCall(RequestType.Withdrawal, SystemCallError.NoCodeAtAddress(_))) => succeed
      case other => fail(s"expected SystemCall/NoCodeAtAddress, got $other")

  // -- coordinator: ordering + noOp degradation (besu RequestProcessorCoordinator) -----------------------------------

  private def pragueWorld(): InMemoryWorldState =
    world(
      (SystemCallRequestProcessor.WithdrawalQueueAddress, contractAccount, returnByteCode(0xab)),
      (SystemCallRequestProcessor.ConsolidationQueueAddress, contractAccount, returnByteCode(0xcd))
    )

  test("coordinator — runs Deposit(0x00) → Withdrawal(0x01) → Consolidation(0x02) in RequestType order"):
    val (logData, flat) = depositLog()
    val coordinator = RequestProcessors.prague(systemCall, depositContract)
    val (_, requests) = coordinator.process(context(pragueWorld(), List(depositLogEvent(logData)))).toOption.get
    assert(
      requests.map(_.requestType) == List(RequestType.Deposit, RequestType.Withdrawal, RequestType.Consolidation) &&
        requests(0).data == flat &&
        requests(1).data == returnWord(0xab) &&
        requests(2).data == returnWord(0xcd)
    )

  test("coordinator — noOp yields no requests and leaves the world untouched (PoW / pre-Prague)"):
    val w = pragueWorld()
    val (after, requests) = RequestProcessors.noOp.process(context(w)).toOption.get
    assert(requests.isEmpty && after == w)

  // -- BlockProcessor wiring: fork gating + fail-loud requestsHash mismatch -------------------------------------------

  private val processor = new BlockProcessor(new TransactionProcessor(interpreter))

  private def pragueSpec: ProtocolSpec =
    ProtocolSpec(
      EvmConfig.EthPrague,
      PreExecutionProcessor.NoPreExecution,
      RewardScheme.PosNoRewardScheme,
      RequestProcessors.prague(systemCall, depositContract),
      Some(WithdrawalsProcessor.Eip4895WithdrawalsProcessor),
      FeeDisposition.Burn
    )

  private def powSpec: ProtocolSpec =
    ProtocolSpec(
      EvmConfig.EtcOlympia,
      PreExecutionProcessor.NoPreExecution,
      RewardScheme.Ecip1017RewardScheme(),
      RequestProcessors.noOp,
      None,
      FeeDisposition.Absent
    )

  private def emptyBlock: Block = Block(header(1), BlockBody(Nil, Nil, Some(Nil)))

  test("BlockProcessor — Prague+ ETH computes requestsHash = the fold over the queue-call requests"):
    val executed = processor.execute(pragueSpec, emptyBlock, pragueWorld(), chainId).toOption.get
    // no deposit logs ⇒ deposit request empty (excluded); the fold is over the two non-empty queue requests.
    val expected = RequestsHash.compute(
      List(Request(RequestType.Withdrawal, returnWord(0xab)), Request(RequestType.Consolidation, returnWord(0xcd)))
    )
    assert(executed.requestsHash.contains(expected))

  test("BlockProcessor — ETC / noOp path yields no requestsHash (None)"):
    val w = world()
    val executed = processor.execute(powSpec, Block(header(1), BlockBody(Nil, Nil, None)), w, chainId).toOption.get
    assert(executed.requestsHash.isEmpty)

  test("BlockProcessor — a self-consistent Prague header verifies GREEN (requestsHash matches)"):
    val w = pragueWorld()
    val executed = processor.execute(pragueSpec, emptyBlock, w, chainId).toOption.get
    val committed = emptyBlock.copy(header =
      emptyBlock.header.copy(
        gasUsed = executed.gasUsed.toLong,
        receiptsRoot = Hash(executed.receiptsRoot),
        stateRoot = Hash(executed.stateRoot),
        logsBloom = executed.logsBloom,
        requestsHash = executed.requestsHash.map(Hash(_))
      )
    )
    assert(processor.processBlock(pragueSpec, committed, w, chainId).isRight)

  test("BlockProcessor — a wrong requestsHash fails LOUD (RequestsHashMismatch), before the state-root check"):
    val w = pragueWorld()
    val executed = processor.execute(pragueSpec, emptyBlock, w, chainId).toOption.get
    val committed = emptyBlock.copy(header =
      emptyBlock.header.copy(
        gasUsed = executed.gasUsed.toLong,
        receiptsRoot = Hash(executed.receiptsRoot),
        stateRoot = Hash(executed.stateRoot),
        logsBloom = executed.logsBloom,
        requestsHash = Some(Hash.Zero) // tampered
      )
    )
    processor.processBlock(pragueSpec, committed, w, chainId) match
      case Left(_: BlockExecutionError.RequestsHashMismatch) => succeed
      case other                                             => fail(s"expected RequestsHashMismatch, got $other")

  test("BlockProcessor — a Prague header MISSING requestsHash fails LOUD (mismatch vs computed)"):
    val w = pragueWorld()
    val executed = processor.execute(pragueSpec, emptyBlock, w, chainId).toOption.get
    val committed = emptyBlock.copy(header =
      emptyBlock.header.copy(
        gasUsed = executed.gasUsed.toLong,
        receiptsRoot = Hash(executed.receiptsRoot),
        stateRoot = Hash(executed.stateRoot),
        logsBloom = executed.logsBloom,
        requestsHash = None // Prague header must carry it
      )
    )
    processor.processBlock(pragueSpec, committed, w, chainId) match
      case Left(BlockExecutionError.RequestsHashMismatch(_, None)) => succeed
      case other => fail(s"expected RequestsHashMismatch(_, None), got $other")
