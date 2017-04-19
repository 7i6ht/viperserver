/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */


package viper.server

import akka.actor.{Actor, ActorRef, ActorSystem, Kill, Props, Terminated}
import org.rogach.scallop.singleArgConverter
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Logger
import viper.server.RequestHandler._
import viper.silicon.Silicon
import viper.silver.frontend.SilFrontendConfig
import viper.silver.verifier.Verifier

import scala.collection.mutable.ListBuffer
import scala.language.postfixOps

object ViperServerRunner {

  def logger = LoggerFactory.getLogger(getClass.getName+"_IDE").asInstanceOf[Logger]

  private var _config: ViperConfig = _

  final def config = _config

  private var _actorWatcher: ActorRef = null

  final def actorWatcher = _actorWatcher

  private var _actorSystem: ActorSystem = null

  final def actorSystem = _actorSystem

  private var _running = true

  def main(args: Array[String]) {
    try {
      initialize()
      logger.info("This is the Viper Server.")
      parseCommandLine(args)
      //print("> ")
      while (_running) {
        serve()
      }
    } finally {
      cleanUp()
    }
    sys.exit(0)
  }

  private def initialize(): Unit = {
//  logger.setLevel(org.apache.log4j.Level.INFO)
    _actorSystem = ActorSystem("MyActorSystem")
    _actorWatcher = actorSystem.actorOf(Props[WatchActor])
  }

  private def cleanUp(): Unit = {
    actorSystem.terminate()
  }

  private def serve(): Unit = {
    val input: String = scala.io.StdIn.readLine()
    if (input == null || input.length == 0) {
      return
    }

    val args = splitCommandLineArgs(input.trim())

    if (args.isEmpty) {
      return
    } else if (args.head == "stop") {
      actorWatcher ! Stop
    } else if (args.head == "exit") {
      actorWatcher ! Stop
      _running = false
    } else {
      actorWatcher ! Verify(args)
    }
  }

  private def splitCommandLineArgs(args: String): List[String] = {
    var res = new ListBuffer[String]()
    if (args != null) {
      var last: Char = '\u0000'
      var inQuotes = false
      var arg: StringBuilder = new StringBuilder()

      val trimmedArgs: String = args.trim() + " "

      trimmedArgs foreach (char =>
        char match {
          case '"' => {
            if (inQuotes) {
              if (last == '"') {
                arg += char
                last = '\u0000'
              } else {
                last = char
              }
            } else {
              inQuotes = true
            }
          }
          case ' ' => {
            if (last == '"') {
              inQuotes = false
            }
            if (inQuotes) {
              arg += char
            } else {
              res += arg.toString()
              arg = new StringBuilder()
            }
            last = char
          }
          case c => {
            if (last == '"') {
              inQuotes = false
            }
            arg += char
            last = char
          }
        }
        )
    }
    return res.toList
  }

  def parseCommandLine(args: Seq[String]) {
    _config = new ViperConfig(args)
  }
}

class WatchActor extends Actor {
  private var _child: ActorRef = null
  private var _args: List[String] = null
  private var _backend: Verifier = null



  def receive = {
    case Stop => {
      if (_backend != null) {
        try {
          //takes care of background processes
          _backend.stop()
        } catch {
          case e: Exception => {
            throw e
            //e.printStackTrace()
          }
        }
      }
      if (_child != null) {
        try {
          _child ! Stop
        } catch {
          case e: Exception => {
            throw e
            //e.printStackTrace()
          }
        }
      }
      _backend = null
    }
    case Verify(args)
    => {
      if (_child != null) {
        _args = args
        self ! Stop
      } else {
        verify(args)
      }
    }
    case Stopped
    => {
      _child = null
      if (_args != null) {
        val args = _args
        _args = null
        verify(args)
      }
    }
    case Terminated(child)
    => {}
    case Backend(backend)
    => {
      _backend = backend
    }
    case msg => println("ActorWatcher: unexpected message received: " + msg)
  }

  def verify(args: List[String]): Unit = {
    assert(_child == null)
    _child = context.actorOf(Props[RequestHandler], "RequestHandlerActor")
    context.watch(_child)
    _child ! Verify(args)
  }
}

class ViperConfig(args: Seq[String]) extends SilFrontendConfig(args, "Viper") {

  val logLevel = opt[String]("logLevel",
    descr = "One of the log levels ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF (default: OFF)",
    default = Some("WARN"),
    noshort = true,
    hidden = Silicon.hideInternalOptions
  )(singleArgConverter(level => level.toUpperCase))

}
