package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownDetailsSectionTest {

    @Test
    fun `parse trailing details section`() {
        val markdown = """
            # 更新日志

            **2026/07/15**
            最新更新。

            <details>
            <summary>more</summary>

            **2026/07/14**
            历史更新。
            </details>
        """.trimIndent()

        val section = MarkdownDetailsSection.parse(markdown)!!

        assertEquals("# 更新日志\n\n**2026/07/15**\n最新更新。", section.preview)
        assertEquals("more", section.summary)
        assertEquals("**2026/07/14**\n历史更新。", section.details)
        assertEquals(
            "# 更新日志\n\n**2026/07/15**\n最新更新。\n\n**2026/07/14**\n历史更新。",
            section.expandedMarkdown,
        )
    }

    @Test
    fun `ignore malformed or non-trailing details section`() {
        assertNull(MarkdownDetailsSection.parse("# 更新日志"))
        assertNull(MarkdownDetailsSection.parse("# 更新日志\n<details>\n<summary>more</summary>"))
        assertNull(
            MarkdownDetailsSection.parse(
                "# 更新日志\n<details>\n<summary>more</summary>\n旧日志\n</details>\n其他内容",
            ),
        )
    }
}
