/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package testpkg

import org.scalatest._
import scala.concurrent._
import scala.annotation.tailrec
import sbt.protocol.ClientSocket
import scala.util.Try
import TestServer.withTestServer
import java.io.File
import sbt.io.syntax._
import sbt.io.IO
import sbt.RunFromSourceMain
import scala.concurrent.ExecutionContext
import java.util.concurrent.ForkJoinPool

class ServerSpec extends fixture.AsyncFreeSpec with fixture.AsyncTestDataFixture with Matchers {
  "server" - {
    "should start" in { implicit td =>
      withTestServer("handshake") { p =>
        p.writeLine(
          """{ "jsonrpc": "2.0", "id": "3", "method": "sbt/setting", "params": { "setting": "root/name" } }"""
        )
        assert(p.waitForString(10) { s =>
          s contains """"id":"3""""
        })
      }
    }

    "return number id when number id is sent" in { implicit td =>
      withTestServer("handshake") { p =>
        p.writeLine(
          """{ "jsonrpc": "2.0", "id": 3, "method": "sbt/setting", "params": { "setting": "root/name" } }"""
        )
        assert(p.waitForString(10) { s =>
          s contains """"id":3"""
        })
      }
    }

    "report task failures in case of exceptions" in { implicit td =>
      withTestServer("events") { p =>
        p.writeLine(
          """{ "jsonrpc": "2.0", "id": 11, "method": "sbt/exec", "params": { "commandLine": "hello" } }"""
        )
        assert(p.waitForString(10) { s =>
          (s contains """"id":11""") && (s contains """"error":""")
        })
      }
    }

    "return error if cancelling non-matched task id" in { implicit td =>
      withTestServer("events") { p =>
        p.writeLine(
          """{ "jsonrpc": "2.0", "id":12, "method": "sbt/exec", "params": { "commandLine": "run" } }"""
        )
        p.writeLine(
          """{ "jsonrpc": "2.0", "id":13, "method": "sbt/cancelRequest", "params": { "id": "55" } }"""
        )

        assert(p.waitForString(20) { s =>
          (s contains """"error":{"code":-32800""")
        })
      }
    }

    "cancel on-going task with numeric id" in { implicit td =>
      withTestServer("events") { p =>
        p.writeLine(
          """{ "jsonrpc": "2.0", "id":12, "method": "sbt/exec", "params": { "commandLine": "run" } }"""
        )

        assert(p.waitForString(60) { s =>
          p.writeLine(
            """{ "jsonrpc": "2.0", "id":13, "method": "sbt/cancelRequest", "params": { "id": "12" } }"""
          )
          s contains """"result":{"status":"Task cancelled""""
        })
      }
    }

    "cancel on-going task with string id" in { implicit td =>
      withTestServer("events") { p =>
        p.writeLine(
          """{ "jsonrpc": "2.0", "id": "foo", "method": "sbt/exec", "params": { "commandLine": "run" } }"""
        )

        assert(p.waitForString(60) { s =>
          p.writeLine(
            """{ "jsonrpc": "2.0", "id": "bar", "method": "sbt/cancelRequest", "params": { "id": "foo" } }"""
          )
          s contains """"result":{"status":"Task cancelled""""
        })
      }
    }
  }
}

object TestServer {

  private val serverTestBase: File = new File(".").getAbsoluteFile / "sbt" / "src" / "server-test"

  def withTestServer(
      testBuild: String
  )(f: TestServer => Future[Assertion])(implicit td: TestData): Future[Assertion] = {
    println(s"Starting test: ${td.name}")
    IO.withTemporaryDirectory { temp =>
      IO.copyDirectory(serverTestBase / testBuild, temp / testBuild)
      withTestServer(testBuild, temp / testBuild)(f)
    }
  }

  def withTestServer(testBuild: String, baseDirectory: File)(
      f: TestServer => Future[Assertion]
  )(implicit td: TestData): Future[Assertion] = {
    // Each test server instance will be executed in a Thread pool separated from the tests
    val testServer = TestServer(baseDirectory)(
      ExecutionContext.fromExecutor(new ForkJoinPool())
    )
    // checking last log message after initialization
    // if something goes wrong here the communication streams are corrupted, restarting
    val init =
      Try {
        testServer.waitForString(30) { s =>
          s contains """"message":"Done""""
        }
      }.toOption

    init match {
      case Some(_) =>
        try {
          f(testServer)
        } finally {
          try { testServer.bye() } finally {}
        }
      case _ =>
        try { testServer.bye() } finally {}
        hostLog("Server started but not connected properly... restarting...")
        withTestServer(testBuild)(f)
    }
  }

