package com.salesforce.graphalow

import com.salesforce.revoman.input.config.HookConfig.Companion.post
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.PickUtils.afterStepContainingHeader

const val IGNORE_HTTP_STATUS_UNSUCCESSFUL = "ignoreHTTPStatusUnsuccessful"
val WAIT_HOOK = post(afterStepContainingHeader("isAsync"), { _, _ -> Thread.sleep(7000) })
