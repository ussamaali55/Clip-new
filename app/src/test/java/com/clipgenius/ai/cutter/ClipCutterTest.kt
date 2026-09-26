package com.clipgenius.ai.cutter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.clipgenius.ai.data.ProjectTypeConverters
import com.clipgenius.ai.state.ClipCandidate
import com.clipgenius.ai.state.CutStatus
import com.clipgenius.ai.state.SourceMedia
import com.clipgenius.ai.state.SourceRange
import com.clipgenius.ai.state.VerificationStatus
import com.clipgenius.ai.state.VerifiedClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ClipCutterTest {

    private lateinit var context: Context
    private val projectId = "proj_test_123"
    private val typeConverters = ProjectTypeConverters()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testDraftClipFilePath_neverOverwritesSource() {
        val clipId = "clip_abc_456"
        val draftFile = ClipCutter.getDraftClipFile(context, projectId, clipId)

        assertEquals("${clipId}_draft.mp4", draftFile.name)
        assertTrue(draftFile.absolutePath.contains("projects/$projectId/clips"))
        assertTrue(draftFile.parentFile?.exists() == true)
    }

    @Test
    fun testDeleteDraft_removesFileAndFreesSpace() {
        val clipId = "clip_del_test"
        val draftFile = ClipCutter.getDraftClipFile(context, projectId, clipId)
        draftFile.writeText("sample draft mp4 content")
        assertTrue(draftFile.exists())

        val deleted = ClipCutter.deleteDraft(context, projectId, clipId)
        assertTrue(deleted)
        assertFalse(draftFile.exists())
    }

    @Test
    fun testStorageGuard_checksSpaceCorrectly() {
        val dummyVideo = File(context.filesDir, "sample_video.mp4")
        dummyVideo.writeBytes(ByteArray(1024 * 1024 * 5)) // 5 MB

        val media = SourceMedia(
            path = dummyVideo.absolutePath,
            fileName = "sample_video.mp4",
            durationMs = 60_000L, // 60s
            fileSizeBytes = dummyVideo.length()
        )

        val verifiedClip = VerifiedClip(
            clipId = "c1",
            verifiedRanges = listOf(SourceRange(10_000L, 40_000L)), // 30s cut
            originalCandidate = ClipCandidate(clipId = "c1", title = "Viral Hook")
        )

        val result = ClipCutter.checkStorageSpace(context, listOf(verifiedClip), media)
        assertTrue(result is ClipCutter.StorageCheckResult.Sufficient)
    }

    @Test
    fun testQualityCheck_failsOnMissingOrEmptyFile() {
        // Missing file
        val missingFile = File(context.cacheDir, "non_existent.mp4")
        val qcMissing = ClipCutter.runQualityCheck(missingFile, expectedDurationMs = 15000L)
        assertFalse(qcMissing.passed)
        assertTrue(qcMissing.failureReason?.contains("missing or 0 bytes") == true)

        // Empty file
        val emptyFile = File(context.cacheDir, "empty.mp4")
        emptyFile.createNewFile()
        val qcEmpty = ClipCutter.runQualityCheck(emptyFile, expectedDurationMs = 15000L)
        assertFalse(qcEmpty.passed)
        assertTrue(qcEmpty.failureReason?.contains("missing or 0 bytes") == true)
    }

    @Test
    fun testTypeConverter_serializesAndDeserializesPhase8CutFields() {
        val clip = VerifiedClip(
            clipId = "clip_typeconv_1",
            verifiedRanges = listOf(
                SourceRange(startMs = 5000L, endMs = 25000L),
                SourceRange(startMs = 35000L, endMs = 55000L)
            ),
            verifiedHookStartMs = 5000L,
            verifiedHookEndMs = 10000L,
            status = VerificationStatus.VERIFIED,
            issues = listOf("Manually set by user"),
            originalCandidate = ClipCandidate(
                clipId = "clip_typeconv_1",
                title = "Hook to Payoff",
                hookSentence = "You won't believe this",
                score = 88f
            ),
            isManuallyEdited = true,
            cutStatus = CutStatus.DONE,
            draftVideoPath = "/data/user/0/com.clipgenius.ai/files/projects/p1/clips/c1_draft.mp4",
            cutProgress = 1.0f,
            qcPassed = true,
            qcDetails = "QC Passed • 40s • 12.5 MB",
            cutDurationMs = 40000L,
            cutFileSizeBytes = 12500000L
        )

        val json = typeConverters.fromVerifiedClips(listOf(clip))
        assertNotNull(json)

        val restoredList = typeConverters.toVerifiedClips(json)
        assertEquals(1, restoredList.size)
        val restored = restoredList.first()

        assertEquals(clip.clipId, restored.clipId)
        assertEquals(2, restored.verifiedRanges.size)
        assertEquals(CutStatus.DONE, restored.cutStatus)
        assertEquals(clip.draftVideoPath, restored.draftVideoPath)
        assertEquals(1.0f, restored.cutProgress, 0.001f)
        assertTrue(restored.qcPassed)
        assertEquals(clip.qcDetails, restored.qcDetails)
        assertEquals(40000L, restored.cutDurationMs)
        assertEquals(12500000L, restored.cutFileSizeBytes)
    }

    @Test
    fun testCutStatus_transitions() {
        val clip = VerifiedClip(
            clipId = "trans_1",
            originalCandidate = ClipCandidate(clipId = "trans_1")
        )

        assertEquals(CutStatus.NOT_CUT, clip.cutStatus)

        val cuttingClip = clip.copy(cutStatus = CutStatus.CUTTING, cutProgress = 0.45f)
        assertEquals(CutStatus.CUTTING, cuttingClip.cutStatus)
        assertEquals(0.45f, cuttingClip.cutProgress, 0.01f)

        val doneClip = cuttingClip.copy(cutStatus = CutStatus.DONE, qcPassed = true, cutProgress = 1f)
        assertEquals(CutStatus.DONE, doneClip.cutStatus)
        assertTrue(doneClip.qcPassed)

        val errorClip = clip.copy(cutStatus = CutStatus.ERROR, cutErrorMessage = "Stream copy failed")
        assertEquals(CutStatus.ERROR, errorClip.cutStatus)
        assertFalse(errorClip.qcPassed)
        assertEquals("Stream copy failed", errorClip.cutErrorMessage)
    }
}
