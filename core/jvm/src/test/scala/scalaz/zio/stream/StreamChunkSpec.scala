package scalaz.zio.stream

import org.specs2.ScalaCheck

import scala.{ Stream => _ }
import scala.concurrent.duration._
import scalaz.zio.{ AbstractRTSSpec, ExitResult, GenIO, IO }

import scala.annotation.tailrec

class StreamChunkSpec(implicit ee: org.specs2.concurrent.ExecutionEnv)
    extends AbstractRTSSpec
    with GenIO
    with ScalaCheck {

  override val DefaultTimeout = 20.seconds

  def is = "StreamChunkSpec".title ^ s2"""
  StreamChunk.map           $map
  StreamChunk.filter        $filter
  StreamChunk.filterNot     $filterNot
  StreamChunk.mapConcat     $mapConcat
  StreamChunk.drop          $drop
  StreamChunk.dropWhile     $dropWhile
  StreamChunk.take          $take
  StreamChunk.takeWhile     $takeWhile
  StreamChunk.mapAccum      $mapAccum
  StreamChunk.mapM          $mapM
  StreamChunk.++            $concat
  StreamChunk.zipWithIndex  $zipWithIndex
  StreamChunk.foreach0      $foreach0
  StreamChunk.foreach       $foreach
  StreamChunk.monadLaw1     $monadLaw1
  StreamChunk.monadLaw2     $monadLaw2
  StreamChunk.monadLaw3     $monadLaw3
  StreamChunk.withEffect    $withEffect
  StreamChunk.foldLeft      $foldLeft
  StreamChunk.foldLazy      $foldLazy
  StreamChunk.flattenChunks $flattenChunks
  """

  import ArbitraryStreamChunk._
  import ExitResult._

  private def slurp[E, A](s: StreamChunk[E, A]): ExitResult[E, Seq[A]] = s match {
    case s: StreamChunkPure[A] =>
      succeeded(
        s.chunks.foldPureLazy(Chunk.empty: Chunk[A])(_ => true)((acc, el) => acc ++ el).toSeq
      )
    case s => slurpM(s)
  }

  private def slurpM[E, A](s: StreamChunk[E, A]): ExitResult[E, Seq[A]] =
    unsafeRunSync {
      s.foldLazyChunks(Chunk.empty: Chunk[A])(_ => true)((acc, el) => IO.now(acc ++ el)).map(_.toSeq)
    }

  private def map =
    prop { (s: StreamChunk[String, String], f: String => Int) =>
      slurp(s.map(f)) must_=== slurp(s).map(_.map(f))
    }

  private def filter =
    prop { (s: StreamChunk[String, String], p: String => Boolean) =>
      slurp(s.filter(p)) must_=== slurp(s).map(_.filter(p))
    }

  private def filterNot =
    prop { (s: StreamChunk[String, String], p: String => Boolean) =>
      slurp(s.filterNot(p)) must_=== slurp(s).map(_.filterNot(p))
    }

  private def mapConcat = {
    import ArbitraryChunk._
    prop { (s: StreamChunk[String, String], f: String => Chunk[Int]) =>
      slurp(s.mapConcat(f)) must_=== slurp(s).map(_.flatMap(v => f(v).toSeq))
    }
  }

  private def drop =
    prop { (s: StreamChunk[String, String], n: Int) =>
      slurp(s.drop(n)) must_=== slurp(s).map(_.drop(n))
    }

  private def dropWhile =
    prop { (s: StreamChunk[String, String], p: String => Boolean) =>
      slurp(s.dropWhile(p)) must_=== slurp(s).map(_.dropWhile(p))
    }

  private def take =
    prop { (s: StreamChunk[Nothing, String], n: Int) =>
      slurp(s.take(n)) must_=== slurp(s).map(_.take(n))
    }

  private def takeWhile =
    prop { (s: StreamChunk[Nothing, String], p: String => Boolean) =>
      val streamTakeWhile = slurp(s.takeWhile(p))
      val listTakeWhile   = slurp(s).map(_.takeWhile(p))
      streamTakeWhile must_=== listTakeWhile
    }

  private def concat =
    prop { (s1: StreamChunk[String, String], s2: StreamChunk[String, String]) =>
      val listConcat = for {
        left  <- slurp(s1)
        right <- slurp(s2)
      } yield left ++ right
      val streamConcat = slurpM(s1 ++ s2)
      streamConcat must_=== listConcat
    }

  private def zipWithIndex =
    prop((s: StreamChunk[String, String]) => slurp(s.zipWithIndex) must_=== slurp(s).map(_.zipWithIndex))

  private def mapAccum =
    prop { s: StreamChunk[String, Int] =>
      val slurped = slurpM(s.mapAccum(0)((acc, el) => (acc + el, acc + el)))
      slurped must_=== slurp(s).map(_.scan(0)((acc, el) => acc + el).drop(1))
    }

  private def mapM =
    prop { (s: StreamChunk[String, Int], f: Int => Int) =>
      slurpM(s.mapM(a => IO.now(f(a)))) must_=== slurp(s).map(_.map(f))
    }

  private def foreach0 =
    prop { (s: StreamChunk[String, Int], cont: Int => Boolean) =>
      var acc = List[Int]()

      val result = unsafeRunSync {
        s.foreach0 { a =>
          IO.sync {
            if (cont(a)) {
              acc ::= a
              true
            } else false
          }
        }
      }

      result.map(_ => acc.reverse) must_=== slurp(s.takeWhile(cont)).map(_.toList)
    }

  private def foreach =
    prop { s: StreamChunk[String, Int] =>
      var acc = List[Int]()

      val result = unsafeRunSync {
        s.foreach(a => IO.sync(acc ::= a))
      }

      result.map(_ => acc.reverse) must_=== slurp(s).map(_.toList)
    }

  private def monadLaw1 =
    prop(
      (x: Int, f: Int => StreamChunk[String, Int]) => slurp(StreamChunk.point(Chunk(x)).flatMap(f)) must_=== slurp(f(x))
    )

  private def monadLaw2 =
    prop((m: StreamChunk[String, Int]) => slurp(m.flatMap(i => StreamChunk.point(Chunk(i)))) must_=== slurp(m))
      .set(maxSize = 50)

  private def monadLaw3 =
    prop { (m: StreamChunk[String, Int], f: Int => StreamChunk[String, Int], g: Int => StreamChunk[String, Int]) =>
      val leftStream  = m.flatMap(f).flatMap(g)
      val rightStream = m.flatMap(x => f(x).flatMap(g))
      slurp(leftStream) must_=== slurp(rightStream)
    }

  private def withEffect =
    prop { (s: StreamChunk[String, String]) =>
      val withoutEffect = slurp(s)
      var acc           = List[String]()
      val withEffect    = slurp(s.withEffect(a => IO.sync(acc ::= a)))

      (withEffect must_=== withoutEffect) and
        ((Succeeded(acc.reverse) must_== withoutEffect) when withoutEffect.succeeded)
    }

  private def foldLeft =
    prop { (s: StreamChunk[String, String], zero: Int, f: (Int, String) => Int) =>
      unsafeRunSync(s.foldLeft(zero)(f)) must_=== slurp(s).map(_.foldLeft(zero)(f))
    }

  private def foldLazy =
    prop { (s: StreamChunk[Nothing, String], zero: Int, cont: Int => Boolean, f: (Int, String) => Int) =>
      val streamResult = unsafeRunSync(s.foldLazy(zero)(cont)((acc, a) => IO.now(f(acc, a))))
      val listResult   = slurp(s).map(l => foldLazyList(l.toList, zero)(cont)(f))
      streamResult must_=== listResult
    }

  private def foldLazyList[S, T](list: List[T], zero: S)(cont: S => Boolean)(f: (S, T) => S): S = {
    @tailrec
    def loop(xs: List[T], state: S): S = xs match {
      case head :: tail if cont(state) => loop(tail, f(state, head))
      case _                           => state
    }
    loop(list, zero)
  }

  private def flattenChunks =
    prop { (s: StreamChunk[String, String]) =>
      val result = unsafeRunSync {
        s.flattenChunks.foldLeft[String, List[String]](Nil)((acc, a) => a :: acc).map(_.reverse)
      }
      result must_== slurp(s)
    }
}
