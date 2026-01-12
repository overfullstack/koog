package com.salesforce.graphalow.agent

import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeAppendPrompt
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMRequestStructured
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.structure.StructuredResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("Work_Type_Group_Response")
@LLMDescription("Outputs_the_LLM_Response_to_determine_work_type_group")
data class WorkTypeGroupResponse(
  @property:LLMDescription("The Id of the work type Group") val Id: String,
  @property:LLMDescription("The name of the work type Group") val name: String,
  @property:LLMDescription("Follow Up Question - Optional") val followUpQuestion: String,
)

@Serializable
@SerialName("Work_Type_Group_Response")
@LLMDescription("Outputs_the_LLM_Response_to_determine_work_type_group")
data class AppointmentTypeResponse(
    @property:LLMDescription("The label of the appointment type") val label: String,
    @property:LLMDescription("The value of the appointment type") val value: String,
    @property:LLMDescription("Follow Up Question - Optional") val followUpQuestion: String,
)

@Serializable
@SerialName("Time_Slot_Response")
@LLMDescription("Outputs_the_LLM_Response_to_determine_time_slot")
data class TimeSlotResponse(
    @property:LLMDescription("The start time of the timeslot. Not Found if not present") val startTime: String,
    @property:LLMDescription("The end time of the timeslot. Not Found if not present") val endTime: String,
    @property:LLMDescription("The service resource ID of the associated slot. Not found if not present.") val serviceResourceId: String,
    @property:LLMDescription("Follow Up Question - Optional") val followUpQuestion: String,
)

@Serializable
@SerialName("Confirmation_Object")
@LLMDescription("Outputs_the_LLM_Response_to_confirm")
data class ConfirmationResponse(
    @property:LLMDescription("Only 2 Permissable values - yes or no") val confirm: String,
)

object SchedulerAgentStrategy {

  val schedulerAIAgentStrategy =
    strategy("Create a service appointment.") {

        val nodeStartInput by
        nodeAppendPrompt<String>(name = "Serice_Appointmentt_Input_Start") {
            var question =
                "Hello! How can i help you?"
            println(question)
            val input = readln()
            system(question)
            user(input)
        }

        val processQuery by subgraphWithTask<String, String>(
            tools = SchedulerAgentTools.CustomTools().asTools(),
            assistantResponseRepeatMax = 5,
        ) { userQuery ->
            """
    You are a helpful assistant that can answer questions about various topics.
    Please help with the following query:
    $userQuery
    """
        }

        val workTypeSubgraph by
        subgraph<String, String>(
            "Work_type_subgraph",
            tools = SchedulerAgentTools.CustomTools().asTools(),
        ) {
            var workTypeGroupFound = false
            var firstIteration = true
            var resultConfirmed = false

            // Define nodes for the strategy
            val nodeStartPrompt by
            nodeAppendPrompt<String>(name = "Work_Type_Group_Prompt") {
                system(
                    "'Role: You are a specialized Assistant for Work Type Groups.\n" +
                            "\n" +
                            "Task: Analyze the provided criteria and identify the single most relevant Work Type Group ID and its corresponding Name.\n" +
                            "\n" +
                            "Constraints: The work type group should match the one of the output which has been returned by the tool call above.\n" +
                            "\n" +
                            "Uniqueness: You must output exactly ONE Work Type Group. Do not provide a list or options.\n" +
                            "\n" +
                            "Strict Matching: Only select an ID if it matches the criteria with 100% high confidence.\n" +
                            "\n" +
                            "Fallback: If no existing Work Type Group matches the criteria, the Id should be 'Not Found', and you should ask a " +
                            "follow up for the user which makes the system conversational.\n"
                )
            }

            val nodeUserInputPrompt by
            node<WorkTypeGroupResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt
                    firstIteration = false

                    prompt =
                        prompt(existingPrompt) {
                            var questionString = "What type of appointment are you looking for?"
                            println(questionString)
                            system(
                                questionString
                            )
                            val input = readln()
                            user(input)
                        }
                }
                input.followUpQuestion
            }

