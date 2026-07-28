package io.legado.app.service

import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.CacheProgress
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 缓存书籍服务
 */
class CacheBookService : BaseService() {

    companion object {
        var isRun = false
            private set
    }

    private val threadCount = AppConfig.threadCount
    private var cachePool =
        Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    private var downloadJob: Job? = null
    private var notificationContent = appCtx.getString(R.string.service_starting)
    private var mutex = Mutex()
    private val notificationBuilder by lazy {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.offline_cache))
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<CacheBookService>(IntentAction.stop)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        CacheBook.beginProgressSession()
        isRun = true
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                upCacheBookNotification(CacheBook.progress())
                postEvent(EventBus.UP_DOWNLOAD, "")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.start -> addDownloadData(
                    intent.getStringExtra("bookUrl"),
                    intent.getIntExtra("start", 0),
                    intent.getIntExtra("end", 0)
                )

                IntentAction.remove -> removeDownload(intent.getStringExtra("bookUrl"))
                IntentAction.stop -> stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        isRun = false
        cachePool.close()
        CacheBook.close()
        super.onDestroy()
        postEvent(EventBus.UP_DOWNLOAD, "")
    }

    private fun addDownloadData(bookUrl: String?, start: Int, end: Int) {
        bookUrl ?: return
        execute {
            val cacheBook = CacheBook.getOrCreate(bookUrl) ?: return@execute
            val chapterCount = appDb.bookChapterDao.getChapterCount(bookUrl)
            val book = cacheBook.book
            if (chapterCount == 0) {
                cacheBook.setLoading()
                mutex.withLock {
                    val name = book.name
                    if (book.tocUrl.isEmpty()) {
                        kotlin.runCatching {
                            WebBook.getBookInfoAwait(cacheBook.bookSource, book)
                        }.onFailure {
                            CacheBook.recordSetupFailure(bookUrl, name)
                            removeDownload(bookUrl)
                            val msg = "《$name》目录为空且加载详情页失败\n${it.localizedMessage}"
                            AppLog.put(msg, it, true)
                            return@execute
                        }
                    }
                    WebBook.getChapterListAwait(cacheBook.bookSource, book).onFailure {
                        if (book.totalChapterNum > 0) {
                            book.totalChapterNum = 0
                            book.update()
                        }
                        CacheBook.recordSetupFailure(bookUrl, name)
                        removeDownload(bookUrl)
                        val msg = "《$name》目录为空且加载目录失败\n${it.localizedMessage}"
                        AppLog.put(msg, it, true)
                        return@execute
                    }.getOrNull()?.let { toc ->
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    }
                    book.update()
                }
            }
            val end2 = if (end < 0) {
                book.lastChapterIndex
            } else {
                min(end, book.lastChapterIndex)
            }
            cacheBook.addDownload(start, end2)
            upCacheBookNotification(CacheBook.progress())
        }.onFinally {
            if (downloadJob == null) {
                download()
            }
        }
    }

    private fun removeDownload(bookUrl: String?) {
        CacheBook.cacheBookMap[bookUrl]?.stop()
        postEvent(EventBus.UP_DOWNLOAD, "")
        if (downloadJob == null && CacheBook.isRun) {
            download()
            return
        }
        if (CacheBook.cacheBookMap.isEmpty()) {
            stopSelf()
        }
    }

    private fun download() {
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch(cachePool) {
            CacheBook.startProcessJob(cachePool)
            val progress = CacheBook.progress()
            withContext(Main) {
                if (progress.total > 0) {
                    showCompletionNotification(progress)
                }
                stopSelf()
            }
        }
    }

    private fun upCacheBookNotification(progress: CacheProgress) {
        notificationContent = when {
            progress.total > 0 -> getString(
                R.string.cache_progress_summary,
                progress.processed,
                progress.total,
                progress.downloading,
                progress.waiting,
                progress.failed,
                progress.canceled
            )

            progress.loadingBookCount > 0 -> getString(R.string.cache_loading_toc)
            else -> getString(R.string.service_starting)
        }
        notificationBuilder.setContentText(notificationContent)
        when {
            progress.total > 0 -> notificationBuilder.setProgress(
                progress.total,
                progress.processed,
                false
            )

            progress.isIndeterminate -> notificationBuilder.setProgress(0, 0, true)
            else -> notificationBuilder.setProgress(0, 0, false)
        }
        val notification = notificationBuilder.build()
        notificationManager.notify(NotificationId.CacheBookService, notification)
    }

    private fun showCompletionNotification(progress: CacheProgress) {
        val openCache = activityPendingIntent<CacheActivity>("cacheActivity")
        notificationBuilder
            .clearActions()
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openCache)
            .setContentText(
                getString(
                    R.string.cache_complete_notification,
                    progress.processed,
                    progress.total,
                    progress.failed,
                    progress.canceled
                )
            )
            .setProgress(progress.total, progress.processed, false)
            .addAction(R.drawable.ic_export, getString(R.string.export), openCache)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        notificationManager.notify(
            NotificationId.CacheBookService,
            notificationBuilder.build()
        )
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        notificationBuilder.setProgress(0, 0, true)
        val notification = notificationBuilder.build()
        startForegroundCompat(
            NotificationId.CacheBookService,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            "创建缓存通知出错"
        )
    }

}
