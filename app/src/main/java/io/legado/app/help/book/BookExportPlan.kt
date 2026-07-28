package io.legado.app.help.book

enum class ExportMissingChapterPolicy(val value: String) {
    RequireComplete("require_complete"),
    CachedOnly("cached_only");

    companion object {
        fun from(value: String?): ExportMissingChapterPolicy {
            return entries.firstOrNull { it.value == value } ?: RequireComplete
        }
    }
}

data class BookExportPlan<T>(
    val selected: List<T>,
    val missing: List<T>,
    val totalCount: Int
) {
    val availableCount: Int
        get() = totalCount - missing.size
}

fun <T> createBookExportPlan(
    chapters: List<T>,
    policy: ExportMissingChapterPolicy,
    isContentChapter: (T) -> Boolean,
    isAvailable: (T) -> Boolean
): BookExportPlan<T> {
    val availability = chapters.map { chapter ->
        chapter to (!isContentChapter(chapter) || isAvailable(chapter))
    }
    val missing = availability.filterNot { it.second }.map { it.first }
    val selected = when (policy) {
        ExportMissingChapterPolicy.RequireComplete -> chapters
        ExportMissingChapterPolicy.CachedOnly -> availability.filter { it.second }.map { it.first }
    }
    return BookExportPlan(
        selected = selected,
        missing = missing,
        totalCount = chapters.size
    )
}

fun parseExportChapterScope(scope: String): Set<Int> {
    val indexes = linkedSetOf<Int>()
    scope.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { item ->
            val range = item.split("-")
            if (range.size == 1) {
                range[0].toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { indexes.add(it - 1) }
                return@forEach
            }
            if (range.size != 2) return@forEach
            val start = range[0].trim().toIntOrNull() ?: return@forEach
            val end = range[1].trim().toIntOrNull() ?: return@forEach
            if (start <= 0 || end <= 0 || start > end) return@forEach
            for (index in start..end) {
                indexes.add(index - 1)
            }
        }
    return indexes
}
