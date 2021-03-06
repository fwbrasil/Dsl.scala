package com.thoughtworks.dsl

import java.util.concurrent.{ExecutorService, Executors}

import com.thoughtworks.dsl.Dsl.!!
import com.thoughtworks.dsl.keywords.{Each, Shift}

import scala.util.{Failure, Success, Try, continuations}
import com.thoughtworks.dsl.keywords.Shift.implicitShift
import monix.execution.{Cancelable, Scheduler}
import org.openjdk.jmh.annotations.{Fork => JmhFork, _}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent._
import scala.util.continuations._
import scala.util.control.{NoStackTrace, NonFatal}
import scala.async.Async._

object benchmarks {

  private def blockingAwaitControlContext[A](task: ControlContext[A, Unit, Unit]): A = {
    val syncVar = new SyncVar[Try[A]]
    task.foreachFull({ a =>
      syncVar.put(Success(a))
    }, { e =>
      syncVar.put(Failure(e))
    })
    syncVar.take(TimeOut.toMillis).get
  }

  private def blockingAwaitContinuations[A](task: => A @suspendable): A = {
    blockingAwaitControlContext(reify(task))
  }

  private def blockingAwaitMonix[A](task: _root_.monix.eval.Task[A]): A = {
    val syncVar = new SyncVar[Try[A]]
    task.runOnComplete(syncVar.put)(Scheduler.trampoline())
    syncVar.take(TimeOut.toMillis).get
  }

  final class IntException(val n: Int) extends Exception with NoStackTrace

  @State(Scope.Benchmark)
  abstract class BenchmarkState

  final val ThreadPool = "thread-pool"
  final val CurrentThread = "current-thread"

  final val TimeOut = 5.seconds

  abstract class SumState extends BenchmarkState {

    var size: Int

    @Param(Array(ThreadPool, CurrentThread))
    var executedIn: String = _

    protected def continuationTask: List[() => Int @suspendable] = {
      executedIn match {
        case ThreadPool =>
          List.fill(size)(() =>
            shift { (continue: Int => Unit) =>
              threadPool.execute(new Runnable {
                def run(): Unit = continue(1)
              })
          })
        case CurrentThread =>
          List.fill(size)(() => shiftUnit(1))
      }
    }

    protected def inputDslTasks: List[task.Task[Int]] = {
      import task.Task
      {
        executedIn match {
          case ThreadPool =>
            List.fill(size)(_ {
              !Task.switchExecutionContext(threadPool)
              1
            })
          case CurrentThread =>
            List.fill(size)(_(1))
        }
      }
    }

    protected def inputMonixTasks = {
      import _root_.monix.eval.Task
      val scheduler = Scheduler(threadPool: ExecutorService)
      executedIn match {
        case ThreadPool =>
          List.fill(size)(Task.fork(Task.eval(1), scheduler))
        case CurrentThread =>
          List.fill(size)(Task.now(1))
      }
    }

    protected def inputCatsIOs = {
      import _root_.cats.effect.IO
      executedIn match {
        case ThreadPool =>
          List.fill(size) {
            for {
              _ <- IO.shift(threadPool)
            } yield 1
          }
        case CurrentThread =>
          List.fill(size)(IO.pure(1))
      }
    }

    protected def inputScalazTasks = {
      import _root_.scalaz.concurrent.Task
      executedIn match {
        case ThreadPool =>
          List.fill(size)(Task(1)(threadPool))
        case CurrentThread =>
          List.fill(size)(Task.now(1))
      }
    }

    protected def inputFutures = {
      import _root_.scala.concurrent.Future
      executedIn match {
        case ThreadPool =>
          List.fill(size)(Future(1)(threadPool))
        case CurrentThread =>
          List.fill(size)(Future.successful(1))
      }
    }

    implicit protected lazy val threadPool = {
      ExecutionContext.fromExecutorService(Executors.newWorkStealingPool())
    }

    @TearDown
    def tearDown(): Unit = {
      threadPool.shutdownNow()
    }

  }

  class RawCartesianProduct extends SumState {

    @Param(Array("50"))
    var size: Int = _

