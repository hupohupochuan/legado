package io.legado.app.model.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the reader's rule adapters when updating the underlying HTML/XML parser. */
class HtmlRuleCompatibilityTest {

    private val catalog = """
        <div id="catalog">
          <a class="chapter" href="/1.html">第一章 &amp; 开始</a>
          <a class="chapter" href="/2.html">第二章 <span>继续</span></a>
          <a class="chapter" href="/3.html">第三章</a>
        </div>
    """.trimIndent()

    @Test
    fun legacyRulesPreserveOrderAndNegativeIndexes() {
        val parser = AnalyzeByJSoup(catalog)
        assertEquals(
            listOf("第一章 & 开始", "第二章 继续", "第三章"),
            parser.getStringList("id.catalog@tag.a@text")
        )
        assertEquals("/3.html", parser.getString("class.chapter.-1@href"))
        assertEquals(
            listOf("第三章", "第二章 继续", "第一章 & 开始"),
            parser.getStringList("class.chapter[-1:0]@text")
        )
    }

    @Test
    fun cssRulesPreserveFallbackAndAttributeExtraction() {
        val parser = AnalyzeByJSoup(catalog)
        assertEquals(
            listOf("/1.html", "/2.html", "/3.html"),
            parser.getStringList("@CSS:.missing@href||#catalog a.chapter@href")
        )
        assertEquals("第二章", parser.getString("@CSS:a:nth-child(2)@ownText"))
    }

    @Test
    fun contentRulesPreserveTextNodesAndStripScriptsFromHtml() {
        val content = "<div id='content'>首段<br>次段<script>ad()</script><style>.ad{}</style></div>"
        assertEquals("首段\n次段", AnalyzeByJSoup(content).getString("id.content@textNodes"))
        val html = AnalyzeByJSoup(content).getString("id.content@html").orEmpty()
        assertTrue(html.contains("首段"))
        assertTrue(html.contains("次段"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<style"))
    }

    @Test
    fun xpathRulesPreserveTextAttributesAndTableFragments() {
        val parser = AnalyzeByXPath(catalog)
        assertEquals(listOf("/1.html", "/2.html", "/3.html"), parser.getStringList("//a/@href"))
        assertEquals(listOf("第二章 继续"), parser.getStringList("//a[2]"))
        assertEquals(listOf("标题"), AnalyzeByXPath("<td>标题</td>").getStringList("//td"))
    }

    @Test
    fun xmlRulesPreserveCaseAndCdata() {
        val xml = """<?xml version="1.0"?><feed><Entry><Title><![CDATA[正文 <br> & 标题]]></Title></Entry></feed>"""
        assertEquals(
            listOf("正文 <br> & 标题"),
            AnalyzeByJSoup(xml).getStringList("tag.Title@text")
        )
        assertEquals(
            listOf("正文 <br> & 标题"),
            AnalyzeByXPath(xml).getStringList("//Entry/Title")
        )
    }
}
