package org.skepsun.kototoro.desktop.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.builtins.ListSerializer
import org.skepsun.kototoro.core.source.groups.SourceGroup
import org.skepsun.kototoro.core.source.groups.SourceGroupJson

internal class DesktopSourceGroupStore(private val file: Path) {

	fun load(): List<SourceGroup> {
		return try {
			if (!Files.exists(file)) return emptyList()
			SourceGroupJson.json.decodeFromString(
				ListSerializer(SourceGroup.serializer()),
				Files.readString(file),
			)
		} catch (e: Exception) {
			emptyList()
		}
	}

	fun save(groups: List<SourceGroup>) {
		file.parent?.let { Files.createDirectories(it) }
		val text = SourceGroupJson.json.encodeToString(ListSerializer(SourceGroup.serializer()), groups)
		val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
		Files.writeString(tmp, text)
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
	}

	fun upsert(current: List<SourceGroup>, group: SourceGroup): List<SourceGroup> {
		return if (current.any { it.id == group.id }) {
			current.map { if (it.id == group.id) group else it }
		} else {
			current + group
		}
	}
}
