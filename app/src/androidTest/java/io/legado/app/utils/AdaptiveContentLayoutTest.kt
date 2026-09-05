package io.legado.app.utils

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android measure/layout and RecyclerView reuse; only View.post is driven manually. */
@RunWith(AndroidJUnit4::class)
class AdaptiveContentLayoutTest {

    @Test
    fun existingAndRecycledRowsStayAlignedAfterWidthChange() = onMain {
        val list = createList()
        list.applyCenteredContentPadding()
        settleLayout(list, list.px(600))
        assertRowsAligned(list, 0)

        val wideWidth = list.px(1200)
        layout(list, wideWidth)
        list.runPostedTasks()
        assertTrue("Padding must request layout after View.layout has returned", list.isLayoutRequested)
        settleLayout(list, wideWidth)
        val widePadding = (wideWidth - list.px(840)) / 2
        assertRowsAligned(list, widePadding)

        list.scrollBy(0, list.px(180))
        assertRowsAligned(list, widePadding)
        settleLayout(list, list.px(600))
        assertRowsAligned(list, 0)
        list.scrollBy(0, -list.px(120))
        assertRowsAligned(list, 0)
    }

    @Test
    fun pendingUpdateUsesFinalWidthAndModeAndKeepsVerticalInsets() = onMain {
        val list = createList()
        var enabled = true
        list.setPaddingRelative(0, 17, 0, 29)
        list.applyCenteredContentPadding(minimumHorizontalPaddingDp = 12) { enabled }
        settleLayout(list, list.px(600))

        // Several layouts can happen before the queued padding update, including offscreen.
        layout(list, list.px(1200))
        layout(list, list.px(1000))
        enabled = false
        list.runPostedTasks()
        settleLayout(list, list.px(1000))
        assertRowsAligned(list, list.px(12))
        assertEquals(17, list.paddingTop)
        assertEquals(29, list.paddingBottom)

        enabled = true
        list.updateCenteredContentPadding(minimumHorizontalPaddingDp = 12)
        settleLayout(list, list.px(1000))
        assertRowsAligned(list, (list.width - list.px(840)) / 2)
        assertEquals(17, list.paddingTop)
        assertEquals(29, list.paddingBottom)
    }

    private fun createList(): QueuedRecyclerView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return QueuedRecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 100

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val row = TextView(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60))
                    }
                    return object : RecyclerView.ViewHolder(row) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    (holder.itemView as TextView).text = "Row $position"
                }
            }
        }
    }

    private fun layout(list: QueuedRecyclerView, width: Int) {
        val height = list.px(400)
        list.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, width, height)
    }

    private fun settleLayout(list: QueuedRecyclerView, width: Int) {
        layout(list, width)
        repeat(5) {
            list.runPostedTasks()
            if (!list.isLayoutRequested) return
            layout(list, width)
        }
        error("Centered padding must settle without a layout loop")
    }

    private fun assertRowsAligned(list: QueuedRecyclerView, expectedPadding: Int) {
        assertEquals(expectedPadding, list.paddingLeft)
        assertEquals(expectedPadding, list.paddingRight)
        assertTrue("Fixture must contain visible rows", list.childCount > 0)
        repeat(list.childCount) { index ->
            val row = list.getChildAt(index)
            assertEquals("Left edge of row $index", expectedPadding, row.left)
            assertEquals("Right edge of row $index", list.width - expectedPadding, row.right)
        }
    }

    private fun View.px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private class QueuedRecyclerView(context: Context) : RecyclerView(context) {
        private val postedTasks = mutableListOf<Runnable>()

        override fun post(action: Runnable): Boolean {
            postedTasks.add(action)
            return true
        }

        override fun removeCallbacks(action: Runnable): Boolean {
            postedTasks.removeAll { it === action }
            return true
        }

        fun runPostedTasks() {
            val tasks = postedTasks.toList()
            postedTasks.clear()
            tasks.forEach { it.run() }
        }
    }
}
