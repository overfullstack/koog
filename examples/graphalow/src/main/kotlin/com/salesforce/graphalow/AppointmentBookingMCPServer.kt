package com.salesforce.graphalow

import ai.koog.utils.io.SuitableForIO
import com.salesforce.graphalow.CoreUtils.ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS
import com.salesforce.graphalow.CoreUtils.ASSERT_COMPOSITE_RESPONSE_SUCCESS
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepContainingHeader
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A simple MCP server for testing purposes. This server provides a simple tool that returns a
 * greeting message.
 */
class AppointmentBookingMCPServer(private val port: Int) {
  private var serverJob: Job? = null
  private var isRunning = false

  /** Configures the MCP server with a simple greeting tool. */
  private fun configureServer(): Server {
    val server =
      Server(
        Implementation(name = "test-mcp-server", version = "0.1.0"),
        ServerOptions(
          capabilities =
            ServerCapabilities(
              prompts = ServerCapabilities.Prompts(listChanged = true),
              resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
              tools = ServerCapabilities.Tools(listChanged = true),
            )
        ),
      )

    // Add a simple greeting tool
    server.addTool(
      name = "greeting",
      description = "A simple greeting tool",
      inputSchema =
        Tool.Input(
          properties =
            buildJsonObject {
              putJsonObject("name") {
                put("type", "string")
                put("description", "A name to greet")
              }
              putJsonObject("title") {
                putJsonArray("anyOf") {
                  addJsonObject { put("type", "null") }
                  addJsonObject { put("type", "string") }
                }
                put("description", "Title to use in the greeting")
              }
            },
          required = listOf("name"),
        ),
    ) { request ->
      val name = request.arguments?.get("name")?.jsonPrimitive?.content
      val title = request.arguments?.get("title")?.jsonPrimitive?.content
      CallToolResult(
        content =
          listOf(TextContent("Hello, ${if (title.isNullOrEmpty()) "" else "$title "}$name!"))
      )
    }

    server.addTool(
      name = "Create Service Appointment",
      description =
        "Creates a new service appointment given appointment topic name and service territory name. Successful output would contain Service Appointment and Assigned Resource Id.",
      inputSchema =
        Tool.Input(
          properties =
            buildJsonObject {
              putJsonObject("appointmentTopicName") {
                put("type", "string")
                put("description", "Appointment topic name")
              }
              putJsonObject("serviceTerritoryName") {
                put("type", "string")
                put("description", "Service territory name")
              }
              putJsonObject("previousEnvironment") {
                put("type", "string")
                put(
                  "description",
                  "Optional: `mutableEnv` JSON property from previous `command_` call response",
                )
              }
            },
          required = listOf("appointmentTopicName", "serviceTerritoryName"),
        ),
    ) { request ->
      try {
        val appointmentTopicName =
          request.arguments?.get("appointmentTopicName")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("appointmentTopicName is required")
        val serviceTerritoryName =
          request.arguments!!["serviceTerritoryName"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("serviceTerritoryName is required")

        val pmCollectionPaths = "scheduler-e2e/Schedule Appointment E2E.postman_collection.json"
        val pmEnvironmentPaths = listOf("scheduler-e2e/scheduler-sdb14.postman_environment.json")

        // Build dynamic environment
        val dynamicEnv =
          mutableMapOf<String, String>(
            "stName" to serviceTerritoryName,
            "wtgName" to appointmentTopicName,
          )

        val rundown =
          ReVoman.revUp(
            Kick.configure()
              .templatePaths(pmCollectionPaths)
              .dynamicEnvironment(dynamicEnv)
              .environmentPaths(pmEnvironmentPaths)
              .haltOnFailureOfTypeExcept(
                com.salesforce.revoman.output.ExeType.HTTP_STATUS,
                afterStepContainingHeader(IGNORE_HTTP_STATUS_UNSUCCESSFUL),
              )
              .hooks(
                WAIT_HOOK,
                ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS,
                ASSERT_COMPOSITE_RESPONSE_SUCCESS,
              )
              .nodeModulesPath("js")
              .off()
          )

        val lastStep = rundown.stepReports.last()
        val saResponse = lastStep.responseInfo?.get()?.httpMsg?.body.toString()

        CallToolResult(content = listOf(TextContent(saResponse)))
      } catch (e: Exception) {
        CallToolResult(
          content =
            listOf(
              TextContent(
                """{"error": "${e.message ?: "Unknown error occurred in appointment scheduling"}"}"""
              )
            ),
          isError = true,
        )
      }
    }

    return server
  }

  /** Starts the MCP server on the specified port. */
  fun start() {
    if (isRunning) return

    serverJob =
      CoroutineScope(Dispatchers.SuitableForIO).launch {
        embeddedServer(CIO, host = "0.0.0.0", port = port) {
            mcp {
              return@mcp configureServer()
            }
          }
          .start(wait = true)
      }

    isRunning = true
    println("Test MCP server started on port $port")
  }

  /** Stops the MCP server. */
  fun stop() {
    if (!isRunning) return

    serverJob?.cancel()
    serverJob = null
    isRunning = false
    println("Test MCP server stopped")
  }
}

/**
 * Main function to run the Test MCP server. The server can be configured via the PORT environment
 * variable (default: 3000).
 */
fun main() {
  val port = System.getenv("PORT")?.toIntOrNull() ?: 3000
  val server = AppointmentBookingMCPServer(port)

  // Add shutdown hook for graceful shutdown
  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        println("\nShutting down Test MCP server...")
        server.stop()
      }
    )

  // Start the server
  server.start()

  // Keep the main thread alive
  try {
    Thread.currentThread().join()
  } catch (e: InterruptedException) {
    println("Main thread interrupted")
    server.stop()
  }
}
