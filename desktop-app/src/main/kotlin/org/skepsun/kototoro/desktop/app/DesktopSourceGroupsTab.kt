package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import org.skepsun.kototoro.core.source.groups.SOURCE_GROUP_MAX_BYTES
import org.skepsun.kototoro.core.source.groups.SourceGroup
import org.skepsun.kototoro.core.source.groups.SourceGroupJson
import org.skepsun.kototoro.core.source.groups.SourceGroupPackage

@Composable
fun DesktopSourceGroupsTab(controller: DesktopController) {
	val store = remember { DesktopSourceGroupStore(controller.session.storage.paths.root.resolve("source-groups.json")) }
	var groups by remember { mutableStateOf(store.load()) }
	var selected by remember { mutableStateOf<String?>(null) }
	var error by remember { mutableStateOf<String?>(null) }
	Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(onClick = {
				val group = SourceGroup(
					id = java.util.UUID.randomUUID().toString().replace("-", ""),
					name = "Group " + java.time.LocalDateTime.now().format(
						java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
					),
					createdAt = System.currentTimeMillis(),
					packages = controller.state.value.installedEntries.map {
						SourceGroupPackage(
							pkg = it.id,
							kind = it.kind.name.lowercase(),
							versionName = it.version,
							versionCode = it.versionCode ?: 0L,
						)
					},
				)
				groups = store.upsert(groups, group)
				store.save(groups)
			}) {
				Text("Save current")
			}
			Button(onClick = {
				val dialog = FileDialog(null as Frame?, "Import", FileDialog.LOAD)
				dialog.isVisible = true
				val directory = dialog.directory
				val file = dialog.file
				if (directory != null && file != null) {
					val input = File(directory, file)
					if (input.length() > SOURCE_GROUP_MAX_BYTES) {
						error = "Source group file is too large"
					} else {
						try {
							val group = SourceGroupJson.json.decodeFromString(
								SourceGroup.serializer(),
								input.readText(),
							)
							groups = store.upsert(groups, group)
							store.save(groups)
							error = null
						} catch (e: Exception) {
							error = e.message
						}
					}
				}
			}) {
				Text("Import")
			}
			Button(
				enabled = selected != null,
				onClick = {
					val group = groups.firstOrNull { it.id == selected } ?: return@Button
					val dialog = FileDialog(null as Frame?, "Export", FileDialog.SAVE)
					dialog.isVisible = true
					val directory = dialog.directory
					val file = dialog.file
					if (directory != null && file != null) {
						val text = SourceGroupJson.json.encodeToString(SourceGroup.serializer(), group)
						File(directory, file).writeText(text)
					}
				},
			) {
				Text("Export")
			}
		}
		val currentError = error
		if (currentError != null) {
			Text(currentError, color = MaterialTheme.colorScheme.error)
		}
		LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
			items(groups, key = { it.id }) { group ->
				Row(
					modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						group.name,
						color = if (selected == group.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
					)
					Text("${group.packages.size} packages")
					TextButton(onClick = { selected = group.id }) {
						Text("Select")
					}
					TextButton(onClick = {
						groups = groups.filterNot { it.id == group.id }
						if (selected == group.id) {
							selected = null
						}
						store.save(groups)
					}) {
						Text("Delete")
					}
				}
			}
		}
	}
}
