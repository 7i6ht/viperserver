/**
  * This Source Code Form is subject to the terms of the Mozilla Public
  * License, v. 2.0. If a copy of the MPL was not distributed with this
  * file, You can obtain one at http://mozilla.org/MPL/2.0/.
  *
  * Copyright (c) 2011-2020 ETH Zurich.
  */

package viper.server.frontends.lsp

import java.util.concurrent.{CompletableFuture => CFuture}

import org.eclipse.lsp4j.jsonrpc.services.{JsonNotification, JsonRequest}
import org.eclipse.lsp4j.services.{LanguageClient, LanguageClientAware}
import org.eclipse.lsp4j.{DidChangeConfigurationParams, DidChangeTextDocumentParams, DidCloseTextDocumentParams, DidOpenTextDocumentParams, DocumentSymbolParams, InitializeParams, InitializeResult, InitializedParams, Location, Range, ServerCapabilities, SymbolInformation, TextDocumentPositionParams, TextDocumentSyncKind}
import viper.server.frontends.lsp.LogLevel._
import viper.server.frontends.lsp.VerificationState._

import scala.collection.mutable.ArrayBuffer



abstract class StandardReceiver extends LanguageClientAware {
  var received_shutdown = false

  @JsonRequest("initialize")
  def initialize(params: InitializeParams): CFuture[InitializeResult] = {
    println("initialize")
    val capabilities = new ServerCapabilities()
    //    OG
    //    always send full text document for each notification:
    //    capabilities.setCompletionProvider(new CompletionOptions(true, null))

    //    val Coordinator.verifier = new ViperServerService(Array())
    //    Coordinator.verifier.setReady(Option.empty)

    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
    capabilities.setDefinitionProvider(true)
    capabilities.setDocumentSymbolProvider(true)
    CFuture.completedFuture(new InitializeResult(capabilities))
  }
  @JsonNotification("initialized")
  def initialize(params: InitializedParams): Unit = {
    println("initialized")
  }

  @JsonNotification("textDocument/didOpen")
  def onDidOpenDocument(params: DidOpenTextDocumentParams): Unit = {
    println("On opening document")
    try {
      val uri: String = params.getTextDocument.getUri
      Common.isViperSourceFile(uri).thenAccept(isViperFile => {
        if (true) {
          //notify client
          Coordinator.client.notifyFileOpened(uri)
          if (!Coordinator.files.contains(uri)) {
            //create new task for opened file
            val manager = new FileManager(uri)
            Coordinator.files += (uri -> manager)
          }
        }
      })
    } catch {
      case e: Throwable => Log.debug("Error handling TextDocument opened", e)
    }
  }

  @JsonNotification("workspace/didChangeConfiguration")
  def onDidChangeConfig(params: DidChangeConfigurationParams): Unit = {
    println("On config change")
    Log.lowLevel("check if restart needed")
    try {
      if (Coordinator.verifier == null) {
        Log.info("Change Backend: from 'No Backend' to Silicon")
        Coordinator.backend = BackendProperties(
                                "ViperServer Silicon", "Silicon", null, null,
                                5000, null, 5000, null)
        Coordinator.client.notifyBackendStarted(BackendStartedParams("Silicon"))
      } else {
        Log.log("No need to restart backend. It is still the same", LogLevel.Debug)
      }
    } catch {
      case e: Throwable => Log.debug("Error handling swap backend request: " + e)
    }
  }

  @JsonNotification("textDocument/didChange")
  def onDidChangeDocument(params: DidChangeTextDocumentParams): Unit = {
    println("On changing document")
    val manager_opt = Coordinator.files.get(params.getTextDocument.getUri)
    val manager: FileManager = manager_opt.getOrElse(return)
    manager.symbolInformation = ArrayBuffer()
    manager.definitions = ArrayBuffer()
  }

  @JsonRequest("textDocument/documentSymbol")
  def onGetDocumentSymbol(params: DocumentSymbolParams): CFuture[Array[SymbolInformation]] = {
    var symbolInfo_list: Array[SymbolInformation] = Array()
    val manager_opt = Coordinator.files.get(params.getTextDocument.getUri)
    val manager = manager_opt.getOrElse(return CFuture.completedFuture(symbolInfo_list))
    symbolInfo_list = manager.symbolInformation.toArray
    CFuture.completedFuture(symbolInfo_list)
  }

