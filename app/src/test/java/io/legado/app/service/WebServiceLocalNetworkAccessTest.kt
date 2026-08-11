package io.legado.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebServiceLocalNetworkAccessTest {

    @Test
    fun permissionIsNotRequiredBeforeAndroid17() {
        assertFalse(
            WebServiceLocalNetworkAccess.isPermissionRequired(
                sdkInt = WebServiceLocalNetworkAccess.ANDROID_17_API_LEVEL - 1,
                targetSdkVersion = WebServiceLocalNetworkAccess.ANDROID_17_API_LEVEL
            )
        )
    }

    @Test
    fun permissionIsNotRequiredForOlderTargetOnAndroid17() {
        assertFalse(
            WebServiceLocalNetworkAccess.isPermissionRequired(
                sdkInt = WebServiceLocalNetworkAccess.ANDROID_17_API_LEVEL,
                targetSdkVersion = WebServiceLocalNetworkAccess.ANDROID_17_API_LEVEL - 1
            )
        )
    }

    @Test
    fun permissionIsRequiredForTarget37OnAndroid17AndLater() {
        assertTrue(
            WebServiceLocalNetworkAccess.isPermissionRequired(
                sdkInt = WebServiceLocalNetworkAccess.ANDROID_17_API_LEVEL,
                targetSdkVersion = WebServiceLocalNetworkAccess.ANDROID_17_API_LEVEL
            )
        )
        assertTrue(
            WebServiceLocalNetworkAccess.isPermissionRequired(
                sdkInt = WebServiceLocalNetworkAccess.ANDROID_17_API_LEVEL + 1,
                targetSdkVersion = WebServiceLocalNetworkAccess.ANDROID_17_API_LEVEL
            )
        )
    }
}