    @Benchmark
    def dsl() = {
      import com.thoughtworks.dsl.task._

      def cellTask(taskX: Task[Int], taskY: Task[Int]): Task[List[Int]] = _ {
        List(!taskX, !taskY)
      }

      def listTask: Task[List[Int]] = {
        cellTask(!Each(inputDslTasks), !Each(inputDslTasks))
      }

      Task.blockingAwait(listTask, TimeOut)
    }

    @Benchmark
    def monix() = {
      import _root_.monix.eval.Task
      import _root_.monix.scalaz._
      import _root_.scalaz.std.list._
      import _root_.scalaz.syntax.all._

      def cellTask(taskX: Task[Int], taskY: Task[Int]): Task[List[Int]] =
        for {
          x <- taskX
          y <- taskY
        } yield List(x, y)

      def listTask: Task[List[Int]] = {
        inputMonixTasks.traverseM { taskX =>
          inputMonixTasks.traverseM { taskY =>
            cellTask(taskX, taskY)
          }
        }
      }

      blockingAwaitMonix(listTask)
    }

    @Benchmark
    def cats() = {
      import _root_.cats.syntax.all._
      import _root_.cats.effect.IO
      import _root_.cats.instances.list._

      def cellTask(taskX: IO[Int], taskY: IO[Int]): IO[List[Int]] =
        for {
          x <- taskX
          y <- taskY
        } yield List(x, y)

      def listTask: IO[List[Int]] = {
        inputCatsIOs.flatTraverse { taskX =>
          inputCatsIOs.flatTraverse { taskY =>
            cellTask(taskX, taskY)
          }
        }
      }

      listTask.unsafeRunTimed(TimeOut).get
    }

    @Benchmark
    def scalaz() = {
      import _root_.scalaz.syntax.all._
      import _root_.scalaz.concurrent.Task
      import _root_.scalaz.std.list._

      def cellTask(taskX: Task[Int], taskY: Task[Int]): Task[List[Int]] =
        for {
          x <- taskX
          y <- taskY
        } yield List(x, y)

      def listTask: Task[List[Int]] = {
        inputScalazTasks.traverseM { taskX =>
          inputScalazTasks.traverseM { taskY =>
            cellTask(taskX, taskY)
          }
        }
      }

      listTask.unsafePerformSyncFor(TimeOut)
    }

    @Benchmark
    def future() = {
      import _root_.scala.concurrent.Future
      import _root_.scalaz.std.list._
      import _root_.scalaz.std.scalaFuture._
      import _root_.scalaz.syntax.all._

      def cellTask(taskX: Future[Int], taskY: Future[Int]): Future[List[Int]] = async {
        List(await(taskX), await(taskY))
      }

      def listTask: Future[List[Int]] = {
        inputFutures.traverseM { taskX =>
          inputFutures.traverseM { taskY =>
            cellTask(taskX, taskY)
          }
        }
      }

      Await.result(listTask, TimeOut)

    }
  }

  class CartesianProduct extends SumState {

    @Param(Array("50"))
    var size: Int = _

    @Benchmark
    def dsl() = {
      import com.thoughtworks.dsl.task._

      def listTask: Task[List[Int]] = Task.reset {
        List(!(!Each(inputDslTasks)), !(!Each(inputDslTasks)))
      }

      Task.blockingAwait(listTask, TimeOut)
    }

    @Benchmark
    def monix() = {
      import _root_.monix.eval.Task
      import _root_.monix.scalaz._
      import _root_.scalaz.ListT
      import _root_.scalaz.syntax.all._

      def listTask: Task[List[Int]] = {
        for {
          taskX <- ListT(Task.now(inputMonixTasks))
          taskY <- ListT(Task.now(inputMonixTasks))
          x <- taskX.liftM[ListT]
          y <- taskY.liftM[ListT]
          r <- ListT(Task.now(List(x, y)))
        } yield r
      }.run

      blockingAwaitMonix(listTask)
    }

    @Benchmark
    def scalaz() = {
      import _root_.scalaz.syntax.all._
      import _root_.scalaz.concurrent.Task
      import _root_.scalaz.ListT

      def listTask: Task[List[Int]] = {
        for {
          taskX <- ListT(Task.now(inputScalazTasks))
          taskY <- ListT(Task.now(inputScalazTasks))
          x <- taskX.liftM[ListT]
          y <- taskY.liftM[ListT]
          r <- ListT(Task.now(List(x, y)))
        } yield r
      }.run

      listTask.unsafePerformSyncFor(TimeOut)
    }

