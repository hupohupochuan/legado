package io.legado.app.ui.book.read.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadTouchDeciderTest {

    @Test
    fun zeroConfigurationUsesSystemPagingTouchSlop() {
        val resolved = ReadTouchDecider.resolvePageTouchSlop(
            configuredSlop = 0,
            systemPagingTouchSlop = 16
        )

        assertEquals(16, resolved)
    }

    @Test
    fun positiveConfigurationOverridesSystemPagingTouchSlop() {
        val resolved = ReadTouchDecider.resolvePageTouchSlop(
            configuredSlop = 24,
            systemPagingTouchSlop = 16
        )

        assertEquals(24, resolved)
    }

    @Test
    fun invalidNegativeConfigurationUsesSystemPagingTouchSlop() {
        val resolved = ReadTouchDecider.resolvePageTouchSlop(
            configuredSlop = -1,
            systemPagingTouchSlop = 16
        )

        assertEquals(16, resolved)
    }

    @Test
    fun jitterBetweenTouchAndPagingSlopRemainsTap() {
        val exceedsTouchSlop = ReadTouchDecider.exceedsSlop(12f, 0f, 8)
        val exceedsPageSlop = ReadTouchDecider.exceedsSlop(12f, 0f, 16)

        assertTrue(exceedsTouchSlop)
        assertFalse(exceedsPageSlop)
        assertTrue(
            ReadTouchDecider.shouldHandleTap(
                pageGestureExceeded = exceedsPageSlop,
                delegateMoved = false,
                longPressed = false,
                pressOnTextSelected = false
            )
        )
    }

    @Test
    fun diagonalMovementUsesRealDistance() {
        assertFalse(ReadTouchDecider.exceedsSlop(6f, 8f, 10))
        assertTrue(ReadTouchDecider.exceedsSlop(6.1f, 8f, 10))
        assertTrue(ReadTouchDecider.exceedsSlop(-6.1f, -8f, 10))
        assertTrue(ReadTouchDecider.exceedsSlop(12f, 12f, 16))
    }

    @Test
    fun customSlopSupportsValuesBelowAndAboveSystemTouchSlop() {
        val smallPageSlop = ReadTouchDecider.resolvePageTouchSlop(1, 16)
        val largePageSlop = ReadTouchDecider.resolvePageTouchSlop(9999, 16)

        assertTrue(ReadTouchDecider.exceedsSlop(2f, 0f, smallPageSlop))
        assertFalse(ReadTouchDecider.exceedsSlop(1920f, 1200f, largePageSlop))
    }

    @Test
    fun pageGestureDoesNotBecomeTapWhenDelegateRejectsBoundarySwipe() {
        assertFalse(
            ReadTouchDecider.shouldHandleTap(
                pageGestureExceeded = true,
                delegateMoved = false,
                longPressed = false,
                pressOnTextSelected = false
            )
        )
    }

    @Test
    fun delegateMovementDoesNotBecomeTap() {
        assertFalse(
            ReadTouchDecider.shouldHandleTap(
                pageGestureExceeded = false,
                delegateMoved = true,
                longPressed = false,
                pressOnTextSelected = false
            )
        )
    }

    @Test
    fun longPressAndSelectedTextPressDoNotBecomeTap() {
        assertFalse(
            ReadTouchDecider.shouldHandleTap(
                pageGestureExceeded = false,
                delegateMoved = false,
                longPressed = true,
                pressOnTextSelected = false
            )
        )
        assertFalse(
            ReadTouchDecider.shouldHandleTap(
                pageGestureExceeded = false,
                delegateMoved = false,
                longPressed = false,
                pressOnTextSelected = true
            )
        )
    }

    @Test
    fun abortedAnimationDoesNotConsumeMenuAction() {
        assertFalse(
            ReadTouchDecider.shouldConsumeAbortedAction(
                animationAborted = true,
                action = ReadTouchDecider.MENU_ACTION
            )
        )
    }

    @Test
    fun abortedAnimationStillConsumesPageAndOtherActions() {
        assertTrue(
            ReadTouchDecider.shouldConsumeAbortedAction(
                animationAborted = true,
                action = 1
            )
        )
        assertTrue(
            ReadTouchDecider.shouldConsumeAbortedAction(
                animationAborted = true,
                action = 10
            )
        )
        assertFalse(
            ReadTouchDecider.shouldConsumeAbortedAction(
                animationAborted = false,
                action = 1
            )
        )
    }

    @Test
    fun centerMenuSurvivesJitterAndAbortedAnimationTogether() {
        val pageGestureExceeded = ReadTouchDecider.exceedsSlop(12f, 0f, 16)

        assertTrue(
            ReadTouchDecider.shouldHandleTap(
                pageGestureExceeded = pageGestureExceeded,
                delegateMoved = false,
                longPressed = false,
                pressOnTextSelected = false
            )
        )
        assertFalse(
            ReadTouchDecider.shouldConsumeAbortedAction(
                animationAborted = true,
                action = ReadTouchDecider.MENU_ACTION
            )
        )
    }
}
