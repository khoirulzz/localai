package id.localai.app.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class WebSearchResult(
    val id: Int,
    val title: String,
    val url: String,
    val snippet: String,
    val publishedDate: String? = null
)

sealed interface SearchOutcome {
    data class Success(val results: List<WebSearchResult>, val creditsUsed: Int) : SearchOutcome
    data object MissingApiKey : SearchOutcome
    data object InvalidApiKey : SearchOutcome
    data object QuotaExceeded : SearchOutcome
    data object OfflineOrTimeout : SearchOutcome
    data class UnknownError(val message: String) : SearchOutcome
}

interface SearchProvider {
    suspend fun search(query: String, isNewsIntent: Boolean = false): SearchOutcome
    suspend fun testConnection(): SearchOutcome
}

class TavilySearchProvider(
    private val keyStore: TavilyKeyStore
) : SearchProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun search(query: String, isNewsIntent: Boolean): SearchOutcome =
        withContext(Dispatchers.IO) {
            val apiKey = keyStore.readApiKey()
            if (apiKey.isNullOrBlank()) return@withContext SearchOutcome.MissingApiKey

            val payload = JSONObject().apply {
                put("query", query.take(280))
                put("search_depth", "basic")
                put("max_results", 5)
                put("chunks_per_source", 2)
                put("topic", if (isNewsIntent) "news" else "general")
                put("include_answer", false)
                put("include_raw_content", false)
                put("auto_parameters", false)
                put("include_usage", true)
            }

            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> {
                            val bodyStr = response.body?.string().orEmpty()
                            val json = JSONObject(bodyStr)
                            val arr = json.optJSONArray("results") ?: JSONArray()
                            val parsed = mutableListOf<WebSearchResult>()
                            val seenUrls = mutableSetOf<String>()

                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                val url = item.optString("url").trim()
                                if (url.isEmpty() || !seenUrls.add(url)) continue
                                val title = item.optString("title").ifBlank { url }
                                val rawSnippet = item.optString("content")
                                val cleanSnippet = sanitizeUntrustedWebContent(rawSnippet).take(550)
                                parsed.add(
                                    WebSearchResult(
                                        id = parsed.size + 1,
                                        title = title,
                                        url = url,
                                        snippet = cleanSnippet,
                                        publishedDate = item.optString("published_date").takeIf { it.isNotBlank() }
                                    )
                                )
                            }
                            val credits = json.optJSONObject("usage")?.optInt("credits", 1) ?: 1
                            SearchOutcome.Success(parsed, credits)
                        }
                        401 -> SearchOutcome.InvalidApiKey
                        429, 432, 433 -> SearchOutcome.QuotaExceeded
                        else -> SearchOutcome.UnknownError("Status HTTP ${response.code}")
                    }
                }
            } catch (_: IOException) {
                SearchOutcome.OfflineOrTimeout
            } catch (e: Exception) {
                SearchOutcome.UnknownError(e.message ?: "Gagal menghubungi layanan pencarian.")
            }
        }

    override suspend fun testConnection(): SearchOutcome {
        return search(query = "status koneksi", isNewsIntent = false)
    }

    /**
     * Membersihkan script/tag HTML dari konten web tidak tepercaya sebelum masuk ke prompt lokal.
     */
    private fun sanitizeUntrustedWebContent(raw: String): String {
        return raw
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
