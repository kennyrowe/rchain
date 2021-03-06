package coop.rchain.rholang

import java.io.StringReader

import coop.rchain.catscontrib.mtl.implicits._
import coop.rchain.metrics
import coop.rchain.metrics.Metrics
import coop.rchain.rholang.interpreter.storage.StoragePrinter
import coop.rchain.rholang.interpreter.{EvaluateResult, Interpreter, Runtime}
import coop.rchain.rholang.interpreter.accounting._
import monix.execution.Scheduler.Implicits.global
import coop.rchain.rholang.Resources.mkRuntime
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}
import coop.rchain.shared.Log

import scala.concurrent.duration._
import scala.util.Try

class InterpreterSpec extends FlatSpec with Matchers {
  private val mapSize     = 10L * 1024L * 1024L
  private val tmpPrefix   = "rspace-store-"
  private val maxDuration = 5.seconds

  implicit val logF: Log[Task]            = new Log.NOPLog[Task]
  implicit val noopMetrics: Metrics[Task] = new metrics.Metrics.MetricsNOP[Task]

  behavior of "Interpreter"

  it should "restore RSpace to its prior state after evaluation error" in {
    import coop.rchain.catscontrib.effect.implicits.bracketTry

    val sendRho = "@{0}!(0)"

    val (initStorage, beforeError, afterError, afterSend, finalContent) =
      mkRuntime[Task, Task.Par](tmpPrefix, mapSize)
        .use { runtime =>
          for {
            initStorage  <- storageContents(runtime)
            _            <- success(runtime, sendRho)
            beforeError  <- storageContents(runtime)
            _            <- failure(runtime, "@1!(1) | @2!(3.noSuchMethod())")
            afterError   <- storageContents(runtime)
            _            <- success(runtime, "new stdout(`rho:io:stdout`) in { stdout!(42) }")
            afterSend    <- storageContents(runtime)
            _            <- success(runtime, "for (_ <- @0) { Nil }")
            finalContent <- storageContents(runtime)
          } yield (initStorage, beforeError, afterError, afterSend, finalContent)
        }
        .runSyncUnsafe(maxDuration)

    assert(beforeError.contains(sendRho))
    assert(afterError == beforeError)
    assert(afterSend == beforeError)
    assert(finalContent == initStorage)
  }

  it should "yield correct results for the PrimeCheck contract" in {
    import coop.rchain.catscontrib.effect.implicits.bracketTry
    val contents = mkRuntime[Task, Task.Par](tmpPrefix, mapSize)
      .use { runtime =>
        for {
          _ <- success(
                runtime,
                """
              |new loop, primeCheck, stdoutAck(`rho:io:stdoutAck`) in {
              |            contract loop(@x) = {
              |              match x {
              |                [] => Nil
              |                [head ...tail] => {
              |                  new ret in {
              |                    for (_ <- ret) {
              |                      loop!(tail)
              |                    } | primeCheck!(head, *ret)
              |                  }
              |                }
              |              }
              |            } |
              |            contract primeCheck(@x, ret) = {
              |              match x {
              |                Nil => { stdoutAck!("Nil", *ret) | @0!("Nil") }
              |                ~{~Nil | ~Nil} => { stdoutAck!("Prime", *ret) | @0!("Pr") }
              |                _ => { stdoutAck!("Composite", *ret) |  @0!("Co") }
              |              }
              |            } |
              |            loop!([Nil, 7, 7 | 8, 9 | Nil, 9 | 10, Nil, 9])
              |  }
            """.stripMargin
              )

          contents <- storageContents(runtime)
        } yield contents
      }
      .runSyncUnsafe(maxDuration)

    // TODO: this is not the way we should be testing execution results,
    // yet strangely it works - and we don't have a better way for now
    assert(
      contents.startsWith(
        Seq(
          """@{0}!("Nil") |""",
          """@{0}!("Pr") |""",
          """@{0}!("Co") |""",
          """@{0}!("Pr") |""",
          """@{0}!("Co") |""",
          """@{0}!("Nil") |""",
          """@{0}!("Pr") |"""
        ).mkString("\n")
      )
    )
  }

  it should "signal syntax errors to the caller" in {
    val badRholang = "new f, x in { f(x) }"
    val EvaluateResult(_, errors) =
      mkRuntime[Task, Task.Par](tmpPrefix, mapSize)
        .use { runtime =>
          for {
            res <- execute(runtime, badRholang)
          } yield (res)
        }
        .runSyncUnsafe(maxDuration)

    errors should not be empty
    errors(0) shouldBe a[coop.rchain.rholang.interpreter.errors.SyntaxError]
  }

  it should "capture rholang parsing errors and charge for parsing" in {
    val badRholang = """ for(@x <- @"x"; @y <- @"y"){ @"xy"!(x + y) | @"x"!(1) | @"y"!("hi") """
    val EvaluateResult(cost, errors) =
      mkRuntime[Task, Task.Par](tmpPrefix, mapSize)
        .use { runtime =>
          for {
            res <- execute(runtime, badRholang)
          } yield (res)
        }
        .runSyncUnsafe(maxDuration)

    errors should not be empty
    cost.value shouldEqual (parsingCost(badRholang).value)
  }

  it should "charge for parsing even when there's not enough phlo to complete it" in {
    val sendRho = "@{0}!(0)"
    val EvaluateResult(cost, errors) =
      mkRuntime[Task, Task.Par](tmpPrefix, mapSize)
        .use { runtime =>
          implicit val c = runtime.cost
          for {
            res <- Interpreter[Task]
                    .evaluate(runtime, sendRho, parsingCost(sendRho) - Cost(1))
          } yield (res)
        }
        .runSyncUnsafe(maxDuration)

    errors should not be empty
    cost.value shouldEqual (parsingCost(sendRho).value)
  }

  private def storageContents(runtime: Runtime[Task]): Task[String] =
    StoragePrinter.prettyPrint(runtime.space)

  private def success(runtime: Runtime[Task], rho: String): Task[Unit] =
    execute(runtime, rho).map(
      res =>
        assert(
          res.errors.isEmpty,
          s"""Execution failed for: $rho
              |Cause:
              |${res.errors}""".stripMargin
        )
    )

  private def failure(runtime: Runtime[Task], rho: String): Task[Unit] =
    execute(runtime, rho).map(
      res => assert(res.errors.nonEmpty, s"Expected $rho to fail - it didn't.")
    )

  private def execute(
      runtime: Runtime[Task],
      source: String
  ): Task[EvaluateResult] = {
    implicit val c = runtime.cost
    Interpreter[Task].evaluate(runtime, source)
  }

}
