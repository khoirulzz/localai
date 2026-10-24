package id.localai.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import id.localai.app.attachments.AttachmentProcessor
import id.localai.app.data.db.AppDatabase
import id.localai.app.models.ModelDownloadManager
import id.localai.app.models.ModelRepository
import id.localai.app.search.TavilyKeyStore
import id.localai.app.search.TavilySearchProvider
import id.localai.inference.llamacpp.LlamaCppEngine

/** Wadah dependensi tingkat aplikasi: hidup selama proses, tidak ikut Activity/ViewModel. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN summary TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversations ADD COLUMN summarizedThroughMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN activeDocumentId TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN thinking TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE messages ADD COLUMN generatedTokens INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN elapsedMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN tokensPerSecond REAL NOT NULL DEFAULT 0")
    }
}

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val engine = LlamaCppEngine()

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "localai.db")
            .addMigrations(MIGRATION_1_2)
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
