package org.jetbrains.demo.agent

/**
 * ANSI color codes for colored terminal logging.
 * Each A2A component gets a distinct color for easy visual differentiation.
 */
object LogColors {
    // ANSI escape codes
    private const val RESET = "\u001B[0m"
    private const val BOLD = "\u001B[1m"

    // Colors
    private const val RED = "\u001B[31m"
    private const val GREEN = "\u001B[32m"
    private const val YELLOW = "\u001B[33m"
    private const val BLUE = "\u001B[34m"
    private const val MAGENTA = "\u001B[35m"
    private const val CYAN = "\u001B[36m"
    private const val WHITE = "\u001B[37m"

    // Bright colors
    private const val BRIGHT_RED = "\u001B[91m"
    private const val BRIGHT_GREEN = "\u001B[92m"
    private const val BRIGHT_YELLOW = "\u001B[93m"
    private const val BRIGHT_BLUE = "\u001B[94m"
    private const val BRIGHT_MAGENTA = "\u001B[95m"
    private const val BRIGHT_CYAN = "\u001B[96m"

    // Background colors
    private const val BG_GREEN = "\u001B[42m"
    private const val BG_BLUE = "\u001B[44m"
    private const val BG_WHITE = "\u001B[47m"
    private const val BLACK = "\u001B[30m"

    // Component-specific prefixes with colors
    val ORCHESTRATOR = "$BOLD$BRIGHT_MAGENTA[ORCHESTRATOR]$RESET"
    val CHAT = "$BOLD$BRIGHT_CYAN[CHAT]$RESET"
    val SESSION = "$CYAN[SESSION]$RESET"
    val ROUTE_PLANNER = "$BOLD$GREEN[ROUTE_PLANNER]$RESET"
    val POI_RESEARCHER = "$BOLD$YELLOW[POI_RESEARCHER]$RESET"
    val PLAN_COMPOSER = "$BOLD$BLUE[PLAN_COMPOSER]$RESET"
    val LLM = "$BRIGHT_RED[LLM]$RESET"
    val SERVER = "$BOLD$WHITE[SERVER]$RESET"
    val USER_INPUT = "$BOLD$BG_GREEN$BLACK[USER_INPUT]$RESET"

    // Utility functions for inline coloring
    fun magenta(text: String): String = "$BRIGHT_MAGENTA$text$RESET"
    fun cyan(text: String): String = "$BRIGHT_CYAN$text$RESET"
    fun green(text: String): String = "$GREEN$text$RESET"
    fun yellow(text: String): String = "$YELLOW$text$RESET"
    fun blue(text: String): String = "$BLUE$text$RESET"
    fun red(text: String): String = "$BRIGHT_RED$text$RESET"
    fun bold(text: String): String = "$BOLD$text$RESET"
    fun userInput(text: String): String = "$BG_GREEN$BLACK$text$RESET"

    // Banners
    fun orchestratorBanner(text: String): String = "$BOLD$BRIGHT_MAGENTA========== $text ==========$RESET"
    fun routePlannerBanner(text: String): String = "$BOLD$GREEN========== $text ==========$RESET"
    fun poiResearcherBanner(text: String): String = "$BOLD$YELLOW========== $text ==========$RESET"
    fun planComposerBanner(text: String): String = "$BOLD$BLUE========== $text ==========$RESET"
    fun serverBanner(text: String): String = "$BOLD$WHITE========== $text ==========$RESET"
    fun chatBanner(text: String): String = "$BOLD$BRIGHT_CYAN========== $text ==========$RESET"
}
