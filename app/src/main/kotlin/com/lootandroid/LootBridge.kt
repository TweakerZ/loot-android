package com.lootandroid

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/**
 * Calls the Python wrapper around libloot.
 *
 * libloot is a native Rust library, but the Android build is exposed through
 * its PyO3 Python module. Kotlin passes real filesystem paths and plain lists
 * across the bridge; Python returns a JSON string so the result shape is
 * explicit and easy to log.
 */
object LootBridge {

    fun sort(context: Context, request: LootSortRequest): LootSortResult {
        return try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }

            val module = Python.getInstance().getModule("loot_android")
            // Plugin name lists are passed as newline-separated strings because
            // Chaquopy does not reliably convert Java List arguments into
            // iterable Python lists across the callAttr boundary.
            val json = module.callAttr(
                "sort_load_order_json",
                request.gameId,
                request.gameRoot,
                request.dataDir,
                request.localPath,
                request.allPlugins.joinToString("\n"),
                request.activePlugins.joinToString("\n"),
                request.masterlistPath,
                request.userlistPath.orEmpty()
            ).toString()

            parseResult(json)
        } catch (failure: Throwable) {
            LootSortResult(
                success = false,
                error = failure.message ?: failure.javaClass.simpleName,
                liblootVersion = null,
                loadedCount = 0,
                missingHeaders = emptyList(),
                sortedAll = emptyList(),
                sortedActive = emptyList(),
                messages = emptyList(),
                generalMessages = emptyList()
            )
        }
    }

    private fun parseResult(json: String): LootSortResult {
        val obj = JSONObject(json)
        val success = obj.optBoolean("ok", false)
        val error = obj.optString("error").takeIf { it.isNotBlank() }

        return LootSortResult(
            success = success,
            error = error,
            liblootVersion = obj.optString("liblootVersion").takeIf { it.isNotBlank() },
            loadedCount = obj.optInt("loadedCount", 0),
            missingHeaders = obj.optJSONArray("missingHeaders").toStringList(),
            sortedAll = obj.optJSONArray("sortedAll").toStringList(),
            sortedActive = obj.optJSONArray("sortedActive").toStringList(),
            messages = obj.optJSONArray("messages").toMessages(),
            generalMessages = obj.optJSONArray("generalMessages").toStringList()
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optString(index).takeIf { it.isNotBlank() }
        }
    }

    private fun JSONArray?.toMessages(): List<LootMessage> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            val text = item.optString("text").trim()
            if (text.isEmpty()) return@mapNotNull null
            LootMessage(
                plugin = item.optString("plugin"),
                type = item.optString("type", "say"),
                text = text
            )
        }
    }
}

data class LootSortRequest(
    val gameId: String,
    val gameRoot: String,
    val dataDir: String,
    val localPath: String,
    val allPlugins: List<String>,
    val activePlugins: List<String>,
    val masterlistPath: String,
    val userlistPath: String?
)

data class LootSortResult(
    val success: Boolean,
    val error: String?,
    val liblootVersion: String?,
    val loadedCount: Int,
    val missingHeaders: List<String>,
    val sortedAll: List<String>,
    val sortedActive: List<String>,
    val messages: List<LootMessage>,
    val generalMessages: List<String>
)

data class LootMessage(
    val plugin: String,
    val type: String,
    val text: String
)
