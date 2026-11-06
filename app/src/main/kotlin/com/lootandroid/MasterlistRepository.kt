package com.lootandroid

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and caches the LOOT masterlist for Skyrim Legendary Edition.
 *
 * The masterlist is refreshed weekly. If the network is unavailable, the last
 * cached copy is used. The first run needs a connection.
 */
class MasterlistRepository(context: Context) {

    private val cacheDir = File(context.filesDir, "masterlists")
    private val maxAgeMillis = 7L * 24L * 60L * 60L * 1000L

    fun ensureSkyrimMasterlist(): File {
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
            throw IOException("Could not create ${cacheDir.path}")
        }

        val target = File(cacheDir, "skyrim.yaml")
        val fresh = target.isFile &&
            target.length() > MINIMUM_SIZE &&
            System.currentTimeMillis() - target.lastModified() < maxAgeMillis
        if (fresh) return target

        try {
            download(MASTERLIST_URL, target)
            return target
        } catch (failure: Exception) {
            if (target.isFile && target.length() > MINIMUM_SIZE) {
                return target
            }
            throw IOException(
                "Could not download the LOOT masterlist from $MASTERLIST_URL. " +
                    "Connect to the internet once; it is cached afterwards. " +
                    failure.message.orEmpty(),
                failure
            )
        }
    }

    private fun download(url: String, target: File) {
        val temporary = File(cacheDir, "${target.name}.tmp")
        try {
            val connection = java.net.URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "loot-android/0.1")
            connection.requestMethod = "GET"

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }

            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }

            if (temporary.length() <= MINIMUM_SIZE) {
                throw IOException("Downloaded masterlist is unexpectedly small")
            }

            val looksLikeMasterlist = temporary.bufferedReader().useLines { lines ->
                lines.take(5_000).any { MASTERLIST_TOP_LEVEL.matches(it) }
            }
            if (!looksLikeMasterlist) {
                throw IOException("Downloaded file does not look like a LOOT masterlist")
            }

            if (target.exists() && !target.delete()) {
                throw IOException("Could not replace ${target.path}")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        // Keep this branch in step with the libloot version bundled in
        // app/src/main/wheels. The master branch currently uses syntax that
        // libloot 0.29 rejects (duplicate keys), while v0.29 is valid for it.
        private const val MASTERLIST_URL =
            "https://raw.githubusercontent.com/loot/skyrim/v0.29/masterlist.yaml"
        private const val MINIMUM_SIZE = 100_000L

        // The masterlist starts with comment lines, so look for a top-level
        // key rather than checking the very first line.
        private val MASTERLIST_TOP_LEVEL =
            Regex("(?m)^(plugins|globals|groups|bash_tags):")
    }
}
