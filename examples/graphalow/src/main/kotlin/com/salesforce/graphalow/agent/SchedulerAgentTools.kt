package com.salesforce.graphalow.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.salesforce.graphalow.CoreUtils.ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS
import com.salesforce.graphalow.CoreUtils.ASSERT_COMPOSITE_RESPONSE_SUCCESS
import com.salesforce.graphalow.IGNORE_HTTP_STATUS_UNSUCCESSFUL
import com.salesforce.graphalow.WAIT_HOOK
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepContainingHeader
import com.salesforce.revoman.output.ExeType

object SchedulerAgentTools {

   val dynamicEnv = mutableMapOf<String, String>()

  @LLMDescription("Tools for obtaining the work type group")
  class CustomTools : ToolSet {

    @Tool
    @LLMDescription("Gets a response of the work Type Groups available")
    fun getWorkType(): String {

      val pmCollectionPaths = "scheduler-e2e/Koog_Test.json"
      val pmEnvironmentPaths = listOf("scheduler-e2e/Koog_Test_Env.json")

      val rundown =
        ReVoman.revUp(
          Kick.configure()
            .templatePaths(pmCollectionPaths)
            .dynamicEnvironment(dynamicEnv)
            .environmentPaths(pmEnvironmentPaths)
            .haltOnFailureOfTypeExcept(
              ExeType.HTTP_STATUS,
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

      return lastStep.responseInfo?.get().toString()
    }


      @Tool
      @LLMDescription("Gets a response of the different Appointment Types")
      fun getAppointmentTypes(): String {

          val pmCollectionPaths = "scheduler-e2e/Koog_Test_Appt_Types.json"
          val pmEnvironmentPaths = listOf("scheduler-e2e/Koog_Test_Env.json")

          val rundown =
              ReVoman.revUp(
                  Kick.configure()
                      .templatePaths(pmCollectionPaths)
                      .dynamicEnvironment(dynamicEnv)
                      .environmentPaths(pmEnvironmentPaths)
                      .haltOnFailureOfTypeExcept(
                          ExeType.HTTP_STATUS,
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

          return lastStep.responseInfo?.get().toString()
      }

      @Tool
      @LLMDescription("Gets a response of the different Service Territories")
      fun getServiceTerritories(): String {

          val pmCollectionPaths = "scheduler-e2e/Koog_Test_Service_Territory.json"
          val pmEnvironmentPaths = listOf("scheduler-e2e/Koog_Test_Env.json")

          println(pmCollectionPaths)

          val rundown =
              ReVoman.revUp(
                  Kick.configure()
                      .templatePaths(pmCollectionPaths)
                      .dynamicEnvironment(dynamicEnv)
                      .environmentPaths(pmEnvironmentPaths)
                      .haltOnFailureOfTypeExcept(
                          ExeType.HTTP_STATUS,
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

          return lastStep.toString()
      }

      @Tool
      @LLMDescription("Gets a response of the the Time Slots")
      fun getTimeSlots(): String {

          val pmCollectionPaths = "scheduler-e2e/Koog_Test_Time_Slot.json"
          val pmEnvironmentPaths = listOf("scheduler-e2e/Koog_Test_Env.json")

          val rundown =
              ReVoman.revUp(
                  Kick.configure()
                      .templatePaths(pmCollectionPaths)
                      .dynamicEnvironment(dynamicEnv)
                      .environmentPaths(pmEnvironmentPaths)
                      .haltOnFailureOfTypeExcept(
                          ExeType.HTTP_STATUS,
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

          return lastStep.toString()
      }

      @Tool
      @LLMDescription("Tool to book an appointment")
      fun bookServiceAppt(): String {

          val pmCollectionPaths = "scheduler-e2e/Koog_Test_book_appt.json"
          val pmEnvironmentPaths = listOf("scheduler-e2e/Koog_Test_Env.json")

          val rundown =
              ReVoman.revUp(
                  Kick.configure()
                      .templatePaths(pmCollectionPaths)
                      .dynamicEnvironment(dynamicEnv)
                      .environmentPaths(pmEnvironmentPaths)
                      .haltOnFailureOfTypeExcept(
                          ExeType.HTTP_STATUS,
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

          return lastStep.toString()
      }
  }
}
