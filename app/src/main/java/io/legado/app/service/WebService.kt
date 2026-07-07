package io.legado.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
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
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.receiver.NetworkChangedListener
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
import io.legado.app.utils.safeStartForegroundService
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

class WebService : BaseService() {

    companion object {
        var isRun = false
        var hostAddress = ""
        private const val TAG = "WebService"
        private const val RESTART_INTERVAL_MS = 3 * 60 * 60 * 1000L
        private const val RESTART_CHECK_INTERVAL_MS = 30 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, WebService::class.java)
            context.safeStartForegroundService(intent, "启动Web服务出错")
        }

        fun startForeground(context: Context) {
            start(context)
        }

        fun stop(context: Context) {
            context.stopService<WebService>()
        }

        fun serve() {
            if (isRun) {
                appCtx.startService<WebService> {
                    action = "serve"
                }
            }
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
    private val restartGuard = Any()
    private var restartCheckerJob: Job? = null
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun = true
        startTimeMs = System.currentTimeMillis()
        upTile(true)
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = onNetworkChanged@{
            if (foregroundStartRejected) return@onNetworkChanged
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
            if (foregroundStartRejected) return@onNetworkChanged
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
        when (intent?.action) {
            IntentAction.stop -> stopSelf()
            "copyHostAddress" -> sendToClip(hostAddress)
            "serve" -> if (useWakeLock) {
                wakeLock.acquire()
                wifiLock?.acquire()
            }

            else -> upWebServer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        restartCheckerJob?.cancel()
        restartCheckerJob = null
        foregroundNotificationStarted = false
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        networkChangedListener.unRegister()
        isRun = false
        if (httpServer?.isAlive == true) {
            httpServer?.stop()
        }
        if (webSocketServer?.isAlive == true) {
            webSocketServer?.stop()
        }
        postEvent(EventBus.WEB_SERVICE, "")
        upTile(false)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForForegroundRestriction(null, timeLimit = true)
        super.onTimeout(startId, fgsType)
    }

    private fun tryRestartOnChapterLoaded() {
        if (!isRun || foregroundStartRejected) return
        val elapsed = System.currentTimeMillis() - startTimeMs
        if (elapsed < RESTART_INTERVAL_MS) return
        LogUtils.d(TAG) {
            "Web服务已运行${elapsed / 60000}分钟，触发重启"
        }
        upWebServer()
    }

    private fun upWebServer() {
        synchronized(restartGuard) {
            if (isRestarting) return
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
                    isRun = true
                    startTimeMs = System.currentTimeMillis()
                    postEvent(EventBus.WEB_SERVICE, hostAddress)
                    startForegroundNotification()
                    if (!foregroundStartRejected) {
                        LogUtils.d(TAG) { "Web服务(重新)启动成功，重新开始计时" }
                    }
                } catch (e: IOException) {
                    toastOnUi(e.localizedMessage ?: "")
                    e.printOnDebug()
                    stopSelf()
                }
            } else {
                toastOnUi("web service cant start, no ip address")
                stopSelf()
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
        if (foregroundStartRejected) return
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
                stopSelf()
            }
        }
    }

    private fun stopForForegroundRestriction(error: Throwable?, timeLimit: Boolean) {
        if (foregroundStartRejected) return
        foregroundStartRejected = true
        foregroundNotificationStarted = false
        appCtx.putPrefBoolean(PreferKey.webService, false)
        val message = if (timeLimit) {
            getString(R.string.web_service_data_sync_time_limit)
        } else {
            getString(R.string.web_service_foreground_start_rejected)
        }
        AppLog.put(message, error, toast = true)
        stopSelf()
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
