package de.astubenbord.paperless_mobile

import android.content.Context
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSchema.LongPropertyConfig
import androidx.appsearch.app.AppSearchSchema.PropertyConfig
import androidx.appsearch.app.AppSearchSchema.StringPropertyConfig
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage

data class IndexedDocument(val id: Int, val title: String?, val content: String?, val created: Long?)

data class SearchHit(val id: String, val title: String, val snippet: String)

object SearchIndex {
    private const val DATABASE = "documents"
    private const val SCHEMA_TYPE = "PaperlessDocument"
    private const val PREFS = "search_index"
    private const val KEY_ACTIVE_USER = "active_user_id"

    @Volatile
    private var session: AppSearchSession? = null

    @Synchronized
    private fun session(context: Context): AppSearchSession {
        session?.let { return it }
        val searchContext = LocalStorage.SearchContext.Builder(context.applicationContext, DATABASE).build()
        val created = LocalStorage.createSearchSessionAsync(searchContext).get()
        created.setSchemaAsync(
            SetSchemaRequest.Builder().addSchemas(schema()).setForceOverride(false).build()
        ).get()
        session = created
        return created
    }

    private fun schema(): AppSearchSchema = AppSearchSchema.Builder(SCHEMA_TYPE)
        .addProperty(textProperty("title"))
        .addProperty(textProperty("content"))
        .addProperty(
            LongPropertyConfig.Builder("created")
                .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                .build()
        )
        .build()

    private fun textProperty(name: String) = StringPropertyConfig.Builder(name)
        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
        .setIndexingType(StringPropertyConfig.INDEXING_TYPE_PREFIXES)
        .setTokenizerType(StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
        .build()

    fun put(context: Context, userId: String, docs: List<IndexedDocument>) {
        if (docs.isEmpty()) return
        val request = PutDocumentsRequest.Builder().addGenericDocuments(docs.map { doc ->
            val builder = GenericDocument.Builder<GenericDocument.Builder<*>>(userId, doc.id.toString(), SCHEMA_TYPE)
            doc.title?.let { builder.setPropertyString("title", it) }
            doc.content?.let { builder.setPropertyString("content", it) }
            doc.created?.let { builder.setPropertyLong("created", it) }
            builder.build()
        }).build()
        session(context).putAsync(request).get()
    }

    fun retainOnly(context: Context, userId: String, ids: List<Int>) {
        val keep = ids.map { it.toString() }.toSet()
        val stale = listIds(context, userId).filterNot { it in keep }
        if (stale.isEmpty()) return
        session(context).removeAsync(
            RemoveByDocumentIdRequest.Builder(userId).addIds(stale).build()
        ).get()
    }

    fun clear(context: Context, userId: String) {
        session(context).removeAsync("", namespaceSpec(userId).build()).get()
    }

    fun count(context: Context, userId: String): Int = listIds(context, userId).size

    fun search(context: Context, userId: String, query: String, limit: Int): List<SearchHit> {
        val terms = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val spec = namespaceSpec(userId)
            .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
            .setResultCountPerPage(limit)
            .setSnippetCount(1)
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

    fun setActiveUser(context: Context, userId: String?) {
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
