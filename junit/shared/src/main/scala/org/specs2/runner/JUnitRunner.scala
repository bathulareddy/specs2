package org.specs2
package runner

import org.junit.runner.manipulation.{Filterable, NoTestsRemainException}
import org.junit.runner.notification.{Failure, RunNotifier}
import main._
import fp.syntax._
import control._
import specification.core._
import specification.process.Stats
import reporter._
import scala.util.control.NonFatal

/**
 * Runner for specs2 specifications
 */
class JUnitRunner(klass: Class[_]) extends org.junit.runner.Runner with Filterable { outer =>

  /** specification to execute */
  lazy val specification = {
    val structure = SpecificationStructure.create(klass.getName, Thread.currentThread.getContextClassLoader, Some(env))
    structure.unsafeRun
  }

  /** command line arguments, extracted from the system properties */
  lazy val arguments: Arguments =
    Arguments("junit")

  /** specification environment */
  lazy val env: Env =
    Env(arguments = arguments, lineLogger = LineLogger.consoleLogger)

  lazy val getDescription: org.junit.runner.Description =
    getDescription(env)

  def getDescription(env: Env): org.junit.runner.Description =
    try JUnitDescriptions.createDescription(specStructure)(env.specs2ExecutionEnv)
    catch { case NonFatal(t) => env.shutdown; throw t; }

  /** specification structure for the environment */
  lazy val specStructure: SpecStructure =
    specification.structure(env)

  /** run the specification with a Notifier */
  def run(n: RunNotifier): Unit = {
    try {
      runWithEnv(n, env).runAction(env.specs2ExecutionEnv) match {
        case Right(_) => ()
        case Left(t) => n.fireTestFailure(new Failure(getDescription, new RuntimeException(t)))
      }
    }
    finally env.shutdown
  }

  /** run the specification with a Notifier and an environment */
  def runWithEnv(runNotifier: RunNotifier, env: Env): Action[Stats] = {
    val loader = Thread.currentThread.getContextClassLoader
    val arguments = env.arguments
    val customInstances = CustomInstances(arguments, loader, ConsoleLogger())
    val printerFactory = PrinterFactory(arguments, env, customInstances, ConsoleLogger())
    val junitPrinter = JUnitPrinter(env, runNotifier)

    for {
      printers <- printerFactory.createPrinters.toAction
      reporter <- customInstances.createCustomInstance[Reporter]( "reporter",
           (m: String) => "a custom reporter can not be instantiated " + m, "no custom reporter defined, using the default one")
           .map(_.getOrElse(DefaultReporter(arguments, env, junitPrinter +: printers))).toAction
      stats <- reporter.report(specStructure)
     } yield stats
  }

  /**
   * This is used to filter out the entire specification based
   * on categories annotations
   *
   * if the more fine-grained filtering is needed tags must be used
   */
  def filter(filter: org.junit.runner.manipulation.Filter): Unit = {
    if (!filter.shouldRun(getDescription)) throw new NoTestsRemainException
  }
}
