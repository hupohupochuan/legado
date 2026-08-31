package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveContentWidthTest {

    @Test
    fun `expanded window centers content within maximum width`() {
        assertEquals(
            445,
            calculateCenteredHorizontalPadding(
                containerWidthPx = 3200,
                maxContentWidthPx = 2310,
                minimumHorizontalPaddingPx = 0,
            ),
        )
    }

    @Test
    fun `compact window keeps its minimum padding`() {
        assertEquals(
            33,
            calculateCenteredHorizontalPadding(
                containerWidthPx = 1600,
                maxContentWidthPx = 2310,
                minimumHorizontalPaddingPx = 33,
            ),
        )
    }

    @Test
    fun `minimum padding wins near the width boundary`() {
        assertEquals(
            24,
            calculateCenteredHorizontalPadding(
                containerWidthPx = 2340,
                maxContentWidthPx = 2310,
                minimumHorizontalPaddingPx = 24,
            ),
        )
    }
}
