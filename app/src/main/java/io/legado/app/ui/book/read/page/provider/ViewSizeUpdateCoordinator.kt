package io.legado.app.ui.book.read.page.provider

internal class ViewSizeUpdateCoordinator(
    private val currentSize: () -> Size,
    private val scheduleDelayed: (Long, () -> Unit) -> (() -> Unit),
    private val applySize: (Size) -> Unit,
) {

    data class Size(val width: Int, val height: Int)

    private var generation = 0L
    private var cancelPending: (() -> Unit)? = null

    fun update(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        val requestedSize = Size(width, height)
        val requestGeneration = ++generation
        cancelPending?.invoke()
        cancelPending = null

        val currentSize = currentSize()
        if (requestedSize == currentSize) return

        if (requestedSize.width == currentSize.width) {
            cancelPending = scheduleDelayed(HEIGHT_ONLY_DELAY_MILLIS) {
                if (requestGeneration != generation) return@scheduleDelayed
                cancelPending = null
                val latestSize = currentSize()
                if (requestedSize != latestSize && requestedSize.width == latestSize.width) {
                    applySize(requestedSize)
                }
            }
        } else {
            applySize(requestedSize)
        }
    }

    companion object {
        private const val HEIGHT_ONLY_DELAY_MILLIS = 300L
    }
}
