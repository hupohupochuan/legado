package io.legado.app.model

import android.content.Context
import android.content.Intent
import io.legado.app.constant.IntentAction
import io.legado.app.service.DownloadService
import io.legado.app.utils.startForegroundServiceCompat

object Download {


    fun start(context: Context, url: String, fileName: String) {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = IntentAction.start
            putExtra("url", url)
            putExtra("fileName", fileName)
        }
        context.startForegroundServiceCompat(intent)
    }

}