package io.legado.app.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * 章节下载状态管理: 统一管理预下载字段, 供 [ReadBook] 和 [ReadMangaViewModel] 共享,
 * 消除两者重复定义的 `downloadedChapters` / `downloadFailChapters` / `downloadScope` /
 * `preDownloadSemaphore` / `preDownloadTask` 五个字段.
 *
 * 只承载状态数据和生命周期取消, 不包含下载调度逻辑 (文本和漫画的下载路径差异大,
 * 强行合并会引入接口爆炸). 调度逻辑仍留在各自阅读模型里, 通过本类统一状态.
 *
 * 线程安全: [downloadedChapters] 和 [downloadFailChapters] 使用线程安全集合,
 * [clear] 和 [cancelPreDownload] 可在任意线程调用.
 */
class ContentDownloadState {

    /** 已下载成功的章节 index 集合 */
    val downloadedChapters: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** 下载失败次数: chapterIndex -> 失败次数 (>=3 则预下载跳过) */
    val downloadFailChapters: ConcurrentHashMap<Int, Int> = ConcurrentHashMap()

    /** 下载协程 scope: 用 SupervisorJob, 子 Job 失败不影响其他下载 */
    val downloadScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** 预下载并发信号量 (默认 2) */
    val preDownloadSemaphore = Semaphore(2)

    /** 预下载总 Job, 取消它可停止全部预下载子协程 */
    @Volatile
    var preDownloadTask: Job? = null

    /** 清空下载记录 (切换书 / 重新加载时调用) */
    fun clear() {
        preDownloadTask?.cancel()
        preDownloadTask = null
        downloadedChapters.clear()
        downloadFailChapters.clear()
    }

    /** 取消预下载任务并终止下载 scope 的子协程 */
    fun cancelPreDownload() {
        preDownloadTask?.cancel()
        preDownloadTask = null
        downloadScope.coroutineContext.cancelChildren()
    }

    /** 记录下载成功 */
    fun markDownloaded(index: Int) {
        downloadedChapters.add(index)
        downloadFailChapters.remove(index)
    }

    /** 记录下载失败, 返回当前失败次数 */
    fun markFailed(index: Int): Int {
        val count = (downloadFailChapters[index] ?: 0) + 1
        downloadFailChapters[index] = count
        return count
    }

    /** 章节是否已下载 */
    fun isDownloaded(index: Int): Boolean = downloadedChapters.contains(index)

    /** 章节失败次数是否已达上限 (默认 3) */
    fun isFailedTooMany(index: Int, max: Int = 3): Boolean =
        (downloadFailChapters[index] ?: 0) >= max
}