    @Benchmark
    def future() = {
      import _root_.scalaz.syntax.all._
      import _root_.scalaz.ListT
      import _root_.scalaz.std.scalaFuture._

      def listTask: Future[List[Int]] = {
        for {
          taskX <- ListT(Future.successful(inputFutures))
          taskY <- ListT(Future.successful(inputFutures))
          x <- taskX.liftM[ListT]
          y <- taskY.liftM[ListT]
          r <- ListT(Future.successful(List(x, y)))
        } yield r
      }.run

      Await.result(listTask, TimeOut)

    }
  }

  class RawSum extends SumState {

    @Param(Array("1000"))
    var size: Int = _

    @Benchmark
    def scalaContinuation() = {

      @inline def loop(tasks: List[() => Int @suspendable], accumulator: Int = 0): ControlContext[Int, Unit, Unit] = {
        tasks match {
          case head :: tail =>
            reify(head()).flatMap { i: Int =>
              loop(tail, i + accumulator)
            }
          case Nil =>
            new ControlContext[Int, Unit, Unit](null, accumulator)
        }
      }

      blockingAwaitControlContext(loop(continuationTask))
    }

    @Benchmark
    def dsl() = {
      import com.thoughtworks.dsl.task._
      @inline def loop(tasks: List[Task[Int]], accumulator: Int = 0)(continue: Int => TaskDomain): TaskDomain = {
        tasks match {
          case head :: tail =>
            // Expand to: head.cpsApply(i => loop(tail, i + accumulator)(continue))
            loop(tail, !head + accumulator)(continue)
          case Nil =>
            continue(accumulator)
        }
      }

      Task.blockingAwait(loop(inputDslTasks), TimeOut)
    }

    @Benchmark
    def cats() = {
      import _root_.cats.effect.IO
      @inline def loop(tasks: List[IO[Int]], accumulator: Int = 0): IO[Int] = {
        tasks match {
          case head :: tail =>
            head.flatMap { i =>
              loop(tail, i + accumulator)
            }
          case Nil =>
            IO.pure(accumulator)
        }
      }

      loop(inputCatsIOs).unsafeRunTimed(TimeOut).get
    }

    @Benchmark
    def monix() = {
      import _root_.monix.eval.Task

      @inline def loop(tasks: List[Task[Int]], accumulator: Int = 0): Task[Int] = {
        tasks match {
          case head :: tail =>
            head.flatMap { i =>
              loop(tail, i + accumulator)
            }
          case Nil =>
            Task.now(accumulator)
        }
      }

      blockingAwaitMonix(loop(inputMonixTasks))
    }

    @Benchmark
    def scalaz() = {
      import _root_.scalaz.concurrent.Task

      @inline def loop(tasks: List[Task[Int]], accumulator: Int = 0): Task[Int] = {
        tasks match {
          case head :: tail =>
            head.flatMap { i =>
              loop(tail, i + accumulator)
            }
          case Nil =>
            Task.now(accumulator)
        }
      }

      loop(inputScalazTasks).unsafePerformSyncFor(TimeOut)
    }

    @Benchmark
    def future() = {
      import _root_.scala.concurrent.Future

      @inline def loop(tasks: List[Future[Int]], accumulator: Int = 0): Future[Int] = {
        tasks match {
          case head :: tail =>
            head.flatMap { i =>
              loop(tail, i + accumulator)
            }
          case Nil =>
            Future.successful(accumulator)
        }
      }

      Await.result(loop(inputFutures), TimeOut)
    }

  }

  class LeftAssociatedSum extends SumState {

    @Param(Array("1000"))
    var size: Int = _

    @Benchmark
    def scalaContinuation() = {

      @inline def loop(tasks: List[() => Int @suspendable]): Int @suspendable = {
        tasks match {
          case head :: tail =>
            head() + loop(tail)
          case Nil =>
            0
        }
      }

      blockingAwaitContinuations(loop(continuationTask))

    }

    @Benchmark
    def dsl() = {
      import com.thoughtworks.dsl.task._

      @inline def loop(tasks: List[Task[Int]]): Task[Int] = _ {
        tasks match {
          case head :: tail =>
            !head + !loop(tail)
          case Nil =>
            0
        }
      }
      Task.blockingAwait(loop(inputDslTasks), TimeOut)
    }

