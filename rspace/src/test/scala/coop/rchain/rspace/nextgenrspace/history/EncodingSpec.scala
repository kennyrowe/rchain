package coop.rchain.rspace.nextgenrspace.history

import coop.rchain.rspace.Blake2b256Hash
import coop.rchain.rspace.examples.StringExamples.{Pattern, StringsCaptor}
import coop.rchain.rspace.internal.Datum
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}
import coop.rchain.rspace.util.stringCodec
import scodec.Codec
import coop.rchain.rspace.examples.StringExamples._
import coop.rchain.rspace.examples.StringExamples.implicits._
import coop.rchain.rspace.internal._
import coop.rchain.rspace.test.ArbitraryInstances._

class EncodingSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks {

  type Continuation = WaitingContinuation[Pattern, StringsCaptor]
  type Join         = Seq[String]

  implicit val codecChannel: Codec[String] = stringCodec

  implicit val codecDatumString: Codec[Datum[String]] = codecDatum(stringCodec)

  implicit val codecContinuation: Codec[WaitingContinuation[Pattern, StringsCaptor]] =
    codecWaitingContinuation(
      implicits.patternSerialize.toCodec,
      implicits.stringClosureSerialize.toCodec
    )

  "Datum list encode" should "return same hash for different orderings of each datum" in forAll {
    (datum1: Datum[String], datum2: Datum[String]) =>
      val bytes1 = HistoryRepositoryImpl.encodeData(datum1 :: datum2 :: Nil)
      val bytes2 = HistoryRepositoryImpl.encodeData(datum2 :: datum1 :: Nil)
      Blake2b256Hash.create(bytes1) shouldBe Blake2b256Hash.create(bytes2)
  }

  "WaitingContinuation list encode" should "return same hash for different orderings of each continuation" in forAll {
    (c1: Continuation, c2: Continuation, c3: Continuation) =>
      val bytes1 = HistoryRepositoryImpl.encodeContinuations(c1 :: c2 :: c3 :: Nil)
      val bytes2 = HistoryRepositoryImpl.encodeContinuations(c3 :: c2 :: c1 :: Nil)
      val bytes3 = HistoryRepositoryImpl.encodeContinuations(c2 :: c3 :: c1 :: Nil)
      Blake2b256Hash.create(bytes1) shouldBe Blake2b256Hash.create(bytes2)
      Blake2b256Hash.create(bytes1) shouldBe Blake2b256Hash.create(bytes3)
  }

  "Joins list encode" should "preserve channel orderings" in forAll { (j1: Join, j2: Join) =>
    whenever(j1 != j2) {
      val joins         = j1 :: j2 :: Nil
      val bytes         = HistoryRepositoryImpl.encodeJoins(joins)
      val reversedBytes = HistoryRepositoryImpl.encodeJoins(joins.reverse)

      HistoryRepositoryImpl.decodeJoins(bytes)(codecChannel) shouldBe (joins)
      HistoryRepositoryImpl.decodeJoins(reversedBytes)(codecChannel) shouldBe (joins.reverse)
    }
  }
}
