package io.legado.app.help

import io.legado.app.data.entities.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookProgressIdentityTest {

    @Test
    fun `identity file name is stable and WebDAV safe`() {
        val first = BookProgressIdentity.storageFileName("content-sha1:abc")
        val second = BookProgressIdentity.storageFileName("content-sha1:abc")
        val different = BookProgressIdentity.storageFileName("content-sha1:def")

        assertEquals(first, second)
        assertNotEquals(first, different)
        assertTrue(first.matches(Regex("v2-[0-9a-f]{64}\\.json")))
    }

    @Test
    fun `identity target ignores legacy file`() {
        val target = BookProgressStorageTarget.forBook(
            progressKey = "content-sha1:book-a",
            legacyFileName = "legacy.json",
            sameNameBookCount = 1
        )

        assertEquals(
            target.identityFileName,
            target.selectAvailable(setOf("legacy.json", checkNotNull(target.identityFileName)))
        )
        assertEquals(listOf(target.identityFileName), target.candidates)
    }

    @Test
    fun `legacy target remains available for an old unambiguous book`() {
        val target = BookProgressStorageTarget.forBook(
            progressKey = null,
            legacyFileName = "legacy.json",
            sameNameBookCount = 1
        )

        assertEquals("legacy.json", target.selectAvailable(setOf("legacy.json")))
    }

    @Test
    fun `keyless local migration prefers v2 and falls back to legacy once`() {
        val target = BookProgressStorageTarget.forBook(
            progressKey = "content-sha1:book-a",
            legacyFileName = "legacy.json",
            sameNameBookCount = 1,
            allowLegacyMigration = true
        )
        val identityFile = checkNotNull(target.identityFileName)

        assertEquals(listOf(identityFile, "legacy.json"), target.candidates)
        assertEquals(identityFile, target.selectAvailable(setOf(identityFile, "legacy.json")))
        assertEquals("legacy.json", target.selectAvailable(setOf("legacy.json")))
        assertTrue(target.accepts("legacy.json", progress(null)))
    }

    @Test
    fun `legacy migration is unavailable when same-name identity is ambiguous`() {
        val target = BookProgressStorageTarget.forBook(
            progressKey = "content-sha1:book-a",
            legacyFileName = "legacy.json",
            sameNameBookCount = 2,
            allowLegacyMigration = true
        )

        assertEquals(listOf(target.identityFileName), target.candidates)
        assertFalse(target.accepts("legacy.json", progress(null)))
    }

    @Test
    fun `migration compares normalized chapter positions`() {
        val earlier = progress(null).copy(durChapterPos = -80)
        val later = progress(null).copy(durChapterPos = 100)

        assertTrue(BookProgressMigration.isMoreRecentThan(later, earlier))
        assertFalse(BookProgressMigration.isMoreRecentThan(earlier, later))
    }

    @Test
    fun `ambiguous legacy file is never selected`() {
        val target = BookProgressStorageTarget.forBook(
            progressKey = null,
            legacyFileName = "legacy.json",
            sameNameBookCount = 2
        )

        assertTrue(target.candidates.isEmpty())
        assertNull(target.selectAvailable(setOf("legacy.json")))
    }

    @Test
    fun `identity file requires exact progress key while legacy allows missing key`() {
        val target = target(allowLegacy = true)
        val identityFile = checkNotNull(target.identityFileName)

        assertTrue(target.accepts(identityFile, progress("content-sha1:book-a")))
        assertFalse(target.accepts(identityFile, progress("content-sha1:book-b")))
        assertFalse(target.accepts(identityFile, progress(null)))
        assertTrue(target.accepts("legacy.json", progress(null)))
        assertFalse(target.accepts("legacy.json", progress("content-sha1:book-b")))
    }

    private fun target(allowLegacy: Boolean) = BookProgressStorageTarget(
        progressKey = "content-sha1:book-a",
        legacyFileName = "legacy.json",
        allowLegacyFallback = allowLegacy
    )

    private fun progress(progressKey: String?) = BookProgress(
        name = "同名书",
        author = "作者",
        durChapterIndex = 1,
        durChapterPos = 2,
        durChapterTime = 3,
        durChapterTitle = "章节",
        bookProgressKey = progressKey
    )
}
