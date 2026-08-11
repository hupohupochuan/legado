package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.ui.config.WebServicePermissionActivity
import io.legado.app.utils.LogUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.sendToClip
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import io.legado.app.web.HttpServer
import io.legado.app.web.WebSocketServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.wifiManager
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class WebService : BaseService() {

    companion object {
        @Volatile
        var isRun = false
        @Volatile
        var hostAddress = ""
        private const val TAG = "WebService"
        private const val RESTART_INTERVAL_MS = 3 * 60 * 60 * 1000L
        private const val RESTART_CHECK_INTERVAL_MS = 30 * 60 * 1000L
        private val startRequested = AtomicBoolean(false)

        fun start(context: Context) {
            startRequested.set(true)
            if (!WebServiceLocalNetworkAccess.isGranted(context)) {
                WebServicePermissionActivity.start(context)
                return
            }
            startWithPermission(context)
        }

        fun startForeground(context: Context) {
            start(context)
        }

        fun stop(context: Context) {
            startRequested.set(false)
            appCtx.putPrefBoolean(PreferKey.webService, false)
            notificationManager.cancel(NotificationId.WebService)
            context.stopService<WebService>()
        }

        fun restart(context: Context) {
            startRequested.set(true)
            notificationManager.cancel(NotificationId.WebService)
            context.stopService<WebService>()
            start(context)
        }

        fun serve() {
            if (isRun) {
                if (!WebServiceLocalNetworkAccess.isGranted(appCtx)) {
                    stopForMissingLocalNetworkPermission()
                    return
                }
                appCtx.startService<WebService> {
                    action = "serve"
                }
            }
        }

        internal fun markStartRequested() {
            startRequested.set(true)
        }

        internal fun isStartPending(): Boolean {
            return startRequested.get() && !isRun
        }

        internal fun startIfRequested(context: Context) {
            if (!startRequested.get() || !WebServiceLocalNetworkAccess.isGranted(context)) return
            startWithPermission(context)
        }

        internal fun cancelPermissionRequest(context: Context, showMessage: Boolean) {
            stop(context)
            postEvent(EventBus.WEB_SERVICE, "")
            if (showMessage) {
                AppLog.put(
                    appCtx.getString(R.string.web_service_local_network_permission_denied),
                    toast = true
                )
            }
        }

        private fun startWithPermission(context: Context) {
            if (!startRequested.get()) return
            val appContext = context.applicationContext
            appContext.putPrefBoolean(PreferKey.webService, true)
            try {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, WebService::class.java)
                )
            } catch (e: Exception) {
                startRequested.set(false)
                appContext.putPrefBoolean(PreferKey.webService, false)
                AppLog.put("启动Web服务出错,${e.localizedMessage}", e, toast = true)
            }
        }

        private fun stopForMissingLocalNetworkPermission(error: Throwable? = null) {
            startRequested.set(false)
            appCtx.putPrefBoolean(PreferKey.webService, false)
            notificationManager.cancel(NotificationId.WebService)
            appCtx.stopService<WebService>()
            postEvent(EventBus.WEB_SERVICE, "")
            AppLog.put(
                appCtx.getString(R.string.web_service_local_network_permission_denied),
                error,
                toast = true
            )
        }
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.webServiceWakeLock, false)
    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:WebService")
            .apply {
                setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:WebService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private var httpServer: HttpServer? = null
    private var webSocketServer: WebSocketServer? = null
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
    @Volatile
    private var startTimeMs: Long = 0L
    @Volatile
    private var isRestarting: Boolean = false
    @Volatile
    private var foregroundNotificationStarted: Boolean = false
    @Volatile
    private var foregroundStartRejected: Boolean = false
    @Volatile
    private var isStopping: Boolean = false
    private var runtimeInitialized: Boolean = false
    private val restartGuard = Any()
    private var restartCheckerJob: Job? = null
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        if (!hasLocalNetworkPermissionOrStop()) return
        runtimeInitialized = true
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = onNetworkChanged@{
            if (isStopping || foregroundStartRejected) return@onNetworkChanged
            if (!hasLocalNetworkPermissionOrStop()) return@onNetworkChanged
            val addressList = NetworkUtils.getLocalIPAddress()
            notificationList.clear()
            if (addressList.any()) {
                notificationList.addAll(addressList.map { address ->
                    getString(
                        R.string.http_ip,
                        address.hostAddress,
                        getPort()
                    )
                })
                hostAddress = notificationList.first()
            } else {
                hostAddress = getString(R.string.network_connection_unavailable)
                notificationList.add(hostAddress)
            }
            startForegroundNotification()
            if (isStopping || foregroundStartRejected) return@onNetworkChanged
            postEvent(EventBus.WEB_SERVICE, hostAddress)
        }
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) {
            tryRestartOnChapterLoaded()
        }
        startRestartChecker()
    }

    private fun startRestartChecker() {
        restartCheckerJob?.cancel()
        restartCheckerJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(RESTART_CHECK_INTERVAL_MS)
                tryRestartOnChapterLoaded()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocalNetworkPermissionOrStop()) return START_NOT_STICKY
        when (intent?.action) {
            IntentAction.stop -> {
                startRequested.set(false)
                appCtx.putPrefBoolean(PreferKey.webService, false)
                requestStop()
            }
            "copyHostAddress" -> sendToClip(hostAddress)
            "serve" -> if (!isStopping && useWakeLock) {
                wakeLock.acquire()
                wifiLock?.acquire()
            }

            else -> if (!isStopping) upWebServer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        markStartFailed()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 统一停机入口：先 tearDown() 复位状态并移除通知，再 stopSelf()。
     * 所有 WebService 内部停止请求均走此方法，避免 stopSelf() 后残留通知或后台回调重新启动 server。
     *
     * 锁安全：当从 upWebServer 的 IOException/无网络分支调用时，requestStop 进入 tearDown 会再次
     * synchronized(restartGuard)；同一线程对 JVM monitor 是可重入的，因此不会自死锁。
     */
    private fun requestStop() {
        tearDown()
        stopSelf()
    }

    /**
     * 统一停机逻辑：通知栏取消、设置开关、快捷磁贴、启动失败、Android 16 超时均走此路径。
     * 在 restartGuard 临界区内提交 isStopping/isRun/isRestarting，与 upWebServer 的"提交成功"段互斥，
     * 避免后台线程在 tearDown 之后再把 isRun 写回 true 并发布有效地址事件。
     * 锁外执行网络注销、server 关闭和锁释放等可能阻塞或抛异常的清理；通知移除放入 finally，
     * 保证即使其它清理步骤抛异常，ID 105 通知也一定被移除。幂等，重复调用安全。
     *
     * 清理步骤显式捕获 Exception（非 Throwable），不吞掉 OOM、ThreadDeath 等致命错误。
     */
    private fun tearDown() {
        synchronized(restartGuard) {
            if (isStopping) return
            isStopping = true
            isRun = false
            isRestarting = false
        }
        try {
            restartCheckerJob?.cancel()
            restartCheckerJob = null
            if (runtimeInitialized) {
                try { networkChangedListener.unRegister() } catch (e: Exception) {
                    LogUtils.e(TAG, "unRegister 失败: ${e.localizedMessage}")
                }
            }
            stopServerSafely(httpServer, "httpServer")
            stopServerSafely(webSocketServer, "webSocketServer")
            if (runtimeInitialized && useWakeLock) {
                try { wakeLock.release() } catch (e: Exception) {
                    LogUtils.e(TAG, "release wakeLock 失败: ${e.localizedMessage}")
                }
                try { wifiLock?.release() } catch (e: Exception) {
                    LogUtils.e(TAG, "release wifiLock 失败: ${e.localizedMessage}")
                }
            }
            try { postEvent(EventBus.WEB_SERVICE, "") } catch (e: Exception) {
                LogUtils.e(TAG, "postEvent 失败: ${e.localizedMessage}")
            }
            try { upTile(false) } catch (e: Exception) {
                LogUtils.e(TAG, "upTile 失败: ${e.localizedMessage}")
            }
        } finally {
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                LogUtils.e(TAG, "stopForeground 失败: ${e.localizedMessage}")
            }
            try {
                notificationManager.cancel(NotificationId.WebService)
            } catch (e: Exception) {
                LogUtils.e(TAG, "cancel 通知失败: ${e.localizedMessage}")
            }
            foregroundNotificationStarted = false
        }
    }

    private fun stopServerSafely(server: HttpServer?, name: String) {
        if (server?.isAlive != true) return
        try {
            server.stop()
        } catch (e: Exception) {
            LogUtils.e(TAG, "stop $name 失败: ${e.localizedMessage}")
        }
    }

    private fun stopServerSafely(server: WebSocketServer?, name: String) {
        if (server?.isAlive != true) return
        try {
            server.stop()
        } catch (e: Exception) {
            LogUtils.e(TAG, "stop $name 失败: ${e.localizedMessage}")
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForForegroundRestriction(null, timeLimit = true)
        super.onTimeout(startId, fgsType)
    }

    private fun tryRestartOnChapterLoaded() {
        if (isStopping || !isRun || foregroundStartRejected) return
        if (!hasLocalNetworkPermissionOrStop()) return
        val elapsed = System.currentTimeMillis() - startTimeMs
        if (elapsed < RESTART_INTERVAL_MS) return
        LogUtils.d(TAG) {
            "Web服务已运行${elapsed / 60000}分钟，触发重启"
        }
        upWebServer()
    }

    private fun upWebServer() {
        if (isStopping) return
        if (!hasLocalNetworkPermissionOrStop()) return
        synchronized(restartGuard) {
            if (isRestarting || isStopping) return
            isRestarting = true
        }
        try {
            val addressList = NetworkUtils.getLocalIPAddress()
            if (addressList.any()) {
                val port = getPort()
                try {
                    if (httpServer?.isAlive == true) {
                        httpServer?.stop()
                    }
                    if (webSocketServer?.isAlive == true) {
                        webSocketServer?.stop()
                    }
                    httpServer = HttpServer(port)
                    webSocketServer = WebSocketServer(port + 1)
                    httpServer?.start()
                    webSocketServer?.start(AppConst.timeLimit.toInt()) // 通信超时设置
                    notificationList.clear()
                    notificationList.addAll(addressList.map { address ->
                        getString(
                            R.string.http_ip,
                            address.hostAddress,
                            getPort()
                        )
                    })
                    hostAddress = notificationList.first()
                    // 提交段与 tearDown 共用同一临界区（restartGuard），保证 isRun/isStopping 写入和 isStopping 检查
                    // 不会被 tearDown 拆开。若 tearDown 已抢先置 isStopping=true，本分支立即停掉刚启动的 server 并返回，
                    // 避免"界面显示开启但端口已关闭"的竞态。
                    synchronized(restartGuard) {
                        if (isStopping) {
                            try { httpServer?.stop() } catch (e: Exception) {
                                LogUtils.e(TAG, "stop httpServer 失败: ${e.localizedMessage}")
                            }
                            try { webSocketServer?.stop() } catch (e: Exception) {
                                LogUtils.e(TAG, "stop webSocketServer 失败: ${e.localizedMessage}")
                            }
                            return
                        }
                        isRun = true
                        startTimeMs = System.currentTimeMillis()
                        upTile(true)
                        postEvent(EventBus.WEB_SERVICE, hostAddress)
                        startForegroundNotification()
                        if (!foregroundStartRejected) {
                            LogUtils.d(TAG) { "Web服务(重新)启动成功，重新开始计时" }
                        }
                    }
                } catch (e: IOException) {
                    toastOnUi(e.localizedMessage ?: "")
                    e.printOnDebug()
                    markStartFailed()
                    requestStop()
                }
            } else {
                toastOnUi("web service cant start, no ip address")
                markStartFailed()
                requestStop()
            }
        } finally {
            isRestarting = false
        }
    }

    private fun getPort(): Int {
        var port = getPrefInt(PreferKey.webPort, 1122)
        if (port !in 1024..65530) {
            port = 1122
        }
        return port
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        if (isStopping || foregroundStartRejected) return
        if (!hasLocalNetworkPermissionOrStop()) return
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setContentTitle(getString(R.string.web_service))
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(
                servicePendingIntent<WebService>("copyHostAddress")
            )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<WebService>(IntentAction.stop)
        )
        val notification = builder.build()
        try {
            if (foregroundNotificationStarted) {
                notificationManager.notify(NotificationId.WebService, notification)
            } else {
                ServiceCompat.startForeground(
                    this,
                    NotificationId.WebService,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                foregroundNotificationStarted = true
            }
        } catch (e: Exception) {
            if (e.isForegroundServiceStartNotAllowed()) {
                stopForForegroundRestriction(e, e.isDataSyncTimeLimit())
            } else {
                AppLog.put("创建Web服务通知出错,${e.localizedMessage}", e, true)
                requestStop()
            }
        }
    }

    private fun stopForForegroundRestriction(error: Throwable?, timeLimit: Boolean) {
        if (foregroundStartRejected) return
        foregroundStartRejected = true
        markStartFailed()
        val message = if (timeLimit) {
            getString(R.string.web_service_data_sync_time_limit)
        } else {
            getString(R.string.web_service_foreground_start_rejected)
        }
        AppLog.put(message, error, toast = true)
        requestStop()
    }

    private fun hasLocalNetworkPermissionOrStop(): Boolean {
        if (WebServiceLocalNetworkAccess.isGranted(this)) return true
        if (!isStopping) {
            promoteBeforePermissionStop()
            markStartFailed()
            AppLog.put(
                getString(R.string.web_service_local_network_permission_denied),
                toast = true
            )
            requestStop()
        }
        return false
    }

    /**
     * A caller inside the same UID can bypass start() and launch this foreground service directly. Android still
     * requires the service to complete foreground promotion before it stops, even when the permission gate rejects
     * the launch in onCreate(). The notification is removed immediately by requestStop()/tearDown().
     */
    private fun promoteBeforePermissionStop() {
        if (foregroundNotificationStarted) return
        val notification = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(getString(R.string.web_service))
            .setContentText(getString(R.string.web_service_local_network_permission_denied))
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                NotificationId.WebService,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            foregroundNotificationStarted = true
        } catch (e: Exception) {
            AppLog.put("Web服务拒权停机前创建前台通知失败,${e.localizedMessage}", e)
        }
    }

    private fun markStartFailed() {
        startRequested.set(false)
        appCtx.putPrefBoolean(PreferKey.webService, false)
    }

    private fun Throwable.isForegroundServiceStartNotAllowed(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    }

    private fun Throwable.isDataSyncTimeLimit(): Boolean {
        val msg = message ?: return false
        return msg.contains("Time limit already exhausted", ignoreCase = true) &&
                msg.contains("dataSync", ignoreCase = true)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun upTile(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            kotlin.runCatching {
                startService<WebTileService> {
                    action = if (active) {
                        IntentAction.start
                    } else {
                        IntentAction.stop
                    }
                }
            }

        }
    }
}
