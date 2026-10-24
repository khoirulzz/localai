package id.localai.app.models

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class DownloadStatus { IDLE, DOWNLOADING, VERIFYING, PAUSED, FAILED }

data class DownloadUi(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Pemilik state unduhan model. Hidup selama proses aplikasi (bukan Activity), jadi unduhan
 * tidak putus saat layar diputar atau aplikasi dipindah ke latar belakang.
 */
class ModelRepository(
    private val context: Context,
    val downloader: ModelDownloadManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val importCancel = ConcurrentHashMap<String, AtomicBoolean>()

    private val _installed = MutableStateFlow(computeInstalled())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val _downloads = MutableStateFlow(computeInitialDownloads())
    val downloads: StateFlow<Map<String, DownloadUi>> = _downloads.asStateFlow()

    // Status proyektor terpisah agar model chat tidak ikut dianggap belum terpasang.
    private val _visionInstalled = MutableStateFlow(computeVisionInstalled())
    val visionInstalled: StateFlow<Set<String>> = _visionInstalled.asStateFlow()

    private val _visionDownloads = MutableStateFlow<Map<String, DownloadUi>>(emptyMap())
    val visionDownloads: StateFlow<Map<String, DownloadUi>> = _visionDownloads.asStateFlow()

    private fun computeVisionInstalled(): Set<String> =
        ModelCatalog.entries.filter { downloader.hasVisionProjector(it) }.map { it.id }.toSet()

    private fun computeInstalled(): Set<String> =
        ModelCatalog.entries.filter { downloader.isInstalled(it) }.map { it.id }.toSet()

    /** Unduhan yang terhenti saat aplikasi dimatikan tampil sebagai "Dijeda" dengan progres terakhir. */
    private fun computeInitialDownloads(): Map<String, DownloadUi> =
        ModelCatalog.entries.associate { e ->
            val onDisk = downloader.bytesOnDiskMain(e)
            e.id to if (!downloader.isInstalled(e) && onDisk > 0L) {
                DownloadUi(DownloadStatus.PAUSED, onDisk, e.ggufSizeBytes)
            } else DownloadUi()
        }

    fun isDownloading(modelId: String): Boolean = jobs[modelId]?.isActive == true

    fun start(entry: ModelManifestEntry) {
        if (isDownloading(entry.id) || downloader.isInstalled(entry)) return

        val needed = (entry.ggufSizeBytes - downloader.bytesOnDiskMain(entry)).coerceAtLeast(0L)
        if (!downloader.hasEnoughSpaceFor(needed)) {
            setState(entry.id, DownloadUi(DownloadStatus.FAILED, message = "Ruang penyimpanan tidak cukup (butuh sekitar ${formatBytes(needed)} lagi)."))
            return
        }

        setState(entry.id, DownloadUi(DownloadStatus.DOWNLOADING, downloader.bytesOnDiskMain(entry), entry.ggufSizeBytes))
        DownloadService.start(context)

        jobs[entry.id] = scope.launch {
            val result = runDownload(entry)
            if (result.isSuccess) {
                _installed.update { it + entry.id }
                setState(entry.id, DownloadUi())
            } else {
                val e = result.exceptionOrNull()
                val paused = e is InterruptedException
                setState(
                    entry.id,
                    DownloadUi(
                        status = if (paused) DownloadStatus.PAUSED else DownloadStatus.FAILED,
                        downloadedBytes = downloader.bytesOnDiskMain(entry),
                        totalBytes = entry.ggufSizeBytes,
                        message = if (paused) null else (e?.message ?: "Unduhan gagal.")
                    )
                )
            }
            jobs.remove(entry.id)
            if (jobs.isEmpty()) DownloadService.stop(context)
        }
    }

    /** Proyektor diunduh sesudah GGUF utama; tidak diperlukan agar model bisa dipakai. */
    fun startProjector(entry: ModelManifestEntry) {
        if (!entry.supportsVision || entry.projectorUrl == null ||
            !downloader.isInstalled(entry) || downloader.hasVisionProjector(entry) ||
            isDownloading(entry.id)) return
        val needed = (entry.projectorSizeBytes - downloader.bytesOnDiskProjector(entry)).coerceAtLeast(0L)
        if (!downloader.hasEnoughSpaceFor(needed)) {
            _visionDownloads.update { it + (entry.id to DownloadUi(
                DownloadStatus.FAILED, message = "Penyimpanan tidak cukup untuk proyektor gambar."
            )) }
            return
        }
        _visionDownloads.update { it + (entry.id to DownloadUi(
            DownloadStatus.DOWNLOADING,
            downloader.bytesOnDiskProjector(entry), entry.projectorSizeBytes
        )) }
        DownloadService.start(context)
        jobs[entry.id] = scope.launch {
            val result = downloader.downloadFileWithResume(
                entry.id, entry.projectorUrl, entry.projectorSha256,
                downloader.getInstalledProjectorFile(entry.id),
                onProgress = { done, total ->
                    _visionDownloads.update { it + (entry.id to DownloadUi(
                        DownloadStatus.DOWNLOADING, done,
                        if (total > 0L) total else entry.projectorSizeBytes
                    )) }
                },
                onVerifying = {
                    _visionDownloads.update { it + (entry.id to DownloadUi(DownloadStatus.VERIFYING)) }
                }
            )
            if (result.isSuccess) {
                _visionInstalled.update { it + entry.id }
                _visionDownloads.update { it - entry.id }
            } else {
                val ex = result.exceptionOrNull()
                _visionDownloads.update { it + (entry.id to DownloadUi(
                    if (ex is InterruptedException) DownloadStatus.PAUSED else DownloadStatus.FAILED,
                    downloader.bytesOnDiskProjector(entry), entry.projectorSizeBytes,
                    ex?.message
                )) }
            }
            jobs.remove(entry.id)
            if (jobs.isEmpty()) DownloadService.stop(context)
        }
    }

    fun pause(entry: ModelManifestEntry) {
        importCancel[entry.id]?.set(true)
        downloader.pauseDownload(entry.id)
    }

    fun delete(entry: ModelManifestEntry) {
        importCancel[entry.id]?.set(true)
        downloader.pauseDownload(entry.id)
        val running = jobs.remove(entry.id)
        scope.launch {
            running?.join()
            downloader.deleteModelFiles(entry.id)
            _installed.update { it - entry.id }
            _visionInstalled.update { it - entry.id }
            _visionDownloads.update { it - entry.id }
            setState(entry.id, DownloadUi())
        }
    }

    /**
     * Impor berkas GGUF yang sudah ada di penyimpanan (mis. hasil unduhan di PC atau dari aplikasi
     * lama) supaya tidak perlu mengunduh ulang. Berkas bernama "mmproj" dianggap proyektor visi,
     * sisanya model utama. Hanya dicopy, tidak ada koneksi internet yang dipakai.
     */
    fun importFiles(entry: ModelManifestEntry, uris: List<Uri>) {
        if (uris.isEmpty() || isDownloading(entry.id)) return
        val cancel = AtomicBoolean(false)
        importCancel[entry.id] = cancel
        setState(entry.id, DownloadUi(DownloadStatus.DOWNLOADING, 0L, entry.ggufSizeBytes, null))

        jobs[entry.id] = scope.launch {
            var error: String? = null
            var doneBefore = 0L
            for (uri in uris) {
                val name = displayName(uri)
                val isProjector = name.contains("mmproj", ignoreCase = true)
                if (isProjector && !entry.supportsVision) continue
                val target = if (isProjector) downloader.getInstalledProjectorFile(entry.id)
                else downloader.getInstalledGgufFile(entry.id)
                val size = sizeOf(uri)
                if (size > 0 && !downloader.hasEnoughSpaceFor(size)) {
                    error = "Ruang penyimpanan tidak cukup untuk menyalin $name (butuh ${formatBytes(size)})."
                    break
                }
                val err = copyGguf(uri, target, cancel) { copied ->
                    setState(entry.id, DownloadUi(DownloadStatus.DOWNLOADING, doneBefore + copied, entry.ggufSizeBytes))
                }
                if (err != null) { error = err; break }
                doneBefore += target.length()
            }

            if (downloader.isInstalled(entry)) _installed.update { it + entry.id }
            if (downloader.hasVisionProjector(entry)) _visionInstalled.update { it + entry.id }
            when {
                error != null -> setState(entry.id, DownloadUi(
                    DownloadStatus.FAILED, message = error
                ))
                cancel.get() -> setState(entry.id, DownloadUi(
                    DownloadStatus.PAUSED, downloader.bytesOnDiskMain(entry), entry.ggufSizeBytes
                ))
                downloader.isInstalled(entry) || downloader.hasVisionProjector(entry) ->
                    setState(entry.id, DownloadUi())
                else -> setState(entry.id, DownloadUi(
                    DownloadStatus.FAILED, message = "Berkas GGUF belum dipilih."
                ))
            }
            importCancel.remove(entry.id)
            jobs.remove(entry.id)
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    /** Salin ke .part, cek magic "GGUF", lalu rename atomik. Mengembalikan pesan error atau null. */
    private fun copyGguf(uri: Uri, target: File, cancel: AtomicBoolean, onCopied: (Long) -> Unit): String? {
        val dir = target.parentFile ?: return "Folder tujuan tidak valid."
        dir.mkdirs()
        val part = File(dir, "${target.name}.part")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return "Berkas tidak bisa dibuka."
            input.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    var first = true
                    var lastReport = 0L
                    while (true) {
                        if (cancel.get()) { part.delete(); return "Impor dihentikan." }
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (first) {
                            first = false
                            if (n < 4 || buf[0] != 'G'.code.toByte() || buf[1] != 'G'.code.toByte() ||
                                buf[2] != 'U'.code.toByte() || buf[3] != 'F'.code.toByte()
                            ) { part.delete(); return "Berkas ini bukan model GGUF yang valid." }
                        }
                        out.write(buf, 0, n)
                        total += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 250L) { lastReport = now; onCopied(total) }
                    }
                    onCopied(total)
                }
            }
            if (part.length() == 0L) { part.delete(); return "Berkas kosong." }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) return "Gagal memindahkan berkas model."
            return null
        } catch (e: IOException) {
            part.delete()
            return "Gagal menyalin berkas: ${e.message ?: "kesalahan penyimpanan"}"
        }
    }

    private suspend fun runDownload(entry: ModelManifestEntry): Result<Unit> {
        val total = entry.ggufSizeBytes
        var lastNotify = 0L

        suspend fun fetch(url: String, sha: String, target: java.io.File, doneBefore: Long): Result<Unit> {
            if (target.exists() && target.length() > 0L) return Result.success(Unit)
            var lastUi = 0L
            var lastDownloaded = doneBefore
            val r = downloader.downloadFileWithResume(
                modelId = entry.id,
                url = url,
                expectedSha256 = sha,
                targetFile = target,
                onProgress = { done, _ ->
                    lastDownloaded = doneBefore + done
                    val now = System.currentTimeMillis()
                    if (now - lastUi >= 250L) {
                        lastUi = now
                        setState(entry.id, DownloadUi(DownloadStatus.DOWNLOADING, doneBefore + done, total))
                        if (now - lastNotify >= 1000L) {
                            lastNotify = now
                            notifyProgress(entry, doneBefore + done, total)
                        }
                    }
                },
                onVerifying = {
                    setState(entry.id, DownloadUi(DownloadStatus.VERIFYING, lastDownloaded, total))
                }
            )
            return r.map { }
        }

        val main = fetch(entry.ggufUrl, entry.ggufSha256, downloader.getInstalledGgufFile(entry.id), 0L)
        if (main.isFailure) return main

        // Vision adalah komponen tambahan yang boleh diunduh/impor kapan saja.
        return Result.success(Unit)
    }

    private fun setState(modelId: String, state: DownloadUi) {
        _downloads.update { it + (modelId to state) }
    }

    private fun notifyProgress(entry: ModelManifestEntry, done: Long, total: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val n = NotificationCompat.Builder(context, DownloadService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Mengunduh ${entry.displayName}")
            .setContentText("${formatBytes(done)} dari ${formatBytes(total)} · $pct%")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, false)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(DownloadService.NOTIFICATION_ID, n) }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1_000_000L) return "${bytes / 1000} KB"
    if (bytes < 1_000_000_000L) return "${bytes / 1_000_000} MB"
    return "%.2f GB".format(java.util.Locale("id", "ID"), bytes / 1_000_000_000.0)
}

/** Foreground service tipis: hanya menjaga proses tetap hidup selama unduhan berjalan. */
class DownloadService : android.app.Service() {

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Mengunduh model")
            .setContentText("Unduhan berjalan di latar belakang")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL_ID = "model_downloads"
        const val NOTIFICATION_ID = 4101

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val mgr = context.getSystemService(NotificationManager::class.java)
                if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                    mgr.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Unduhan model", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }
    }
}
