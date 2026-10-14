package org.skepsun.kototoro.sync.google.domain

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsSyncStore @Inject constructor(
	@ApplicationContext context: Context,
	appSettings: AppSettings,
) {

	private val stores: Map<String, SharedPreferences> = linkedMapOf(
		STORE_APP to appSettings.prefs,
		STORE_TAP_GRID to context.getSharedPreferences("tap_grid", Context.MODE_PRIVATE),
		STORE_NOVEL_READER to context.getSharedPreferences("novel_reader_settings", Context.MODE_PRIVATE),
		// Source locks (per-source enabled state lock): name -> true/false, "none" = unlocked marker.
		STORE_SOURCE_LOCKS to context.getSharedPreferences("source_locks", Context.MODE_PRIVATE),
	)
	private val meta = context.getSharedPreferences("sync_settings_meta", Context.MODE_PRIVATE)

	fun buildLocal(now: Long): Map<String, Map<String, SettingEntry>> {
		val metaUpdates = HashMap<String, String>()
		val result = LinkedHashMap<String, Map<String, SettingEntry>>()
		for ((store, prefs) in stores) {
			val entries = LinkedHashMap<String, SettingEntry>()
			for ((key, value) in prefs.all) {
				if (!SettingsSyncMerger.isSyncableKey(key)) continue
				val encoded = encode(value) ?: continue
				val metaKey = "$store/$key"
				val hash = hashOf(encoded)
				val stored = meta.getString(metaKey, null)
				val storedHash = stored?.substringBefore('|')?.toIntOrNull()
				val storedAt = stored?.substringAfter('|', "")?.toLongOrNull()
				val at = when {
					stored == null -> {
						metaUpdates[metaKey] = "$hash|0"
						0L
					}
					storedHash == hash && storedAt != null -> storedAt
					else -> {
						metaUpdates[metaKey] = "$hash|$now"
						now
					}
				}
				entries[key] = SettingEntry(t = encoded.first, v = encoded.second, at = at)
			}
			result[store] = entries
		}
		saveMeta(metaUpdates)
		return result
	}

	fun apply(merged: Map<String, Map<String, SettingEntry>>): Int {
		val metaUpdates = HashMap<String, String>()
		var applied = 0
		for ((store, entries) in merged) {
			val prefs = stores[store] ?: continue
			val localValues = prefs.all
			val writes = LinkedHashMap<String, Any>()
			for ((key, entry) in entries) {
				if (!SettingsSyncMerger.isSyncableKey(key)) continue
				if (encode(localValues[key]) == (entry.t to entry.v)) continue
				val value = decode(entry) ?: continue
				val encoded = encode(value) ?: continue
				writes[key] = value
				metaUpdates["$store/$key"] = "${hashOf(encoded)}|${entry.at}"
			}
			if (writes.isEmpty()) continue
			prefs.edit {
				for ((key, value) in writes) {
					when (value) {
						is Boolean -> putBoolean(key, value)
						is Int -> putInt(key, value)
						is Long -> putLong(key, value)
						is Float -> putFloat(key, value)
						is String -> putString(key, value)
						is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
					}
				}
			}
			applied += writes.size
		}
		saveMeta(metaUpdates)
		return applied
	}

	private fun encode(value: Any?): Pair<String, String>? {
		return when (value) {
			is Boolean -> "b" to value.toString()
			is Int -> "i" to value.toString()
			is Long -> "l" to value.toString()
			is Float -> "f" to value.toString()
			is String -> "s" to value
			is Set<*> -> {
				val strings = value.filterIsInstance<String>()
				if (strings.size != value.size) {
					null
				} else {
					"ss" to Json.encodeToString(ListSerializer(String.serializer()), strings.sorted())
				}
			}
			else -> null
		}
	}

	private fun decode(entry: SettingEntry): Any? = runCatching {
		when (entry.t) {
			"b" -> entry.v.toBooleanStrict()
			"i" -> entry.v.toInt()
			"l" -> entry.v.toLong()
			"f" -> entry.v.toFloat()
			"s" -> entry.v
			"ss" -> Json.decodeFromString(ListSerializer(String.serializer()), entry.v).toSet()
			else -> null
		}
	}.getOrNull()

	private fun hashOf(encoded: Pair<String, String>): Int = (encoded.first + ":" + encoded.second).hashCode()

	private fun saveMeta(updates: Map<String, String>) {
		if (updates.isEmpty()) return
		meta.edit {
			for ((key, value) in updates) {
				putString(key, value)
			}
		}
	}

	private companion object {
		const val STORE_APP = "app"
		const val STORE_TAP_GRID = "tap_grid"
		const val STORE_NOVEL_READER = "novel_reader"
		const val STORE_SOURCE_LOCKS = "source_locks"
	}
}