  @JsonRequest("textDocument/definition")
  def onGetDefinition(params: TextDocumentPositionParams): CFuture[Location] = {
    Log.log("Handling definitions request for params: " + params.toString, LogLevel.Debug)
    val document = params.getTextDocument
    val pos = params.getPosition
    val manager_opt = Coordinator.files.get(params.getTextDocument.getUri)

    manager_opt match {
      case Some(manager) =>
        Log.log("Found verification task for URI " + document.getUri, LogLevel.LowLevelDebug)
        Coordinator.client.requestIdentifier(pos).thenApply(word => {
          Log.log("Got word: " + word, LowLevelDebug)
          def hasGlobalScope(d: Definition) = d.scope == null
          def isPosInScope(d: Definition) = hasGlobalScope(d) ||
            (Common.comparePosition(d.scope.getStart, pos) <= 0 &&
              Common.comparePosition(d.scope.getEnd, pos) >= 0)
          val defs_in_scope = manager.definitions.filter(isPosInScope)
          val matching_def = defs_in_scope.find(d => word == d.name).getOrElse(return null)
          val def_range = new Range(matching_def.code_location, matching_def.code_location)
          new Location(document.getUri, def_range)
        })
      case None =>
        // No definition found - maybe it's a keyword.
        val e = s"Verification task not found for URI ${document.getUri}"
        Log.debug(e)
        CFuture.failedFuture(new Throwable(e)) // needs to return some CF.
    }
  }

  @JsonNotification("textDocument/didClose")
  def onDidCloseDocument(params: DidCloseTextDocumentParams): Unit = {
    println("On closing document")
    try {
      val uri = params.getTextDocument.getUri
      Common.isViperSourceFile(uri).thenAccept(isViperFile => {
        if (isViperFile) Coordinator.client.notifyFileClosed(uri)
      })
    } catch {
      case _: Throwable => Log.debug("Error handling TextDocument opened")
    }
  }

  @JsonRequest(value = "shutdown")
  def onShutdown(): CFuture[AnyRef] = {
    println("shutdown")
    received_shutdown = true
    CFuture.completedFuture(null)
  }

  @JsonNotification(value = "exit")
  def onExit(): Unit = {
    println("exit")
    Coordinator.verifier.stop()
    if(received_shutdown) sys.exit(0) else sys.exit(1)
  }

//  @JsonRequest("textDocument/completion")
//  def completion(params: CompletionParams): CFuture[CompletionList] = {
//    val tsItem = new CompletionItem("Do the completion for Viper!!")
//    tsItem.setKind(CompletionItemKind.Text)
//    tsItem.setData(1)
//    val completions = new CompletionList(List(tsItem).asJava)
//    CFuture.completedFuture(completions)
//  }
//
//  @JsonRequest("completionItem/resolve")
//  def completionItemResolve(item: CompletionItem): CFuture[CompletionItem] = {
//    val data: Object = item.getData
//    data match {
//      case n: JsonPrimitive if n.getAsInt == 1 =>
//        item.setDetail("TypeScript details")
//        item.setDocumentation("TypeScript documentation")
//      case n: JsonPrimitive if n.getAsInt == 2 =>
//        item.setDetail("JavaScript details")
//        item.setDocumentation("JavaScript documentation")
//      case _ =>
//        item.setDetail(s"${data.toString} is instance of ${data.getClass}")
//    }
//    CFuture.completedFuture(item)
//  }
}

class CustomReceiver extends StandardReceiver {

  @JsonNotification(S2C_Commands.FileClosed)
  def onFileClosed(uri: String): Unit = {
    println("On closing file")
    val manager_opt = Coordinator.files.get(uri)
    val manager = manager_opt.getOrElse(return)
    manager.resetDiagnostics()
    Coordinator.files -= uri
  }

