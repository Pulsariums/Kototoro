package org.skepsun.kototoro.sync.google.domain

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionPackage
import java.io.File

class GoogleDriveSyncPackageRestoreTest {

    @TempDir
    lateinit var directory: File

    private val prefs = mockk<SharedPreferences>(relaxed = true) {
        every { getLong(any(), any()) } returns 1L
    }
    private val context = mockk<Context> {
        every { filesDir } answers { directory }
        every { getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE) } returns prefs
    }

    private fun repository() = GoogleDriveSyncRepository(
        context = context,
        settings = mockk(),
        appSettings = mockk(),
        auth = mockk(),
        api = mockk(),
        database = mockk(),
        favouritesRepository = mockk(),
        historyRepository = mockk(),
        trackingRepository = mockk(),
        mihonExtensionManager = mockk(),
        aniyomiExtensionManager = mockk(),
        ireaderExtensionManager = mockk(),
        tsundokuExtensionManager = mockk(),
        cloudstreamRuntimeManager = mockk(),
        settingsStore = mockk(),
    )

    @Test
    fun `metadata only package reports incomplete recovery until installed`() = runTest {
        val snapshot = snapshot()
        val repository = repository()
        assertEquals(listOf("Source"), repository.restoreExtensionPackages(snapshot))
        File(directory, "plugins").mkdirs()
        File(directory, "plugins/source.jar").writeText("installed")
        assertTrue(repository.restoreExtensionPackages(snapshot).isEmpty())
    }

    @Test
    fun `truncated payload is reported without creating a plugin file`() = runTest {
        assertEquals(listOf("Source"), repository().restoreExtensionPackages(snapshot(payload = "YQ==", size = 10)))
        assertTrue(!File(directory, "plugins/source.jar").exists())
    }

    @Test
    fun `cancellation during runtime reload reaches the owning sync job`() = runTest {
        mockkObject(GlobalExtensionManager)
        try {
            every { GlobalExtensionManager.initialize(context) } throws CancellationException("cancelled")
            val error = runCatching {
                repository().restoreExtensionPackages(snapshot(payload = "YQ=="))
            }.exceptionOrNull()
            assertTrue(error is CancellationException, "Expected cancellation, got $error")
        } finally {
            unmockkObject(GlobalExtensionManager)
        }
    }

    private fun snapshot(payload: String? = null, size: Long = 1) = GoogleDriveSyncSnapshot(
        extensions = listOf(SyncExtensionPackage(
            packageId = "source",
            name = "Source",
            kind = "jar",
            fileName = "source.jar",
            versionCode = 1,
            payloadBase64 = payload,
            isPayloadIncluded = payload != null,
            sizeBytes = size,
        )),
    )
}
