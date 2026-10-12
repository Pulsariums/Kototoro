package org.skepsun.kototoro.settings.sources.groups

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton

private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain")

@Composable
fun SourceGroupsDialog(
	onDismissRequest: () -> Unit,
	viewModel: SourceGroupsViewModel = hiltViewModel(),
) {
	val groups by viewModel.groups.collectAsStateWithLifecycle()
	val applyState by viewModel.progress.collectAsStateWithLifecycle()
	val message by viewModel.message.collectAsStateWithLifecycle()
	var showNameDialog by remember { mutableStateOf(false) }
	var pendingExportId by remember { mutableStateOf<String?>(null) }

	val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) viewModel.importGroup(uri)
	}
	val exportLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("application/json"),
	) { uri ->
		val id = pendingExportId
		pendingExportId = null
		if (uri != null && id != null) viewModel.exportGroup(id, uri)
	}

	SettingsAlertDialog(
		title = stringResource(R.string.source_groups_title),
		onDismissRequest = onDismissRequest,
		confirmButton = {
			SettingsDialogActionButton(
				text = stringResource(R.string.close),
				onClick = onDismissRequest,
			)
		},
		text = {
			Column(
				verticalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 480.dp)
					.verticalScroll(rememberScrollState()),
			) {
				Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					SettingsDialogActionButton(
						text = stringResource(R.string.source_groups_save_current),
						onClick = { showNameDialog = true },
						enabled = !applyState.running,
					)
					SettingsDialogActionButton(
						text = stringResource(R.string.source_groups_import),
						onClick = { importLauncher.launch(IMPORT_MIME_TYPES) },
						enabled = !applyState.running,
					)
				}

				if (applyState.running || applyState.finished) {
					ApplyProgressSection(applyState)
				}

				if (groups.isEmpty()) {
					Text(
						text = stringResource(R.string.source_groups_empty),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				groups.forEach { group ->
					Column {
						Text(
							text = group.name,
							style = MaterialTheme.typography.titleSmall,
						)
						Text(
							text = stringResource(
								R.string.source_groups_meta,
								group.repos.size,
								group.packages.size,
								group.sourceStates.size,
							),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
							SettingsDialogActionButton(
								text = stringResource(R.string.source_groups_apply),
								onClick = { viewModel.applyGroup(group.id) },
								enabled = !applyState.running,
							)
							SettingsDialogActionButton(
								text = stringResource(R.string.source_groups_export),
								onClick = {
									pendingExportId = group.id
									exportLauncher.launch("${group.name}.json")
								},
								enabled = !applyState.running,
							)
							SettingsDialogActionButton(
								text = stringResource(R.string.source_groups_delete),
								onClick = { viewModel.delete(group.id) },
								enabled = !applyState.running,
							)
						}
					}
				}

				message?.let { text ->
					Text(
						text = text,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.primary,
						modifier = Modifier.clickable { viewModel.consumeMessage() },
					)
				}
			}
		},
	)

	if (showNameDialog) {
		val defaultName = stringResource(R.string.source_groups_default_name, groups.size + 1)
		var name by remember { mutableStateOf(defaultName) }
		SettingsAlertDialog(
			title = stringResource(R.string.source_groups_name_title),
			onDismissRequest = { showNameDialog = false },
			confirmButton = {
				SettingsDialogActionButton(
					text = stringResource(R.string.save),
					onClick = {
						showNameDialog = false
						viewModel.saveCurrentAsGroup(name.ifBlank { defaultName })
					},
				)
			},
			dismissButton = {
				SettingsDialogActionButton(
					text = stringResource(R.string.cancel),
					onClick = { showNameDialog = false },
				)
			},
			text = {
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					singleLine = true,
					label = { Text(stringResource(R.string.source_groups_name_hint)) },
				)
			},
		)
	}
}

@Composable
private fun ApplyProgressSection(state: ApplyProgress) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		state.groupName?.let { name ->
			Text(text = name, style = MaterialTheme.typography.labelLarge)
		}
		if (state.running) {
			LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
		}
		Text(
			text = stringResource(
				R.string.source_groups_progress,
				state.step.coerceAtMost(state.total),
				state.total,
			),
			style = MaterialTheme.typography.bodySmall,
		)
		state.errors.take(10).forEach { error ->
			Text(
				text = error,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}
}
