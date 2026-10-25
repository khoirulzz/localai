package id.localai.app.orchestrator

/**
 * Context size is the shared KV-cache window (prompt + generated tokens), not
 * an output-only limit. Keep a substantial answer allowance and a safety buffer.
 */
object OutputBudget {
    fun answerTokens(contextTokens: Int): Int =
        (contextTokens / 2).coerceIn(512, 4096).coerceAtMost((contextTokens - 384).coerceAtLeast(256))

    /** Thinking has its own bounded pass; if exhausted we force a final non-thinking answer. */
    fun thinkingTokens(contextTokens: Int, mode: String): Int {
        val answer = answerTokens(contextTokens)
        return when (mode) {
            "DEEP" -> (contextTokens * 0.38f).toInt().coerceAtMost(answer)
            "STANDARD" -> (contextTokens * 0.25f).toInt().coerceAtMost(answer)
            else -> answer
        }.coerceAtLeast(256)
    }

    fun promptSafetyTokens(contextTokens: Int): Int =
        (contextTokens / 16).coerceAtLeast(256)
}
