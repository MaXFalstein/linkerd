package com.twitter.finagle.buoyant.h2
package netty4

import com.twitter.concurrent.AsyncQueue
import com.twitter.finagle.netty4.{BufAsByteBuf, ByteBufAsBuf}
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.util.{Future, Promise, Return, Stopwatch, Throw}
import io.netty.buffer.CompositeByteBuf
import io.netty.handler.codec.http2._
import scala.collection.immutable.Queue
import scala.util.control.NoStackTrace
import java.util.concurrent.atomic.AtomicReference

private[h2] object Netty4Stream {
  private val log = com.twitter.logging.Logger.get(getClass.getName)

  type Releaser = Int => Future[Unit]

  /**
   * States of a data stream
   */
  private sealed trait State

  /** The stream is open and more data is expected */
  private object Open extends State

  /** All data has been read to the user, but there is a pending trailers frame to be returned. */
  private case class Draining(trailers: Frame.Trailers) extends State

  /** The stream has been closed with a cause; all subsequent reads will fail */
  private case class Closed(cause: Throwable) extends State

  private val closedException =
    new IllegalStateException("stream closed") with NoStackTrace

  private val closedState =
    Closed(closedException)

  private def expectedOpenException(state: State) =
    new IllegalStateException(s"expected Open state; found ${state}")

  private def unexpectedFrame(f: Http2StreamFrame) =
    new IllegalStateException(s"Unexpected frame: ${f.name}")

  private val illegalReadException =
    new IllegalStateException("No data or trailers available")

  /** A data frame that wraps an Http2DataFrame */
  private class FrameData(frame: Http2DataFrame, releaser: Releaser) extends Frame.Data {
    private[this] val windowIncrement = frame.content.readableBytes + frame.padding
    def buf = ByteBufAsBuf.Owned(frame.content)
    def isEnd = frame.isEndStream
    def release(): Future[Unit] = {
      // XXX this causes problems currently, but it seems like we should release things here...
      // frame.content.release()
      releaser(windowIncrement)
    }
  }
}

/**
 * An Http2 Data stream that serves values from a queue of
 * Http2StreamFrames.
 *
 * Useful when reading a Message from a Transport. The transport can
 * just put data & trailer frames on the queue without processing.
 *
 * Data frames may be combined if there are at least `minAccumFrames`
 * pending in the queue. This allows the queue to be flushed more
 * quickly as it backs up. By default, frames are not accumulated.
 */
private[h2] class Netty4Stream(
  releaser: Netty4Stream.Releaser,
  minAccumFrames: Int = Int.MaxValue,
  frameq: AsyncQueue[Http2StreamFrame] = new AsyncQueue,
  stats: StatsReceiver = NullStatsReceiver
) extends Stream.Reader with Stream.Writer[Http2StreamFrame] {

  import Netty4Stream._

  private[this] val accumBytes = stats.stat("accum_bytes")
  private[this] val accumMicros = stats.stat("accum_us")
  private[this] val readQlens = stats.stat("read_qlen")
  private[this] val readMicros = stats.stat("read_us")

  private[this] val state: AtomicReference[State] = new AtomicReference(Open)

  private[this] val endP = new Promise[Unit]
  override def onEnd: Future[Unit] = endP

  /** Fail the underlying queue and*/
  override def reset(exn: Throwable): Unit =
    if (state.compareAndSet(Open, Closed(exn))) {
      frameq.fail(exn, discard = true)
      endP.setException(exn)
    }

  override def write(frame: Http2StreamFrame): Boolean =
    state.get match {
      case Open => frameq.offer(frame)
      case _ => false
    }

  /**
   * Read a Data from the underlying queue.
   *
   * If there are at least `minAccumFrames` in the queue, data frames may be combined
   */
  override def read(): Future[Frame] = {
    val start = Stopwatch.start()
    val f = state.get match {
      case Open =>
        val sz = frameq.size
        readQlens.add(sz)
        if (sz < minAccumFrames) frameq.poll().map(andDrainAccum)
        else Future.const(frameq.drain()).map(accumStream)

      case Closed(cause) => Future.exception(cause)

      case s@Draining(trailers) =>
        if (state.compareAndSet(s, closedState)) {
          frameq.fail(closedException)
          endP.setDone()
          Future.value(trailers)
        } else Future.exception(closedException)
    }
    f.onSuccess(_ => readMicros.add(start().inMicroseconds))
    f
  }

  // Try to read as much data as is available so that it may be
  // chunked. This is done after poll() returns because it's likely
  // that multiple items may have entered the queue.
  private[this] val andDrainAccum: Http2StreamFrame => Frame = {
    case f: Http2DataFrame if f.isEndStream =>
      if (state.compareAndSet(Open, closedState)) {
        endP.setDone()
        toData(f)
      } else throw expectedOpenException(state.get)

    case f: Http2DataFrame =>
      if (frameq.size + 1 < minAccumFrames) toData(f)
      else frameq.drain() match {
        case Throw(_) => toData(f)
        case Return(q) => accumStream(f +: q)
      }

    case f: Http2HeadersFrame if f.isEndStream => // Trailers
      if (state.compareAndSet(Open, closedState)) {
        endP.setDone()
        Netty4Message.Trailers(f.headers)
      } else throw expectedOpenException(state.get)

    case f => throw unexpectedFrame(f)
  }

  private def toData(f: Http2DataFrame): Frame.Data =
    new FrameData(f, releaser)

  /**
   * Accumulate a queue of stream frames to a single frame.
   *
   * If a trailers frame exists
   */
  private val accumStream: Queue[Http2StreamFrame] => Frame = { frames =>
    require(frames.nonEmpty)
    val start = Stopwatch.start()

    var content: CompositeByteBuf = null
    var bytes = 0
    var dataEos = false
    var trailers: Frame.Trailers = null

    val nFrames = frames.length
    val iter = frames.iterator
    while (!dataEos && trailers == null && iter.hasNext) {
      iter.next() match {
        case f: Http2DataFrame =>
          bytes += f.content.readableBytes + f.padding
          // Initialize content using the first frame's allocator
          if (content == null) {
            content = f.content.alloc.compositeBuffer(nFrames)
          }
          content.addComponent(true /*advance widx*/ , f.content)
          dataEos = f.isEndStream

        case f: Http2HeadersFrame =>
          trailers = Netty4Message.Trailers(f.headers)

        case _ =>
      }
    }

    val data: Frame.Data =
      if (content == null) null
      else {
        accumBytes.add(bytes)
        new Frame.Data {
          val buf = ByteBufAsBuf.Owned(content.retain())
          def isEnd = dataEos
          def release() =
            if (bytes > 0) releaser(bytes)
            else Future.Unit
        }
      }

    val next = (data, trailers) match {
      case (null, null) => throw illegalReadException
      case (data, null) => data
      case (null, tlrs) => tlrs
      case (data, tlrs) =>
        if (state.compareAndSet(Open, Draining(tlrs))) data
        else throw expectedOpenException(state.get)
    }
    if (next.isEnd) endP.setDone()

    accumMicros.add(start().inMicroseconds)
    next
  }

}
