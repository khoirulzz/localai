package id.localai.app.models

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class ModelManifestEntry(
    val id: String,
    val displayName: String,
    val roleSummary: String,
    val licenseName: String,
    val quantization: String,
    val ggufUrl: String,
    val ggufSha256: String,
    val ggufSizeBytes: Long,
    val supportsVision: Boolean,
    val projectorUrl: String? = null,
    val projectorSha256: String? = null,
    val projectorSizeBytes: Long = 0L,
    val minFreeRamMb: Int = 2200
)

object ModelCatalog {
    val entries = listOf(
        ModelManifestEntry(
            id = "qwen3.5-2b-q4_k_m",
            displayName = "Qwen3.5 2B",
            roleSummary = "Default seimbang · Bahasa Indonesia, analisis dokumen, dan multimodal",
            licenseName = "Apache-2.0",
            quantization = "GGUF Q4_K_M",
            ggufUrl = "https://huggingface.co/gguf/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
            ggufSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ggufSizeBytes = 1_288_490_188L, // ~1,20 GB
            supportsVision = true,
            projectorUrl = "https://huggingface.co/gguf/Qwen3.5-2B-GGUF/resolve/main/mmproj-Qwen3.5-2B-f16.gguf",
            projectorSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            projectorSizeBytes = 681_574_400L, // ~0,64 GB
            minFreeRamMb = 2800
        ),
        ModelManifestEntry(
            id = "qwen3.5-0.8b-q4_k_m",
            displayName = "Qwen3.5 0.8B",
            roleSummary = "Ringan & cepat · Hemat memori untuk obrolan teks harian",
            licenseName = "Apache-2.0",
            quantization = "GGUF Q4_K_M",
            ggufUrl = "https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen_Qwen3.5-0.8B-Q4_K_M.gguf",
            ggufSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ggufSizeBytes = 597_688_320L, // ~0,56 GB
            supportsVision = false,
            minFreeRamMb = 1600
        ),
        ModelManifestEntry(
            id = "gemma-4-e2b-it-q4_k_m",
            displayName = "Gemma 4 E2B",
            roleSummary = "Pembanding kualitas instruksi & penalaran pada runtime GGUF",
            licenseName = "Gemma Terms of Use",
            quantization = "GGUF Q4_K_M",
            ggufUrl = "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
            ggufSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ggufSizeBytes = 1_610_612_736L, // ~1,50 GB
            supportsVision = false,
            minFreeRamMb = 3100
        )
    )
}

class ModelDownloadManager(private val context: Context) {

    private val client = OkHttpClient()
    private val activeCancelFlags = mutableMapOf<String, AtomicBoolean>()

    fun getAvailableBytes(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBytes
    }

    fun hasEnoughSpaceFor(requiredBytes: Long): Boolean {
        val safetyMarginBytes = 500L * 1024L * 1024L // 500 MB safety margin
        return getAvailableBytes() >= (requiredBytes + safetyMarginBytes)
    }

    fun getInstalledGgufFile(modelId: String): File {
        return File(File(context.filesDir, "models/$modelId"), "model.gguf")
    }

    fun getInstalledProjectorFile(modelId: String): File {
        return File(File(context.filesDir, "models/$modelId"), "mmproj.gguf")
    }

    fun pauseDownload(modelId: String) {
        activeCancelFlags[modelId]?.set(true)
    }

    suspend fun downloadFileWithResume(
        modelId: String,
        url: String,
        expectedSha256: String,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val cancelFlag = AtomicBoolean(false)
        activeCancelFlags[modelId] = cancelFlag

        val parent = targetFile.parentFile ?: context.filesDir
        if (!parent.exists()) parent.mkdirs()
        val partFile = File(parent, "${targetFile.name}.part")
        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        val reqBuilder = Request.Builder().url(url)
        if (existingBytes > 0L) {
            reqBuilder.addHeader("Range", "bytes=$existingBytes-")
        }

        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return@withContext Result.failure(IllegalStateException("HTTP ${response.code}"))
                }
                val isResume = response.code == 206
                val contentLength = response.body?.contentLength() ?: -1L
                val totalBytes = if (isResume) existingBytes + contentLength else contentLength

                RandomAccessFile(partFile, "rw").use { raf ->
                    if (isResume) {
                        raf.seek(existingBytes)
                    } else {
                        raf.setLength(0)
                    }

                    val buffer = ByteArray(64 * 1024)
                    var currentBytes = if (isResume) existingBytes else 0L
                    val stream = response.body?.byteStream()
                        ?: return@withContext Result.failure(IllegalStateException("Stream kosong"))

                    stream.use { input ->
                        while (true) {
                            if (cancelFlag.get()) {
                                return@withContext Result.failure(InterruptedException("Unduhan dijeda"))
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            currentBytes += read
                            onProgress(currentBytes, totalBytes)
                        }
                    }
                }
            }

            // Verifikasi SHA-256 sebelum rename atomik
            val digest = sha256Hex(partFile)
            if (expectedSha256.isNotBlank() && !digest.equals(expectedSha256, ignoreCase = true)) {
                partFile.delete()
                return@withContext Result.failure(IllegalStateException("Checksum SHA-256 tidak cocok"))
            }

            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                return@withContext Result.failure(IllegalStateException("Gagal memindahkan file model"))
            }
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeCancelFlags.remove(modelId)
        }
    }

    fun deleteModelFiles(modelId: String) {
        val dir = File(context.filesDir, "models/$modelId")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
