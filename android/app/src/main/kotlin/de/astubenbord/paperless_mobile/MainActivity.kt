package de.astubenbord.paperless_mobile

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterFragmentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var channel: MethodChannel? = null
    private var pendingDocumentId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingDocumentId = documentId(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = documentId(intent) ?: return
        val channel = channel
        if (channel != null) {
            pendingDocumentId = null
            channel.invokeMethod("openDocument", mapOf("id" to id))
        } else {
            pendingDocumentId = id
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SEARCH_INDEX_CHANNEL).apply {
            setMethodCallHandler(::onMethodCall)
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        channel?.setMethodCallHandler(null)
        channel = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    private fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "takePendingDocumentId") {
            result.success(pendingDocumentId)
            pendingDocumentId = null
            return
        }
        val context = applicationContext
        executor.execute {
            try {
                val value: Any? = when (call.method) {
                    "put" -> {
                        val docs = call.argument<List<Map<String, Any?>>>("documents")!!.map {
                            IndexedDocument(
                                id = (it["id"] as Number).toInt(),
                                title = it["title"] as String?,
                                content = it["content"] as String?,
                                created = (it["created"] as Number?)?.toLong(),
                            )
                        }
                        SearchIndex.put(context, call.userId(), docs)
                        null
                    }
                    "retainOnly" -> {
                        val ids = call.argument<List<Number>>("ids")!!.map { it.toInt() }
                        SearchIndex.retainOnly(context, call.userId(), ids)
                        null
                    }
                    "clear" -> {
                        SearchIndex.clear(context, call.userId())
                        null
                    }
                    "count" -> SearchIndex.count(context, call.userId())
                    "setActiveUser" -> {
                        SearchIndex.setActiveUser(context, call.argument<String>("userId"))
                        null
                    }
                    else -> {
                        mainHandler.post { result.notImplemented() }
                        return@execute
                    }
                }
                mainHandler.post { result.success(value) }
            } catch (e: Exception) {
                mainHandler.post { result.error("search_index", e.message, null) }
            }
        }
    }

    private fun MethodCall.userId(): String = argument<String>("userId")!!

    private fun documentId(intent: Intent): Int? =
        if (intent.hasExtra(EXTRA_OPEN_DOCUMENT_ID)) intent.getIntExtra(EXTRA_OPEN_DOCUMENT_ID, 0) else null

    companion object {
        const val EXTRA_OPEN_DOCUMENT_ID = "open_document_id"
        private const val SEARCH_INDEX_CHANNEL = "de.astubenbord.paperless_mobile/search_index"
    }
}
