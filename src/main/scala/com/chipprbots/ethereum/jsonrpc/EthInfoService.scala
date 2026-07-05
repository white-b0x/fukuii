package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.syntax.either.*

import scala.annotation.unused

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.PrecompiledContracts

object EthInfoService:
  case class ChainIdRequest()
  case class ChainIdResponse(value: ChainId)

  case class ConfigRequest()
  case class ForkConfig(
      activationBlock: BigInt,
      chainId: ChainId,
      precompiles: Map[String, Address],
      systemContracts: Map[String, Address]
  )
  case class ConfigResponse(
      current: Option[ForkConfig],
      next: Option[ForkConfig],
      last: Option[ForkConfig]
  )

  case class ProtocolVersionRequest()
  case class ProtocolVersionResponse(value: String)

  case class SyncingRequest()
  case class SyncingStatus(
      startingBlock: BigInt,
      currentBlock: BigInt,
      highestBlock: BigInt,
      knownStates: BigInt,
      pulledStates: BigInt
  )
  case class SyncingResponse(syncStatus: Option[SyncingStatus])

  case class CallTx(
      from: Option[ByteString],
      to: Option[ByteString],
      gas: Option[BigInt],
      gasPrice: GasPrice,
      value: Wei,
      data: ByteString,
      gasPriceExplicit: Boolean = false
  )

  case class CallRequest(tx: CallTx, block: BlockParam)
  case class CallResponse(returnData: ByteString)
  case class EstimateGasResponse(gas: GasAmount)
  case class CreateAccessListRequest(tx: CallTx, block: BlockParam):
    def toCallRequest: CallRequest = CallRequest(tx, block)
  case class CreateAccessListResponse(
      accessList: Seq[Map[String, Any]],
      gasUsed: GasAmount,
      error: Option[String]
  )

