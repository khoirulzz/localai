package id.localai.inference.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class EngineState {
    UNLOADED,
    LOADING,
    READY,
    PREPARING,
    VISION_ENCODING,
    GENERATING
}

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT
}

data class ChatTurn(
    val role: MessageRole,
    val content: String
)

/** Format prompt yang dipakai model. Tiap keluarga model punya token giliran bicara sendiri. */
enum class PromptFormat {
    /** Qwen3 / Qwen3.5: ChatML dengan blok think kosong (mode non-thinking). */
    CHATML_QWEN,

    /** Gemma 4: `<|turn>role ... <turn|>` dengan kanal thought kosong. */
    GEMMA4
}

data class ModelConfig(
    val id: String,
    val ggufPath: String,
    val projectorPath: String? = null,
    val promptFormat: PromptFormat = PromptFormat.CHATML_QWEN,
    val contextTokens: Int = 2048,
    val cpuThreads: Int = 4
)

data class InferenceRequest(
    val messages: List<ChatTurn>,
    val imagePath: String? = null,
    val maxOutputTokens: Int = 512,
    val temperature: Float = 0.6f
)

data class RunStats(
    val modelId: String,
    val coldLoadMs: Long,
    val ttftMs: Long,
    val prefillTokens: Int,
    val decodeTokens: Int,
    val prefillTokPerSec: Float,
    val decodeTokPerSec: Float,
    val peakPssMb: Int,
    val cpuThreads: Int,
    val contextTokens: Int
)

sealed interface InferenceEvent {
    data object Preparing : InferenceEvent
    data object VisionEncoding : InferenceEvent
    data class TextDelta(val text: String) : InferenceEvent
    data class Completed(val stats: RunStats) : InferenceEvent
    data class Stopped(val partialText: String, val stats: RunStats) : InferenceEvent
    data class Failed(val cause: String) : InferenceEvent
}

interface InferenceEngine {
    val state: StateFlow<EngineState>
    val activeConfig: StateFlow<ModelConfig?>

    suspend fun load(model: ModelConfig): Result<Long>
    fun generate(request: InferenceRequest): Flow<InferenceEvent>
    suspend fun cancel()
    suspend fun unload()
}
