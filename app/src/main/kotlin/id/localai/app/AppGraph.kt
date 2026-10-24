package id.localai.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import id.localai.app.attachments.AttachmentProcessor
import id.localai.app.data.db.AppDatabase
import id.localai.app.models.ModelDownloadManager
import id.localai.app.models.ModelRepository
import id.localai.app.search.TavilyKeyStore
import id.localai.app.search.TavilySearchProvider
import id.localai.inference.llamacpp.LlamaCppEngine

/** Wadah dependensi tingkat aplikasi: hidup selama proses, tidak ikut Activity/ViewModel. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val engine = LlamaCppEngine()

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "localai.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val models: ModelRepository by lazy { ModelRepository(appContext, ModelDownloadManager(appContext)) }

    /** PdfBox memuat aset saat init, jadi dibuat malas dan dipanggil dari thread IO. */
    val attachments: AttachmentProcessor by lazy { AttachmentProcessor(appContext, database.chatDao()) }

    val keyStore = TavilyKeyStore(appContext)
    val search = TavilySearchProvider(keyStore)
}

class LocalAiApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
