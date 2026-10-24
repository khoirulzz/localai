package id.localai.inference.llamacpp

import android.os.Debug
import android.os.SystemClock
import id.localai.inference.api.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

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

    /** True bila proyektor visi berhasil dimuat bersama model aktif. */
    val supportsVision: Boolean
        get() = _activeConfig.value != null && LlamaBridge.nativeSupportsVision()

    override suspend fun load(model: ModelConfig): Result<Long> = withContext(inferenceDispatcher) {
        val file = File(model.ggufPath)
        if (!file.exists()) {
            _state.value = EngineState.UNLOADED
            return@withContext Result.failure(IllegalArgumentException("File GGUF tidak ditemukan: ${model.ggufPath}"))
        }

        _state.value = EngineState.LOADING
        val startMs = SystemClock.elapsedRealtime()
        val ok = try {
            LlamaBridge.nativeLoadModel(
                ggufPath = model.ggufPath,
                projectorPath = model.projectorPath?.takeIf { File(it).exists() },
                contextTokens = model.contextTokens,
                cpuThreads = model.cpuThreads
            )
        } catch (e: UnsatisfiedLinkError) {
            _state.value = EngineState.UNLOADED
            return@withContext Result.failure(IllegalStateException("Pustaka native llama.cpp tidak termuat di perangkat ini."))
        }
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

    override fun generate(request: InferenceRequest): Flow<InferenceEvent> = channelFlow {
        val config = _activeConfig.value
        if (config == null || _state.value == EngineState.UNLOADED) {
            send(InferenceEvent.Failed("Model belum dimuat ke memori."))
            return@channelFlow
        }
        if (request.imagePath != null && !LlamaBridge.nativeSupportsVision()) {
            send(InferenceEvent.Failed("Model aktif belum memuat proyektor visi, jadi belum bisa membaca gambar."))
            return@channelFlow
        }

        cancelRequested.set(false)
        val producerJob = coroutineContext.job
        _state.value = EngineState.PREPARING
        send(InferenceEvent.Preparing)
        if (request.imagePath != null) {
            _state.value = EngineState.VISION_ENCODING
            send(InferenceEvent.VisionEncoding)
        }

        val formattedPrompt = formatPrompt(config.promptFormat, request.messages, withImage = request.imagePath != null)
        val prefillTokenEstimate = (formattedPrompt.length / 4).coerceAtLeast(1)
        val startMs = SystemClock.elapsedRealtime()
        var firstTokenMs = 0L
        var emittedPieces = 0
        val fullBuilder = StringBuilder()
        val outer: ProducerScope<InferenceEvent> = this

        // Coalescing 40 ms (DESIGN.md 5.4): satu event per jendela, bukan per token.
        val pendingChunk = StringBuilder()
        var lastFlushMs = SystemClock.elapsedRealtime()

        val status = withContext(inferenceDispatcher) {
            _state.value = EngineState.GENERATING
            LlamaBridge.nativeGenerateStream(
                formattedPrompt = formattedPrompt,
                imagePath = request.imagePath,
                maxOutputTokens = request.maxOutputTokens,
                temperature = request.temperature,
                callback = object : NativeStreamCallback {
                    override fun onTokenPiece(piece: String): Boolean {
                        if (cancelRequested.get() || !producerJob.isActive) return false
                        val now = SystemClock.elapsedRealtime()
                        if (firstTokenMs == 0L) firstTokenMs = (now - startMs).coerceAtLeast(1L)
                        emittedPieces++
                        fullBuilder.append(piece)
                        pendingChunk.append(piece)
                        if (now - lastFlushMs >= 40L) {
                            outer.trySend(InferenceEvent.TextDelta(pendingChunk.toString()))
                            pendingChunk.clear()
                            lastFlushMs = now
                        }
                        return true
                    }
                }
            )
        }

        if (pendingChunk.isNotEmpty()) {
            send(InferenceEvent.TextDelta(pendingChunk.toString()))
            pendingChunk.clear()
        }

        val totalMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
        val ttft = if (firstTokenMs > 0L) firstTokenMs else totalMs
        val decodeDurationSec = ((totalMs - ttft).coerceAtLeast(1L)) / 1000f
        val decodeTokens = if (status >= 0) status else emittedPieces
        val stats = RunStats(
            modelId = config.id,
            coldLoadMs = lastColdLoadMs,
            ttftMs = ttft,
            prefillTokens = prefillTokenEstimate,
            decodeTokens = decodeTokens,
            prefillTokPerSec = prefillTokenEstimate / (ttft / 1000f),
            decodeTokPerSec = decodeTokens / decodeDurationSec,
            peakPssMb = (Debug.getPss() / 1024).toInt(),
            cpuThreads = config.cpuThreads,
            contextTokens = config.contextTokens
        )

        _state.value = if (_activeConfig.value != null) EngineState.READY else EngineState.UNLOADED

        when {
            cancelRequested.get() || status == STATUS_ABORTED || !producerJob.isActive ->
                send(InferenceEvent.Stopped(fullBuilder.toString(), stats))
            status < 0 -> send(InferenceEvent.Failed(describeFailure(status)))
            else -> send(InferenceEvent.Completed(stats))
        }
    }.buffer(Channel.UNLIMITED)

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

    private fun describeFailure(status: Int): String = when (status) {
        -1 -> "Model belum dimuat ke memori."
        -3 -> "Konteks penuh. Mulai percakapan baru atau perbesar ukuran konteks di Pengaturan."
        -4 -> "Proses decoding gagal. Memori perangkat mungkin tidak cukup untuk model ini."
        -5 -> "Teks tidak dapat diproses oleh tokenizer model."
        -6 -> "Gambar tidak dapat diproses oleh model ini."
        else -> "Generasi dihentikan karena kesalahan native (kode $status)."
    }

    companion object {
        private const val STATUS_ABORTED = -99

        /** Penanda media yang diganti mtmd dengan token gambar. */
        private const val MEDIA_MARKER = "<__media__>"

        /**
         * Susun prompt sesuai template chat tiap keluarga model, dalam mode non-thinking
         * supaya model kecil tidak masuk loop berpikir. Gambar (jika ada) menempel pada
         * giliran pengguna terakhir.
         */
        internal fun formatPrompt(format: PromptFormat, messages: List<ChatTurn>, withImage: Boolean): String {
            val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
            fun contentOf(index: Int, turn: ChatTurn): String {
                val text = turn.content.trim()
                return if (withImage && index == lastUserIndex) "$MEDIA_MARKER\n$text" else text
            }

            val sb = StringBuilder()
            when (format) {
                PromptFormat.CHATML_QWEN -> {
                    messages.forEachIndexed { i, turn ->
                        val tag = when (turn.role) {
                            MessageRole.SYSTEM -> "system"
                            MessageRole.USER -> "user"
                            MessageRole.ASSISTANT -> "assistant"
                        }
                        sb.append("<|im_start|>").append(tag).append('\n')
                        sb.append(contentOf(i, turn)).append("<|im_end|>\n")
                    }
                    sb.append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
                }
                PromptFormat.GEMMA4 -> {
                    sb.append("<bos>")
                    messages.forEachIndexed { i, turn ->
                        val tag = when (turn.role) {
                            MessageRole.SYSTEM -> "system"
                            MessageRole.USER -> "user"
                            MessageRole.ASSISTANT -> "model"
                        }
                        sb.append("<|turn>").append(tag).append('\n')
                        sb.append(contentOf(i, turn)).append("<turn|>\n")
                    }
                    sb.append("<|turn>model\n<|channel>thought\n<channel|>")
                }
            }
            return sb.toString()
        }
    }
}
