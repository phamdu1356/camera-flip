package com.phamdu.flipcamera

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phamdu.flipcamera.adminflow.PartialWriteProvider
import com.phamdu.flipcamera.data.CameraDatabase
import com.phamdu.flipcamera.data.SegmentEntity
import com.phamdu.flipcamera.data.SegmentFileStore
import com.phamdu.flipcamera.data.SegmentStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdminFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext.applicationContext
    private val dao get() = CameraDatabase.getInstance(context).segmentDao()
    private val fixtures = mutableListOf<TestMedia>()

    @After
    fun cleanFixtures() = runBlocking {
        try {
            fixtures.forEach { media ->
                runCatching {
                    var latest = dao.getById(media.segment.id)
                    if (latest != null && latest.status == SegmentStatus.READY) {
                        if (latest.isProtected) {
                            dao.setProtected(latest.id, false, latest.status, latest.revision)
                            latest = dao.getById(latest.id)
                        }
                        latest?.let { dao.markDeletedWithEvents(it.id, it.status, it.revision) }
                    }
                }
                runCatching {
                    SegmentFileStore(context).deleteReferencedFile(
                        media.segment.id,
                        media.segment.internalName,
                        media.segment.path,
                    )
                }
            }
        } finally {
            listOf(
                PartialWriteProvider.PARTIAL_FILE,
                PartialWriteProvider.OPEN_MARKER,
                PartialWriteProvider.DELETE_MARKER,
                PartialWriteProvider.PARTIAL_WRITTEN_MARKER,
            ).forEach { name ->
                runCatching { File(context.cacheDir, name).delete() }
            }
        }
    }

    @Test
    fun protectUnprotectAndDeleteRequireExplicitSelectionAndConfirmation() {
        val media = createReadySegment()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            showSegmentActions(scenario, media.segment.id)
            clickText("Bảo vệ khỏi tự xóa")
            awaitCondition("segment becomes protected") {
                runBlocking { dao.getById(media.segment.id)?.isProtected == true }
            }

            showSegmentActions(scenario, media.segment.id)
            clickText("Bỏ bảo vệ")
            awaitCondition("segment becomes unprotected") {
                runBlocking { dao.getById(media.segment.id)?.isProtected == false }
            }

            showSegmentActions(scenario, media.segment.id)
            clickText("Xóa video")
            assertTrue(findText("Xóa video này?").isNotEmpty())
            clickText("Hủy")
            awaitDialogClosed("Xóa video này?")
            assertEquals(SegmentStatus.READY, runBlocking { dao.getById(media.segment.id)?.status })
            assertTrue(media.file.exists())

            showSegmentActions(scenario, media.segment.id)
            clickText("Xóa video")
            awaitCondition("delete confirmation opens") { findText("Xóa video này?").isNotEmpty() }
            clickText("Xóa video")
            awaitDialogClosed("Xóa video này?")
            awaitCondition(
                description = "confirmed deletion tombstones metadata and removes media",
                condition = {
                    runBlocking { dao.getById(media.segment.id)?.status == SegmentStatus.DELETED } &&
                        !media.file.exists()
                },
                diagnostics = {
                    "status=${runBlocking { dao.getById(media.segment.id)?.status }} " +
                        "file=${media.file.exists()} path=${media.file.absolutePath}"
                },
            )
        }
    }

    @Test
    fun retentionShowsPreviewAndCancelPreservesMediaBeforeConfirmationDeletesOnlyEligibleMedia() {
        val expired = createReadySegment(ageMillis = TWO_DAYS)
        val protected = createReadySegment(ageMillis = TWO_DAYS, protected = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openRetentionPreview(scenario)
            assertTrue(findText("Tìm thấy 1 video").isNotEmpty())
            clickText("Hủy")
            awaitDialogClosed("Tìm thấy 1 video")
            assertEquals(SegmentStatus.READY, runBlocking { dao.getById(expired.segment.id)?.status })
            assertTrue(expired.file.exists())

            openRetentionPreview(scenario)
            clickText("Dọn ngay")
            awaitCondition(
                description = "retention deletes eligible video and preserves protected video",
                condition = {
                    val expiredDeleted =
                        runBlocking { dao.getById(expired.segment.id)?.status == SegmentStatus.DELETED }
                    val protectedReady = runBlocking {
                        val row = dao.getById(protected.segment.id)
                        row?.status == SegmentStatus.READY && row.isProtected
                    }
                    expiredDeleted && protectedReady && !expired.file.exists() && protected.file.exists()
                },
                diagnostics = {
                    "expired=${runBlocking { dao.getById(expired.segment.id)?.status }} " +
                        "expiredFile=${expired.file.exists()} protected=" +
                        "${runBlocking { dao.getById(protected.segment.id)?.status }}"
                },
            )
        }
    }

    @Test
    fun cancellingSafDocumentPickerClearsPendingExportWithoutChangingSource() {
        val media = createReadySegment()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> invoke(activity, "beginSegmentExport", media.segment) }
            awaitCondition("SAF picker is opened") {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName
                    ?.toString()?.contains("documentsui", ignoreCase = true) == true
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitCondition("cancelled picker clears pending export") {
                scenarioPendingExport(scenario) == null
            }
            assertEquals(SegmentStatus.READY, runBlocking { dao.getById(media.segment.id)?.status })
            assertTrue(media.file.exists())
        }
    }

    @Test
    fun failedPartialSafWriteDeletesDestinationAndLeavesSourceReady() {
        val media = createReadySegment(bytes = 256 * 1024)
        val testContext = context
        val authority = TEST_EXPORT_AUTHORITY
        val destination = DocumentsContract.buildDocumentUri(authority, "partial")
        val cache = testContext.cacheDir
        val partialFile = File(cache, PartialWriteProvider.PARTIAL_FILE)
        val openedMarker = File(cache, PartialWriteProvider.OPEN_MARKER)
        val deletedMarker = File(cache, PartialWriteProvider.DELETE_MARKER)
        val partialWrittenMarker = File(cache, PartialWriteProvider.PARTIAL_WRITTEN_MARKER)
        partialFile.delete()
        openedMarker.delete()
        deletedMarker.delete()
        partialWrittenMarker.delete()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> invoke(activity, "copySegmentToUri", media.segment, destination) }
            awaitCondition(
                description = "failed export cleanup deletes the partial destination",
                condition = {
                    openedMarker.exists() && partialWrittenMarker.exists() &&
                        deletedMarker.exists() && !partialFile.exists()
                },
                diagnostics = {
                    "open=${openedMarker.exists()} partial=${partialWrittenMarker.exists()} " +
                        "delete=${deletedMarker.exists()} destination=${partialFile.exists()}"
                },
            )
            assertEquals(SegmentStatus.READY, runBlocking { dao.getById(media.segment.id)?.status })
            assertTrue(media.file.exists())
        }
    }

    private fun openRetentionPreview(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.btnRetention).performClick()
        }
        clickText("1 ngày")
        awaitCondition("retention preview appears") { findText("Tìm thấy 1 video").isNotEmpty() }
    }

    private fun showSegmentActions(scenario: ActivityScenario<MainActivity>, segmentId: String) {
        val segment = requireNotNull(runBlocking { dao.getById(segmentId) })
        scenario.onActivity { activity -> invoke(activity, "showSegmentActions", segment) }
    }

    private fun createReadySegment(
        ageMillis: Long = 0L,
        protected: Boolean = false,
        bytes: Int = 16,
    ): TestMedia = runBlocking {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "segments").apply { mkdirs() }
        val startUtc = System.currentTimeMillis() - ageMillis - SEGMENT_DURATION
        val file = File(directory, "$id.mp4").apply { writeBytes(ByteArray(bytes) { 0x5a }) }
        val recording = SegmentEntity(
            id = id,
            sessionId = UUID.randomUUID().toString(),
            startUtc = startUtc,
            internalName = "$id.pending.mp4",
            path = File(directory, "$id.pending.mp4").absolutePath,
        )
        dao.insert(recording)
        assertEquals(
            1,
            dao.finalizeSegment(
                id = id,
                expectedRevision = recording.revision,
                startUtc = startUtc,
                endUtc = startUtc + SEGMENT_DURATION,
                duration = SEGMENT_DURATION,
                bytes = file.length(),
                width = 640,
                height = 360,
                codec = "video/avc",
                audio = false,
                finalInternalName = file.name,
                finalPath = file.canonicalPath,
            ),
        )
        if (protected) {
            assertEquals(1, dao.setProtected(id, true, SegmentStatus.READY, expectedRevision = 2))
        }
        TestMedia(requireNotNull(dao.getById(id)), file).also(fixtures::add)
    }

    private fun scenarioPendingExport(scenario: ActivityScenario<MainActivity>): SegmentEntity? {
        var result: SegmentEntity? = null
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("segmentPendingExport")
                .apply { isAccessible = true }
            result = field.get(activity) as? SegmentEntity
        }
        return result
    }

    private fun invoke(activity: MainActivity, name: String, vararg arguments: Any) {
        val method = MainActivity::class.java.declaredMethods.first { candidate ->
            candidate.name == name && candidate.parameterTypes.size == arguments.size
        }.apply { isAccessible = true }
        method.invoke(activity, *arguments)
    }

    private fun findText(text: String): List<AccessibilityNodeInfo> {
        instrumentation.waitForIdleSync()
        return instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText(text)
            .orEmpty()
    }

    private fun clickText(text: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val matches = findText(text)
            val clickableNodes = matches.mapNotNull { match ->
                var clickable: AccessibilityNodeInfo? = match
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                clickable?.takeIf { it.isVisibleToUser }
            }.distinctBy { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                "${node.className}:$bounds"
            }
            val clickable = clickableNodes.maxByOrNull { node ->
                Rect().also(node::getBoundsInScreen).exactCenterY()
            }
            if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                instrumentation.waitForIdleSync()
                return
            }
            if (clickable != null) {
                val bounds = Rect().also(clickable::getBoundsInScreen)
                if (!bounds.isEmpty) {
                    val x = bounds.exactCenterX()
                    val y = bounds.exactCenterY()
                    val downTime = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
                    try {
                        instrumentation.uiAutomation.injectInputEvent(down, true)
                        instrumentation.uiAutomation.injectInputEvent(up, true)
                    } finally {
                        down.recycle()
                        up.recycle()
                    }
                    instrumentation.waitForIdleSync()
                    return
                }
            }
            SystemClock.sleep(100)
        }
        val details = findText(text).joinToString { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            "${node.className} clickable=${node.isClickable} bounds=$bounds"
        }
        throw AssertionError(
            "Could not click '$text'; matching nodes: $details; ${accessibilitySnapshot()}"
        )
    }

    private fun awaitDialogClosed(text: String) = awaitCondition(
        description = "dialog '$text' closes",
        diagnostics = { accessibilitySnapshot() },
    ) { findText(text).isEmpty() }

    private fun awaitCondition(
        description: String,
        diagnostics: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for $description. ${diagnostics()}", condition())
    }

    private fun accessibilitySnapshot(): String {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "rootInActiveWindow=null"
        val descriptions = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || descriptions.size >= 120) return
            val text = node.text?.toString()?.takeIf(String::isNotBlank)
            val description = node.contentDescription?.toString()?.takeIf(String::isNotBlank)
            if (text != null || description != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                descriptions += "${node.className}[text=$text,desc=$description,bounds=$bounds]"
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { collect(it, depth + 1) }
            }
        }
        collect(root, 0)
        return "window=${root.packageName} texts=${descriptions.joinToString(" | ")}"
    }

    private data class TestMedia(val segment: SegmentEntity, val file: File)

    companion object {
        private const val SEGMENT_DURATION = 10_000L
        private const val TWO_DAYS = 2L * 24L * 60L * 60L * 1_000L
        private const val UI_TIMEOUT_MS = 10_000L
        private const val TEST_EXPORT_AUTHORITY = "com.phamdu.flipcamera.adminflowtest"
    }
}