  def hostLog(s: String): Unit = {
    println(s"""[${scala.Console.MAGENTA}build-1${scala.Console.RESET}] $s""")
  }
}

case class TestServer(baseDirectory: File)(implicit ec: ExecutionContext) {
  import TestServer.hostLog

  val readBuffer = new Array[Byte](4096)
  var buffer: Vector[Byte] = Vector.empty
  var bytesRead = 0
  private val delimiter: Byte = '\n'.toByte
  private val RetByte = '\r'.toByte

  hostLog("fork to a new sbt instance")
  val process =
    Future {
      RunFromSourceMain.fork(baseDirectory)
    }

  lazy val portfile = baseDirectory / "project" / "target" / "active.json"

  hostLog("wait 30s until the server is ready to respond")
  def waitForPortfile(n: Int): Unit =
    if (portfile.exists) ()
    else {
      if (n <= 0) sys.error(s"Timeout. $portfile is not found.")
      else {
        Thread.sleep(1000)
        if ((n - 1) % 10 == 0) {
          hostLog("waiting for the server...")
        }
        waitForPortfile(n - 1)
      }
    }
  waitForPortfile(90)

  // make connection to the socket described in the portfile
  val (sk, tkn) = ClientSocket.socket(portfile)
  val out = sk.getOutputStream
  val in = sk.getInputStream

  // initiate handshake
  sendJsonRpc(
    """{ "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": { "initializationOptions": { } } }"""
  )

  def test(f: TestServer => Future[Assertion]): Future[Assertion] = {
    f(this)
  }

  def bye(): Unit = {
    hostLog("sending exit")
    sendJsonRpc(
      """{ "jsonrpc": "2.0", "id": 9, "method": "sbt/exec", "params": { "commandLine": "exit" } }"""
    )
    for {
      p <- process
    } {
      p.destroy()
    }
  }

  def sendJsonRpc(message: String): Unit = {
    writeLine(s"""Content-Length: ${message.size + 2}""")
    writeLine("")
    writeLine(message)
  }

  def writeLine(s: String): Unit = {
    def writeEndLine(): Unit = {
      val retByte: Byte = '\r'.toByte
      val delimiter: Byte = '\n'.toByte
      out.write(retByte.toInt)
      out.write(delimiter.toInt)
      out.flush
    }

    if (s != "") {
      out.write(s.getBytes("UTF-8"))
    }
    writeEndLine
  }

  def readFrame: Option[String] = {
    def getContentLength: Int = {
      readLine map { line =>
        line.drop(16).toInt
      } getOrElse (0)
    }

    val l = getContentLength
    readLine
    readLine
    readContentLength(l)
  }

  @tailrec
  final def waitForString(num: Int)(f: String => Boolean): Boolean = {
    if (num < 0) { throw new Exception("Retries are over.") } else {
      // readFrame should be called in another Thread in orrder to be able to time limit it's execution
      val res = Future { readFrame }(ec)

      import scala.concurrent.duration._
      Try {
        Await.result(res, 1.second)
      }.toOption.flatten match {
        // function f should be called in this Thread in order to be executed exactly once before eventually returning
        case Some(str) if f(str) => true
        case _                   => waitForString(num - 1)(f)
      }
    }
  }

  def readLine: Option[String] = {
    if (buffer.isEmpty) {
      val bytesRead = in.read(readBuffer)
      if (bytesRead > 0) {
        buffer = buffer ++ readBuffer.toVector.take(bytesRead)
      }
    }
    val delimPos = buffer.indexOf(delimiter)
    if (delimPos > 0) {
      val chunk0 = buffer.take(delimPos)
      buffer = buffer.drop(delimPos + 1)
      // remove \r at the end of line.
      val chunk1 = if (chunk0.lastOption contains RetByte) chunk0.dropRight(1) else chunk0
      Some(new String(chunk1.toArray, "utf-8"))
    } else None // no EOL yet, so skip this turn.
  }

  def readContentLength(length: Int): Option[String] = {
    if (buffer.isEmpty) {
      val bytesRead = in.read(readBuffer)
      if (bytesRead > 0) {
        buffer = buffer ++ readBuffer.toVector.take(bytesRead)
      }
    }
    if (length <= buffer.size) {
      val chunk = buffer.take(length)
      buffer = buffer.drop(length)
      Some(new String(chunk.toArray, "utf-8"))
    } else None // have not read enough yet, so skip this turn.
  }

}