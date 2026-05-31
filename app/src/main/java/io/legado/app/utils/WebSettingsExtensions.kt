package io.legado.app.utils

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.legado.app.help.config.AppConfig

/**
 * 设置是否夜间模式
 */
fun WebSettings.setDarkeningAllowed(allow: Boolean) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        kotlin.runCatching {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, allow)
        }.onFailure {
            it.printOnDebug()
        }
    }
}