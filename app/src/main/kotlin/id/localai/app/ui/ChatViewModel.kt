package id.localai.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.localai.app.LocalAiApp
import id.localai.app.data.db.ConversationEntity
import id.localai.app.data.db.MessageEntity
import id.localai.app.data.db.CitationEntity
import id.localai.app.attachments.PdfImportResult
import id.localai.app.data.db.PdfChunkEntity
import id.localai.app.models.DownloadUi
import id.localai.app.models.ModelCatalog
import id.localai.app.models.ModelManifestEntry
import id.localai.app.orchestrator.ContextBuilder
import id.localai.app.orchestrator.OutputBudget
import id.localai.app.search.SearchOutcome
import id.localai.app.search.WebSearchResult
import id.localai.inference.api.ChatTurn
import id.localai.inference.api.InferenceEvent
import id.localai.inference.api.InferenceRequest
import id.localai.inference.api.MessageRole
import id.localai.inference.api.PromptFormat
import id.localai.inference.api.RunStats
import id.localai.inference.api.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Menghubungkan UI dengan mesin llama.cpp, unduhan model, dan pemrosesan lampiran.
 * Riwayat dan ringkasan persisten di Room, dengan inferensi lokal per percakapan.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as LocalAiApp).graph
    private val prefs = application.getSharedPreferences("localai_prefs", Context.MODE_PRIVATE)

    // ---- Model ----------------------------------------------------------------------------
    val installedModelIds: StateFlow<Set<String>> get() = graph.models.installed
    val downloads: StateFlow<Map<String, DownloadUi>> get() = graph.models.downloads
    val visionInstalled: StateFlow<Set<String>> get() = graph.models.visionInstalled
    val visionDownloads: StateFlow<Map<String, DownloadUi>> get() = graph.models.visionDownloads

    var activeModelId by mutableStateOf<String?>(prefs.getString(KEY_ACTIVE_MODEL, null))
        private set

    var thinkingMode by mutableStateOf(prefs.getString("think_${activeModelId}", "OFF") ?: "OFF")
        private set
    fun updateThinkingMode(mode: String) {
        if (mode !in setOf("OFF", "STANDARD", "DEEP")) return
        thinkingMode = mode
        prefs.edit().putString("think_${activeModelId}", mode).apply()
    }

    val activeEntry: ModelManifestEntry?
        get() = ModelCatalog.byId(activeModelId)?.takeIf { installedModelIds.value.contains(it.id) }

    // ---- Pengaturan lanjutan --------------------------------------------------------------
    var contextSize by mutableStateOf(prefs.getInt(KEY_CTX, 2048))
        private set
    var cpuThreads by mutableStateOf(prefs.getInt(KEY_THREADS, 4))
        private set
    var temperature by mutableStateOf(prefs.getFloat(KEY_TEMP, 0.6f))
        private set
    var searchEnabled by mutableStateOf(false)
        private set
    var customInstructions by mutableStateOf(prefs.getString(KEY_CUSTOM, "") ?: "")
        private set

    // ---- Percakapan -----------------------------------------------------------------------
    var conversations by mutableStateOf(listOf(blankConversation("conv-1")))
        private set
    var activeConvId by mutableStateOf("conv-1")
        private set
    var historyReady by mutableStateOf(false)
        private set
    private val persistenceMutex = Mutex()

    val currentConversation: UiConversation
        get() = conversations.firstOrNull { it.id == activeConvId } ?: conversations.first()

    var generationPhase by mutableStateOf(GenerationPhase.IDLE)
        private set
    private var loadingLabel by mutableStateOf<String?>(null)

    /** Teks status singkat untuk jawaban yang belum mulai mengalir. */
    val phaseLabel: String?
        get() = when (generationPhase) {
            GenerationPhase.IDLE, GenerationPhase.STREAMING -> null
            GenerationPhase.PREPARING -> loadingLabel ?: "Menyiapkan…"
            GenerationPhase.READING_DOC -> "Membaca dokumen…"
            GenerationPhase.READING_IMAGE -> "Membaca gambar…"
            GenerationPhase.SEARCHING_WEB -> "Mencari di web…"
        }

    var pendingAttachment by mutableStateOf<UiAttachment?>(null)
        private set

    /** Pesan singkat non-blokir (gagal lampiran, pencarian web, dll.). */
    var notice by mutableStateOf<String?>(null)
        private set

    private var generationJob: Job? = null
    private var attachJob: Job? = null
    @Volatile private var stopRequested = false

    init {
        // Restore dilakukan di background agar halaman segera tampil.
        viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val dao = graph.database.chatDao()
                    dao.observeConversations().first().map { row ->
                        UiConversation(
                            id = row.id,
                            title = row.title,
                            documentId = row.activeDocumentId,
                            summary = row.summary,
                            summarizedThroughMs = row.summarizedThroughMs,
                            messages = dao.observeMessages(row.id).first().map { m ->
                                UiMessage(
                                    id = m.id, isUser = m.role == "user", text = m.content,
                                    attachment = m.attachmentName?.let {
                                        UiAttachment(it, m.attachmentType ?: "pdf", "")
                                    },
                                    citations = dao.getCitationsForMessage(m.id).map {
                                        UiCitation(it.title, it.urlOrPage, it.sourceType == "WEB")
                                    },
                                    status = if (m.status == "STREAMING") "STOPPED" else m.status,
                                    errorText = m.errorText,
                                    thinking = m.thinking, generatedTokens = m.generatedTokens,
                                    elapsedMs = m.elapsedMs, tokensPerSecond = m.tokensPerSecond,
                                    createdAtMs = m.createdAtMs
                                )
                            }
                        )
                    }
                }
                if (restored.isNotEmpty()) {
                    conversations = restored
                    activeConvId = prefs.getString(KEY_ACTIVE_CONV, null)
                        ?.takeIf { id -> restored.any { it.id == id } } ?: restored.first().id
                }
            } catch (e: Exception) {
                notice = "Riwayat tidak dapat dimuat: ${e.message}"
            } finally {
                historyReady = true
            }
        }
        // Pilih otomatis model terpasang bila belum ada yang aktif, dan lepas model yang sudah dihapus.
        viewModelScope.launch {
            graph.models.installed.collect { ids ->
                if (activeModelId == null || activeModelId !in ids) {
                    setActiveModel(ids.firstOrNull { it == activeModelId } ?: ids.firstOrNull())
                }
            }
        }
    }

    // ===== Model =============================================================================

    fun selectModel(id: String) {
        if (id in installedModelIds.value) setActiveModel(id)
    }

    private fun setActiveModel(id: String?) {
        activeModelId = id
        thinkingMode = prefs.getString("think_${id}", "OFF") ?: "OFF"
        prefs.edit().putString(KEY_ACTIVE_MODEL, id).apply()
    }

    fun startDownload(entry: ModelManifestEntry) = graph.models.start(entry)
    fun downloadProjector(entry: ModelManifestEntry) = graph.models.startProjector(entry)

    fun pauseDownload(entry: ModelManifestEntry) = graph.models.pause(entry)

    fun importModel(entry: ModelManifestEntry, uris: List<Uri>) = graph.models.importFiles(entry, uris)

    fun deleteModel(entry: ModelManifestEntry) {
        if (generationPhase != GenerationPhase.IDLE && activeModelId == entry.id) stop()
        viewModelScope.launch {
            if (graph.engine.activeConfig.value?.id == entry.id) graph.engine.unload()
            graph.models.delete(entry)
        }
    }

    // ===== Pengaturan ========================================================================

    fun updateContextSize(v: Int) { contextSize = v; prefs.edit().putInt(KEY_CTX, v).apply() }
    fun updateCpuThreads(v: Int) { cpuThreads = v; prefs.edit().putInt(KEY_THREADS, v).apply() }
    fun updateTemperature(v: Float) { temperature = v; prefs.edit().putFloat(KEY_TEMP, v).apply() }
    fun updateSearchEnabled(v: Boolean) { searchEnabled = v }
    fun updateCustomInstructions(v: String) {
        customInstructions = v.take(ContextBuilder.MAX_CUSTOM_INSTRUCTIONS)
        prefs.edit().putString(KEY_CUSTOM, customInstructions).apply()
    }
    fun dismissNotice() { notice = null }

    // ===== Percakapan ========================================================================

    fun newConversation() {
        if (!historyReady) return
        if (generationPhase != GenerationPhase.IDLE) stop()
        val c = blankConversation("conv-${System.currentTimeMillis()}")
        conversations = listOf(c) + conversations
        activeConvId = c.id
        prefs.edit().putString(KEY_ACTIVE_CONV, c.id).apply()
        persistConversation(c.id)
        pendingAttachment = null
        notice = null
        searchEnabled = false
    }

    fun selectConversation(id: String) {
        if (id != activeConvId && generationPhase != GenerationPhase.IDLE) stop()
        activeConvId = id
        prefs.edit().putString(KEY_ACTIVE_CONV, id).apply()
        pendingAttachment = null
        notice = null
    }

    fun deleteConversation(id: String) {
        if (!historyReady) return
        if (id == activeConvId && generationPhase != GenerationPhase.IDLE) stop()
        val removed = conversations.firstOrNull { it.id == id }
        val remaining = conversations.filter { it.id != id }
        conversations = remaining.ifEmpty { listOf(blankConversation("conv-${System.currentTimeMillis()}")) }
        if (activeConvId == id) activeConvId = conversations.first().id
        prefs.edit().putString(KEY_ACTIVE_CONV, activeConvId).apply()
        viewModelScope.launch(Dispatchers.IO) {
            persistenceMutex.withLock {
                graph.database.chatDao().deleteConversation(id)
                removed?.documentId?.let { graph.database.chatDao().deletePdfChunks(it) }
            }
        }
        if (conversations.size == 1 && conversations.first().messages.isEmpty()) {
            persistConversation(conversations.first().id)
        }
    }

    // ===== Lampiran ==========================================================================

    fun removeAttachment() {
        attachJob?.cancel()
        val att = pendingAttachment
        pendingAttachment = null
        att?.documentId?.let { docId ->
            if (currentConversation.documentId != docId) {
                viewModelScope.launch(Dispatchers.IO) { runCatching { graph.database.chatDao().deletePdfChunks(docId) } }
            }
        }
    }

    fun attachImage(uri: Uri, displayName: String) {
        val entry = activeEntry ?: return
        if (!entry.supportsVision) {
            notice = "${entry.displayName} hanya mendukung chat teks di aplikasi ini. Pilih model dengan dukungan visi."
            return
        }
        val projectorReady = graph.models.downloader.hasVisionProjector(entry)
        if (!projectorReady) {
            notice = "Untuk membaca gambar dengan ${entry.displayName}, unduh atau impor proyektor visi (mmproj) di Kelola model. Chat teks tetap bisa digunakan."
            return
        }
        attachJob?.cancel()
        notice = null
        pendingAttachment = UiAttachment(displayName, "image", "Memproses…", isProcessing = true)
        attachJob = viewModelScope.launch {
            val processor = withContext(Dispatchers.IO) { graph.attachments }
            val img = processor.prepareImageForVlm(uri, displayName)
            if (img == null) {
                pendingAttachment = null
                notice = "Gambar tidak dapat dibaca."
            } else {
                pendingAttachment = UiAttachment(
                    name = displayName, type = "image", meta = "${img.width}×${img.height}",
                    localPath = img.localPath
                )
            }
        }
    }

    fun attachPdf(uri: Uri, displayName: String) {
        attachJob?.cancel()
        notice = null
        pendingAttachment = UiAttachment(displayName, "pdf", "Membaca…", isProcessing = true)
        attachJob = viewModelScope.launch {
            val processor = withContext(Dispatchers.IO) { graph.attachments }
            val result = processor.importAndIndexPdf(uri, displayName) { page, total ->
                pendingAttachment = pendingAttachment?.takeIf { it.isProcessing }?.copy(meta = "Membaca hlm. $page/$total")
                    ?: pendingAttachment
            }
            when (result) {
                is PdfImportResult.Ready -> {
                    val empty = if (result.emptyPageCount > 0) " · ${result.emptyPageCount} hlm kosong" else ""
                    pendingAttachment = UiAttachment(
                        name = displayName, type = "pdf", meta = "${result.totalPages} hlm$empty",
                        documentId = result.documentId
                    )
                }
                PdfImportResult.NoTextLayer -> {
                    pendingAttachment = null
                    notice = "PDF ini tidak punya teks yang bisa dibaca (kemungkinan hasil scan)."
                }
                PdfImportResult.Encrypted -> {
                    pendingAttachment = null
                    notice = "PDF ini dikunci dengan kata sandi, jadi belum bisa dibaca."
                }
                is PdfImportResult.Error -> {
                    pendingAttachment = null
                    notice = result.message
                }
            }
        }
    }

    // ===== Kirim & hentikan ==================================================================

    fun send(rawText: String) {
        if (!historyReady) return
        if (generationPhase != GenerationPhase.IDLE) return
        val entry = activeEntry ?: return
        val att = pendingAttachment
        if (att?.isProcessing == true) return

        var question = rawText.trim()
        if (question.isBlank() && att == null) return
        if (question.isBlank()) {
            question = if (att?.type == "image") "Jelaskan gambar ini." else "Ringkas dokumen ini."
        }

        val convId = activeConvId
        val stamp = System.currentTimeMillis()
        val assistantId = "a-$stamp"
        val prior = currentConversation.messages
        val documentId = att?.documentId ?: currentConversation.documentId

        updateConversation(convId) { c ->
            c.copy(
                title = if (c.messages.isEmpty()) question.take(36) else c.title,
                documentId = documentId,
                messages = c.messages +
                    UiMessage(id = "u-$stamp", isUser = true, text = question, attachment = att, createdAtMs = stamp) +
                    UiMessage(id = assistantId, isUser = false, text = "", status = "STREAMING", createdAtMs = stamp + 1)
            )
        }
        persistConversation(convId)
        pendingAttachment = null
        notice = null
        stopRequested = false
        generationPhase = GenerationPhase.PREPARING

        val webEnabled = searchEnabled
        generationJob = viewModelScope.launch {
            runGeneration(convId, assistantId, entry, question, att, prior, documentId, webEnabled)
        }
    }

    fun stop() {
        if (generationPhase == GenerationPhase.IDLE) return
        stopRequested = true
        viewModelScope.launch { graph.engine.cancel() }
    }

    private suspend fun runGeneration(
        convId: String,
        assistantId: String,
        entry: ModelManifestEntry,
        question: String,
        attachment: UiAttachment?,
        prior: List<UiMessage>,
        documentId: String?,
        webEnabled: Boolean
    ) {
        val answer = StringBuilder()
        val requestedThinking = thinkingMode
        val thinkingEnabled = requestedThinking != "OFF"
        try {
            // 1) Muat model bila belum (atau pengaturan berubah).
            val loaded = ensureModelLoaded(entry)
            loadingLabel = null
            if (loaded.isFailure) {
                finishMessage(convId, assistantId, "FAILED", loaded.exceptionOrNull()?.message ?: "Model gagal dimuat.")
                return
            }
            if (stopRequested) { finishMessage(convId, assistantId, "STOPPED"); return }

            // Pemadatan hanya pada sesi aktif; tidak menghapus pesan asli.
            maybeSummarizeHistory(convId, prior)
            if (stopRequested) { finishMessage(convId, assistantId, "STOPPED"); return }

            // 2) Ambil potongan PDF yang relevan.
            var pdfChunks: List<PdfChunkEntity> = emptyList()
            if (documentId != null) {
                generationPhase = GenerationPhase.READING_DOC
                pdfChunks = retrievePdfChunks(documentId, question)
            }

            // 3) Pencarian web (opsional, hanya bila diaktifkan pengguna).
            var web: List<WebSearchResult> = emptyList()
            if (webEnabled) {
                generationPhase = GenerationPhase.SEARCHING_WEB
                when (val outcome = graph.search.search(question)) {
                    is SearchOutcome.Success -> web = outcome.results
                    SearchOutcome.MissingApiKey -> notice = "API key Tavily belum disimpan. Menjawab tanpa pencarian web."
                    SearchOutcome.InvalidApiKey -> notice = "API key Tavily ditolak. Menjawab tanpa pencarian web."
                    SearchOutcome.QuotaExceeded -> notice = "Kuota Tavily habis. Menjawab tanpa pencarian web."
                    SearchOutcome.OfflineOrTimeout -> notice = "Pencarian web tidak terjangkau. Menjawab tanpa pencarian web."
                    is SearchOutcome.UnknownError -> notice = "Pencarian web gagal: ${outcome.message}"
                }
            }
            if (stopRequested) { finishMessage(convId, assistantId, "STOPPED"); return }

            // 4) Susun prompt dengan batas konteks, lalu generate.
            generationPhase = GenerationPhase.PREPARING
            val session = conversations.firstOrNull { it.id == convId }
            val history = prior
                .filter { it.createdAtMs > (session?.summarizedThroughMs ?: 0L) }
                .filter { (it.status == "COMPLETED" || it.status == "STOPPED") && it.text.isNotBlank() }
                .map { ChatTurn(if (it.isUser) MessageRole.USER else MessageRole.ASSISTANT, it.text) }
            val answerLimit = OutputBudget.answerTokens(contextSize)
            val firstPassLimit = OutputBudget.thinkingTokens(contextSize, requestedThinking)
            val turns = ContextBuilder.buildBoundedTurns(
                history, question, pdfChunks, web, contextSize, customInstructions,
                sessionSummary = session?.summary.orEmpty(),
                reservedOutputTokens = answerLimit
            )
            val imagePath = if (attachment?.type == "image") attachment.localPath else null
            val citations = buildCitations(pdfChunks, web)

            graph.engine.generate(
                InferenceRequest(
                    messages = turns,
                    imagePath = imagePath,
                    maxOutputTokens = firstPassLimit,
                    thinkingEnabled = thinkingEnabled,
                    temperature = temperature
                )
            ).collect { event ->
                when (event) {
                    InferenceEvent.Preparing -> Unit
                    InferenceEvent.VisionEncoding -> generationPhase = GenerationPhase.READING_IMAGE
                    is InferenceEvent.TextDelta -> {
                        answer.append(event.text)
                        generationPhase = GenerationPhase.STREAMING
                        val (thought, shown) = splitModelOutput(answer.toString(), thinkingEnabled, entry.promptFormat)
                        updateMessage(convId, assistantId) { it.copy(text = shown, thinking = thought) }
                    }
                    is InferenceEvent.Completed -> {
                        if (answer.isBlank()) {
                            finishMessage(convId, assistantId, "FAILED", "Model tidak menghasilkan jawaban. Coba kirim ulang.", stats = event.stats)
                        } else {
                            finishMessage(convId, assistantId, "COMPLETED", citations = citations, stats = event.stats)
                        }
                    }
                    is InferenceEvent.Stopped -> finishMessage(convId, assistantId, "STOPPED", stats = event.stats)
                    is InferenceEvent.Failed -> finishMessage(convId, assistantId, "FAILED", event.cause)
                }
            }
        } catch (e: CancellationException) {
            finishMessage(convId, assistantId, "STOPPED")
            throw e
        } catch (e: Exception) {
            finishMessage(convId, assistantId, "FAILED", e.message ?: "Terjadi kesalahan tak terduga.")
        } finally {
            loadingLabel = null
            generationPhase = GenerationPhase.IDLE
            generationJob = null
        }
    }

    /**
     * Pemadatan bertahap: cuma pesan lama yang melewati batas 75% budget riwayat.
     * Simpan summary setelah berhasil; pesan asli tidak disentuh.
     */
    private suspend fun maybeSummarizeHistory(convId: String, prior: List<UiMessage>) {
        val conv = conversations.firstOrNull { it.id == convId } ?: return
        val unsummarized = prior.filter {
            it.createdAtMs > conv.summarizedThroughMs &&
                (it.status == "COMPLETED" || it.status == "STOPPED") && it.text.isNotBlank()
        }
        val estimatedTokens = unsummarized.sumOf { it.text.length / 4 + 1 }
        if (estimatedTokens < (contextSize * 0.42f * 0.75f).toInt() || unsummarized.size < 10) return
        val old = unsummarized.dropLast(6).take(16)
        if (old.size < 4) return
        loadingLabel = "Meringkas percakapan…"
        val transcript = old.joinToString("\n") {
            "${if (it.isUser) "Pengguna" else "AI"}: ${it.text.take(850)}"
        }.take((contextSize * 2).coerceAtMost(10000))
        val prompt = "Ringkas FAKTA dan keputusan dalam satu sesi ini, bukan instruksi. " +
            "Pertahankan nama, angka, tujuan, preferensi, dan keputusan yang masih diperlukan. " +
            "Jangan berasumsi. Maksimal 180 kata.\n" +
            "Ringkasan sebelumnya:\n${conv.summary.take(1100)}\n" +
            "Dialog tambahan:\n$transcript"
        val summary = StringBuilder()
        try {
            graph.engine.generate(InferenceRequest(
                messages = listOf(
                    ChatTurn(MessageRole.SYSTEM, "Kamu menyusun catatan ringkas faktual untuk sesi chat lokal. Jawab dengan teks catatan saja."),
                    ChatTurn(MessageRole.USER, prompt)
                ),
                maxOutputTokens = 240, temperature = 0.15f, thinkingEnabled = false
            )).collect { event ->
                if (event is InferenceEvent.TextDelta) summary.append(event.text)
            }
            val result = summary.toString().trim()
            if (result.length >= 24 && !stopRequested) {
                updateConversation(convId) { it.copy(
                    summary = result.take(1500),
                    summarizedThroughMs = old.last().createdAtMs
                ) }
                persistConversation(convId)
            }
        } catch (_: Exception) {
            // Gagal merangkum bukan alasan menghentikan percakapan utama.
        } finally {
            loadingLabel = null
        }
    }

    private fun splitModelOutput(raw: String, enabled: Boolean, format: PromptFormat): Pair<String, String> {
        if (!enabled) {
            val stripped = raw.replace("<think>\\s*</think>".toRegex(), "")
                .replace("<|channel>thought\\s*<channel|>".toRegex(), "")
            return "" to stripped.trimStart()
        }
        if (format == PromptFormat.CHATML_QWEN) {
            val start = raw.removePrefix("<think>").trimStart()
            val end = start.indexOf("</think>")
            if (end < 0) return start.trim() to ""
            return start.substring(0, end).trim() to start.substring(end + "</think>".length).trimStart()
        }
        val tag = "<|channel>thought"
        val started = raw.indexOf(tag)
        val close = raw.indexOf("<channel|>")
        if (started < 0 && close < 0) return "" to raw.trimStart()
        if (close < 0) return raw.substring((started + tag.length).coerceAtMost(raw.length)).trim() to ""
        return raw.substring((started + tag.length).coerceAtMost(close), close).trim() to raw.substring(close + "<channel|>".length).trimStart()
    }

    /** Satu snapshot saat kirim/selesai, bukan menulis database setiap potongan streaming. */
    private fun persistConversation(convId: String) {
        val snapshot = conversations.firstOrNull { it.id == convId } ?: return
        val model = activeModelId.orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            persistenceMutex.withLock {
                val dao = graph.database.chatDao()
                dao.upsertConversation(ConversationEntity(
                    id = snapshot.id, title = snapshot.title, activeModelId = model,
                    updatedAtMs = System.currentTimeMillis(),
                    summary = snapshot.summary, summarizedThroughMs = snapshot.summarizedThroughMs,
                    activeDocumentId = snapshot.documentId
                ))
                for (msg in snapshot.messages) {
                    dao.upsertMessage(MessageEntity(
                        id = msg.id, conversationId = snapshot.id,
                        role = if (msg.isUser) "user" else "assistant",
                        content = msg.text, attachmentName = msg.attachment?.name,
                        attachmentType = msg.attachment?.type, status = msg.status,
                        errorText = msg.errorText, thinking = msg.thinking,
                        generatedTokens = msg.generatedTokens, elapsedMs = msg.elapsedMs,
                        tokensPerSecond = msg.tokensPerSecond,
                        createdAtMs = msg.createdAtMs
                    ))
                    if (msg.citations.isNotEmpty()) dao.insertCitations(
                        msg.citations.mapIndexed { index, cit ->
                            CitationEntity(
                                id = "${msg.id}-citation-$index",
                                messageId = msg.id, title = cit.label,
                                urlOrPage = cit.urlOrPage,
                                sourceType = if (cit.isWeb) "WEB" else "PDF",
                                accessedAtMs = System.currentTimeMillis()
                            )
                        }
                    )
                }
            }
        }
    }

    private suspend fun ensureModelLoaded(entry: ModelManifestEntry): Result<Unit> {
        val downloader = graph.models.downloader
        val projector = downloader.getInstalledProjectorFile(entry.id)
        val wanted = ModelConfig(
            id = entry.id,
            ggufPath = downloader.getInstalledGgufFile(entry.id).absolutePath,
            projectorPath = if (graph.models.downloader.hasVisionProjector(entry)) projector.absolutePath else null,
            promptFormat = entry.promptFormat,
            contextTokens = contextSize,
            cpuThreads = cpuThreads
        )
        if (graph.engine.activeConfig.value == wanted) return Result.success(Unit)

        loadingLabel = "Memuat ${entry.displayName}…"
        graph.engine.unload()
        return graph.engine.load(wanted).map { }
    }

    private suspend fun retrievePdfChunks(documentId: String, question: String): List<PdfChunkEntity> =
        withContext(Dispatchers.IO) {
            val dao = graph.database.chatDao()
            val ftsQuery = buildFtsQuery(question)
            val hits = if (ftsQuery != null) {
                runCatching { dao.searchPdfChunks(documentId, ftsQuery, 6) }.getOrDefault(emptyList())
            } else emptyList()
            hits.ifEmpty { dao.getFirstChunks(documentId, 6) }
        }

    private fun buildFtsQuery(question: String): String? {
        val words = Regex("[\\p{L}\\p{N}]{3,}").findAll(question.lowercase())
            .map { it.value }.distinct().take(8).toList()
        if (words.isEmpty()) return null
        return words.joinToString(" OR ") { "\"$it\"" }
    }

    private fun buildCitations(pdf: List<PdfChunkEntity>, web: List<WebSearchResult>): List<UiCitation> {
        val pages = pdf.map { it.pageNumber }.distinct().sorted()
            .map { UiCitation("Dokumen · hlm. $it", it.toString(), isWeb = false) }
        val sources = web.map { UiCitation("[${it.id}] ${it.title.take(28)}", it.url, isWeb = true) }
        return pages + sources
    }

    // ===== Helper state ======================================================================

    private fun finishMessage(
        convId: String,
        msgId: String,
        status: String,
        error: String? = null,
        citations: List<UiCitation> = emptyList(),
        stats: RunStats? = null
    ) {
        updateMessage(convId, msgId) {
            it.copy(
                status = status, errorText = error,
                citations = if (citations.isEmpty()) it.citations else citations,
                generatedTokens = stats?.decodeTokens ?: it.generatedTokens,
                elapsedMs = stats?.elapsedMs ?: it.elapsedMs,
                tokensPerSecond = stats?.decodeTokPerSec ?: it.tokensPerSecond
            )
        }
        persistConversation(convId)
    }

    private fun updateConversation(id: String, transform: (UiConversation) -> UiConversation) {
        conversations = conversations.map { if (it.id == id) transform(it) else it }
    }

    private fun updateMessage(convId: String, msgId: String, transform: (UiMessage) -> UiMessage) {
        updateConversation(convId) { c ->
            c.copy(messages = c.messages.map { if (it.id == msgId) transform(it) else it })
        }
    }

    private fun blankConversation(id: String) = UiConversation(id, "Percakapan baru", emptyList())

    override fun onCleared() {
        // Engine milik AppGraph dan sengaja tidak dilepas di sini.
        super.onCleared()
    }

    private companion object {
        const val KEY_ACTIVE_MODEL = "active_model"
        const val KEY_ACTIVE_CONV = "active_conv"
        const val KEY_CTX = "ctx"
        const val KEY_THREADS = "threads"
        const val KEY_TEMP = "temp"
        const val KEY_CUSTOM = "custom_instructions"
    }
}
