package io.legado.app.api.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReplaceRuleControllerTest {

    @Test
    fun testRuleReturnsImmediatelyWhenPatternIsEmpty() {
        val postData = """
            {
              "rule": {
                "pattern": "",
                "replacement": "replacement",
                "isRegex": false
              },
              "text": "original"
            }
        """.trimIndent()

        val result = ReplaceRuleController.testRule(postData)

        assertFalse(result.isSuccess)
        assertEquals("替换规则不能为空", result.errorMsg)
        assertNull(result.data)
    }
}
