package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
import kotlinx.serialization.builtins.ListSerializer
import org.skepsun.kototoro.core.source.groups.SourceGroup
import org.skepsun.kototoro.core.source.groups.SourceGroupJson

private val sourceGroupListSerializer = ListSerializer(SourceGroup.serializer())

@Composable
fun DesktopSourceGroupsTab() {
	var names by remember { mutableStateOf(emptyList<String>()) }
	Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(onClick = {
				val dialog = FileDialog(null as Frame?, "Export", FileDialog.SAVE)
				dialog.isVisible = true
				val directory = dialog.directory
				val file = dialog.file
				if (directory != null && file != null) {
					val text = SourceGroupJson.json.encodeToString(sourceGroupListSerializer, emptyList())
					File(directory, file).writeText(text)
				}
			}) {
				Text("Export")
			}
			Button(onClick = {
				val dialog = FileDialog(null as Frame?, "Import", FileDialog.LOAD)
				dialog.isVisible = true
				val directory = dialog.directory
				val file = dialog.file
				if (directory != null && file != null) {
					val groups = SourceGroupJson.json.decodeFromString(
						sourceGroupListSerializer,
						File(directory, file).readText(),
					)
					names = groups.map { it.name }
				}
			}) {
				Text("Import")
			}
		}
		LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
			items(names) { name ->
				Text(name)
			}
		}
	}
}
