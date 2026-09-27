package de.astubenbord.paperless_mobile

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.appsearch.app.AppSearchBatchResult
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSchema.PropertyConfig
import androidx.appsearch.app.AppSearchSchema.StringPropertyConfig
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class IndexedDocument(val id: Int, val title: String?, val content: String?)

data class SearchHit(val id: String, val title: String, val snippet: String)

object SearchIndex {
    private const val DATABASE = "documents"
    // Renamed on schema changes: force override drops the old type, count() hits 0 and Dart runs a full sync.
    private const val SCHEMA_TYPE = "PaperlessDocumentV2"
    private const val PREFS = "search_index"
    private const val KEY_ACTIVE_USER = "active_user_id"
    private const val SHORTCUT_PREFIX = "doc_"

    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var session: AppSearchSession? = null

    @Synchronized
    private fun session(context: Context): AppSearchSession {
        session?.let { return it }
        val searchContext = LocalStorage.SearchContext.Builder(context.applicationContext, DATABASE).build()
        val created = LocalStorage.createSearchSessionAsync(searchContext).get()
        try {
            created.setSchemaAsync(
                SetSchemaRequest.Builder().addSchemas(schema()).setForceOverride(true).build()
            ).get()
        } catch (e: Throwable) {
            created.close()
            throw e
        }
        session = created
        return created
    }

    private fun schema(): AppSearchSchema = AppSearchSchema.Builder(SCHEMA_TYPE)
        .addProperty(
            StringPropertyConfig.Builder("title")
                .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                .setIndexingType(StringPropertyConfig.INDEXING_TYPE_NONE)
                .build()
        )
        .addProperty(textProperty("searchTitle"))
        .addProperty(textProperty("content"))
        .build()

    private fun textProperty(name: String) = StringPropertyConfig.Builder(name)
        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
        .setIndexingType(StringPropertyConfig.INDEXING_TYPE_PREFIXES)
        .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
        .build()

    // LocalStorage ships without ICU data, so Icing does not case-fold non-ASCII text itself.
    private fun normalize(text: String) = text.lowercase(Locale.ROOT).replace('ё', 'е')

    fun put(context: Context, userId: String, docs: List<IndexedDocument>) {
        if (docs.isEmpty()) return
        val request = PutDocumentsRequest.Builder().addGenericDocuments(docs.map { doc ->
            val builder = GenericDocument.Builder<GenericDocument.Builder<*>>(userId, doc.id.toString(), SCHEMA_TYPE)
            doc.title?.let {
                builder.setPropertyString("title", it)
                builder.setPropertyString("searchTitle", normalize(it))
            }
            doc.content?.let { builder.setPropertyString("content", normalize(it)) }
            builder.build()
        }).build()
        session(context).putAsync(request).get().throwOnFailure { false }
    }

    fun retainOnly(context: Context, userId: String, ids: List<Int>) {
        val keep = ids.map { it.toString() }.toSet()
        val staleShortcuts = ShortcutManagerCompat.getDynamicShortcuts(context)
            .map { it.id }
            .filter { it.startsWith(SHORTCUT_PREFIX) && it.removePrefix(SHORTCUT_PREFIX) !in keep }
        if (staleShortcuts.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, staleShortcuts)
        val stale = listIds(context, userId).filterNot { it in keep }
        if (stale.isEmpty()) return
        session(context).removeAsync(
            RemoveByDocumentIdRequest.Builder(userId).addIds(stale).build()
        ).get().throwOnFailure { it.resultCode == AppSearchResult.RESULT_NOT_FOUND }
    }

    private fun <V> AppSearchBatchResult<String, V>.throwOnFailure(ignore: (AppSearchResult<V>) -> Boolean) {
        val failures = failures.filterValues { !ignore(it) }
        if (failures.isNotEmpty()) {
            throw IllegalStateException(failures.entries.joinToString { "${it.key}: ${it.value.errorMessage}" })
        }
    }

    fun clear(context: Context, userId: String) {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        session(context).removeAsync("", namespaceSpec(userId).build()).get()
    }

    fun count(context: Context, userId: String): Int = listIds(context, userId).size

    fun search(context: Context, userId: String, query: String, limit: Int): List<SearchHit> {
        val terms = normalize(query).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val spec = namespaceSpec(userId)
            .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
            .setRankingStrategy(SearchSpec.RANKING_STRATEGY_RELEVANCE_SCORE)
            .setResultCountPerPage(limit)
            .setSnippetCount(limit)
            .setSnippetCountPerProperty(1)
            .setMaxSnippetSize(120)
            .build()
        val page = session(context).search(terms.joinToString(" "), spec).use { it.nextPageAsync.get() }
        return page.take(limit).map { result ->
            val doc = result.genericDocument
            val snippet = result.matchInfos.firstOrNull { it.propertyPath == "content" }?.snippet
            SearchHit(doc.id, doc.getPropertyString("title").orEmpty(), snippet?.toString().orEmpty())
        }
    }

    fun pushRecentDocument(context: Context, id: Int, title: String?) {
        val intent = Intent(context, SearchTrampolineActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(SearchManager.EXTRA_DATA_KEY, id.toString())
        val shortcut = ShortcutInfoCompat.Builder(context, "$SHORTCUT_PREFIX$id")
            .setShortLabel(title?.takeIf { it.isNotBlank() } ?: "#$id")
            .apply { if (!title.isNullOrBlank()) setLongLabel(title) }
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun setActiveUser(context: Context, userId: String?) {
        if (userId == null || userId != activeUser(context)) {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_USER, userId)
            .apply()
    }

    fun activeUser(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_USER, null)

    private fun namespaceSpec(userId: String) = SearchSpec.Builder()
        .addFilterNamespaces(userId)
        .addFilterSchemas(SCHEMA_TYPE)

    private fun listIds(context: Context, userId: String): List<String> {
        val spec = namespaceSpec(userId)
            .addProjection(SCHEMA_TYPE, emptyList())
            .setResultCountPerPage(500)
            .build()
        val ids = mutableListOf<String>()
        session(context).search("", spec).use { results ->
            while (true) {
                val page = results.nextPageAsync.get()
                if (page.isEmpty()) break
                page.mapTo(ids) { it.genericDocument.id }
            }
        }
        return ids
    }
}
