package io.legado.app

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the final, variant-specific Provider permission resolved by Android. */
@RunWith(AndroidJUnit4::class)
class ReaderProviderPermissionTest {
    @Test
    fun readerProviderRequiresVariantSignaturePermission() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${appContext.packageName}.readerProvider"
        val providerInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.resolveContentProvider(
                authority,
                PackageManager.ComponentInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.resolveContentProvider(authority, 0)
        }

        assertNotNull(providerInfo)
        val expectedPermission = "${appContext.packageName}.permission.READ_WRITE"
        assertEquals(expectedPermission, providerInfo!!.readPermission)
        assertEquals(expectedPermission, providerInfo.writePermission)
        assertTrue(providerInfo.exported)
        @Suppress("DEPRECATION")
        val permissionInfo = appContext.packageManager.getPermissionInfo(expectedPermission, 0)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, getBaseProtection(permissionInfo))
    }

    @Suppress("DEPRECATION")
    private fun getBaseProtection(permissionInfo: PermissionInfo): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissionInfo.protection
        } else {
            permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }
    }
}
