package coop.rchain.casper.engine

import cats.data.EitherT
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.{Applicative, Monad}
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.{BlockDagStorage, BlockStore}
import coop.rchain.casper.Estimator.Validator
import coop.rchain.casper.LastApprovedBlock.LastApprovedBlock
import coop.rchain.casper.MultiParentCasperRef.MultiParentCasperRef
import coop.rchain.casper._
import coop.rchain.casper.util.comm.CommUtil
import coop.rchain.casper.genesis.Genesis
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.catscontrib.Catscontrib._
import coop.rchain.catscontrib.MonadTrans
import coop.rchain.comm.discovery.NodeDiscovery
import coop.rchain.comm.protocol.routing.Packet
import coop.rchain.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import coop.rchain.comm.transport.{Blob, TransportLayer}
import coop.rchain.comm.{transport, PeerNode}
import coop.rchain.metrics.Metrics
import coop.rchain.shared.{Log, LogSource, Time}
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** Node in this state has already received at least one [[ApprovedBlock]] and it has created an instance
  * of [[MultiParentCasper]].
  *
  * In the future it will be possible to create checkpoint with new [[ApprovedBlock]].
    **/
class Running[F[_]: RPConfAsk: BlockStore: Monad: ConnectionsCell: TransportLayer: Log: Time](
    private val casper: MultiParentCasper[F],
    approvedBlock: ApprovedBlock
) extends Engine[F] {
  import Engine._

  implicit val _casper            = casper
  def applicative: Applicative[F] = Applicative[F]

  private def handleDoppelganger(
      peer: PeerNode,
      b: BlockMessage,
      self: Validator
  ): F[Unit] =
    if (b.sender == self) {
      Log[F].warn(
        s"There is another node $peer proposing using the same private key as you. Or did you restart your node?"
      )
    } else ().pure[F]

  override def handle(peer: PeerNode, msg: CasperMessage): F[Unit] = msg match {
    case b: BlockMessage              => handleBlockMessage(peer, b)
    case br: BlockRequest             => handleBlockRequest(peer, br)
    case fctr: ForkChoiceTipRequest   => handleForkChoiceTipRequest(peer, fctr)
    case abr: ApprovedBlockRequest    => handleApprovedBlockRequest(peer, abr)
    case na: NoApprovedBlockAvailable => logNoApprovedBlockAvailable[F](na.nodeIdentifer)
    case _                            => noop
  }

  private def handleBlockMessage(peer: PeerNode, b: BlockMessage): F[Unit] =
    for {
      isOldBlock <- MultiParentCasper[F].contains(b)
      _ <- if (isOldBlock)
            Log[F].info(s"Received block ${PrettyPrinter.buildString(b.blockHash)} again.")
          else
            handleNewBlock(peer, b)

    } yield ()

  private def handleNewBlock(
      peer: PeerNode,
      b: BlockMessage
  ): F[Unit] =
    for {
      _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(b)}.")
      _ <- MultiParentCasper[F].addBlock(b, handleDoppelganger(peer, _, _))
    } yield ()

  private def handleBlockRequest(peer: PeerNode, br: BlockRequest): F[Unit] =
    for {
      local      <- RPConfAsk[F].reader(_.local)
      block      <- BlockStore[F].get(br.hash) // TODO: Refactor
      serialized = block.map(_.toByteString)
      maybeMsg = serialized.map(
        serializedMessage => Blob(local, Packet(transport.BlockMessage.id, serializedMessage))
      )
      _        <- maybeMsg.traverse(msg => TransportLayer[F].stream(peer, msg))
      hash     = PrettyPrinter.buildString(br.hash)
      logIntro = s"Received request for block $hash from $peer."
      _ <- block match {
            case None    => Log[F].info(logIntro + "No response given since block not found.")
            case Some(_) => Log[F].info(logIntro + "Response sent.")
          }
    } yield ()

  private def handleForkChoiceTipRequest(
      peer: PeerNode,
      fctr: ForkChoiceTipRequest
  ): F[Unit] =
    for {
      _     <- Log[F].info(s"Received ForkChoiceTipRequest from $peer")
      tip   <- MultiParentCasper.forkChoiceTip
      local <- RPConfAsk[F].reader(_.local)
      msg   = Blob(local, Packet(transport.BlockMessage.id, tip.toByteString))
      _     <- TransportLayer[F].stream(peer, msg)
      _     <- Log[F].info(s"Sending Block ${tip.blockHash} to $peer")
    } yield ()

  private def handleApprovedBlockRequest(
      peer: PeerNode,
      br: ApprovedBlockRequest
  ): F[Unit] =
    for {
      local <- RPConfAsk[F].reader(_.local)
      _     <- Log[F].info(s"Received ApprovedBlockRequest from $peer")
      msg   = Blob(local, Packet(transport.ApprovedBlock.id, approvedBlock.toByteString))
      _     <- TransportLayer[F].stream(peer, msg)
      _     <- Log[F].info(s"Sending ApprovedBlock to $peer")
    } yield ()

}
