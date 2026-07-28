package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookExportPlanTest {

    private data class Chapter(
        val index: Int,
        val isVolume: Boolean = false,
        val cached: Boolean = false
    )

    @Test
    fun requireCompleteReportsMissingWithoutDroppingChapters() {
        val chapters = listOf(
            Chapter(0, cached = true),
            Chapter(1),
            Chapter(2, isVolume = true)
        )

        val plan = createBookExportPlan(
            chapters,
            ExportMissingChapterPolicy.RequireComplete,
            isContentChapter = { !it.isVolume },
            isAvailable = { it.cached || it.isVolume }
        )

        assertEquals(chapters, plan.selected)
        assertEquals(listOf(chapters[1]), plan.missing)
        assertEquals(2, plan.availableCount)
    }

    @Test
    fun cachedOnlyDropsMissingContentButKeepsVolumeHeadings() {
        val chapters = listOf(
            Chapter(0, cached = true),
            Chapter(1),
            Chapter(2, isVolume = true)
        )

        val plan = createBookExportPlan(
            chapters,
            ExportMissingChapterPolicy.CachedOnly,
            isContentChapter = { !it.isVolume },
            isAvailable = { it.cached || it.isVolume }
        )

        assertEquals(listOf(chapters[0], chapters[2]), plan.selected)
        assertEquals(listOf(chapters[1]), plan.missing)
    }

    @Test
    fun availabilityIsCheckedOncePerContentChapter() {
        val chapters = listOf(
            Chapter(0, cached = true),
            Chapter(1),
            Chapter(2, isVolume = true)
        )
        var checkCount = 0

        createBookExportPlan(
            chapters,
            ExportMissingChapterPolicy.CachedOnly,
            isContentChapter = { !it.isVolume },
            isAvailable = {
                checkCount++
                it.cached
            }
        )

        assertEquals(2, checkCount)
    }

    @Test
    fun chapterScopeUsesOneBasedInputAndDeduplicatesOverlaps() {
        val scope = parseExportChapterScope("1-3, 3, 5, 8-9")

        assertEquals(linkedSetOf(0, 1, 2, 4, 7, 8), scope)
    }

    @Test
    fun invalidScopePartsAreIgnored() {
        val scope = parseExportChapterScope("0, -1, 5-2, x, 2")

        assertEquals(setOf(1), scope)
        assertTrue(parseExportChapterScope("bad").isEmpty())
    }

    @Test
    fun missingChapterPolicyUsesStableWireValues() {
        assertEquals(
            ExportMissingChapterPolicy.CachedOnly,
            ExportMissingChapterPolicy.from("cached_only")
        )
        assertEquals(
            ExportMissingChapterPolicy.RequireComplete,
            ExportMissingChapterPolicy.from("unknown")
        )
    }
}
