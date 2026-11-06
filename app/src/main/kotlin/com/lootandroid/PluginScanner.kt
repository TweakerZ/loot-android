package com.lootandroid

import java.io.File
import java.io.IOException

/**
 * Reads and writes Skyrim's plugin lists as real files.
 *
 * DML writes one plugin name per line with no "*" prefix, so LOOT Android does
 * the same. A leading "*" is stripped when reading so lists produced by other
 * tools still work.
 */
object PluginScanner {

    private val pluginExtensions = setOf("esp", "esm", "esl")

    fun scanPlugins(dataDir: File): List<File> {
        if (!dataDir.isDirectory) return emptyList()

        return dataDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.lowercase() in pluginExtensions }
            ?.sortedWith(
                compareBy<File> {
                    when (it.extension.lowercase()) {
                        "esm" -> 0
                        "esl" -> 1
                        else -> 2
                    }
                }.thenBy { it.name.lowercase() }
            )
            ?.toList()
            ?: emptyList()
    }

    fun readPluginNames(file: File): List<String> {
        if (!file.isFile) return emptyList()

        return file.readLines()
            .asSequence()
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .distinctBy { it.lowercase() }
            .toList()
    }

    fun writePluginNames(file: File, names: List<String>) {
        val parent = file.parentFile
            ?: throw IOException("No parent folder for ${file.path}")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create ${parent.path}")
        }

        if (file.isFile) {
            val backup = File(parent, "${file.name}.lootbak")
            try {
                file.copyTo(backup, overwrite = true)
            } catch (_: Exception) {
                // A backup is nice to have, not required for sorting.
            }
        }

        val text = if (names.isEmpty()) "" else names.joinToString(separator = "\n") + "\n"
        file.writeText(text)
    }
}
