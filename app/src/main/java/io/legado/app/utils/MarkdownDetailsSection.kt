package io.legado.app.utils

internal data class MarkdownDetailsSection(
    val preview: String,
    val summary: String,
    val details: String,
) {

    val expandedMarkdown: String
        get() = "$preview\n\n$details"

    companion object {
        private const val DETAILS_OPEN = "<details>"
        private const val DETAILS_CLOSE = "</details>"
        private const val SUMMARY_OPEN = "<summary>"
        private const val SUMMARY_CLOSE = "</summary>"

        fun parse(markdown: String): MarkdownDetailsSection? {
            val detailsStart = markdown.lastIndexOf(DETAILS_OPEN)
            if (detailsStart < 0) return null

            val detailsEnd = markdown.indexOf(DETAILS_CLOSE, detailsStart + DETAILS_OPEN.length)
            if (detailsEnd < 0 || markdown.substring(detailsEnd + DETAILS_CLOSE.length).isNotBlank()) {
                return null
            }

            val summaryStart = markdown.indexOf(SUMMARY_OPEN, detailsStart + DETAILS_OPEN.length)
            if (summaryStart < 0 ||
                markdown.substring(detailsStart + DETAILS_OPEN.length, summaryStart).isNotBlank()
            ) {
                return null
            }

            val summaryEnd = markdown.indexOf(SUMMARY_CLOSE, summaryStart + SUMMARY_OPEN.length)
            if (summaryEnd < 0 || summaryEnd > detailsEnd) return null

            val preview = markdown.substring(0, detailsStart).trimEnd()
            val summary = markdown.substring(summaryStart + SUMMARY_OPEN.length, summaryEnd).trim()
            val details = markdown.substring(summaryEnd + SUMMARY_CLOSE.length, detailsEnd).trim()
            if (preview.isEmpty() || summary.isEmpty() || details.isEmpty()) return null

            return MarkdownDetailsSection(preview, summary, details)
        }
    }
}
