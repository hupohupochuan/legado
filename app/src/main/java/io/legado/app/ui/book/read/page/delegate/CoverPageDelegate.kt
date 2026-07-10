package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.utils.ReaderPerformance
import io.legado.app.utils.screenshot

class CoverPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {
    private val shadowDrawableR: GradientDrawable
    private var preparedDirection = PageDirection.NONE
    private var preparedTextPage: TextPage? = null
    private var preparedAt = 0L

    init {
        val shadowColors = intArrayOf(0x66111111, 0x00000000)
        shadowDrawableR = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, shadowColors
        )
        shadowDrawableR.gradientType = GradientDrawable.LINEAR_GRADIENT
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRunning) return
        val offsetX = touchX - startX

        if ((mDirection == PageDirection.NEXT && offsetX > 0)
            || (mDirection == PageDirection.PREV && offsetX < 0)
        ) {
            return
        }

        val distanceX = if (offsetX > 0) offsetX - viewWidth else offsetX + viewWidth
        if (mDirection == PageDirection.PREV) {
            if (offsetX <= viewWidth) {
                canvas.withTranslation(distanceX) {
                    prevRecorder.draw(canvas)
                }
                addShadow(distanceX, canvas)
            } else {
                prevRecorder.draw(canvas)
            }
        } else if (mDirection == PageDirection.NEXT) {
            val width = nextRecorder.width.toFloat()
            val height = nextRecorder.height.toFloat()
            canvas.withClip(width + offsetX, 0f, width, height) {
                nextRecorder.draw(this)
            }
            canvas.withTranslation(distanceX - viewWidth) {
                curRecorder.draw(this)
            }
            addShadow(distanceX, canvas)
        }
    }

    override fun setBitmap() {
        if (mDirection == PageDirection.NONE) {
            clearPreparedPage()
            return
        }
        val usePreparedPage = isPreparedFor(mDirection)
        ReaderPerformance.trace("android.read.captureCover", 8, "direction=$mDirection") {
            when (mDirection) {
                PageDirection.PREV -> {
                    if (!usePreparedPage) {
                        prevPage.screenshot(prevRecorder)
                    }
                }

                PageDirection.NEXT -> {
                    nextPage.screenshot(nextRecorder)
                    if (!usePreparedPage) {
                        curPage.screenshot(curRecorder)
                    }
                }

                else -> Unit
            }
        }
        clearPreparedPage()
    }

    override fun preparePage(direction: PageDirection) {
        clearPreparedPage()
        ReaderPerformance.trace("android.read.prepareCover", 8, "direction=$direction") {
            when (direction) {
                PageDirection.NEXT -> {
                    curPage.screenshot(curRecorder)
                    preparedTextPage = curPage.textPage
                }

                else -> return@trace
            }
        }
        preparedDirection = direction
        preparedAt = SystemClock.uptimeMillis()
    }

    private fun isPreparedFor(direction: PageDirection): Boolean {
        if (preparedDirection != direction) return false
        if (SystemClock.uptimeMillis() - preparedAt > PREPARED_PAGE_VALID_MS) return false
        val expectedPage = when (direction) {
            PageDirection.NEXT -> curPage.textPage
            else -> return false
        }
        return preparedTextPage === expectedPage
    }

    private fun clearPreparedPage() {
        preparedDirection = PageDirection.NONE
        preparedTextPage = null
        preparedAt = 0L
    }

    private fun addShadow(left: Float, canvas: Canvas) {
        if (left == 0f) return
        val dx = if (left < 0) {
            left + viewWidth
        } else {
            left
        }
        canvas.withTranslation(dx) {
            shadowDrawableR.draw(canvas)
        }
    }

    override fun setViewSize(width: Int, height: Int) {
        clearPreparedPage()
        super.setViewSize(width, height)
        shadowDrawableR.setBounds(0, 0, 30, viewHeight)
    }

    override fun onAnimStop() {
        if (!isCancel) {
            readView.fillPage(mDirection)
        }
    }

    override fun onAnimStart(animationSpeed: Int) {
        val distanceX: Float
        when (mDirection) {
            PageDirection.NEXT -> distanceX =
                if (isCancel) {
                    var dis = viewWidth - startX + touchX
                    if (dis > viewWidth) {
                        dis = viewWidth.toFloat()
                    }
                    viewWidth - dis
                } else {
                    -(touchX + (viewWidth - startX))
                }

            else -> distanceX =
                if (isCancel) {
                    -(touchX - startX)
                } else {
                    viewWidth - (touchX - startX)
                }
        }
        startScroll(touchX.toInt(), 0, distanceX.toInt(), 0, animationSpeed)
    }

    companion object {
        private const val PREPARED_PAGE_VALID_MS = 500L
    }

}
