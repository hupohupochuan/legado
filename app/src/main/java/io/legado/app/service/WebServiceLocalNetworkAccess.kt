package io.legado.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.legado.app.lib.permission.Permissions

/** Android 17 local-network permission boundary used by every WebService entry point. */
internal object WebServiceLocalNetworkAccess {

    const val ANDROID_17_API_LEVEL = Build.VERSION_CODES.CINNAMON_BUN

    fun isPermissionRequired(sdkInt: Int, targetSdkVersion: Int): Boolean {
        return sdkInt >= ANDROID_17_API_LEVEL && targetSdkVersion >= ANDROID_17_API_LEVEL
    }

    fun isGranted(context: Context): Boolean {
        if (!isPermissionRequired(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Permissions.ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED
    }
}
