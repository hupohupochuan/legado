package io.legado.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.lib.permission.Permissions
import io.legado.app.ui.config.WebServicePermissionActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebServiceLocalNetworkPermissionTest {

    @Test
    fun mergedManifestDeclaresAndroid17PermissionAndPrivateRequestActivity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
        }

        assertTrue(
            packageInfo.requestedPermissions.orEmpty()
                .contains(Permissions.ACCESS_LOCAL_NETWORK)
        )
        assertTrue(packageInfo.applicationInfo!!.targetSdkVersion >= Build.VERSION_CODES.CINNAMON_BUN)

        val component = ComponentName(context, WebServicePermissionActivity::class.java)
        val activityInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getActivityInfo(
                component,
                PackageManager.ComponentInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(component, 0)
        }
        assertFalse(activityInfo.exported)
    }

    @Test
    fun localNetworkPermissionIsDangerousOnAndroid17() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val permissionInfo = context.packageManager.getPermissionInfo(
            Permissions.ACCESS_LOCAL_NETWORK,
            0
        )

        assertEquals(PermissionInfo.PROTECTION_DANGEROUS, baseProtection(permissionInfo))
    }

    @Suppress("DEPRECATION")
    private fun baseProtection(permissionInfo: PermissionInfo): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissionInfo.protection
        } else {
            permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }
    }
}
