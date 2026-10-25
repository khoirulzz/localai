package id.localai.app.image

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.localai.app.LocalAiApp
import id.localai.app.models.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.TimeUnit

data class ImageModel(
    val id: String,
    val title: String,
    val detail: String,
    val sourceUrl: String,
    val bytes: Long,
    val steps: Int,
    val cfg: String,
    val sampler: String
)

object ImageModelCatalog {
    val all = listOf(
        ImageModel(
            "sdxs-512-q8", "SDXS 512", "Ringan · 1 langkah · 683 MB",
            "https://huggingface.co/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/main/sdxs-512-tinySDdistilled_Q8_0.gguf",
            682847200L, 1, "1.0", "euler"
        ),
        ImageModel(
            "lcm-dreamshaper-v7-q5", "DreamShaper LCM", "Detail · 4 langkah · 1,69 GB",
            "https://huggingface.co/acojaco/dreamshaper-lcm-gguf/resolve/main/LCM_Dreamshaper_v7_fp16-q5_1.gguf",
            1691405376L, 4, "1.5", "lcm"
        )
    )
}

class ImageStudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as LocalAiApp).graph
    private val downloader: ModelDownloadManager = graph.models.downloader
    private val prefs = app.getSharedPreferences("nalaro_image", Context.MODE_PRIVATE)

    var selectedModelId by mutableStateOf(prefs.getString("model", ImageModelCatalog.all.first().id) ?: ImageModelCatalog.all.first().id)
        private set
    val selectedModel get() = ImageModelCatalog.all.firstOrNull { it.id == selectedModelId } ?: ImageModelCatalog.all.first()

    var editMode by mutableStateOf(false)
        private set
    var sourceImage by mutableStateOf<File?>(null)
        private set
    var strength by mutableStateOf(0.60f)
        private set
    var prompt by mutableStateOf("")
    var status by mutableStateOf("")
        private set
    var running by mutableStateOf(false)
        private set
    var downloading by mutableStateOf(false)
        private set
    var downloadProgress by mutableStateOf(0f)
        private set
    var results by mutableStateOf(listResults())
        private set

    private var generationJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile private var process: Process? = null

    private fun modelsDir() = File(app.filesDir, "image-models").apply { mkdirs() }
    private fun resultsDir() = File(app.filesDir, "image-results").apply { mkdirs() }
    private fun inputDir() = File(app.cacheDir, "image-inputs").apply { mkdirs() }
    fun fileFor(model: ImageModel = selectedModel): File = File(modelsDir(), "${model.id}.gguf")
    fun isInstalled(model: ImageModel = selectedModel): Boolean =
        fileFor(model).let { it.isFile && it.length() >= model.bytes / 2 }

    fun chooseModel(id: String) {
        if (running || downloading || ImageModelCatalog.all.none { it.id == id }) return
        selectedModelId = id
        prefs.edit().putString("model", id).apply()
        status = ""
    }

    fun setMode(edit: Boolean) { if (!running) editMode = edit }
    fun changeStrength(v: Float) { strength = v.coerceIn(0.25f, 0.85f) }
    fun dismissStatus() { if (!running && !downloading) status = "" }

    fun chooseSource(uri: Uri) {
        if (running) return
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri) ?: error("Tidak dapat membuka gambar")
                    val b = input.use { BitmapFactory.decodeStream(it) } ?: error("Format gambar tidak dikenali")
                    val output = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(output)
                    canvas.drawColor(Color.BLACK)
                    val scale = maxOf(512f / b.width, 512f / b.height)
                    val w = b.width * scale
                    val h = b.height * scale
                    val rect = Rect(((512 - w) / 2f).toInt(), ((512 - h) / 2f).toInt(),
                        ((512 + w) / 2f).toInt(), ((512 + h) / 2f).toInt())
                    canvas.drawBitmap(b, null, rect, null)
                    b.recycle()
                    File(inputDir(), "source.png").also { file ->
                        file.outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        output.recycle()
                    }
                }
                sourceImage = f
                editMode = true
                status = ""
            } catch (e: Exception) { status = e.message ?: "Gambar gagal dibaca" }
        }
    }

    fun removeSource() { if (!running) sourceImage = null }

    fun downloadModel() {
        if (downloading || running || isInstalled()) return
        val model = selectedModel
        if (!downloader.hasEnoughSpaceFor(model.bytes)) {
            status = "Penyimpanan tidak cukup untuk ${model.title}"
            return
        }
        downloading = true
        status = "Mengunduh ${model.title}"
        downloadProgress = 0f
        downloadJob = viewModelScope.launch {
            val r = downloader.downloadFileWithResume(
                modelId = "image-${model.id}",
                url = model.sourceUrl,
                expectedSha256 = "",
                targetFile = fileFor(model),
                onProgress = { downloaded, total ->
                    val base = if (total > 0) total else model.bytes
                    downloadProgress = (downloaded.toFloat() / base).coerceIn(0f, 1f)
                }
            )
            downloading = false
            status = if (r.isSuccess) "Model siap digunakan" else
                r.exceptionOrNull()?.message ?: "Unduhan gagal"
        }
    }

    fun cancelDownload() {
        downloader.pauseDownload("image-${selectedModel.id}")
        status = "Unduhan dijeda"
    }

    fun importModel(uri: Uri) {
        if (downloading || running) return
        val model = selectedModel
        downloading = true
        status = "Mengimpor model…"
        downloadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val target = fileFor(model)
                    val part = File(target.path + ".import")
                    try {
                        app.contentResolver.openInputStream(uri).use { source ->
                            require(source != null) { "Tidak dapat membaca berkas GGUF" }
                            part.outputStream().use { targetStream ->
                                source.copyTo(targetStream, 256 * 1024)
                            }
                        }
                        require(part.length() >= model.bytes / 2) { "File terlalu kecil atau belum lengkap" }
                        // GGUF magic (4 bytes) sebelum menimpa model yang terpasang.
                        val magic = ByteArray(4)
                        part.inputStream().use { require(it.read(magic) == 4) }
                        require(String(magic, Charsets.US_ASCII) == "GGUF") { "File ini bukan GGUF" }
                        if (target.exists()) target.delete()
                        require(part.renameTo(target)) { "Gagal menyimpan model" }
                    } finally { part.delete() }
                }
                status = "Model diimpor"
            } catch (e: Exception) { status = e.message ?: "Impor gagal" }
            finally { downloading = false }
        }
    }

    fun generate() {
        val input = prompt.trim()
        if (running || downloading || input.isBlank()) return
        if (!isInstalled()) { status = "Unduh atau impor model terlebih dahulu"; return }
        if (editMode && sourceImage?.exists() != true) { status = "Pilih gambar untuk diedit"; return }
        val exe = File(app.applicationInfo.nativeLibraryDir, "libsdcli.so")
        if (!exe.exists()) { status = "Runtime image tidak tersedia dalam APK ini"; return }
        val model = selectedModel
        running = true
        status = "Menyiapkan model…"
        generationJob = viewModelScope.launch {
            try {
                // Bebaskan bobot Qwen sebelum membuka mesin diffusion agar tidak OOM.
                graph.engine.unload()
                val result = withContext(Dispatchers.IO) {
                    val output = File(resultsDir(), "nalaro-${System.currentTimeMillis()}.png")
                    val args = mutableListOf(
                        exe.absolutePath, "-m", fileFor(model).absolutePath,
                        "-p", input, "-o", output.absolutePath,
                        "-W", "512", "-H", "512",
                        "--steps", model.steps.toString(),
                        "--cfg-scale", model.cfg,
                        "--sampling-method", model.sampler,
                        "--threads", "4",
                        "--seed", (System.currentTimeMillis() % 2147483647L).toString()
                    )
                    if (editMode) {
                        args.addAll(listOf("-i", sourceImage!!.absolutePath, "--strength", strength.toString()))
                    }
                    val builder = ProcessBuilder(args)
                        .directory(app.filesDir)
                        .redirectErrorStream(true)
                    builder.environment()["LD_LIBRARY_PATH"] =
                        app.applicationInfo.nativeLibraryDir + ":/system/lib64:/vendor/lib64"
                    val p = builder.start()
                    process = p
                    val tail = ArrayDeque<String>()
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (line.isNotBlank()) {
                                tail.addLast(line.take(180))
                                while (tail.size > 8) tail.removeFirst()
                                if (line.contains("sampling", true) || line.contains("steps", true)) {
                                    withContext(Dispatchers.Main) { status = "Membuat gambar…" }
                                }
                            }
                        }
                    }
                    val exit = p.waitFor()
                    process = null
                    if (exit != 0 || !output.exists() || output.length() < 1024L) {
                        output.delete()
                        error("Generasi gagal (kode $exit). ${tail.lastOrNull().orEmpty()}")
                    }
                    output
                }
                results = listOf(result) + results.filterNot { it.absolutePath == result.absolutePath }
                status = "Gambar selesai"
            } catch (e: Exception) {
                status = e.message ?: "Gagal membuat gambar"
            } finally {
                process = null
                running = false
            }
        }
    }

    fun stop() {
        process?.destroy()
        process?.destroyForcibly()
        status = "Generasi dihentikan"
    }

    fun saveToGallery(file: File) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Nalaro AI")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = app.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Galeri tidak dapat dibuka")
                    try {
                        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                            ?: error("Gagal menyimpan gambar")
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
                }
                status = "Disimpan ke Galeri · Nalaro AI"
            } catch (e: Exception) { status = e.message ?: "Gagal menyimpan" }
        }
    }

    private fun listResults(): List<File> =
        resultsDir().listFiles { file -> file.extension.equals("png", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    override fun onCleared() {
        process?.destroyForcibly()
        super.onCleared()
    }
}
