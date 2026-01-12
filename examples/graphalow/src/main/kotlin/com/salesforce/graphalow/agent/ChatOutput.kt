package com.salesforce.graphalow.agent

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Custom output channel for agent messages that should appear in the UI.
 * This bypasses System.out to avoid framework/tool noise.
 */
object ChatOutput {
    private val messageQueue: BlockingQueue<String> = LinkedBlockingQueue()
    
    /**
     * Print a message that should appear in the chat UI.
     * This replaces println() calls in the agent strategy.
     */
    fun println(message: String) {
        messageQueue.offer(message)
    }
    
    /**
     * Get the next message from the queue (non-blocking).
     * Returns null if no message is available.
     */
    fun poll(): String? {
        return messageQueue.poll()
    }
    
    /**
     * Get the next message from the queue (blocking).
     */
    fun take(): String {
        return messageQueue.take()
    }
}

