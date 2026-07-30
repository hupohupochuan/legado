package io.legado.app.ui.association

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.lib.dialogs.onCancelled
import io.legado.app.lib.dialogs.onDismiss
import io.legado.app.lib.dialogs.yesButton
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.file.registerHandleFile
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.LocalBookFileImportHelper
import io.legado.app.utils.canRead
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

class FileAssociationDialog() : BaseDialogFragment(R.layout.dialog_progressbar_view) {

    constructor(uri: Uri) : this() {
        arguments = Bundle().apply {
            putParcelable("uri", uri)
        }
    }

    private val viewModel by viewModels<FileAssociationViewModel>()
    private var importJob: Job? = null
    private val localBookTreeSelect by lazy {
        registerHandleFile { result ->
            val uri = BundleCompat.getParcelable(arguments ?: return@registerHandleFile, "uri", Uri::class.java)
                ?: return@registerHandleFile
            result.uri?.let { treeUri ->
                AppConfig.defaultBookTreeUri = treeUri.toString()
                importBook(treeUri, uri)
            }
        }
    }

    private val isShell get() = activity is AssociationActivity

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val uri = arguments?.let { BundleCompat.getParcelable(it, "uri", Uri::class.java) } ?: return dismiss()

        view.findViewById<View>(R.id.tv_footer_left).setOnClickListener {
            importJob?.cancel()
            finishActivity()
        }
        view.findViewById<ProgressBar>(R.id.ck_progress).isIndeterminate = true
        view.findViewById<TextView>(R.id.ck_progress_text).text = getString(R.string.importing)

        viewModel.importBookLiveData.observe(viewLifecycleOwner) {
            importBook(it)
        }
        viewModel.successLive.observe(viewLifecycleOwner) {
            handleSuccess(it)
        }
        viewModel.errorLive.observe(viewLifecycleOwner) {
            toastOnUi(it)
            finishActivity()
        }
        viewModel.openBookLiveData.observe(viewLifecycleOwner) {
            requireContext().startActivityForBook(it)
            finishActivity()
        }
        viewModel.onLineImportLive.observe(viewLifecycleOwner) {
            handleOnLineImport(it)
        }
        viewModel.notSupportedLiveData.observe(viewLifecycleOwner) { data ->
            alert(
                title = appCtx.getString(R.string.draw),
                message = appCtx.getString(R.string.file_not_supported, data.second)
            ) {
                yesButton {
                    importBook(data.first)
                }
                noButton {
                    finishActivity()
                }
                onCancelled {
                    finishActivity()
                }
            }
        }