  @JsonRequest(C2S_Commands.RemoveDiagnostics)
  def onRemoveDiagnostics(uri: String): CFuture[Boolean] = {
    val manager_opt = Coordinator.files.get(uri)
    val manager = manager_opt.getOrElse(return CFuture.completedFuture(false))
    manager.resetDiagnostics()
    CFuture.completedFuture(true)
  }

  @JsonRequest("GetLanguageServerUrl")
  def onGetServerUrl(): CFuture[String] = {
    CFuture.completedFuture(Coordinator.getAddress)
  }

  @JsonNotification(C2S_Commands.StartBackend)
  def onStartBackend(backendName: String): Unit = {
    println("Starting ViperServeService")
    try {
      Coordinator.verifier = new ViperServerService(Array())
      Coordinator.verifier.setReady(Coordinator.backend)
    } catch {
      case e: Throwable => Log.debug("Error handling swap backend request: " + e)
    }
  }

  @JsonNotification(C2S_Commands.SwapBackend)
  def onSwapBackend(backendName: String): Unit = {
    try {
      val b = BackendProperties(
              "new Backend", backendName, null, null,
                      5000, null, 5000, null)
      Coordinator.verifier.swapBackend(b)
    } catch {
      case e: Throwable => Log.debug("Error handling swap backend request: " + e)
    }
  }

  @JsonRequest(C2S_Commands.RequestBackendNames)
  def onGetNames(backendName: String): CFuture[Array[String]] = {
    CFuture.completedFuture(Array("Silicon", "Carbon"))
  }


  @JsonNotification(C2S_Commands.StopBackend)
  def onBackendStop(backendName: String): Unit= {
    Coordinator.verifier.setStopping()
    Coordinator.verifier.setStopped()
  }

  @JsonNotification(C2S_Commands.Verify)
  def onVerify(data: VerifyRequest): Unit = {
    //it does not make sense to reverify if no changes were made and the verification is already running
    if (Coordinator.canVerificationBeStarted(data.uri, data.manuallyTriggered)) {
//      Settings.workspace = data.workspace
      //stop all other verifications because the backend crashes if multiple verifications are run in parallel
      Coordinator.stopAllRunningVerifications().thenAccept(_ => {
        println("Verifications stopped successfully")
//        Coordinator.executedStages = ArrayBuffer()
        Log.log("start or restart verification", LogLevel.Info)

        val manager = Coordinator.files.getOrElse(data.uri, return)
        val hasVerificationstarted = manager.startVerification(data.manuallyTriggered)

        Log.log("Verification Started", LogLevel.Info)
        if (!hasVerificationstarted) {
          Coordinator.client.notifyVerificationNotStarted(data.uri)
        }
      }).exceptionally(_ => {
        Log.debug("Error handling verify request")
        Coordinator.client.notifyVerificationNotStarted(data.uri)
        null //java void
      })
    } else {
      Log.log("The verification cannot be started.", LogLevel.Info)
      Coordinator.client.notifyVerificationNotStarted(data.uri)
    }
  }

  @JsonNotification(C2S_Commands.FlushCache)
  def onFlushCache(file: String): Unit = {
    println("flushing cache...")
    val file_arg: Option[String] = if(file == null) Option.empty else Some(file)
    Coordinator.verifier.flushCache(file_arg)
  }

  @JsonNotification(C2S_Commands.StopVerification)
  def onStopVerification(uri: String): CFuture[Boolean] = {
    println("on stopping verification")
    try {
      val manager = Coordinator.files.getOrElse(uri, return CFuture.completedFuture(false))
        manager.abortVerification().thenApply((success) => {
          val params = StateChangeParams(Ready.id, verificationCompleted = 0, verificationNeeded = 0, uri = uri)
          Coordinator.sendStateChangeNotification(params, Some(manager))
          true
        })
    } catch {
      case e: Throwable =>
        Log.debug("Error handling stop verification request (critical): " + e);
        CFuture.completedFuture(false)
    }
  }

  override def connect(client: LanguageClient): Unit = {
    println("Connecting plugin client.")
    val c = client.asInstanceOf[IdeLanguageClient]
    Coordinator.client = c
  }
}
