package org.specs2
package runner

import control._
import io.StringOutput
import specification.process.Stats
import specification.core._
import reporter._
import main.Arguments
import fp.syntax._
import Runner._
import reporter.LineLogger._

trait ClassRunner {
  def run(className: String): Action[Stats]
  def run(spec: SpecificationStructure): Action[Stats]
  def run(spec: SpecStructure): Action[Stats]
}

/**
 * A runner for Specification classes based on their names
 */

case class DefaultClassRunner(arguments: Arguments, reporter: Reporter, specFactory: SpecFactory, env: Env) extends ClassRunner {

  /** instantiate a Specification from its class name and use arguments to determine how to
   * execute it and report results
   */
  def run(className: String): Action[Stats] =
    specFactory.createSpecification(className).toAction.flatMap(spec => run(spec.structure(env)))

  def run(spec: SpecificationStructure): Action[Stats] =
    run(spec.structure(env))

  def run(specStructure: SpecStructure): Action[Stats] = for {
    stats <- if (arguments.isSet("all")) {
        for {
          ss       <- specFactory.createLinkedSpecs(specStructure).toAction
          sorted   <- Action.pure(SpecStructure.topologicalSort(ss)(env.specs2ExecutionEnv).getOrElse(ss))
          _        <- reporter.prepare(sorted.toList)
          stats    <- sorted.toList.map(reporter.report).sequence
          _        <- reporter.finalize(sorted.toList)
        } yield stats.foldMap(identity _)
      } else reporter.report(specStructure)
    } yield stats

}

trait ClassRunnerMain {
  /**
   * run a specification but don't exit with System.exit
   */
  def run(args: Array[String]): Unit =
    run(args, exit = false)

  /**
   * run the specification, the first argument is expected to be the specification name
   * The class runner expects the first command-line argument to be the class name of
   * a specification to execute
   */
  def run(args: Array[String], exit: Boolean): Unit = {
    val arguments = Arguments(args.drop(1): _*)
    val env = Env(arguments = arguments, lineLogger = consoleLogger)

    val actions: Action[Stats] = args.toList match {
      case Nil =>
        Action.fail("there must be at least one argument, the fully qualified class name") >>
        Action.pure(Stats.empty)

      case className :: rest => for {
          classRunner <- createClassRunner(arguments, env)
          stats <- classRunner.run(className)
        } yield stats
      }

    try execute(actions, arguments, exit)(env)
    finally env.shutdown
  }

  def createClassRunner(arguments: Arguments, env: Env): Action[ClassRunner] = {
    val loader = Thread.currentThread.getContextClassLoader
    val customInstances = CustomInstances(arguments, loader, ConsoleLogger())
    val printerFactory = PrinterFactory(arguments, env, customInstances, ConsoleLogger())
    val specFactory = DefaultSpecFactory(env, loader)

    for {
      printers <- printerFactory.createPrinters.toAction
      reporter <- customInstances.createCustomInstance[Reporter]( "reporter",
        (m: String) => "a custom reporter can not be instantiated " + m, "no custom reporter defined, using the default one")
        .map(_.getOrElse(DefaultReporter(arguments, env, printers))).toAction
      classRunner = DefaultClassRunner(arguments, reporter, specFactory, env)
     } yield classRunner

  }
}

object ClassRunner extends ClassRunnerMain

object consoleRunner extends ClassRunnerMain {
  def main(args: Array[String]) =
    run(args, exit = true)
}

/**
 * Test runner to simulate a console run
 */
object TextRunner extends ClassRunnerMain {

  def run(spec: SpecificationStructure, arguments: Arguments = Arguments())(env: Env): LineLogger with StringOutput = {
    val logger = LineLogger.stringLogger
    val env1 = env.setLineLogger(logger).setArguments(env.arguments.overrideWith(arguments))
    val loader = Thread.currentThread.getContextClassLoader
    val customInstances = CustomInstances(arguments, loader, StringOutputLogger(logger))
    val action =
      for {
        reporter <- customInstances.createCustomInstance[Reporter]( "reporter",
          (m: String) => "a custom reporter can not be instantiated " + m, "no custom reporter defined, using the default one")
          .map(_.getOrElse(DefaultReporter(arguments, env, List(TextPrinter(env1))))).toAction
        stats <- reporter.report(spec.structure(env1))
       } yield stats

    action.runAction(env1.specs2ExecutionEnv)
    logger
  }

}
