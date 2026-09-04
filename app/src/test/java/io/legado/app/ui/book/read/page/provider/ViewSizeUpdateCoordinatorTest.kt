package io.legado.app.ui.book.read.page.provider

import io.legado.app.ui.book.read.page.provider.ViewSizeUpdateCoordinator.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewSizeUpdateCoordinatorTest {

    @Test
    fun staleHeightOnlyUpdateCannotOverwriteNewOrientationSize() {
        var currentSize = Size(width = 1600, height = 2400)
        val appliedSizes = mutableListOf<Size>()
        val scheduledTasks = mutableListOf<ScheduledTask>()
        val coordinator = createCoordinator(
            currentSize = { currentSize },
            onApply = {
                currentSize = it
                appliedSizes.add(it)
            },
            scheduledTasks = scheduledTasks,
        )

        coordinator.update(width = 1600, height = 1500)
        coordinator.update(width = 2400, height = 1500)

        assertTrue(scheduledTasks.single().cancelled)
        scheduledTasks.single().action.invoke()
        assertEquals(listOf(Size(2400, 1500)), appliedSizes)
        assertEquals(Size(2400, 1500), currentSize)
    }

    @Test
    fun consecutiveHeightOnlyUpdatesApplyOnlyLatestSize() {
        var currentSize = Size(width = 1600, height = 2400)
        val appliedSizes = mutableListOf<Size>()
        val scheduledTasks = mutableListOf<ScheduledTask>()
        val coordinator = createCoordinator(
            currentSize = { currentSize },
            onApply = {
                currentSize = it
                appliedSizes.add(it)
            },
            scheduledTasks = scheduledTasks,
        )

        coordinator.update(width = 1600, height = 1800)
        coordinator.update(width = 1600, height = 1500)

        assertTrue(scheduledTasks.first().cancelled)
        scheduledTasks.first().action.invoke()
        scheduledTasks.last().action.invoke()
        assertEquals(listOf(Size(1600, 1500)), appliedSizes)
    }

    @Test
    fun currentSizeCancelsPendingHeightOnlyUpdate() {
        val currentSize = Size(width = 1600, height = 2400)
        val appliedSizes = mutableListOf<Size>()
        val scheduledTasks = mutableListOf<ScheduledTask>()
        val coordinator = createCoordinator(
            currentSize = { currentSize },
            onApply = appliedSizes::add,
            scheduledTasks = scheduledTasks,
        )

        coordinator.update(width = 1600, height = 1800)
        coordinator.update(width = 1600, height = 2400)

        assertTrue(scheduledTasks.single().cancelled)
        scheduledTasks.single().action.invoke()
        assertTrue(appliedSizes.isEmpty())
    }

    private fun createCoordinator(
        currentSize: () -> Size,
        onApply: (Size) -> Unit,
        scheduledTasks: MutableList<ScheduledTask>,
    ) = ViewSizeUpdateCoordinator(
        currentSize = currentSize,
        scheduleDelayed = { _, action ->
            val task = ScheduledTask(action)
            scheduledTasks.add(task)
            val cancelAction: () -> Unit = { task.cancelled = true }
            cancelAction
        },
        applySize = onApply,
    )

    private data class ScheduledTask(
        val action: () -> Unit,
        var cancelled: Boolean = false,
    )
}
