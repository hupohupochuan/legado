package io.legado.app.ui.about

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.AppLog
import io.legado.app.help.CrashHandler
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.update.AppUpdate
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.LogExportUtils
import io.legado.app.utils.LogFileWriter
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendMail
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class AboutFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.about)
        findPreference<Preference>("update_log")?.summary =
            "${getString(R.string.version)} ${appInfo.versionName}"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "contributors" -> openUrl(R.string.contributors_url)
            "support_development" -> openUrl(R.string.project_home_url)
            "update_log" -> showMdFile(getString(R.string.update_log), "updateLog.md")
            "check_update" -> {
                AppUpdate.check(lifecycleScope, requireActivity() as AppCompatActivity)
            }
            "mail" -> requireContext().sendMail(getString(R.string.email))
            "license" -> showMdFile(getString(R.string.license), "LICENSE.md")
            "disclaimer" -> showMdFile(getString(R.string.disclaimer), "disclaimer.md")
            "privacyPolicy" -> showMdFile(getString(R.string.privacy_policy), "privacyPolicy.md")
            "gzGzh" -> requireContext().sendToClip(getString(R.string.legado_gzh))
            "crashLog" -> showDialogFragment<CrashLogsDialog>()
            "saveLog" -> saveLog()
            "createHeapDump" -> createHeapDump()
        }
        return super.onPreferenceTreeClick(preference)
    }

    @Suppress("SameParameterValue")
    private fun openUrl(@StringRes addressID: Int) {
        requireContext().openUrl(getString(addressID))
    }

    /**
     * 显示md文件
     */
    private fun showMdFile(title: String, fileName: String) {
        val mdText = String(requireContext().assets.open(fileName).readBytes())
        showDialogFragment(TextDialog(title, mdText, TextDialog.Mode.MD))
    }


    private fun saveLog() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordLog) {
                appCtx.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(backupPath.toUri(), true)
            val result = copyLogs(doc)
            copyHeapDump(doc)
            appCtx.toastOnUi(result)
        }.onError {
            appCtx.toastOnUi("保存日志失败: ${it.localizedMessage}")
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createHeapDump() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            appCtx.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(backupPath.toUri(), true)
            if (!copyHeapDump(doc)) {
                appCtx.toastOnUi("未找到堆转储文件")
            } else {
                appCtx.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    private fun copyLogs(doc: FileDoc): String {
        val cacheDir = appCtx.externalCache
        val logSrcDir = LogUtils.logDir
        val crashDir = File(cacheDir, "crash")

        val snapshotDir = File(cacheDir, "log-export")
        cleanupSnapshot(snapshotDir)
        snapshotDir.mkdirs()
        val snapshotLogs = File(snapshotDir, "logs")
        val snapshotCrash = File(snapshotDir, "crash")
        snapshotCrash.mkdirs()

        if (crashDir.exists()) {
            crashDir.listFiles()?.forEach { src ->
                val dst = File(snapshotCrash, src.name)
                src.copyTo(dst, overwrite = true)
            }
        }

        val (appLogCount, flushOk) = createSnapshotInLogThread(snapshotDir, logSrcDir)

        val logcatFile = File(snapshotDir, "logcat.txt")
        dumpLogcat(logcatFile)

        val statusFile = File(snapshotDir, "log-status.txt")
        statusFile.writeText(buildString {
            appendLine("App 版本: ${appInfo.versionName} (${appInfo.versionCode})")
            appendLine("日志开关: ${AppConfig.recordLog}")
            appendLine("日志模块状态: ${LogUtils.status}")
            appendLine("日志目录: ${LogUtils.logDir?.absolutePath ?: "未设置"}")
            appendLine("初始化时间: ${
                if (LogUtils.initTime > 0)
                    SimpleDateFormat("yy-MM-dd HH:mm:ss.SSS")
                        .format(Date(LogUtils.initTime))
                else "未初始化"
            }")
            appendLine("最近初始化错误: ${LogUtils.lastInitError ?: "无"}")
            appendLine("落盘等待: ${if (flushOk) "成功" else "失败或无应用日志目录"}")
            appendLine("丢弃日志数量: ${LogFileWriter.droppedLogCount}")
            appendLine("导出 appLog 文件数量: $appLogCount")
        })

        val zipFile = File(cacheDir, "logs.zip")
        if (zipFile.exists()) zipFile.delete()

        val sources = mutableListOf<File>()
        if (snapshotLogs.exists() && snapshotLogs.listFiles()?.isNotEmpty() == true) {
            sources.add(snapshotLogs)
        }
        if (snapshotCrash.exists() && snapshotCrash.listFiles()?.isNotEmpty() == true) {
            sources.add(snapshotCrash)
        }
        sources.add(logcatFile)
        sources.add(statusFile)

        val zipOk = ZipUtils.zipFiles(sources, zipFile)
        if (!zipOk || !zipFile.exists() || zipFile.length() == 0L) {
            cleanupSnapshot(snapshotDir)
            return "ZIP 创建失败，未保存日志"
        }

        doc.find("logs.zip")?.delete()

        val outputDoc = doc.createFileIfNotExist("logs.zip").openOutputStream().getOrNull()
        if (outputDoc == null) {
            zipFile.delete()
            cleanupSnapshot(snapshotDir)
            return "备份目录不可写，未保存日志"
        }
        outputDoc.use { output ->
            zipFile.inputStream().use { input ->
                input.copyTo(output)
            }
        }
        zipFile.delete()
        cleanupSnapshot(snapshotDir)

        return if (appLogCount > 0) {
            "已保存至备份目录（$appLogCount 份应用日志）"
        } else {
            "未找到应用日志，仅导出了系统 logcat\n日志模块状态: ${LogUtils.status}"
        }
    }

    /**
     * 在日志线程中 flush 后复制应用日志快照，保证一致性。
     */
    private fun createSnapshotInLogThread(
        snapshotDir: File,
        logSrcDir: File?
    ): Pair<Int, Boolean> {
        val result = LogExportUtils.createAppLogSnapshot(snapshotDir, logSrcDir) { action ->
            LogUtils.withLogThreadFlush(5000L, action)
        }
        return result.appLogCount to result.flushOk
    }

    private fun cleanupSnapshot(snapshotDir: File) {
        try {
            snapshotDir.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        return true
    }

    private fun dumpLogcat(file: File) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d")
            file.outputStream().use {
                process.inputStream.copyTo(it)
            }
        } catch (e: Exception) {
            AppLog.put("保存Logcat失败\n$e", e)
        }
    }

}
