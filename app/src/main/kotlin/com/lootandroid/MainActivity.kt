package com.lootandroid

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lootandroid.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val masterlistRepository by lazy { MasterlistRepository(this) }

    private val sessionLog = StringBuilder()
    private var lastSortLog: String? = null
    private var pendingFileTarget: FileTarget? = null

    private enum class FileTarget { PLUGINS, LOADORDER }

    private val directoryPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { treeUri ->
                persistPermission(treeUri)
                val folder = StoragePaths.resolve(this, treeUri)
                if (folder == null) {
                    promptForPath(
                        getString(R.string.label_game_folder),
                        suggestPath(),
                        onAccepted = { path -> applyGameFolder(File(path)) }
                    )
                } else {
                    applyGameFolder(folder)
                }
            }
        }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { documentUri ->
                persistPermission(documentUri)
                val target = pendingFileTarget ?: return@let
                pendingFileTarget = null
                val file = StoragePaths.resolve(this, documentUri)
                if (file == null) {
                    promptForPath(
                        labelFor(target),
                        suggestPath(target),
                        onAccepted = { path -> applyFile(target, File(path)) }
                    )
                } else {
                    applyFile(target, file)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spGame.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.game_skyrim_le))
        )

        binding.btnStoragePermission.setOnClickListener {
            StoragePaths.requestAllFilesAccess(this)
        }

        binding.btnSelectGameFolder.setOnClickListener {
            directoryPicker.launch(null)
        }

        binding.btnSelectPlugins.setOnClickListener {
            pendingFileTarget = FileTarget.PLUGINS
            filePicker.launch(arrayOf("*/*"))
        }

        binding.btnSelectLoadorder.setOnClickListener {
            pendingFileTarget = FileTarget.LOADORDER
            filePicker.launch(arrayOf("*/*"))
        }

        binding.btnSortLoot.setOnClickListener { startSort() }
        binding.btnViewLogs.setOnClickListener { showLogs() }

        restoreSelections()
        refreshStorageStatus()
        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshStorageStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001) {
            refreshStorageStatus()
        }
    }

    /* ---------- Selection state ---------- */

    private fun restoreSelections() {
        binding.tvGameFolder.text =
            prefs.getString(PREF_GAME_ROOT, null) ?: getString(R.string.msg_not_selected)
        binding.tvPluginsPath.text =
            prefs.getString(PREF_PLUGINS_PATH, null) ?: getString(R.string.msg_not_selected)
        binding.tvLoadorderPath.text =
            prefs.getString(PREF_LOADORDER_PATH, null) ?: getString(R.string.msg_not_selected)
    }

    private fun applyGameFolder(picked: File) {
        if (!picked.isDirectory) {
            toast("Folder does not exist: ${picked.path}")
            return
        }

        val root = resolveGameRoot(picked)

        prefs.edit().putString(PREF_GAME_ROOT, root.absolutePath).apply()
        binding.tvGameFolder.text = root.absolutePath
        log("Game folder: ${root.absolutePath}")

        val dataDir = File(root, "Data")
        if (!dataDir.isDirectory) {
            toast("Warning: ${dataDir.path} does not exist")
            log("Warning: Data folder not found at ${dataDir.path}")
        }

        autoDetectLocalFiles(root)
        refreshSummary()
    }

    /**
     * Works out the folder that contains Data, because users may pick the
     * install root, the Data folder, or a launcher "port" folder which only
     * contains save/.
     */
    private fun resolveGameRoot(picked: File): File {
        if (File(picked, "Data").isDirectory) return picked

        val parent = picked.parentFile
        if (parent != null && File(parent, "Data").isDirectory) return parent

        return picked
    }

    private fun autoDetectLocalFiles(root: File) {
        // DML static ports commonly keep the launcher folder at
        // <root>/port/save/AppData/Local/Skyrim, while other ports write
        // directly under <root>/save.
        val candidates = listOf(
            File(root, "save/AppData/Local/Skyrim"),
            File(root, "port/save/AppData/Local/Skyrim"),
            File(root, "AppData/Local/Skyrim"),
            File(root, "save/AppData/Local/Skyrim Special Edition")
        )

        val localFolder = candidates.firstOrNull { folder ->
            File(folder, "Plugins.txt").isFile || File(folder, "loadorder.txt").isFile
        } ?: candidates.first()

        maybeAdoptDiscoveredFile(File(localFolder, "Plugins.txt"), PREF_PLUGINS_PATH, root)
        maybeAdoptDiscoveredFile(File(localFolder, "loadorder.txt"), PREF_LOADORDER_PATH, root)
    }

    private fun maybeAdoptDiscoveredFile(discovered: File, prefKey: String, root: File) {
        if (!discovered.isFile) return

        val current = prefs.getString(prefKey, null)
        val currentIsUsable = current != null &&
            File(current).isFile &&
            isUnder(root, File(current))

        if (currentIsUsable) return

        prefs.edit().putString(prefKey, discovered.absolutePath).apply()
        updatePathViews()
        log("Found ${discovered.name}: ${discovered.absolutePath}")
    }

    private fun applyFile(target: FileTarget, file: File) {
        if (!file.isFile) {
            toast("File does not exist: ${file.path}")
            return
        }

        when (target) {
            FileTarget.PLUGINS -> {
                prefs.edit().putString(PREF_PLUGINS_PATH, file.absolutePath).apply()
                val siblingLoadOrder = File(file.parentFile, "loadorder.txt")
                if (siblingLoadOrder.isFile &&
                    prefs.getString(PREF_LOADORDER_PATH, null).isNullOrBlank()
                ) {
                    prefs.edit()
                        .putString(PREF_LOADORDER_PATH, siblingLoadOrder.absolutePath)
                        .apply()
                }
            }

            FileTarget.LOADORDER -> {
                prefs.edit().putString(PREF_LOADORDER_PATH, file.absolutePath).apply()
                val siblingPlugins = File(file.parentFile, "Plugins.txt")
                if (siblingPlugins.isFile &&
                    prefs.getString(PREF_PLUGINS_PATH, null).isNullOrBlank()
                ) {
                    prefs.edit()
                        .putString(PREF_PLUGINS_PATH, siblingPlugins.absolutePath)
                        .apply()
                }
            }
        }

        updatePathViews()
        log("${labelFor(target)}: ${file.absolutePath}")
        refreshSummary()
    }

    private fun updatePathViews() {
        binding.tvGameFolder.text =
            prefs.getString(PREF_GAME_ROOT, null) ?: getString(R.string.msg_not_selected)
        binding.tvPluginsPath.text =
            prefs.getString(PREF_PLUGINS_PATH, null) ?: getString(R.string.msg_not_selected)
        binding.tvLoadorderPath.text =
            prefs.getString(PREF_LOADORDER_PATH, null) ?: getString(R.string.msg_not_selected)
    }

    /* ---------- Storage ---------- */

    private fun refreshStorageStatus() {
        val granted = StoragePaths.hasAllFilesAccess(this)
        binding.tvStorageStatus.text = getString(
            if (granted) R.string.storage_granted else R.string.storage_missing
        )
        binding.btnStoragePermission.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun persistPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some providers only grant read access; the path mapping is what
            // matters, because all reads and writes happen through File.
        }
    }

    /* ---------- Summary ---------- */

    private fun refreshSummary() {
        val rootPath = prefs.getString(PREF_GAME_ROOT, null) ?: return
        val pluginsPath = prefs.getString(PREF_PLUGINS_PATH, null)
        val loadorderPath = prefs.getString(PREF_LOADORDER_PATH, null)

        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                val root = File(rootPath)
                val dataDir = File(root, "Data")
                val found = PluginScanner.scanPlugins(dataDir)
                val active = pluginsPath?.let { PluginScanner.readPluginNames(File(it)) }.orEmpty()
                val order = loadorderPath?.let { PluginScanner.readPluginNames(File(it)) }.orEmpty()

                val foundKeys = found.map { it.name.lowercase() }.toSet()
                val missingActive = active.filter { it.lowercase() !in foundKeys }

                buildString {
                    append("Data: ${if (dataDir.isDirectory) "found" else "missing"} · ")
                    append("${found.size} plugins · ")
                    append("${active.size} active · ")
                    append("${order.size} in loadorder.txt")
                    if (missingActive.isNotEmpty()) {
                        append("\nNot installed but listed in Plugins.txt: ")
                        append(missingActive.joinToString(", "))
                    }
                }
            }

            binding.tvSummary.text = summary
        }
    }

    /* ---------- Sorting ---------- */

    private fun startSort() {
        if (!StoragePaths.hasAllFilesAccess(this)) {
            toast("Grant all-files access first: libloot needs real filesystem paths")
            StoragePaths.requestAllFilesAccess(this)
            return
        }

        val root = prefs.getString(PREF_GAME_ROOT, null)?.let(::File)
        val pluginsFile = prefs.getString(PREF_PLUGINS_PATH, null)?.let(::File)
        val loadorderFile = prefs.getString(PREF_LOADORDER_PATH, null)?.let(::File)

        if (root == null || !root.isDirectory) {
            toast("Select the Skyrim port folder first")
            return
        }
        val dataDir = File(root, "Data")
        if (!dataDir.isDirectory) {
            toast("Data folder not found: ${dataDir.path}")
            return
        }
        if (pluginsFile == null || !pluginsFile.isFile) {
            toast("Select Plugins.txt first")
            return
        }
        if (loadorderFile == null || !loadorderFile.isFile) {
            toast("Select loadorder.txt first")
            return
        }

        setBusy(true)
        binding.tvStatus.text = getString(R.string.status_sorting)
        log("Starting LOOT sort")
        log("Data: ${dataDir.absolutePath}")
        log("Plugins.txt: ${pluginsFile.absolutePath}")
        log("loadorder.txt: ${loadorderFile.absolutePath}")

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                performSort(root, dataDir, pluginsFile, loadorderFile)
            }

            setBusy(false)
            if (outcome.success) {
                binding.tvStatus.text = getString(R.string.status_sorted)
                log(outcome.message)
                toast(outcome.message)
            } else {
                binding.tvStatus.text = getString(R.string.status_failed)
                log("FAILED: ${outcome.message}")
                toast(outcome.message)
                if (outcome.log != null) {
                    lastSortLog = outcome.log
                    saveLastLog(outcome.log)
                    showInlineLog(outcome.log)
                }
            }
            refreshSummary()
        }
    }

    private fun performSort(
        root: File,
        dataDir: File,
        pluginsFile: File,
        loadorderFile: File
    ): SortOutcome {
        return try {
            val found = PluginScanner.scanPlugins(dataDir)
            if (found.isEmpty()) {
                return SortOutcome(false, "No ESP/ESM/ESL plugins found in ${dataDir.path}")
            }

            val byLower = found.associateBy { it.name.lowercase() }
            val loadOrderNames = PluginScanner.readPluginNames(loadorderFile)
            val activeNames = PluginScanner.readPluginNames(pluginsFile)

            val warnings = mutableListOf<String>()
            val complete = LinkedHashMap<String, String>()

            fun addName(name: String) {
                val actual = byLower[name.lowercase()]
                if (actual == null) {
                    val warning = "Not installed: $name"
                    if (warning !in warnings) warnings += warning
                } else {
                    complete.putIfAbsent(actual.name.lowercase(), actual.name)
                }
            }

            loadOrderNames.forEach(::addName)
            activeNames.forEach(::addName)
            found.forEach { complete.putIfAbsent(it.name.lowercase(), it.name) }

            val allPlugins = complete.values.toList()
            val activePlugins = activeNames
                .mapNotNull { byLower[it.lowercase()]?.name }
                .distinctBy { it.lowercase() }

            if (activeNames.isNotEmpty() && activePlugins.isEmpty()) {
                return SortOutcome(
                    false,
                    "None of the plugins in Plugins.txt exist in the Data folder"
                )
            }

            val masterlist = masterlistRepository.ensureSkyrimMasterlist()
            val userlist = File(pluginsFile.parentFile, "userlist.yaml").takeIf { it.isFile }

            val result = LootBridge.sort(
                this,
                LootSortRequest(
                    gameId = "skyrim_le",
                    gameRoot = root.absolutePath,
                    dataDir = dataDir.absolutePath,
                    localPath = pluginsFile.parentFile?.absolutePath ?: root.absolutePath,
                    allPlugins = allPlugins,
                    activePlugins = activePlugins,
                    masterlistPath = masterlist.absolutePath,
                    userlistPath = userlist?.absolutePath
                )
            )

            val log = buildReport(
                result = result,
                root = root,
                dataDir = dataDir,
                pluginsFile = pluginsFile,
                loadorderFile = loadorderFile,
                masterlist = masterlist,
                userlist = userlist,
                foundCount = found.size,
                warnings = warnings
            )

            lastSortLog = log
            saveLastLog(log)

            if (!result.success) {
                return SortOutcome(
                    false,
                    "LOOT failed: ${result.error ?: "unknown error"}",
                    log
                )
            }

            if (result.sortedAll.isEmpty()) {
                return SortOutcome(false, "LOOT returned an empty load order", log)
            }

            PluginScanner.writePluginNames(pluginsFile, result.sortedActive)
            PluginScanner.writePluginNames(loadorderFile, result.sortedAll)

            SortOutcome(
                true,
                "Sorted ${result.sortedAll.size} plugins (${result.sortedActive.size} active)",
                log
            )
        } catch (failure: Throwable) {
            val message = failure.message ?: failure.javaClass.simpleName
            val log = "LOOT Android sort failed\n${failure.javaClass.name}: $message"
            lastSortLog = log
            saveLastLog(log)
            SortOutcome(false, message, log)
        }
    }

    private fun buildReport(
        result: LootSortResult,
        root: File,
        dataDir: File,
        pluginsFile: File,
        loadorderFile: File,
        masterlist: File,
        userlist: File?,
        foundCount: Int,
        warnings: List<String>
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        return buildString {
            appendLine("LOOT Android — sort report")
            appendLine("Time: $timestamp")
            appendLine("Game: Skyrim Legendary Edition")
            appendLine("libloot: ${result.liblootVersion ?: "unknown"}")
            appendLine("Game folder: ${root.absolutePath}")
            appendLine("Data folder: ${dataDir.absolutePath}")
            appendLine("Masterlist: ${masterlist.absolutePath}")
            appendLine("Userlist: ${userlist?.absolutePath ?: "(none)"}")
            appendLine("Plugins.txt: ${pluginsFile.absolutePath}")
            appendLine("loadorder.txt: ${loadorderFile.absolutePath}")
            appendLine()
            appendLine("Plugins found: $foundCount")
            appendLine("Plugins loaded by libloot: ${result.loadedCount}")
            appendLine("Active plugins: ${result.sortedActive.size}")
            appendLine("Sorted plugins: ${result.sortedAll.size}")

            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine("Input warnings:")
                warnings.forEach { appendLine("  - $it") }
            }

            if (result.missingHeaders.isNotEmpty()) {
                appendLine()
                appendLine("Could not read plugin headers (left at the end):")
                result.missingHeaders.forEach { appendLine("  - $it") }
            }

            appendLine()
            appendLine("LOOT messages:")
            if (result.messages.isEmpty()) {
                appendLine("  (none)")
            } else {
                result.messages.forEach { message ->
                    val source = if (message.plugin.isBlank()) "general" else message.plugin
                    appendLine("  [${message.type}] $source: ${message.text}")
                }
            }

            if (result.generalMessages.isNotEmpty()) {
                appendLine()
                appendLine("libloot log:")
                result.generalMessages.forEach { appendLine("  $it") }
            }

            if (result.error != null) {
                appendLine()
                appendLine("Error: ${result.error}")
            }

            appendLine()
            appendLine("Sorted load order:")
            result.sortedAll.forEachIndexed { index, name ->
                appendLine(String.format(Locale.ROOT, "%4d. %s", index + 1, name))
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progressSort.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnSortLoot.isEnabled = !busy
        binding.btnSelectGameFolder.isEnabled = !busy
        binding.btnSelectPlugins.isEnabled = !busy
        binding.btnSelectLoadorder.isEnabled = !busy
        binding.btnViewLogs.isEnabled = !busy
    }

    /* ---------- Logs ---------- */

    private fun showLogs() {
        val log = lastSortLog ?: runCatching {
            File(filesDir, LAST_LOG_NAME).takeIf { it.isFile }?.readText()
        }.getOrNull()

        if (log.isNullOrBlank()) {
            toast(getString(R.string.dialog_logs_empty))
            return
        }

        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            this.text = log
        }

        val scroll = ScrollView(this).apply {
            addView(
                text,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_logs_title)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun saveLastLog(log: String) {
        runCatching { File(filesDir, LAST_LOG_NAME).writeText(log) }
        showInlineLog(log)
    }

    private fun showInlineLog(log: String) {
        binding.tvLogs.text = log.takeLast(4000)
        binding.tvLogs.post {
            val lineCount = binding.tvLogs.lineCount
            val layout = binding.tvLogs.layout
            if (lineCount > 0 && layout != null) {
                val scrollAmount = layout.getLineBottom(lineCount - 1) - binding.tvLogs.height
                binding.tvLogs.scrollTo(0, scrollAmount.coerceAtLeast(0))
            }
        }
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        sessionLog.append("[$timestamp] ").append(message).append('\n')
        binding.tvLogs.text = sessionLog.takeLast(4000)
    }

    /* ---------- Manual path fallback ---------- */

    private fun promptForPath(title: String, initial: String, onAccepted: (String) -> Unit) {
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            setText(initial)
            hint = getString(R.string.dialog_path_hint)
        }

        val container = FrameLayout(this).apply {
            setPadding(24, 8, 24, 0)
            addView(
                editText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(R.string.dialog_path_message)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val path = editText.text.toString().trim()
                if (path.isNotEmpty()) onAccepted(path)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun suggestPath(): String = "/storage/emulated/0/"

    private fun suggestPath(target: FileTarget): String {
        val root = prefs.getString(PREF_GAME_ROOT, null)
        val base = root ?: suggestPath()
        return when (target) {
            FileTarget.PLUGINS -> "$base/save/AppData/Local/Skyrim/Plugins.txt"
            FileTarget.LOADORDER -> "$base/save/AppData/Local/Skyrim/loadorder.txt"
        }
    }

    private fun labelFor(target: FileTarget): String {
        return when (target) {
            FileTarget.PLUGINS -> "Plugins.txt"
            FileTarget.LOADORDER -> "loadorder.txt"
        }
    }

    private fun isUnder(root: File, file: File): Boolean {
        return try {
            val rootPath = root.canonicalPath.trimEnd(File.separatorChar)
            file.canonicalPath.startsWith("$rootPath${File.separator}")
        } catch (_: Exception) {
            false
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private data class SortOutcome(
        val success: Boolean,
        val message: String,
        val log: String? = null
    )

    companion object {
        private const val PREFS = "loot_prefs"
        private const val PREF_GAME_ROOT = "game_root"
        private const val PREF_PLUGINS_PATH = "plugins_path"
        private const val PREF_LOADORDER_PATH = "loadorder_path"
        private const val LAST_LOG_NAME = "loot-last-sort.log"
    }
}
