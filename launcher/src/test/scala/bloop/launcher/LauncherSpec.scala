package bloop.launcher

import java.io._
import java.net.{ServerSocket, Socket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}

import bloop.cli.{CliOptions, CommonOptions}
import bloop.logging.{BloopLogger, BspClientLogger, DebugFilter, RecordingLogger}
import bloop.tasks.TestUtil
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import org.junit.{Assert, Ignore, Test}
import sbt.internal.util.MessageOnlyException

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc._
import scala.util.control.NonFatal

class LauncherSpec extends AbstractLauncherSpec {
  // Update the bsp version whenever we change the bloop version
  private final val bloopVersion = "1.1.2"
  private final val shell = Shell.forTests
  private class LauncherFailure extends Exception("The bloop launcher didn't finish successfully.")
  val successfulCliExit = (successful: Boolean) => if (successful) () else throw new LauncherFailure

  case class LauncherRun(successful: Boolean, logs: List[String])
  def runLauncher(in: InputStream, out: OutputStream, shell: Shell, startedServer: Promise[Unit])(
      launcherLogic: LauncherMain => Boolean
  ): LauncherRun = {
    import java.io.ByteArrayOutputStream
    import java.io.PrintStream
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos, true, "UTF-8")
    try {
      val port = shell.portNumberWithin(8997, 9002)
      val launcher = new LauncherMain(
        in,
        out,
        System.err,
        StandardCharsets.UTF_8,
        shell,
        Some(port),
        startedServer
      )
      val successful = launcherLogic(launcher)
      val logs = new String(baos.toByteArray, StandardCharsets.UTF_8)
      LauncherRun(successful, logs.split(System.lineSeparator()).toList)
    } finally {
      val logs = new String(baos.toByteArray, StandardCharsets.UTF_8)
      System.err.println(logs.mkString(System.lineSeparator()))
      if (ps != null) ps.close()
    }
  }

  def runCli(
      args: Array[String],
      in: InputStream = System.in,
      out: OutputStream = System.out,
      shell: Shell = shell,
      startedServer: Promise[Unit] = Promise[Unit]()
  ): LauncherRun = {
    runLauncher(in, out, shell, startedServer) { launcher =>
      var successful: Boolean = false
      launcher.cli(args, runSuccessfully => if (runSuccessfully) successful = true else ())
      successful
    }
  }

  def testLauncher[T](run: LauncherRun, printLogs: Boolean = true)(
      testFunction: LauncherRun => T
  ): Unit = {
    try {
      testFunction(run)
      ()
    } catch {
      case NonFatal(t) =>
        if (printLogs)
          System.err.println(run.logs.mkString(System.lineSeparator()))
        throw t
    }
  }

  /*  @Test
  def testSystemPropertiesMockingWork(): Unit = {
    // Test from https://stefanbirkner.github.io/system-rules/index.html
    val parentDir = this.binDirectory.getParent()
    parentDir.toFile.deleteOnExit()
    Assert.assertEquals(parentDir, Paths.get(System.getProperty("user.dir")).getParent)
    Assert.assertEquals(parentDir, Paths.get(System.getProperty("user.home")).getParent)
  }

  @Test
  def failIfEmptyArguments(): Unit = {
    testLauncher(runCli(Array.empty)) { run =>
      Assert.assertTrue("Expected failed bloop launcher", !run.successful)
      val errorMsg = "The bloop launcher accepts only one argument: the bloop version"
      Assert.assertTrue(s"Missing '${errorMsg}'", run.logs.exists(_.contains(errorMsg)))
    }
  }

  @Test
  def checkThatPythonIsInClasspath(): Unit = {
    // Python must always be in the classpath in order to run these tests, if this fails install it
    Assert.assertTrue(shell.isPythonInClasspath)
  }

  @Test
  def dontDetectSystemBloop(): Unit = {
    val run = runLauncher(shell) { launcher =>
      // We should not detect the server state unless we have installed it via the launcher
      val state = launcher.detectServerState(bloopVersion)
      if (state == None) true
      else {
        launcher.out.println(s"Found bloop binary in ${state}, expected none!")
        false
      }
    }

    testLauncher(run) { run =>
      Assert.assertTrue(run.successful)
    }
  }

  @Test
  def testInstallationViaInstallpy(): Unit = {
    val run = runLauncher(shell) { launcher =>
      // Install the launcher via `install.py`, which is the preferred installation method
      val state = Installer.installBloopBinaryInHomeDir(
        this.binDirectory,
        launcher.defaultBloopDirectory,
        bloopVersion,
        launcher.out,
        launcher.detectServerState(_),
        launcher.shell
      )

      // We should detect the bloop binary in the place where we installed it!
      val bloopDir = launcher.defaultBloopDirectory.resolve("bloop")
      state match {
        case Some(AvailableAt(binary)) if binary.head == bloopDir.toString => true
        case _ => System.err.println(s"Obtained unexpected ${state}"); false
      }
    }

    testLauncher(run, false) { run =>
      Assert.assertTrue("Failed to install.py and run bloop", run.successful)
    }
  }

  @Test
  def testBloopResolution(): Unit = {
    val run = runLauncher(shell) { launcher =>
      val (_, resolution) = Installer.resolveServer(bloopVersion, true)
      Assert.assertTrue(s"Resolution errors ${resolution.errors}", resolution.errors.isEmpty)
      Installer.fetchJars(resolution, launcher.out).nonEmpty
    }

    testLauncher(run) { run =>
      Assert.assertTrue("Jars were not fetched!", run.successful)
    }
  }*/

  val bspScheduler: Scheduler = Scheduler(
    java.util.concurrent.Executors.newFixedThreadPool(4),
    ExecutionModel.AlwaysAsyncExecution
  )

  def startBspInitializeHandshake[T](
      in: InputStream,
      out: OutputStream,
      logger: BspClientLogger[_]
  )(runEndpoints: LanguageClient => Task[Either[Response.Error, T]]): Task[T] = {
    implicit val ctx: DebugFilter = DebugFilter.Bsp
    import ch.epfl.scala.bsp
    import ch.epfl.scala.bsp.endpoints
    def createServices(addDiagnosticsHandler: Boolean, logger0: BspClientLogger[_]): Services = {
      val logger: bloop.logging.Logger = logger0
      val rawServices = Services
        .empty(logger0)
        .notification(endpoints.Build.showMessage) {
          case bsp.ShowMessageParams(bsp.MessageType.Log, _, _, msg) => logger.debug(msg)
          case bsp.ShowMessageParams(bsp.MessageType.Info, _, _, msg) => logger.info(msg)
          case bsp.ShowMessageParams(bsp.MessageType.Warning, _, _, msg) => logger.warn(msg)
          case bsp.ShowMessageParams(bsp.MessageType.Error, _, _, msg) => logger.error(msg)
        }
        .notification(endpoints.Build.logMessage) {
          case bsp.LogMessageParams(bsp.MessageType.Log, _, _, msg) => logger.debug(msg)
          case bsp.LogMessageParams(bsp.MessageType.Info, _, _, msg) => logger.info(msg)
          case bsp.LogMessageParams(bsp.MessageType.Warning, _, _, msg) => logger.warn(msg)
          case bsp.LogMessageParams(bsp.MessageType.Error, _, _, msg) => logger.error(msg)
        }

      // Lsp4s fails if we try to repeat a handler for a given notification
      if (!addDiagnosticsHandler) rawServices
      else {
        rawServices.notification(endpoints.Build.publishDiagnostics) {
          case bsp.PublishDiagnosticsParams(uri, _, _, diagnostics, _) =>
            // We prepend diagnostics so that tests can check they came from this notification
            def printDiagnostic(d: bsp.Diagnostic): String = s"[diagnostic] ${d.message} ${d.range}"
            diagnostics.foreach { d =>
              d.severity match {
                case Some(bsp.DiagnosticSeverity.Error) => logger.error(printDiagnostic(d))
                case Some(bsp.DiagnosticSeverity.Warning) => logger.warn(printDiagnostic(d))
                case Some(bsp.DiagnosticSeverity.Information) => logger.info(printDiagnostic(d))
                case Some(bsp.DiagnosticSeverity.Hint) => logger.debug(printDiagnostic(d))
                case None => logger.info(printDiagnostic(d))
              }
            }
        }
      }
    }

    implicit val lsClient = new LanguageClient(out, logger)
    val messages = BaseProtocolMessage.fromInputStream(in, logger)
    val services = createServices(false, logger)
    val lsServer = new LanguageServer(messages, lsClient, services, bspScheduler, logger)
    val runningClientServer = lsServer.startTask.runAsync(bspScheduler)

    val cwd = Paths.get(System.getProperty("user.dir"))
    val initializeServer = endpoints.Build.initialize.request(
      bsp.InitializeBuildParams(
        "test-bloop-client",
        "1.0.0",
        "2.0.0-M1",
        rootUri = bsp.Uri(cwd.toUri),
        capabilities = bsp.BuildClientCapabilities(List("scala")),
        None
      )
    )

    for {
      // Delay the task to let the bloop server go live
      initializeResult <- initializeServer
      _ = endpoints.Build.initialized.notify(bsp.InitializedBuildParams())
      otherCalls <- runEndpoints(lsClient)
      _ <- endpoints.Build.shutdown.request(bsp.Shutdown())
      _ = endpoints.Build.exit.notify(bsp.Exit())
    } yield {
      System.err.println("Closing the client input stream")
      in.close()
      System.err.println("Closing the client output stream")
      out.close()
      otherCalls match {
        case Right(t) => t
        case Left(error) => throw new MessageOnlyException(s"Unexpectd BSP client error: ${error}")
      }
    }
  }

  /*  def redirect(common: CommonOptions): (InputStream, OutputStream, ByteArrayOutputStream) = { val outMemory = new ByteArrayOutputStream()
    val newOut = new PrintStream(outMemory)
    val newIn = new PipedInputStream()
    val testOut = new PipedOutputStream(newIn)
    (newIn, newOut)
    (cliOptions, testOut, outMemory)
  }*/

  @Test
  def runBspLauncherWithBloopVersion(): Unit = {
    val launcherIn = new PipedInputStream()
    val clientOut = new PipedOutputStream(launcherIn)

    val clientIn = new PipedInputStream()
    val launcherOut = new PipedOutputStream(clientIn)

    val startedServer = Promise[Unit]()
    val startServer = Task {
      testLauncher(
        runCli(Array("1.1.2"), in = launcherIn, out = launcherOut, startedServer = startedServer)
      ) { run =>
        System.err.println(run.logs.mkString("\n"))
      }
    }

    startServer.runAsync(bspScheduler)

    val logger = BloopLogger.at("asdfasdf", System.err, System.err, true, false, DebugFilter.Bsp)
    val connectToServer = Task.fromFuture(startedServer.future).flatMap { _ =>
      val bspLogger = new BspClientLogger(logger)
      startBspInitializeHandshake(clientIn, clientOut, bspLogger) { c =>
        monix.eval.Task.eval(Right(()))
      }
    }

    try TestUtil.await(FiniteDuration(25, "s"))(connectToServer)
    finally {
      //logger.dump(System.err)
    }
  }
}
