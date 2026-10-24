package id.localai.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val activeModelId: String,
    val updatedAtMs: Long
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // "user" | "assistant"
    val content: String,
    val attachmentName: String? = null,
    val attachmentType: String? = null, // "image" | "pdf"
    val attachmentPages: Int? = null,
    val status: String = "COMPLETED", // "STREAMING" | "COMPLETED" | "STOPPED" | "FAILED"
    val errorText: String? = null,
    val createdAtMs: Long
)

@Entity(
    tableName = "citations",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("messageId")]
)
data class CitationEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val title: String,
    val urlOrPage: String,
    val sourceType: String, // "WEB" | "PDF"
    val accessedAtMs: Long
)

@Entity(tableName = "pdf_chunks")
data class PdfChunkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Int = 0,
    val documentId: String,
    val documentName: String,
    val pageNumber: Int,
    val content: String
)

@Fts4(contentEntity = PdfChunkEntity::class)
@Entity(tableName = "pdf_chunks_fts")
data class PdfChunkFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int,
    val content: String
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAtMs DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' ORDER BY updatedAtMs DESC")
    suspend fun searchConversations(query: String): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMs ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND createdAtMs >= :fromTimestampMs")
    suspend fun deleteMessagesFrom(conversationId: String, fromTimestampMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCitations(citations: List<CitationEntity>)

    @Query("SELECT * FROM citations WHERE messageId = :messageId")
    suspend fun getCitationsForMessage(messageId: String): List<CitationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPdfChunks(chunks: List<PdfChunkEntity>)

    @Query("""
        SELECT pdf_chunks.* FROM pdf_chunks
        JOIN pdf_chunks_fts ON pdf_chunks.rowid = pdf_chunks_fts.rowid
        WHERE pdf_chunks.documentId = :documentId AND pdf_chunks_fts MATCH :ftsQuery
        LIMIT :limit
    """)
    suspend fun searchPdfChunks(documentId: String, ftsQuery: String, limit: Int = 4): List<PdfChunkEntity>

    /** Cadangan bila pertanyaan tidak punya kata kunci yang berguna (mis. "ringkas dokumen ini"). */
    @Query("SELECT * FROM pdf_chunks WHERE documentId = :documentId ORDER BY pageNumber ASC, rowid ASC LIMIT :limit")
    suspend fun getFirstChunks(documentId: String, limit: Int = 6): List<PdfChunkEntity>

    @Query("DELETE FROM pdf_chunks WHERE documentId = :documentId")
    suspend fun deletePdfChunks(documentId: String)
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        CitationEntity::class,
        PdfChunkEntity::class,
        PdfChunkFtsEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