class EthInfoService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    blockchainConfig: BlockchainConfig,
    val mining: Mining,
    stxLedger: StxLedger,
    keyStore: KeyStore,
    syncingController: TypedActorRef[SyncController.Command],
    capability: Capability,
    askTimeout: Timeout,
    scheduler: Scheduler
) extends ResolveBlock
    with Logger:

  import EthInfoService.*

  private given typedScheduler: Scheduler = scheduler

  def protocolVersion(@unused req: ProtocolVersionRequest): ServiceResponse[ProtocolVersionResponse] =
    IO.pure(Right(ProtocolVersionResponse(f"0x${capability.version}%x")))

  def chainId(@unused req: ChainIdRequest): ServiceResponse[ChainIdResponse] =
    IO.pure(Right(ChainIdResponse(blockchainConfig.chainId)))

  /** Implements the eth_syncing method that returns syncing information if the node is syncing.
    *
    * @return
    *   The syncing status if the node is syncing or None if not
    */
  def syncing(@unused req: SyncingRequest): ServiceResponse[SyncingResponse] =
    syncingController
      .askForTyped[SyncProtocol.Status](replyTo => SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(replyTo)))(
        timeout = askTimeout,
        scheduler = typedScheduler
      )
      .map {
        case Status.Syncing(startingBlockNumber, blocksProgress, maybeStateNodesProgress) =>
          val stateNodesProgress = maybeStateNodesProgress.getOrElse(Progress.empty)
          SyncingResponse(
            Some(
              SyncingStatus(
                startingBlock = startingBlockNumber,
                currentBlock = blocksProgress.current,
                highestBlock = blocksProgress.target,
                knownStates = stateNodesProgress.target,
                pulledStates = stateNodesProgress.current
              )
            )
          )
        case Status.NotSyncing => SyncingResponse(None)
        case Status.SyncDone   => SyncingResponse(None)
      }
      .map(_.asRight)

  def config(@unused req: ConfigRequest): ServiceResponse[ConfigResponse] = IO {
    val fbn = blockchainConfig.forkBlockNumbers
    val forks = forkSchedule(fbn)
    val currentBlock = blockchainReader.getBestBlockNumber
    Right(selectForkConfigs(forks, currentBlock, blockchainConfig.chainId))
  }

  private val basePrecompiles: Map[String, Address] = Map(
    "ecrecover" -> PrecompiledContracts.EcDsaRecAddr,
    "sha256" -> PrecompiledContracts.Sha256Addr,
    "ripemd160" -> PrecompiledContracts.Rip160Addr,
    "identity" -> PrecompiledContracts.IdAddr
  )
  private val byzantiumPrecompiles: Map[String, Address] = basePrecompiles ++ Map(
    "modexp" -> PrecompiledContracts.ModExpAddr,
    "bn256Add" -> PrecompiledContracts.Bn128AddAddr,
    "bn256ScalarMul" -> PrecompiledContracts.Bn128MulAddr,
    "bn256Pairing" -> PrecompiledContracts.Bn128PairingAddr
  )
  private val istanbulPrecompiles: Map[String, Address] = byzantiumPrecompiles ++ Map(
    "blake2f" -> PrecompiledContracts.Blake2bCompressionAddr
  )
  private val olympiaPrecompiles: Map[String, Address] = istanbulPrecompiles ++ Map(
    "bls12381G1Add" -> PrecompiledContracts.BlsG1AddAddr,
    "bls12381G1MultiExp" -> PrecompiledContracts.BlsG1MultiExpAddr,
    "bls12381G2Add" -> PrecompiledContracts.BlsG2AddAddr,
    "bls12381G2MultiExp" -> PrecompiledContracts.BlsG2MultiExpAddr,
    "bls12381Pairing" -> PrecompiledContracts.BlsPairingAddr,
    "bls12381MapG1" -> PrecompiledContracts.BlsMapG1Addr,
    "bls12381MapG2" -> PrecompiledContracts.BlsMapG2Addr,
    "p256Verify" -> PrecompiledContracts.P256VerifyAddr
  )

  private val noSystemContracts: Map[String, Address] = Map.empty
  private val olympiaSystemContracts: Map[String, Address] = Map(
    "historyStorage" -> HistoryStorageAddress
  )

  /** Build fork schedule: (name, blockNumber, precompiles, systemContracts). JSON-RPC response boundary
    * (ForkConfig.block: BigInt) — unwrap here.
    */
  private def forkSchedule(fbn: ForkBlockNumbers): List[(String, BigInt, Map[String, Address], Map[String, Address])] =
    List(
      ("Frontier", fbn.frontierBlockNumber.value, basePrecompiles, noSystemContracts),
      ("Homestead", fbn.homesteadBlockNumber.value, basePrecompiles, noSystemContracts),
      ("Atlantis", fbn.atlantisBlockNumber.value, byzantiumPrecompiles, noSystemContracts),
      ("Agharta", fbn.aghartaBlockNumber.value, byzantiumPrecompiles, noSystemContracts),
      ("Phoenix", fbn.phoenixBlockNumber.value, istanbulPrecompiles, noSystemContracts),
      ("Magneto", fbn.magnetoBlockNumber.value, istanbulPrecompiles, noSystemContracts),
      ("Mystique", fbn.mystiqueBlockNumber.value, istanbulPrecompiles, noSystemContracts),
      ("Spiral", fbn.spiralBlockNumber.value, istanbulPrecompiles, noSystemContracts),
      ("Olympia", fbn.olympiaBlockNumber.value, olympiaPrecompiles, olympiaSystemContracts)
    ).filter(_._2 < Long.MaxValue) // exclude forks not configured
      .sortBy(_._2)
      .distinctBy(_._2) // deduplicate by block number

  /** Pick current/next/last [[ForkConfig]] out of `forks` relative to `currentBlock`. `next`/`last` are only reported
    * when a future fork actually exists — matches the previous inline behavior.
    */
  private def selectForkConfigs(
      forks: List[(String, BigInt, Map[String, Address], Map[String, Address])],
      currentBlock: BigInt,
      chainId: ChainId
  ): ConfigResponse =
    def toForkConfig(f: (String, BigInt, Map[String, Address], Map[String, Address])): ForkConfig =
      ForkConfig(f._2, chainId, f._3, f._4)

    // Find current fork (last fork at or before currentBlock)
    val activeForks = forks.filter(_._2 <= currentBlock)
    val futureForks = forks.filter(_._2 > currentBlock)

    val current = activeForks.lastOption.map(toForkConfig)
    val next = futureForks.headOption.map(toForkConfig)
    val last = forks.lastOption.map(toForkConfig)

    ConfigResponse(current, next, if futureForks.nonEmpty then last else None)

  def call(req: CallRequest): ServiceResponse[CallResponse] =
    IO {
      doCall(req)(stxLedger.simulateTransaction).flatMap { r =>
        r.vmError match
          case Some(com.chipprbots.ethereum.vm.RevertOccurs) =>
            val dataHex = "0x" + org.bouncycastle.util.encoders.Hex.toHexString(r.vmReturnData.toArray[Byte])
            Left(JsonRpcError(3, "execution reverted", Some(org.json4s.JString(dataHex))))
          case Some(_) =>
            // Other VM errors (out of gas, etc) — return empty result
            Right(CallResponse(r.vmReturnData))
          case None =>
            Right(CallResponse(r.vmReturnData))
      }
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  def estimateGas(req: CallRequest): ServiceResponse[EstimateGasResponse] =
    IO {
      // First check if the tx reverts at the max gas limit
      val simCheck = doCall(req)(stxLedger.simulateTransaction)
      simCheck.flatMap { r =>
        r.vmError match
          case Some(com.chipprbots.ethereum.vm.RevertOccurs) =>
            val dataHex = "0x" + org.bouncycastle.util.encoders.Hex.toHexString(r.vmReturnData.toArray[Byte])
            Left(JsonRpcError(3, "execution reverted", Some(org.json4s.JString(dataHex))))
          case _ =>
            // Tx doesn't revert — find minimum gas via binary search
            doCall(req)(stxLedger.binarySearchGasEstimation).map(gas => EstimateGasResponse(GasAmount(gas)))
      }
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  def createAccessList(req: CreateAccessListRequest): ServiceResponse[CreateAccessListResponse] =
    IO {
      doCall(req.toCallRequest)(stxLedger.simulateTransaction).map { result =>
        val gasUsed = result.gasUsed
        val error = result.vmError.map(_.toString)
        // eth_createAccessList is only partially implemented: the VM doesn't currently track
        // accessed addresses/storage keys during simulation, so `accessList` is always empty.
        // gasUsed still reflects the real simulated cost. Log so callers relying on the real
        // access list (e.g. gas-optimization tooling) don't silently get a wrong empty answer.
        log.debug(
          "eth_createAccessList called but access-list tracking is not implemented; returning empty accessList (gasUsed={})",
          gasUsed
        )
        CreateAccessListResponse(
          accessList = Seq.empty,
          gasUsed = gasUsed,
          error = error
        )
      }
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  private def doCall[A](req: CallRequest)(
      f: (SignedTransactionWithSender, BlockHeader, Option[InMemoryWorldStateProxy]) => A
  ): Either[JsonRpcError, A] = for
    stx <- prepareTransaction(req)
    block <- resolveBlock(req.block)
  yield
    // EIP-1559: When no gas price is explicitly specified, use baseFee=0 so calls
    // don't need to worry about funding. Matches geth behavior for eth_call/eth_estimateGas.
    val header = if !req.tx.gasPriceExplicit && block.block.header.baseFee.isDefined then
      import BlockHeader.HeaderExtraFields.*
      val zeroBaseFeeExtra = block.block.header.extraFields match
        case HefPostOlympia(_)                    => HefPostOlympia(BaseFeePerGas.Zero)
        case HefPostShanghai(_, wr)               => HefPostShanghai(BaseFeePerGas.Zero, wr)
        case HefPostCancun(_, wr, bg, eb, pb)     => HefPostCancun(BaseFeePerGas.Zero, wr, bg, eb, pb)
        case HefPostPrague(_, wr, bg, eb, pb, rh) => HefPostPrague(BaseFeePerGas.Zero, wr, bg, eb, pb, rh)
        case other                                => other
      block.block.header.copy(extraFields = zeroBaseFeeExtra)
    else block.block.header
    f(stx, header, block.pendingState)

  private def getGasLimit(req: CallRequest): Either[JsonRpcError, BigInt] =
    req.tx.gas.map(Right.apply).getOrElse(resolveBlock(BlockParam.Latest).map(r => r.block.header.gasLimit.value))

  private def prepareTransaction(req: CallRequest): Either[JsonRpcError, SignedTransactionWithSender] =
    getGasLimit(req).map { gasLimit =>
      val fromAddress = req.tx.from
        .map(Address.apply) // `from` param, if specified
        .getOrElse(
          keyStore.listAccounts
            .getOrElse(Nil)
            .headOption // first account, if exists and `from` param not specified
            .getOrElse(Address(0))
        ) // 0x0 default

      val toAddress = req.tx.to.map(Address.apply)

      val tx =
        LegacyTransaction(
          Nonce.Zero,
          req.tx.gasPrice,
          GasAmount(gasLimit),
          toAddress,
          req.tx.value,
          req.tx.data
        )
      val fakeSignature = ECDSASignature(0, 0, 0)
      SignedTransactionWithSender(tx, fakeSignature, fromAddress)
    }
