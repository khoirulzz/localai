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
import id.localai.app.attachments.PdfImportResult
import id.localai.app.data.db.PdfChunkEntity
import id.localai.app.models.DownloadUi
import id.localai.app.models.ModelCatalog
import id.localai.app.models.ModelManifestEntry
import id.localai.app.orchestrator.ContextBuilder
import id.localai.app.search.SearchOutcome
import id.localai.app.search.WebSearchResult
import id.localai.inference.api.ChatTurn
import id.localai.inference.api.InferenceEvent
import id.localai.inference.api.InferenceRequest
import id.localai.inference.api.MessageRole
import id.localai.inference.api.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Menghubungkan UI dengan mesin llama.cpp, unduhan model, dan pemrosesan lampiran.
 * State percakapan masih di memori (belum disimpan ke Room), seperti sebelumnya.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as LocalAiApp).graph
    private val prefs = application.getSharedPreferences("localai_prefs", Context.MODE_PRIVATE)

    // ---- Model ----------------------------------------------------------------------------
    val installedModelIds: StateFlow<Set<String>> get() = graph.models.installed
    val downloads: StateFlow<Map<String, DownloadUi>> get() = graph.models.downloads

    var activeModelId by mutableStateOf<String?>(prefs.getString(KEY_ACTIVE_MODEL, null))
        private set

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
    var customInstructions by mutableStateOf(prefs.getString(KEY_CUSTOM, ) ?: )
        private set

    // ---- Percakapan -----------------------------------------------------------------------
    var conversations by mutableStateOf(listOf(blankConversation("conv-1")))
        private set
    var activeConvId by mutableStateOf("conv-1")
        private set

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
        prefs.edit().putString(KEY_ACTIVE_MODEL, id).apply()
    }

    fun startDownload(entry: ModelManifestEntry) = graph.models.start(entry)

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
        if (generationPhase != GenerationPhase.IDLE) stop()
        val c = blankConversation("conv-${System.currentTimeMillis()}")
        conversations = listOf(c) + conversations
        activeConvId = c.id
        pendingAttachment = null
        notice = null
        searchEnabled = false
    }

    fun selectConversation(id: String) {
        if (id != activeConvId && generationPhase != GenerationPhase.IDLE) stop()
        activeConvId = id
        notice = null
    }

    fun deleteConversation(id: String) {
        if (id == activeConvId && generationPhase != GenerationPhase.IDLE) stop()
        val removed = conversations.firstOrNull { it.id == id }
        val remaining = conversations.filter { it.id != id }
        conversations = remaining.ifEmpty { listOf(blankConversation("conv-${System.currentTimeMillis()}")) }
        if (activeConvId == id) activeConvId = conversations.first().id
        removed?.documentId?.let { docId ->
            viewModelScope.launch(Dispatchers.IO) { runCatching { graph.database.chatDao().deletePdfChunks(docId) } }
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
        val projectorReady = entry.supportsVision &&
            graph.models.downloader.getInstalledProjectorFile(entry.id).exists()
        if (!projectorReady) {
            notice = "${entry.displayName} belum bisa membaca gambar. Pilih Qwen3.5 2B (lengkap dengan proyektor visi)."
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
                    UiMessage(id = "u-$stamp", isUser = true, text = question, attachment = att) +
                    UiMessage(id = assistantId, isUser = false, text = "", status = "STREAMING")
            )
        }
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
        try {
            // 1) Muat model bila belum (atau pengaturan berubah).
            val loaded = ensureModelLoaded(entry)
            loadingLabel = null
            if (loaded.isFailure) {
                finishMessage(convId, assistantId, "FAILED", loaded.exceptionOrNull()?.message ?: "Model gagal dimuat.")
                return
            }
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
            val history = prior
                .filter { (it.status == "COMPLETED" || it.status == "STOPPED") && it.text.isNotBlank() }
                .map { ChatTurn(if (it.isUser) MessageRole.USER else MessageRole.ASSISTANT, it.text) }
            val turns = ContextBuilder.buildBoundedTurns(history, question, pdfChunks, web, contextSize, customInstructions)
            val imagePath = if (attachment?.type == "image") attachment.localPath else null
            val citations = buildCitations(pdfChunks, web)

            graph.engine.generate(
                InferenceRequest(
                    messages = turns,
                    imagePath = imagePath,
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                    temperature = temperature
                )
            ).collect { event ->
                when (event) {
                    InferenceEvent.Preparing -> Unit
                    InferenceEvent.VisionEncoding -> generationPhase = GenerationPhase.READING_IMAGE
                    is InferenceEvent.TextDelta -> {
                        answer.append(event.text)
                        generationPhase = GenerationPhase.STREAMING
                        val shown = answer.toString().trimStart()
                        updateMessage(convId, assistantId) { it.copy(text = shown) }
                    }
                    is InferenceEvent.Completed -> {
                        if (answer.isBlank()) {
                            finishMessage(convId, assistantId, "FAILED", "Model tidak menghasilkan jawaban. Coba kirim ulang.")
                        } else {
                            finishMessage(convId, assistantId, "COMPLETED", citations = citations)
                        }
                    }
                    is InferenceEvent.Stopped -> finishMessage(convId, assistantId, "STOPPED")
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

    private suspend fun ensureModelLoaded(entry: ModelManifestEntry): Result<Unit> {
        val downloader = graph.models.downloader
        val projector = downloader.getInstalledProjectorFile(entry.id)
        val wanted = ModelConfig(
            id = entry.id,
            ggufPath = downloader.getInstalledGgufFile(entry.id).absolutePath,
            projectorPath = if (entry.supportsVision && projector.exists()) projector.absolutePath else null,
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
        citations: List<UiCitation> = emptyList()
    ) {
        updateMessage(convId, msgId) {
            it.copy(status = status, errorText = error, citations = if (citations.isEmpty()) it.citations else citations)
        }
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
        const val KEY_CTX = "ctx"
        const val KEY_THREADS = "threads"
        const val KEY_TEMP = "temp"
        const val KEY_CUSTOM = "custom_instructions"
        const val MAX_OUTPUT_TOKENS = 768
    }
}
