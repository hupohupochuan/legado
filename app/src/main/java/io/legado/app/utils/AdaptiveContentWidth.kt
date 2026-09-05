package io.legado.app.utils

import android.view.View

const val ExpandedSingleColumnMaxWidthDp = 840

internal fun calculateCenteredHorizontalPadding(
    containerWidthPx: Int,
    maxContentWidthPx: Int,
    minimumHorizontalPaddingPx: Int,
): Int {
    if (containerWidthPx <= 0 || maxContentWidthPx <= 0) {
        return minimumHorizontalPaddingPx.coerceAtLeast(0)
    }
    return maxOf(
        minimumHorizontalPaddingPx.coerceAtLeast(0),
        (containerWidthPx - maxContentWidthPx).coerceAtLeast(0) / 2,
    )
}

fun View.updateCenteredContentPadding(
    maxContentWidthDp: Int = ExpandedSingleColumnMaxWidthDp,
    minimumHorizontalPaddingDp: Int = 0,
    enabled: Boolean = true,
) {
    val density = resources.displayMetrics.density
    val minimumPaddingPx = (minimumHorizontalPaddingDp * density).toInt()
    val horizontalPadding = if (enabled) {
        calculateCenteredHorizontalPadding(
            containerWidthPx = width,
            maxContentWidthPx = (maxContentWidthDp * density).toInt(),
            minimumHorizontalPaddingPx = minimumPaddingPx,
        )
    } else {
        minimumPaddingPx
    }
    if (paddingStart != horizontalPadding || paddingEnd != horizontalPadding) {
        setPaddingRelative(
            horizontalPadding,
            paddingTop,
            horizontalPadding,
            paddingBottom,
        )
    }
}

fun View.applyCenteredContentPadding(
    maxContentWidthDp: Int = ExpandedSingleColumnMaxWidthDp,
    minimumHorizontalPaddingDp: Int = 0,
    enabled: () -> Boolean = { true },
) {
    val updatePadding = Runnable {
        // Read the current size and mode, not an intermediate layout's captured values.
        updateCenteredContentPadding(
            maxContentWidthDp = maxContentWidthDp,
            minimumHorizontalPaddingDp = minimumHorizontalPaddingDp,
            enabled = enabled(),
        )
    }
    fun scheduleUpdate() {
        removeCallbacks(updatePadding)
        post(updatePadding)
    }
    addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) {
            // View.layout clears FORCE_LAYOUT after these listeners. Changing padding here
            // can lose requestLayout and leave existing RecyclerView children at old bounds.
            scheduleUpdate()
        }
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            scheduleUpdate()
        }

        override fun onViewDetachedFromWindow(view: View) {
            removeCallbacks(updatePadding)
        }
    })
    scheduleUpdate()
}