        if (uri.isContentScheme() && uri.canRead()) {
            viewModel.dispatchIntent(uri)
        } else if (uri.scheme == "legado" || uri.scheme == "yuedu") {
            viewModel.dispatchIntent(uri)
        } else {
            PermissionsCompat.Builder()
                .addPermissions(*Permissions.Group.STORAGE)
                .rationale(R.string.tip_perm_request_storage)
                .onGranted {
                    viewModel.dispatchIntent(uri)
                }
                .onDenied {
                    toastOnUi("请求存储权限失败。")
                    finishActivity()
                }
                .request()
        }
    }

    private fun handleOnLineImport(uri: Uri) {
        val url = uri.getQueryParameter("src")
        if (url.isNullOrEmpty()) {
            finishActivity()
            return
        }
        when (uri.path) {
            "/bookSource", "/rssSource" -> showImportDialog(ImportBookSourceDialog(url, isShell))
            "/replaceRule" -> showImportDialog(ImportReplaceRuleDialog(url, isShell))
            "/textTocRule" -> showImportDialog(ImportTxtTocRuleDialog(url, isShell))
            "/httpTTS" -> showImportDialog(ImportHttpTtsDialog(url, isShell))
            "/dictRule" -> showImportDialog(ImportDictRuleDialog(url, isShell))
            "/theme" -> showImportDialog(ImportThemeDialog(url, isShell))
            "/addToBookshelf" -> showImportDialog(AddToBookshelfDialog(url, isShell))

            "/readConfig" -> viewModel.getBytes(url) { bytes ->
                viewModel.importReadConfig(bytes) { title, msg ->
                    finallyDialog(title, msg)
                }
            }

            "/importonline" -> when (uri.host) {
                "booksource", "rsssource" -> showImportDialog(ImportBookSourceDialog(url, isShell))
                "replace" -> showImportDialog(ImportReplaceRuleDialog(url, isShell))
                else -> viewModel.determineType(url) { title, msg ->
                    finallyDialog(title, msg)
                }
            }

            else -> viewModel.determineType(url) { title, msg ->
                finallyDialog(title, msg)
            }
        }
    }

    private fun handleSuccess(it: Pair<String, String>) {
        when (it.first) {
            "bookSource", "rssSource" -> showImportDialog(
                ImportBookSourceDialog(
                    it.second,
                    isShell
                )
            )

            "replaceRule" -> showImportDialog(ImportReplaceRuleDialog(it.second, isShell))
            "httpTts" -> showImportDialog(ImportHttpTtsDialog(it.second, isShell))
            "theme" -> showImportDialog(ImportThemeDialog(it.second, isShell))
            "txtRule" -> showImportDialog(ImportTxtTocRuleDialog(it.second, isShell))
            "dictRule" -> showImportDialog(ImportDictRuleDialog(it.second, isShell))
        }
    }

    private fun showImportDialog(dialog: BaseDialogFragment) {
        (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.showDialogFragment(dialog)
        dismiss()
    }

    private fun finallyDialog(title: String, msg: String) {
        alert(title, msg) {
            okButton()
            onDismiss {
                finishActivity()
            }
        }
    }

    private fun finishActivity() {
        if (isShell) {
            activity?.finish()
        }
        dismiss()
    }

    private fun importBook(uri: Uri) {
        val treeUriStr = AppConfig.defaultBookTreeUri
        if (uri.isContentScheme() && treeUriStr.isNullOrEmpty()) {
            localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
                mode = HandleFileContract.DIR_SYS
            }
        } else {
            importBook(treeUriStr?.toUri(), uri)
        }
    }

    private fun importBook(treeUri: Uri?, uri: Uri) {
        importJob?.cancel()
        importJob = lifecycleScope.launch {
            try {
                withContext(IO) {
                    if (treeUri == null) {
                        viewModel.importBook(uri)
                    } else if (treeUri.isContentScheme()) {
                        val treeDoc = DocumentFile.fromTreeUri(requireContext(), treeUri)
                        if (treeDoc?.checkWrite() != true) {
                            throw InvalidBooksDirException("请重新设置书籍保存位置")
                        }
                        val fileDoc = FileDoc.fromUri(uri, false)
                        val name = getImportFileName(fileDoc, uri)
                        var doc = treeDoc.findFile(name)
                        if (doc == null) {
                            doc = treeDoc.createFile(FileUtils.getMimeType(name), name)
                                ?: throw InvalidBooksDirException("请重新设置书籍保存位置")
                        }
                        if (LocalBookFileImportHelper.isSameFile(requireContext(), uri, doc.uri)) {
                            viewModel.importBook(doc.uri)
                        } else {
                            LocalBookFileImportHelper.copyToDocument(
                                requireContext(), uri, doc.uri
                            ) { stage, copied, _ ->
                                view?.post {
                                    updateImportProgress(stage, copied, fileDoc.size)
                                }
                            }
                            viewModel.importBook(doc.uri)
                        }
                    } else {
                        val treeFile = File(treeUri.path ?: treeUri.toString())
                        if (!treeFile.checkWrite()) {
                            throw InvalidBooksDirException("请重新设置书籍保存位置")
                        }
                        val fileDoc = FileDoc.fromUri(uri, false)
                        val name = getImportFileName(fileDoc, uri)
                        val file = treeFile.getFile(name)
                        if (LocalBookFileImportHelper.isSameFile(
                                requireContext(), uri, Uri.fromFile(file)
                            )
                        ) {
                            viewModel.importBook(Uri.fromFile(file))
                        } else {
                            LocalBookFileImportHelper.copyToFile(
                                requireContext(), uri, file
                            ) { stage, copied, _ ->
                                view?.post {
                                    updateImportProgress(stage, copied, fileDoc.size)
                                }
                            }
                            if (fileDoc.lastModified > 0) {
                                file.setLastModified(fileDoc.lastModified)
                            }
                            viewModel.importBook(Uri.fromFile(file))
                        }
                    }
                }
            } catch (_: CancellationException) {
                // 用户取消，静默结束
            } catch (e: InvalidBooksDirException) {
                localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                    mode = HandleFileContract.DIR_SYS
                }
            } catch (e: Exception) {
                val msg = "导入书籍失败\n${e.localizedMessage}"
                AppLog.put(msg, e)
                toastOnUi(msg)
                finishActivity()
            }
        }
    }

    private fun updateImportProgress(
        stage: LocalBookFileImportHelper.ImportStage,
        copied: Long,
        totalSize: Long
    ) {
        val progressBar = view?.findViewById<ProgressBar>(R.id.ck_progress) ?: return
        val progressText = view?.findViewById<TextView>(R.id.ck_progress_text) ?: return
        when (stage) {
            LocalBookFileImportHelper.ImportStage.STAGING -> {
                progressText.text = "正在导入"
                if (totalSize > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.max = 100
                    progressBar.progress = (copied * 100 / totalSize).toInt().coerceIn(0, 100)
                } else {
                    progressBar.isIndeterminate = true
                }
            }
            LocalBookFileImportHelper.ImportStage.BACKING_UP -> {
                progressText.text = "正在备份"
                progressBar.isIndeterminate = true
            }
            LocalBookFileImportHelper.ImportStage.WRITING -> {
                progressText.text = "正在写入"
                progressBar.isIndeterminate = true
            }
            LocalBookFileImportHelper.ImportStage.VERIFYING -> {
                progressText.text = "正在校验"
                progressBar.isIndeterminate = true
            }
            LocalBookFileImportHelper.ImportStage.ROLLING_BACK -> {
                progressText.text = "正在回滚"
                progressBar.isIndeterminate = true
            }
        }
    }

    private fun getImportFileName(fileDoc: FileDoc, uri: Uri): String {
        return fileDoc.name.ifBlank {
            uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
        }.ifBlank {
            throw NoStackTraceException("未获取到文件名")
        }
    }
}
