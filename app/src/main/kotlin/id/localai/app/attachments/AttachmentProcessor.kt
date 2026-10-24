package id.localai.app.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import id.localai.app.data.db.ChatDao
import id.localai.app.data.db.PdfChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

sealed interface PdfImportResult {
    data class Ready(
        val documentId: String,
        val fileName: String,
        val totalPages: Int,
        val emptyPageCount: Int
    ) : PdfImportResult

    data object NoTextLayer : PdfImportResult
    data object Encrypted : PdfImportResult
    data class Error(val message: String) : PdfImportResult
}

data class DownsampledImage(
    val localPath: String,
    val fileName: String,
    val width: Int,
    val height: Int
)

class AttachmentProcessor(
    private val context: Context,
    private val chatDao: ChatDao
) {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    suspend fun importAndIndexPdf(
        uri: Uri,
        displayName: String,
        onPageProgress: (currentPage: Int, totalPages: Int) -> Unit
    ): PdfImportResult = withContext(Dispatchers.IO) {
        val docId = UUID.randomUUID().toString()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { pdf ->
                    if (pdf.isEncrypted) {
                        return@withContext PdfImportResult.Encrypted
                    }
                    val totalPages = pdf.numberOfPages
                    if (totalPages <= 0) {
                        return@withContext PdfImportResult.NoTextLayer
                    }

                    val stripper = PDFTextStripper()
                    val chunks = mutableListOf<PdfChunkEntity>()
                    var emptyPages = 0
                    var nonBlankChars = 0

                    for (page in 1..totalPages) {
                        onPageProgress(page, totalPages)
                        stripper.startPage = page
                        stripper.endPage = page
                        val pageText = stripper.getText(pdf).orEmpty().trim()
                        if (pageText.isBlank()) {
                            emptyPages++
                            continue
                        }
                        nonBlankChars += pageText.length
                        val pageChunks = splitIntoOverlappingChunks(pageText, maxChars = 700, overlapChars = 120)
                        for (segment in pageChunks) {
                            chunks.add(
                                PdfChunkEntity(
                                    documentId = docId,
                                    documentName = displayName,
                                    pageNumber = page,
                                    content = segment
                                )
                            )
                        }
                    }

                    if (nonBlankChars < 24 || chunks.isEmpty()) {
                        return@withContext PdfImportResult.NoTextLayer
                    }

                    chatDao.insertPdfChunks(chunks)
                    return@withContext PdfImportResult.Ready(
                        documentId = docId,
                        fileName = displayName,
                        totalPages = totalPages,
                        emptyPageCount = emptyPages
                    )
                }
            }
            PdfImportResult.Error("Gagal membuka dokumen PDF.")
        } catch (e: Exception) {
            PdfImportResult.Error(e.message ?: "Dokumen PDF tidak dapat dibaca.")
        }
    }

    suspend fun prepareImageForVlm(uri: Uri, displayName: String, maxDimensionPx: Int = 1024): DownsampledImage? =
        withContext(Dispatchers.IO) {
            try {
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOptions)
                }
                val srcW = boundsOptions.outWidth
                val srcH = boundsOptions.outHeight
                if (srcW <= 0 || srcH <= 0) return@withContext null

                var sampleSize = 1
                while (max(srcW / sampleSize, srcH / sampleSize) > maxDimensionPx) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val decoded: Bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                } ?: return@withContext null

                val dir = File(context.filesDir, "attachments")
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, "img_${System.currentTimeMillis()}.jpg")
                FileOutputStream(outFile).use { out ->
                    decoded.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                DownsampledImage(
                    localPath = outFile.absolutePath,
                    fileName = displayName,
                    width = decoded.width,
                    height = decoded.height
                )
            } catch (_: Exception) {
                null
            }
        }

    private fun splitIntoOverlappingChunks(text: String, maxChars: Int, overlapChars: Int): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= maxChars) return listOf(clean)
        val result = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            val end = (start + maxChars).coerceAtMost(clean.length)
            result.add(clean.substring(start, end))
            if (end == clean.length) break
            start = (end - overlapChars).coerceAtLeast(start + 1)
        }
        return result
    }
}