            val nodeSendInput by nodeLLMRequest()
            val nodeGetLLMResponse by nodeLLMRequestStructured<WorkTypeGroupResponse>(name = "response-node")
            val nodeGetLLMConfirmResponse by nodeLLMRequestStructured<ConfirmationResponse>(name = "confirm_llm_node")

            val nodeAskAnotherQuestion by
            node<WorkTypeGroupResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt

                    prompt =
                        prompt(existingPrompt) {
                            system(
                                "You are a conversational agent. Make sure the conversation is a follow up to the previous user input."
                            )
                            system(input.followUpQuestion)
                            println(input.followUpQuestion)
                            val userinput = readln()
                            user(userinput)
                        }
                }
                "Taken User Input"
            }

            val processResult by
            node<Result<StructuredResponse<WorkTypeGroupResponse>>, WorkTypeGroupResponse> { result ->
                when {
                    result.isSuccess -> {
                        var workTypeGroup = result.getOrNull()?.component1()
                        println(workTypeGroup)
                        if (workTypeGroup?.Id.equals("Not Found")) {
                            workTypeGroupFound = false

                            workTypeGroup
                        } else {
                            var id: String? = workTypeGroup?.Id
                            SchedulerAgentTools.dynamicEnv["workTypeGroupID"] = id as String
                            workTypeGroupFound = true
                            workTypeGroup
                        }
                    }

                    result.isFailure -> {
                        "Failed to get structured forecast: ${result.exceptionOrNull()?.message}"
                    }

                    else -> "Unknown result state"
                } as WorkTypeGroupResponse
            }

            val processConfirmation by
            node<Result<StructuredResponse<ConfirmationResponse>>, String> { result ->
                when {
                    result.isSuccess -> {
                        var confirmationRecord = result.getOrNull()?.component1()
                        if (confirmationRecord?.confirm.equals("yes")) {
                            resultConfirmed = true

                            confirmationRecord?.confirm
                        } else {
                            confirmationRecord?.confirm
                        }
                    }

                    else -> "Unknown result state"
                } as String
            }

            val nodeGetUserConfirmation by
            node<WorkTypeGroupResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt

                    prompt =
                        prompt(existingPrompt) {
                            var confirmationString = "Are you sure you want to book the following appointment topic - " + input.name + " ?"
                            println(confirmationString)
                            system(
                                confirmationString
                            )
                            val input = readln()
                            user(input)
                        }
                }
                input.followUpQuestion
            }



            val nodeExecuteTool by nodeExecuteTool()
            val nodeSendToolResult by nodeLLMSendToolResult()

            // Define edges between nodes
            // Start -> Send input
            edge(nodeStart forwardTo nodeStartPrompt)
            edge(nodeStartPrompt forwardTo nodeSendInput)

            // Send input -> Finish
            edge((nodeSendInput forwardTo nodeGetLLMResponse) onAssistantMessage { true })

            // Send input -> Execute tool
            edge((nodeSendInput forwardTo nodeExecuteTool) onToolCall { true })

            // Execute tool -> Send the tool result
            edge(nodeExecuteTool forwardTo nodeSendToolResult)

            // Send the tool result -> finish
            edge((nodeSendToolResult forwardTo nodeGetLLMResponse) onAssistantMessage { true })

            edge(nodeGetLLMResponse forwardTo processResult)
            edge((processResult forwardTo nodeGetUserConfirmation) onCondition { workTypeGroupFound })
            edge(nodeGetUserConfirmation forwardTo nodeGetLLMConfirmResponse)

            edge(nodeGetLLMConfirmResponse forwardTo processConfirmation)

            edge((processConfirmation forwardTo nodeFinish) onCondition { resultConfirmed })
            edge((processConfirmation forwardTo nodeGetLLMResponse) onCondition { !resultConfirmed } )


            edge((processResult forwardTo nodeAskAnotherQuestion) onCondition { !workTypeGroupFound && !firstIteration })
            edge((processResult forwardTo nodeUserInputPrompt) onCondition { !workTypeGroupFound && firstIteration })

            edge(nodeAskAnotherQuestion forwardTo nodeGetLLMResponse)
            edge(nodeUserInputPrompt forwardTo nodeGetLLMResponse)

        }

        val AppointmentTypeSubgraph by
        subgraph<String, String>(
            "Appointment type subgraph",
            tools = SchedulerAgentTools.CustomTools().asTools(),
        ) {
            var appointmentTypeFound = false
            var firstIteration = true
            var resultConfirmed = false

            // Define nodes for the strategy
            val nodeStartPrompt by
            nodeAppendPrompt<String>(name = "Work_Type_Group_Prompt") {
                system(
                    "'Role: You are a specialized Assistant for selecting the Appointment Type.\n" +
                            "\n" +
                            "Task: Analyze the provided criteria and identify the single most relevant label and its corresponding value.\n" +
                            "\n" +
                            "Constraints:\n" +
                            "\n" +
                            "Uniqueness: You must output exactly ONE Appointment Type. Do not provide a list or options.\n" +
                            "\n" +
                            "Strict Matching: Only select an Appointment type if it matches the criteria with 100% high confidence.\n" +
                            "\n" +
                            "Fallback: If no existing Appointment Type matches the criteria, the label should be 'Not Found', and you should ask a " +
                            "follow up for the user which makes the system conversational.\n"
                )
            }

            val nodeUserInputPrompt by
            node<AppointmentTypeResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt
                    firstIteration = false

                    prompt =
                        prompt(existingPrompt) {
                            var questionString = "What mode of appointment would you like to book?"
                            println(questionString)
                            system(
                                questionString
                            )
                            val input = readln()
                            user(input)
                        }
                }
                input.followUpQuestion
            }

            val nodeSendInput by nodeLLMRequest()
            val nodeGetLLMResponse by nodeLLMRequestStructured<AppointmentTypeResponse>(name = "response-node")
            val nodeGetLLMConfirmResponse by nodeLLMRequestStructured<ConfirmationResponse>(name = "confirm_llm_node")

            val nodeAskAnotherQuestion by
            node<AppointmentTypeResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt

                    prompt =
                        prompt(existingPrompt) {
                            system(
                                "You are a conversational agent. Make sure the conversation is a follow up to the previous user input."
                            )
                            system(input.followUpQuestion)
                            println(input.followUpQuestion)
                            val userinput = readln()
                            user(userinput)
                        }
                }
                "Taken User Input"
            }

            val processResult by
            node<Result<StructuredResponse<AppointmentTypeResponse>>, AppointmentTypeResponse> { result ->
                when {
                    result.isSuccess -> {
                        var appointmentType = result.getOrNull()?.component1()
                        println(appointmentType)
                        if (appointmentType?.label.equals("Not Found")) {
                            appointmentTypeFound = false
                            appointmentType
                        } else {
                            SchedulerAgentTools.dynamicEnv["appointmentType"] = appointmentType?.label as String
                            appointmentTypeFound = true
                            appointmentType
                        }
                    }
                    else -> "Unknown result state"
                } as AppointmentTypeResponse
            }

            val processConfirmation by
            node<Result<StructuredResponse<ConfirmationResponse>>, String> { result ->
                when {
                    result.isSuccess -> {
                        var confirmationRecord = result.getOrNull()?.component1()
                        if (confirmationRecord?.confirm.equals("yes")) {
                            resultConfirmed = true

                            confirmationRecord?.confirm
                        } else {
                            confirmationRecord?.confirm
                        }
                    }

                    else -> "Unknown result state"
                } as String
            }

            val nodeGetUserConfirmation by
            node<AppointmentTypeResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt

                    prompt =
                        prompt(existingPrompt) {
                            var confirmationString = "Are you sure you want to book the following appointment mode - " + input.label + " ?"
                            println(confirmationString)
                            system(
                                confirmationString
                            )
                            val input = readln()
                            user(input)
                        }
                }
                input.followUpQuestion
            }

            val nodeExecuteTool by nodeExecuteTool()
            val nodeSendToolResult by nodeLLMSendToolResult()

            // Define edges between nodes
            // Start -> Send input
            edge(nodeStart forwardTo nodeStartPrompt)
            edge(nodeStartPrompt forwardTo nodeSendInput)

            // Send input -> Finish
            edge((nodeSendInput forwardTo nodeGetLLMResponse) onAssistantMessage { true })

            // Send input -> Execute tool
            edge((nodeSendInput forwardTo nodeExecuteTool) onToolCall { true })

            // Execute tool -> Send the tool result
            edge(nodeExecuteTool forwardTo nodeSendToolResult)

            // Send the tool result -> finish
            edge((nodeSendToolResult forwardTo nodeGetLLMResponse) onAssistantMessage { true })

            edge(nodeGetLLMResponse forwardTo processResult)
            edge((processResult forwardTo nodeGetUserConfirmation) onCondition { appointmentTypeFound })
            edge(nodeGetUserConfirmation forwardTo nodeGetLLMConfirmResponse)

            edge(nodeGetLLMConfirmResponse forwardTo processConfirmation)

            edge((processConfirmation forwardTo nodeFinish) onCondition { resultConfirmed })
            edge((processConfirmation forwardTo nodeGetLLMResponse) onCondition { !resultConfirmed } )


            edge((processResult forwardTo nodeAskAnotherQuestion) onCondition { !appointmentTypeFound && !firstIteration })
            edge((processResult forwardTo nodeUserInputPrompt) onCondition { !appointmentTypeFound && firstIteration })

            edge(nodeAskAnotherQuestion forwardTo nodeGetLLMResponse)
            edge(nodeUserInputPrompt forwardTo nodeGetLLMResponse)

        }

        val serviceTerritorySubgraph by
        subgraph<String, String>(
            "Service territory subgraph",
            tools = SchedulerAgentTools.CustomTools().asTools(),
        ) {
            var workTypeGroupFound = false
            var firstIteration = true
            var resultConfirmed = false

            // Define nodes for the strategy
            val nodeStartPrompt by
            nodeAppendPrompt<String>(name = "Service_Territory_Prompt") {
                system(
                    "'Role: You are a specialized Assistant for Service Teritory.\n" +
                            "\n" +
                            "Task: Analyze the provided criteria and identify the single most relevant Service Territory and its corresponding Name.\n" +
                            "\n" +
                            "Constraints:\n" +
                            "\n" +
                            "Uniqueness: You must output exactly ONE Work. Do not provide a list or options.\n" +
                            "\n" +
                            "Strict Matching: Only select an ID if it matches the criteria with 100% confidence.\n" +
                            "\n" +
                            "Fallback: If no existing Work Type Group matches the criteria, the Id should be 'Not Found', and you should ask a " +
                            "follow up for the user which makes the system conversational.\n"
                )
            }


            val nodeUserInputPrompt by
            node<WorkTypeGroupResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt
                    firstIteration = false

                    prompt =
                        prompt(existingPrompt) {
                            var questionString = "Which branch would you like to choose?"
                            println(questionString)
                            system(
                                questionString
                            )
                            val input = readln()
                            user(input)
                        }
                }
                input.followUpQuestion
            }

            val nodeSendInput by nodeLLMRequest()
            val nodeGetLLMResponse by nodeLLMRequestStructured<WorkTypeGroupResponse>(name = "response-node")
            val nodeGetLLMConfirmResponse by nodeLLMRequestStructured<ConfirmationResponse>(name = "confirm_llm_node")

            val nodeAskAnotherQuestion by
            node<WorkTypeGroupResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt

                    prompt =
                        prompt(existingPrompt) {
                            system(
                                "You are a conversational agent. Make sure the conversation is a follow up to the previous user input."
                            )
                            system(input.followUpQuestion)
                            println(input.followUpQuestion)
                            val userinput = readln()
                            user(userinput)
                        }
                }
                "Taken User Input"
            }

            val processResult by
            node<Result<StructuredResponse<WorkTypeGroupResponse>>, WorkTypeGroupResponse> { result ->
                when {
                    result.isSuccess -> {
                        var workTypeGroup = result.getOrNull()?.component1()
                        println(workTypeGroup)
                        if (workTypeGroup?.Id.equals("Not Found")) {
                            workTypeGroupFound = false

                            workTypeGroup
                        } else {
                            var id: String? = workTypeGroup?.Id
                            SchedulerAgentTools.dynamicEnv["serviceTerritoryID"] = id as String
                            workTypeGroupFound = true
                            workTypeGroup
                        }
                    }

                    result.isFailure -> {
                        "Failed to get structured forecast: ${result.exceptionOrNull()?.message}"
                    }

                    else -> "Unknown result state"
                } as WorkTypeGroupResponse
            }

            val processConfirmation by
            node<Result<StructuredResponse<ConfirmationResponse>>, String> { result ->
                when {
                    result.isSuccess -> {
                        var confirmationRecord = result.getOrNull()?.component1()
                        if (confirmationRecord?.confirm.equals("yes")) {
                            resultConfirmed = true

                            confirmationRecord?.confirm
                        } else {
                            confirmationRecord?.confirm
                        }
                    }

                    else -> "Unknown result state"
                } as String
            }

            val nodeGetUserConfirmation by
            node<WorkTypeGroupResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt

                    prompt =
                        prompt(existingPrompt) {
                            var confirmationString = "Are you sure you want to book the following branch - " + input.name + " ?"
                            println(confirmationString)
                            system(
                                confirmationString
                            )
                            val input = readln()
                            user(input)
                        }
                }
                input.followUpQuestion
            }

            val nodeExecuteTool by nodeExecuteTool()
            val nodeSendToolResult by nodeLLMSendToolResult()

            // Define edges between nodes
            // Start -> Send input
            edge(nodeStart forwardTo nodeStartPrompt)
            edge(nodeStartPrompt forwardTo nodeSendInput)

            // Send input -> Finish
            edge((nodeSendInput forwardTo nodeGetLLMResponse) onAssistantMessage { true })

            // Send input -> Execute tool
            edge((nodeSendInput forwardTo nodeExecuteTool) onToolCall { true })

            // Execute tool -> Send the tool result
            edge(nodeExecuteTool forwardTo nodeSendToolResult)

            // Send the tool result -> finish
            edge((nodeSendToolResult forwardTo nodeGetLLMResponse) onAssistantMessage { true })

            edge(nodeGetLLMResponse forwardTo processResult)
            edge((processResult forwardTo nodeGetUserConfirmation) onCondition { workTypeGroupFound })
            edge(nodeGetUserConfirmation forwardTo nodeGetLLMConfirmResponse)

            edge(nodeGetLLMConfirmResponse forwardTo processConfirmation)

            edge((processConfirmation forwardTo nodeFinish) onCondition { resultConfirmed })
            edge((processConfirmation forwardTo nodeGetLLMResponse) onCondition { !resultConfirmed } )


            edge((processResult forwardTo nodeAskAnotherQuestion) onCondition { !workTypeGroupFound && !firstIteration })
            edge((processResult forwardTo nodeUserInputPrompt) onCondition { !workTypeGroupFound && firstIteration })

            edge(nodeAskAnotherQuestion forwardTo nodeGetLLMResponse)
            edge(nodeUserInputPrompt forwardTo nodeGetLLMResponse)

        }

        val timeSlotSubgraph by
        subgraph<String, String>(
            "Time Slot subgraph",
            tools = SchedulerAgentTools.CustomTools().asTools(),
        ) {
            var timeSlotFound = false
            var firstIteration = true
            var resultConfirmed = false
            var timeSlotResponse = null

            // Define nodes for the strategy
            val nodeStartPrompt by
            nodeAppendPrompt<String>(name = "Time_Slot_Prompt") {
                system(
                    "'Role: You are a specialized Assistant for Time slot.\n" +
                            "\n" +
                            "Task: Analyze the provided criteria and identify the single most relevant time slot start and end duration.\n" +
                            "\n" +
                            "Constraints:\n" +
                            "\n" +
                            "Uniqueness: You must output exactly ONE time slot. Do not provide a list or options.\n" +
                            "\n" +
                            "Strict Matching: Only select if it matches the criteria with 100% confidence.\n" +
                            "\n" +
                            "Fallback: If no existing timeslot matches the criteria, the Id should be 'Not Found', and you should ask a " +
                            "follow up for the user which makes the system conversational. Execute the tool to get the available time slots.\n"
                )
            }

            val nodeUserInputPrompt by
            node<TimeSlotResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt
                    firstIteration = false

                    prompt =
                        prompt(existingPrompt) {
                            var questionString = "When do you want to book your appointment?"
                            println(questionString)
                            system(
                                questionString
                            )
                            val input = readln()
                            user(input)
                        }
                }
                input.followUpQuestion
            }

            val nodeSendInput by nodeLLMRequest()

            val nodeVerifyTimeSlots by
            node<String, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt

                    prompt =
                        prompt(existingPrompt) {
                            var questionString = "Make sure the selected response matches one of the timeslots from the tool call. It has to match the start time and end time.  Do not guess the timezone unless the user tells the exact start time of the slot!!!!. There is no exception to this. The duration of the slot also cannot change." +
                                    " Do not make up your own timeslots. The startTime and endTime should exactly match one of the options. Whenever you display times to the user make sure you follow the following format - Date time for eg - 2nd Jan 2025 4:00 pm. Unless you are 100% sure do not populate the start time and end time in the object response." +
                                    "Use the UTC timezone."
                            system(
                                questionString
                            )
                        }
                }
                input
            }

            val nodeGetLLMResponse by nodeLLMRequestStructured<TimeSlotResponse>(name = "response-node")
            val nodeGetLLMConfirmResponse by nodeLLMRequestStructured<ConfirmationResponse>(name = "confirm_llm_node")

            val nodeAskAnotherQuestion by
            node<TimeSlotResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt

                    prompt =
                        prompt(existingPrompt) {
                            system(
                                "You are a conversational agent. Make sure the conversation is a follow up to the previous user input."
                            )
                            system(input.followUpQuestion)
                            println(input.followUpQuestion)
                            val userinput = readln()
                            user(userinput)
                        }
                }
                "Taken User Input"
            }

            val processResult by
            node<Result<StructuredResponse<TimeSlotResponse>>, TimeSlotResponse> { result ->
                when {
                    result.isSuccess -> {
                        var timeSlot = result.getOrNull()?.component1()
                        println(timeSlot)
                        if (timeSlot?.startTime.equals("Not Found")) {
                            timeSlotFound = false

                            timeSlot
                        } else {
                            var startTime: String? = timeSlot?.startTime
                            var endTime: String? = timeSlot?.endTime
                            SchedulerAgentTools.dynamicEnv["startTime"] = startTime as String
                            SchedulerAgentTools.dynamicEnv["endTime"] = endTime as String
                            SchedulerAgentTools.dynamicEnv["serviceResourceID"] = timeSlot?.serviceResourceId as String
                            timeSlotFound = true
                            timeSlot
                        }
                    }

                    result.isFailure -> {
                        "Failed to get structured forecast: ${result.exceptionOrNull()?.message}"
                    }

                    else -> "Unknown result state"
                } as TimeSlotResponse
            }

            val processConfirmation by
            node<Result<StructuredResponse<ConfirmationResponse>>, String> { result ->
                when {
                    result.isSuccess -> {
                        var confirmationRecord = result.getOrNull()?.component1()
                        if (confirmationRecord?.confirm.equals("yes")) {
                            resultConfirmed = true

                            confirmationRecord?.confirm
                        } else {
                            confirmationRecord?.confirm
                        }
                    }

                    else -> "Unknown result state"
                } as String
            }

            val nodeGetUserConfirmation by
            node<TimeSlotResponse, String> { input ->
                llm.writeSession {
                    // Wipes all previous messages from the history
                    val existingPrompt = prompt

                    prompt =
                        prompt(existingPrompt) {
                            var confirmationString = "Are you sure you want to book the following slot starting at - " + input.startTime + " and ending at " + input.endTime + " ?"
                            println(confirmationString)
                            system(
                                confirmationString
                            )
                            val input = readln()
                            user(input)
                        }
                }
                input.followUpQuestion
            }



            val nodeExecuteTool by nodeExecuteTool()
            val nodeSendToolResult by nodeLLMSendToolResult()

            // Define edges between nodes
            // Start -> Send input
            edge(nodeStart forwardTo nodeStartPrompt)
            edge(nodeStartPrompt forwardTo nodeSendInput)

            // Send input -> Finish
            edge((nodeSendInput forwardTo nodeGetLLMResponse) onAssistantMessage { true })

            // Send input -> Execute tool
            edge((nodeSendInput forwardTo nodeExecuteTool) onToolCall { true })

            // Execute tool -> Send the tool result
            edge(nodeExecuteTool forwardTo nodeSendToolResult)

            // Send the tool result -> finish
            edge((nodeSendToolResult forwardTo nodeVerifyTimeSlots) onAssistantMessage { true })
            edge(nodeVerifyTimeSlots forwardTo nodeGetLLMResponse)

            edge(nodeGetLLMResponse forwardTo processResult)
            edge((processResult forwardTo nodeGetUserConfirmation) onCondition { timeSlotFound && !firstIteration })
            edge((processResult forwardTo nodeUserInputPrompt) onCondition { timeSlotFound && firstIteration })
            edge(nodeGetUserConfirmation forwardTo nodeGetLLMConfirmResponse)

            edge(nodeGetLLMConfirmResponse forwardTo processConfirmation)

            edge((processConfirmation forwardTo nodeFinish) onCondition { resultConfirmed })
            edge((processConfirmation forwardTo nodeVerifyTimeSlots) onCondition { !resultConfirmed } )


            edge((processResult forwardTo nodeAskAnotherQuestion) onCondition { !timeSlotFound && !firstIteration })
            edge((processResult forwardTo nodeUserInputPrompt) onCondition { !timeSlotFound && firstIteration })

            edge(nodeAskAnotherQuestion forwardTo nodeVerifyTimeSlots)
            edge(nodeUserInputPrompt forwardTo nodeVerifyTimeSlots)

        }

        val bookApptSubgraph by
        subgraph<String, String>(
            "Book Appointment subgraph",
            tools = SchedulerAgentTools.CustomTools().asTools(),
        ) {
            // Define nodes for the strategy
            val nodeStartPrompt by
            nodeAppendPrompt<String>(name = "Book_Appointment_Prompt") {
                system(
                    "'Role: You are a specialized Assistant for Booking Appointment.\n" +
                            "\n" +
                            "Task: Use the tool to book an appointment and return success or failure.\n"
                )
            }

            val nodeSendInput by nodeLLMRequest()

            val nodeExecuteTool by nodeExecuteTool()
            val nodeSendToolResult by nodeLLMSendToolResult()

            // Define edges between nodes
            // Start -> Send input
            edge(nodeStart forwardTo nodeStartPrompt)
            edge(nodeStartPrompt forwardTo nodeSendInput)

            // Send input -> Execute tool
            edge((nodeSendInput forwardTo nodeExecuteTool) onToolCall { true })

            // Execute tool -> Send the tool result
            edge(nodeExecuteTool forwardTo nodeSendToolResult)

            // Send the tool result -> finish
            edge((nodeSendToolResult forwardTo nodeFinish) onAssistantMessage { true })

        }

        edge(nodeStart forwardTo nodeStartInput)
        edge(nodeStartInput forwardTo workTypeSubgraph)
        edge(workTypeSubgraph forwardTo AppointmentTypeSubgraph)
        edge(AppointmentTypeSubgraph forwardTo serviceTerritorySubgraph)
        edge(serviceTerritorySubgraph forwardTo timeSlotSubgraph)
        edge(timeSlotSubgraph forwardTo bookApptSubgraph)
        edge(bookApptSubgraph forwardTo nodeFinish)
    }
}
