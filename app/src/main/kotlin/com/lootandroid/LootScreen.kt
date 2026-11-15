package com.lootandroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skyrimsuite.ui.SuiteBarAction
import com.skyrimsuite.ui.SuiteCard
import com.skyrimsuite.ui.SuiteChip
import com.skyrimsuite.ui.SuiteChipTone
import com.skyrimsuite.ui.SuiteDetailRow
import com.skyrimsuite.ui.SuiteEmptyState
import com.skyrimsuite.ui.SuiteLogCard
import com.skyrimsuite.ui.SuitePathRow
import com.skyrimsuite.ui.SuitePermissionNotice
import com.skyrimsuite.ui.SuiteScreen
import com.skyrimsuite.ui.SuiteSettingsSheet

/**
 * LOOT screen. Everything that is not an action lives behind the gear: the
 * three path pickers, the fixed game row and the explanatory copy.
 */
@Composable
fun LootScreen(
    gameFolder: String?,
    pluginsPath: String?,
    loadorderPath: String?,
    storageGranted: Boolean,
    statusText: String,
    pluginsFound: Int,
    activeCount: Int,
    loadorderCount: Int,
    missingActive: List<String>,
    busy: Boolean,
    logText: String,
    logExpanded: Boolean,
    onPickGameFolder: () -> Unit,
    onPickPlugins: () -> Unit,
    onPickLoadorder: () -> Unit,
    onGrantStorage: () -> Unit,
    onSort: () -> Unit,
    onViewLogs: () -> Unit,
    onToggleLog: () -> Unit
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val configured = gameFolder != null && pluginsPath != null && loadorderPath != null

    SuiteScreen(
        title = stringResource(R.string.app_name),
        subtitle = stringResource(R.string.app_subtitle),
        chips = listOf(
            SuiteChip(stringResource(R.string.game_skyrim_le_short)),
            SuiteChip(
                label = stringResource(
                    if (storageGranted) R.string.chip_storage_granted
                    else R.string.chip_storage_missing
                ),
                tone = if (storageGranted) SuiteChipTone.Positive else SuiteChipTone.Warning
            ),
            SuiteChip(stringResource(R.string.chip_plugins, pluginsFound)),
            SuiteChip(stringResource(R.string.chip_active, activeCount))
        ),
        onSettings = { showSettings = true },
        primaryAction = SuiteBarAction(
            label = stringResource(R.string.btn_sort_loot),
            onClick = onSort,
            enabled = !busy
        ),
        secondaryActions = listOf(
            SuiteBarAction(
                label = stringResource(R.string.btn_view_logs),
                onClick = onViewLogs,
                enabled = !busy
            )
        ),
        busy = busy,
        busyText = statusText
    ) {
        SuitePermissionNotice(
            granted = storageGranted,
            title = stringResource(R.string.storage_permission_title),
            body = stringResource(R.string.storage_missing),
            grantLabel = stringResource(R.string.btn_grant_storage),
            onGrant = onGrantStorage
        )

        SuiteCard {
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(
                    if (configured) R.string.status_hint_ready else R.string.summary_empty
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (configured) {
            SuiteCard {
                Text(
                    text = stringResource(R.string.label_overview),
                    style = MaterialTheme.typography.titleMedium
                )
                SuiteDetailRow(
                    label = stringResource(R.string.label_plugins_found),
                    value = pluginsFound.toString()
                )
                SuiteDetailRow(
                    label = stringResource(R.string.label_active_plugins),
                    value = activeCount.toString()
                )
                SuiteDetailRow(
                    label = stringResource(R.string.label_loadorder_count),
                    value = loadorderCount.toString()
                )
            }
        } else {
            SuiteEmptyState(
                title = stringResource(R.string.summary_empty),
                body = stringResource(R.string.setup_hint)
            )
        }

        if (missingActive.isNotEmpty()) {
            SuiteCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    text = stringResource(R.string.label_missing_plugins),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = missingActive.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        SuiteLogCard(
            title = stringResource(R.string.label_loot_logs),
            log = logText,
            expanded = logExpanded,
            onToggle = onToggleLog,
            emptyText = stringResource(R.string.logs_empty)
        )
    }

    SuiteSettingsSheet(
        visible = showSettings,
        onDismiss = { showSettings = false },
        title = stringResource(R.string.settings_title)
    ) {
        SuiteDetailRow(
            label = stringResource(R.string.label_game),
            value = stringResource(R.string.game_skyrim_le)
        )
        SuitePathRow(
            title = stringResource(R.string.label_game_folder),
            path = gameFolder,
            onChange = onPickGameFolder,
            placeholder = stringResource(R.string.msg_not_selected),
            supportingText = stringResource(R.string.hint_game_folder)
        )
        SuitePathRow(
            title = stringResource(R.string.label_plugins_file),
            path = pluginsPath,
            onChange = onPickPlugins,
            placeholder = stringResource(R.string.msg_not_selected),
            supportingText = stringResource(R.string.hint_plugins_file)
        )
        SuitePathRow(
            title = stringResource(R.string.label_loadorder_file),
            path = loadorderPath,
            onChange = onPickLoadorder,
            placeholder = stringResource(R.string.msg_not_selected),
            supportingText = stringResource(R.string.hint_loadorder_file)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.setup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