    @Benchmark
    def future() = {
      import _root_.scala.concurrent.Future

      @inline def loop(tasks: List[Future[Int]]): Future[Int] = async {
        tasks match {
          case head :: tail =>
            await(head) + await(loop(tail))
          case Nil =>
            0
        }
      }

      Await.result(loop(inputFutures), TimeOut)
    }

    @Benchmark
    def monix() = {
      import _root_.monix.eval.Task

      @inline def loop(tasks: List[Task[Int]]): Task[Int] = {
        tasks match {
          case head :: tail =>
            for {
              i <- head
              accumulator <- loop(tail)
            } yield i + accumulator
          case Nil =>
            Task(0)
        }
      }

      blockingAwaitMonix(loop(inputMonixTasks))
    }

    @Benchmark
    def scalaz() = {
      import _root_.scalaz.concurrent.Task

      @inline def loop(tasks: List[Task[Int]]): Task[Int] = {
        tasks match {
          case head :: tail =>
            for {
              i <- head
              accumulator <- loop(tail)
            } yield i + accumulator
          case Nil =>
            Task(0)
        }
      }

      loop(inputScalazTasks).unsafePerformSyncFor(TimeOut)
    }

    @Benchmark
    def cats() = {
      import _root_.cats.effect.IO

      @inline def loop(tasks: List[IO[Int]]): IO[Int] = {
        tasks match {
          case head :: tail =>
            for {
              i <- head
              accumulator <- loop(tail)
            } yield i + accumulator
          case Nil =>
            IO.pure(0)
        }
      }

      loop(inputCatsIOs).unsafeRunTimed(TimeOut).get
    }

  }

  class RightAssociatedSum extends SumState {

    @Param(Array("1000"))
    var size: Int = _

    @Benchmark
    def scalaContinuation() = {

      @inline def loop(tasks: List[() => Int @suspendable], accumulator: Int = 0): Int @suspendable = {
        tasks match {
          case head :: tail =>
            loop(tail, head() + accumulator)
          case Nil =>
            accumulator
        }
      }

      blockingAwaitContinuations(loop(continuationTask))

    }

    @Benchmark
    def dsl() = {
      import com.thoughtworks.dsl.task._

      @inline def loop(tasks: List[Task[Int]], accumulator: Int = 0): Task[Int] = _ {
        tasks match {
          case head :: tail =>
            !loop(tail, !head + accumulator)
          case Nil =>
            accumulator
        }
      }

      Task.blockingAwait(loop(inputDslTasks), TimeOut)
    }

    @Benchmark
    def cats() = {
      import _root_.cats.effect.IO

      @inline def loop(tasks: List[IO[Int]], accumulator: Int = 0): IO[Int] = {
        tasks match {
          case head :: tail =>
            for {
              i <- head
              r <- loop(tail, i + accumulator)
            } yield r
          case Nil =>
            IO.pure(accumulator)
        }
      }

      loop(inputCatsIOs).unsafeRunTimed(TimeOut).get
    }

    @Benchmark
    def monix() = {
      import _root_.monix.eval.Task

      @inline def loop(tasks: List[Task[Int]], accumulator: Int = 0): Task[Int] = {
        tasks match {
          case head :: tail =>
            for {
              i <- head
              r <- loop(tail, i + accumulator)
            } yield r
          case Nil =>
            Task.now(accumulator)
        }
      }

      blockingAwaitMonix(loop(inputMonixTasks))
    }

    @Benchmark
    def scalaz() = {
      import _root_.scalaz.concurrent.Task

      @inline def loop(tasks: List[Task[Int]], accumulator: Int = 0): Task[Int] = {
        tasks match {
          case head :: tail =>
            for {
              i <- head
              r <- loop(tail, i + accumulator)
            } yield r
          case Nil =>
            Task.now(accumulator)
        }
      }

      loop(inputScalazTasks).unsafePerformSyncFor(TimeOut)
    }

    @Benchmark
    def future() = {
      import _root_.scala.concurrent.Future

      @inline def loop(tasks: List[Future[Int]], accumulator: Int = 0): Future[Int] = async {
        tasks match {
          case head :: tail =>
            await(loop(tail, await(head) + accumulator))
          case Nil =>
            accumulator
        }
      }

      Await.result(loop(inputFutures), TimeOut)
    }

  }

}
