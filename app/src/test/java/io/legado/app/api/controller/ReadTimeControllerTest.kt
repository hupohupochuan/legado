package io.legado.app.api.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadTimeControllerTest {

    @Test
    fun parsesIntegerDurationAndIgnoresLegacyTimestamp() {
        val payload = ReadTimeController.parsePayload(
            """{"bookName":"测试书","durationMs":5000,"timestamp":123}"""
        )

        assertEquals(ReadTimeController.SaveReadTimePayload("测试书", 5_000L), payload)
    }

    @Test
    fun rejectsMissingOrNonIntegerDuration() {
        assertNull(ReadTimeController.parsePayload("""{"bookName":"测试书"}"""))
        assertNull(
            ReadTimeController.parsePayload(
                """{"bookName":"测试书","durationMs":5000.5}"""
            )
        )
        assertNull(
            ReadTimeController.parsePayload(
                """{"bookName":"测试书","durationMs":"5000"}"""
            )
        )
    }
}
