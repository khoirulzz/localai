package id.localai.app.models

import android.content.Context
import android.os.StatFs
import id.localai.inference.api.PromptFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ModelManifestEntry(
    val id: String,
    val displayName: String,
    val roleSummary: String,
    val licenseName: String,
    val quantization: String,
    val ggufUrl: String,
    /** Kosong = ambil SHA-256 resmi dari header Hugging Face saat mengunduh. */
    val ggufSha256: String = "",
    /** Perkiraan ukuran (untuk cek ruang & progres); ukuran pasti dibaca dari server. */
    val ggufSizeBytes: Long,
    val supportsVision: Boolean,
    val promptFormat: PromptFormat = PromptFormat.CHATML_QWEN,
    val projectorUrl: String? = null,
    val projectorSha256: String = "",
    val projectorSizeBytes: Long = 0L,
    val minFreeRamMb: Int = 2200
) {
    val totalSizeBytes: Long get() = ggufSizeBytes + projectorSizeBytes
}

object ModelCatalog {
    // URL dan nama berkas diverifikasi terhadap daftar berkas di Hugging Face (Okt 2026).
    val entries = listOf(
        ModelManifestEntry(
            id = "qwen3.5-2b-q4_k_m",
            displayName = "Qwen3.5 2B",
            roleSummary = "Default seimbang · Bahasa Indonesia, analisis dokumen, dan multimodal",
            licenseName = "Apache-2.0",
            quantization = "GGUF Q4_K_M",
            ggufUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
            ggufSizeBytes = 1_280_835_840L,
            supportsVision = true,
            promptFormat = PromptFormat.CHATML_QWEN,
            projectorUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/mmproj-F16.gguf",
            projectorSizeBytes = 668_227_264L,
            minFreeRamMb = 2800
        ),
        ModelManifestEntry(
            id = "qwen3.5-0.8b-q4_k_m",
            displayName = "Qwen3.5 0.8B",
            roleSummary = "Ringan & cepat · Hemat memori untuk obrolan teks harian",
            licenseName = "Apache-2.0",
            quantization = "GGUF Q4_K_M",
            ggufUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
            ggufSizeBytes = 533_000_000L,
            supportsVision = false,
            promptFormat = PromptFormat.CHATML_QWEN,
            minFreeRamMb = 1600
        ),
        ModelManifestEntry(
            id = "gemma-4-e2b-it-q4_0",
            displayName = "Gemma 4 E2B",
            roleSummary = "Pembanding kualitas instruksi & penalaran pada runtime GGUF",
            licenseName = "Gemma Terms of Use",
            quantization = "GGUF Q4_0",
            ggufUrl = "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf",
            ggufSizeBytes = 2_840_000_000L,
            supportsVision = false,
            promptFormat = PromptFormat.GEMMA4,
            minFreeRamMb = 4000
        )
    )

    fun byId(id: String?): ModelManifestEntry? = entries.firstOrNull { it.id == id }
}

/** Info berkas jarak jauh dari header Hugging Face (hash SHA-256 dan ukuran sebenarnya). */
data class RemoteFileInfo(val sha256: String?, val sizeBytes: Long?)

class ModelDownloadManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val activeCancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    fun getAvailableBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    fun hasEnoughSpaceFor(requiredBytes: Long): Boolean {
        val safetyMarginBytes = 300L * 1024L * 1024L
        return getAvailableBytes() >= (requiredBytes + safetyMarginBytes)
    }

    fun getInstalledGgufFile(modelId: String): File =
        File(File(context.filesDir, "models/$modelId"), "model.gguf")

    fun getInstalledProjectorFile(modelId: String): File =
        File(File(context.filesDir, "models/$modelId"), "mmproj.gguf")

    /**
     * GGUF utama saja sudah cukup untuk chat teks. Proyektor visi adalah unduhan opsional.
     * File parsial (.part) tidak boleh ditandai sebagai terpasang.
     */
    fun isInstalled(entry: ModelManifestEntry): Boolean {
        val gguf = getInstalledGgufFile(entry.id)
        return gguf.isFile && gguf.length() >= 4L
    }

    fun hasVisionProjector(entry: ModelManifestEntry): Boolean {
        if (!entry.supportsVision) return false
        val proj = getInstalledProjectorFile(entry.id)
        return proj.isFile && proj.length() >= 4L
    }

    fun bytesOnDiskMain(entry: ModelManifestEntry): Long {
        val gguf = getInstalledGgufFile(entry.id)
        return (if (gguf.exists()) gguf.length() else 0L) +
            (if (partOf(gguf).exists()) partOf(gguf).length() else 0L)
    }

    fun bytesOnDiskProjector(entry: ModelManifestEntry): Long {
        val proj = getInstalledProjectorFile(entry.id)
        return (if (proj.exists()) proj.length() else 0L) +
            (if (partOf(proj).exists()) partOf(proj).length() else 0L)
    }

    /** Byte yang sudah ada di disk (berkas final + sisa unduhan .part). */
    fun bytesOnDisk(entry: ModelManifestEntry): Long {
        fun sizeOf(f: File) = if (f.exists()) f.length() else 0L
        val gguf = getInstalledGgufFile(entry.id)
        val proj = getInstalledProjectorFile(entry.id)
        return sizeOf(gguf) + sizeOf(partOf(gguf)) + sizeOf(proj) + sizeOf(partOf(proj))
    }

    fun pauseDownload(modelId: String) {
        activeCancelFlags.entries.filter { it.key.startsWith("$modelId/") }.forEach { it.value.set(true) }
    }

    /** HEAD tanpa mengikuti redirect: Hugging Face menaruh SHA-256 di X-Linked-Etag. */
    suspend fun fetchRemoteInfo(url: String): RemoteFileInfo = withContext(Dispatchers.IO) {
        val noRedirect = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        try {
            noRedirect.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                val candidates = listOfNotNull(r.header("x-linked-etag"), r.header("etag"))
                    .map { it.trim().removePrefix("W/").trim('"') }
                val sha = candidates.firstOrNull { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
                val size = r.header("x-linked-size")?.toLongOrNull()
                RemoteFileInfo(sha?.lowercase(), size)
            }
        } catch (_: Exception) {
            RemoteFileInfo(null, null)
        }
    }

    /**
     * Unduh dengan resume (HTTP Range). Berkas ditulis ke `.part` lalu di-rename secara atomik
     * setelah ukuran dan SHA-256 cocok. Jika [expectedSha256] kosong, hash diambil dari server.
     */
    suspend fun downloadFileWithResume(
        modelId: String,
        url: String,
        expectedSha256: String,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onVerifying: () -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val cancelFlag = AtomicBoolean(false)
        val flagKey = "$modelId/${targetFile.name}"
        activeCancelFlags[flagKey] = cancelFlag

        try {
            val parent = targetFile.parentFile ?: context.filesDir
            if (!parent.exists()) parent.mkdirs()
            val partFile = partOf(targetFile)

            val remote = if (expectedSha256.isBlank()) fetchRemoteInfo(url) else RemoteFileInfo(expectedSha256, null)
            val expectedSha = expectedSha256.ifBlank { remote.sha256 ?: "" }
            val knownSize = remote.sizeBytes

            var existingBytes = if (partFile.exists()) partFile.length() else 0L
            if (knownSize != null && existingBytes > knownSize) {
                partFile.delete()
                existingBytes = 0L
            }

            var totalBytes = knownSize ?: -1L
            val alreadyComplete = knownSize != null && existingBytes == knownSize

            if (!alreadyComplete) {
                val reqBuilder = Request.Builder().url(url)
                if (existingBytes > 0L) reqBuilder.addHeader("Range", "bytes=$existingBytes-")

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.code == 416 && existingBytes > 0L) {
                        // Server menolak range karena berkas .part sudah seukuran penuh.
                        return@use
                    }
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IOException("Server menjawab HTTP ${response.code}"))
                    }
                    val body = response.body ?: return@withContext Result.failure(IOException("Respons kosong"))
                    val isResume = response.code == 206 && existingBytes > 0L
                    val contentLength = body.contentLength()
                    totalBytes = when {
                        knownSize != null -> knownSize
                        contentLength >= 0 -> if (isResume) existingBytes + contentLength else contentLength
                        else -> -1L
                    }

                    RandomAccessFile(partFile, "rw").use { raf ->
                        if (isResume) raf.seek(existingBytes) else raf.setLength(0)
                        val buffer = ByteArray(256 * 1024)
                        var current = if (isResume) existingBytes else 0L
                        body.byteStream().use { input ->
                            while (true) {
                                if (cancelFlag.get()) {
                                    return@withContext Result.failure(InterruptedException("Unduhan dijeda"))
                                }
                                val read = input.read(buffer)
                                if (read == -1) break
                                raf.write(buffer, 0, read)
                                current += read
                                onProgress(current, totalBytes)
                            }
                        }
                    }
                }
            }

            val finalLength = if (partFile.exists()) partFile.length() else 0L
            if (finalLength == 0L) {
                return@withContext Result.failure(IOException("Berkas unduhan kosong"))
            }
            if (totalBytes > 0 && finalLength < totalBytes) {
                // Koneksi putus di tengah jalan; .part dipertahankan agar bisa dilanjutkan.
                return@withContext Result.failure(IOException("Koneksi terputus di tengah unduhan"))
            }
            onProgress(finalLength, if (totalBytes > 0) totalBytes else finalLength)

            if (expectedSha.isNotBlank()) {
                onVerifying()
                val digest = sha256Hex(partFile) { cancelFlag.get() }
                    ?: return@withContext Result.failure(InterruptedException("Unduhan dijeda"))
                if (!digest.equals(expectedSha, ignoreCase = true)) {
                    partFile.delete()
                    return@withContext Result.failure(IOException("Checksum SHA-256 tidak cocok, berkas dihapus. Coba unduh ulang."))
                }
            }

            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                return@withContext Result.failure(IOException("Gagal memindahkan berkas model"))
            }
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeCancelFlags.remove(flagKey)
        }
    }

    fun deleteModelFiles(modelId: String) {
        val dir = File(context.filesDir, "models/$modelId")
        if (dir.exists()) dir.deleteRecursively()
    }

    private fun partOf(target: File) = File(target.parentFile, "${target.name}.part")

    /** Mengembalikan null bila [isCancelled] bernilai true di tengah pembacaan. */
    private fun sha256Hex(file: File, isCancelled: () -> Boolean): String? {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                if (isCancelled()) return null
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
