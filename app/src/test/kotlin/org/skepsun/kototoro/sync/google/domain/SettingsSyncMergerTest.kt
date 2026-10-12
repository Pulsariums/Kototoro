package org.skepsun.kototoro.sync.google.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SettingsSyncMergerTest : StringSpec({

	"newer timestamp wins on either side" {
		val oldLocal = mapOf("reader_mode" to SettingEntry("s", "old-local", 100L))
		val newRemote = mapOf("reader_mode" to SettingEntry("s", "new-remote", 200L))
		SettingsSyncMerger.merge(oldLocal, newRemote)["reader_mode"]?.v shouldBe "new-remote"

		val newLocal = mapOf("reader_mode" to SettingEntry("s", "new-local", 300L))
		SettingsSyncMerger.merge(newLocal, newRemote)["reader_mode"]?.v shouldBe "new-local"
	}

	"equal timestamp prefers remote" {
		val local = mapOf("theme" to SettingEntry("s", "local", 100L))
		val remote = mapOf("theme" to SettingEntry("s", "remote", 100L))
		SettingsSyncMerger.merge(local, remote)["theme"]?.v shouldBe "remote"
	}

	"key present on one side is kept" {
		val local = mapOf("reader_mode" to SettingEntry("b", "true", 10L))
		val remote = mapOf("theme" to SettingEntry("i", "3", 20L))
		val merged = SettingsSyncMerger.merge(local, remote)
		merged.keys shouldBe setOf("reader_mode", "theme")
		merged["reader_mode"]?.v shouldBe "true"
		merged["theme"]?.v shouldBe "3"
	}

	"non syncable keys are rejected" {
		listOf(
			"app_password",
			"proxy_login",
			"discord_token",
			"local_manga_dirs",
			"backup_device_id",
			"pages_dir",
			"sync_x",
		).forEach { key ->
			SettingsSyncMerger.isSyncableKey(key) shouldBe false
		}
	}

	"reader and theme keys are syncable" {
		SettingsSyncMerger.isSyncableKey("reader_mode") shouldBe true
		SettingsSyncMerger.isSyncableKey("theme") shouldBe true
	}
})
