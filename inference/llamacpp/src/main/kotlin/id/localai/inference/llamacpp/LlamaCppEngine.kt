package id.localai.inference.llamacpp

import android.os.Debug
import android.os.SystemClock
import id.localai.inference.api.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class LlamaCppEngine(
    @OptIn(ExperimentalCoroutinesApi::class)
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) : InferenceEngine {

    private val _state = MutableStateFlow(EngineState.UNLOADED)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _activeConfig = MutableStateFlow<ModelConfig?>(null)
    override val activeConfig: StateFlow<ModelConfig?> = _activeConfig.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)
    private var lastColdLoadMs: Long = 0L

    override suspend fun load(model: ModelConfig): Result<Long> = withContext(inferenceDispatcher) {
        val file = File(model.ggufPath)
        if (!file.exists()) {
            _state.value = EngineState.UNLOADED
            return@withContext Result.failure(IllegalArgumentException("File GGUF tidak ditemukan: ${model.ggufPath}"))
        }

        _state.value = EngineState.LOADING
        val startMs = SystemClock.elapsedRealtime()
        val ok = LlamaBridge.nativeLoadModel(
            ggufPath = model.ggufPath,
            projectorPath = model.projectorPath,
            contextTokens = model.contextTokens,
            cpuThreads = model.cpuThreads
        )
        val elapsed = SystemClock.elapsedRealtime() - startMs

        if (ok) {
            lastColdLoadMs = elapsed
            _activeConfig.value = model
            _state.value = EngineState.READY
            Result.success(elapsed)
        } else {
            _activeConfig.value = null
            _state.value = EngineState.UNLOADED
            Result.failure(IllegalStateException("Model ini gagal dimuat atau melebihi memori yang tersedia."))
        }
    }

    override fun generate(request: InferenceRequest): Flow<InferenceEvent> = callbackFlow {
        val config = _activeConfig.value
        if (config == null || _state.value == EngineState.UNLOADED) {
            trySend(InferenceEvent.Failed("Model belum dimuat ke memori."))
            close()
            return@callbackFlow
        }

        cancelRequested.set(false)
        _state.value = EngineState.PREPARING
        trySend(InferenceEvent.Preparing)

        if (request.imagePath != null) {
            _state.value = EngineState.VISION_ENCODING
            trySend(InferenceEvent.VisionEncoding)
        }

        val formattedPrompt = formatChatMlPrompt(request.messages)
        val prefillTokenEstimate = (formattedPrompt.length / 4).coerceAtLeast(1)
        val startMs = SystemClock.elapsedRealtime()
        var firstTokenMs = 0L
        var emittedTokens = 0
        val fullBuilder = StringBuilder()

        // Coalescing buffer 40ms sesuai DESIGN.md bagian 5.4
        val pendingChunk = StringBuilder()
        var lastFlushMs = SystemClock.elapsedRealtime()

        _state.value = EngineState.GENERATING

        val status = LlamaBridge.nativeGenerateStream(
            formattedPrompt = formattedPrompt,
            imagePath = request.imagePath,
            maxOutputTokens = request.maxOutputTokens,
            temperature = request.temperature,
            callback = object : NativeStreamCallback {
                override fun onTokenPiece(piece: String): Boolean {
                    if (cancelRequested.get()) return false
                    val now = SystemClock.elapsedRealtime()
                    if (firstTokenMs == 0L) {
                        firstTokenMs = (now - startMs).coerceAtLeast(1L)
                    }
                    emittedTokens++
                    fullBuilder.append(piece)
                    pendingChunk.append(piece)

                    if (now - lastFlushMs >= 40L) {
                        trySend(InferenceEvent.TextDelta(pendingChunk.toString()))
                        pendingChunk.clear()
                        lastFlushMs = now
                    }
                    return !cancelRequested.get()
                }
            }
        )

        if (pendingChunk.isNotEmpty()) {
            trySend(InferenceEvent.TextDelta(pendingChunk.toString()))
            pendingChunk.clear()
        }

        val totalMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
        val ttft = if (firstTokenMs > 0L) firstTokenMs else totalMs
        val decodeDurationSec = ((totalMs - ttft).coerceAtLeast(1L)) / 1000f
        val prefillDurationSec = (ttft.coerceAtLeast(1L)) / 1000f
        val pssMb = (Debug.getPss() / 1024).toInt()

        val stats = RunStats(
            modelId = config.id,
            coldLoadMs = lastColdLoadMs,
            ttftMs = ttft,
            prefillTokens = prefillTokenEstimate,
            decodeTokens = emittedTokens,
            prefillTokPerSec = prefillTokenEstimate / prefillDurationSec,
            decodeTokPerSec = emittedTokens / decodeDurationSec,
            peakPssMb = pssMb,
            cpuThreads = config.cpuThreads,
            contextTokens = config.contextTokens
        )

        _state.value = EngineState.READY

        when {
            cancelRequested.get() || status == -99 -> {
                trySend(InferenceEvent.Stopped(fullBuilder.toString(), stats))
            }
            status < 0 -> {
                trySend(InferenceEvent.Failed("Decoding dihentikan karena batas memori atau konteks penuh."))
            }
            else -> {
                trySend(InferenceEvent.Completed(stats))
            }
        }

        close()
        awaitClose {
            LlamaBridge.nativeCancel()
        }
    }.flowOn(inferenceDispatcher)

    override suspend fun cancel() {
        cancelRequested.set(true)
        LlamaBridge.nativeCancel()
    }

    override suspend fun unload() = withContext(inferenceDispatcher) {
        cancelRequested.set(true)
        LlamaBridge.nativeUnload()
        _activeConfig.value = null
        _state.value = EngineState.UNLOADED
    }

    /**
     * Format ChatML Qwen3.5 non-thinking default sesuai PRD & ARCHITECTURE bagian 5.
     */
    private fun formatChatMlPrompt(messages: List<ChatTurn>): String {
        val sb = StringBuilder()
        for (turn in messages) {
            val roleTag = when (turn.role) {
                MessageRole.SYSTEM -> "system"
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
            }
            sb.append("<|im_start|>").append(roleTag).append("\n")
            sb.append(turn.content.trim()).append("<|im_end|>\n")
        }
        // Non-thinking default: langsung tutup blok think kosong agar model 2B tidak masuk thinking loop
        sb.append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        return sb.toString()
    }
}
