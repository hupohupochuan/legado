package io.legado.app.ui.book.read.page

internal object ReadTouchDecider {

    const val MENU_ACTION = 0

    fun resolvePageTouchSlop(configuredSlop: Int, systemPagingTouchSlop: Int): Int {
        return if (configuredSlop > 0) configuredSlop else systemPagingTouchSlop
    }

    fun exceedsSlop(deltaX: Float, deltaY: Float, slop: Int): Boolean {
        val normalizedSlop = slop.coerceAtLeast(0).toFloat()
        return deltaX * deltaX + deltaY * deltaY > normalizedSlop * normalizedSlop
    }

    fun shouldHandleTap(
        pageGestureExceeded: Boolean,
        delegateMoved: Boolean,
        longPressed: Boolean,
        pressOnTextSelected: Boolean
    ): Boolean {
        return !pageGestureExceeded &&
            !delegateMoved &&
            !longPressed &&
            !pressOnTextSelected
    }

    fun shouldConsumeAbortedAction(animationAborted: Boolean, action: Int): Boolean {
        return animationAborted && action != MENU_ACTION
    }
}
