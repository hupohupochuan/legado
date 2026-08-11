package io.legado.app.ui.config

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.negativeButton
import io.legado.app.lib.dialogs.onCancelled
import io.legado.app.lib.dialogs.positiveButton
import io.legado.app.lib.permission.Permissions
import io.legado.app.service.WebService
import io.legado.app.service.WebServiceLocalNetworkAccess

/**
 * Self-contained Android 17 local-network permission flow for WebService.
 *
 * This Activity deliberately does not use the process-global PermissionsCompat callback. Permission and
 * Settings results are restored by ActivityResultRegistry, so a configuration or process recreation cannot
 * accidentally start the service without rechecking the exact permission.
 */
class WebServicePermissionActivity : AppCompatActivity() {

    private var dialog: AlertDialog? = null
    private var flowStage = STAGE_RATIONALE

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (WebServiceLocalNetworkAccess.isGranted(this)) {
            startWebServiceAndFinish()
        } else {
            flowStage = STAGE_DENIED
            WebService.cancelPermissionRequest(this, showMessage = false)
            showDeniedDialog()
        }
    }

    private val appSettingsRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (WebServiceLocalNetworkAccess.isGranted(this)) {
            startWebServiceAndFinish()
        } else {
            cancelAndFinish(showMessage = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        flowStage = savedInstanceState?.getInt(STATE_FLOW_STAGE, STAGE_RATIONALE)
            ?: STAGE_RATIONALE
        if (flowStage != STAGE_DENIED) {
            WebService.markStartRequested()
        }
        onBackPressedDispatcher.addCallback(this) {
            cancelAndFinish(showMessage = true)
        }
        if (WebServiceLocalNetworkAccess.isGranted(this)) {
            startWebServiceAndFinish()
        } else {
            when (flowStage) {
                STAGE_RATIONALE -> showRationaleDialog()
                STAGE_DENIED -> showDeniedDialog()
                STAGE_PERMISSION, STAGE_SETTINGS -> Unit
                else -> {
                    flowStage = STAGE_RATIONALE
                    showRationaleDialog()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_FLOW_STAGE, flowStage)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun showRationaleDialog() {
        dialog?.dismiss()
        dialog = alert(
            getString(R.string.web_service),
            getString(R.string.web_service_local_network_permission_rationale)
        ) {
            positiveButton(R.string.dialog_confirm) {
                flowStage = STAGE_PERMISSION
                permissionRequest.launch(Permissions.ACCESS_LOCAL_NETWORK)
            }
            negativeButton(R.string.dialog_cancel) {
                cancelAndFinish(showMessage = true)
            }
            onCancelled {
                cancelAndFinish(showMessage = true)
            }
        }
    }

    private fun showDeniedDialog() {
        if (isFinishing || isDestroyed) return
        dialog?.dismiss()
        dialog = alert(
            getString(R.string.web_service),
            getString(R.string.web_service_local_network_permission_denied)
        ) {
            positiveButton(R.string.dialog_setting) {
                flowStage = STAGE_SETTINGS
                WebService.markStartRequested()
                try {
                    appSettingsRequest.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                } catch (e: Exception) {
                    AppLog.put(getString(R.string.tip_cannot_jump_setting_page), e, toast = true)
                    cancelAndFinish(showMessage = false)
                }
            }
            negativeButton(R.string.dialog_cancel) {
                cancelAndFinish(showMessage = true)
            }
            onCancelled {
                cancelAndFinish(showMessage = true)
            }
        }
    }

    private fun startWebServiceAndFinish() {
        WebService.startIfRequested(this)
        finish()
    }

    private fun cancelAndFinish(showMessage: Boolean) {
        WebService.cancelPermissionRequest(this, showMessage)
        finish()
    }

    companion object {
        private const val ACTION_REQUEST_PERMISSION =
            "io.legado.app.action.REQUEST_WEB_SERVICE_LOCAL_NETWORK_PERMISSION"
        private const val STATE_FLOW_STAGE = "flowStage"
        private const val STAGE_RATIONALE = 0
        private const val STAGE_PERMISSION = 1
        private const val STAGE_DENIED = 2
        private const val STAGE_SETTINGS = 3
        private const val REQUEST_CODE = 371122

        fun start(context: Context) {
            val intent = createIntent(context)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                WebService.cancelPermissionRequest(context, showMessage = false)
                AppLog.put(
                    context.getString(R.string.web_service_local_network_permission_denied),
                    e,
                    toast = true
                )
            }
        }

        fun pendingIntent(context: Context): PendingIntent {
            return PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                createIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createIntent(context: Context): Intent {
            return Intent(context, WebServicePermissionActivity::class.java).apply {
                action = ACTION_REQUEST_PERMISSION
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }
}
