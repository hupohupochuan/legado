package io.legado.app.utils

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import splitties.init.appCtx

fun <T> ActivityResultLauncher<T?>.launch() {
    launch(null)
}

class SelectImageContract : ActivityResultContract<Int?, SelectImageContract.Result>() {

    private val delegate = ActivityResultContracts.PickVisualMedia()
    private var requestCode: Int? = null
    private var useFallback = false

    override fun createIntent(context: Context, input: Int?): Intent {
        requestCode = input
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        val photoPickerIntent = delegate.createIntent(context, request)
        if (photoPickerIntent.resolveActivity(appCtx.packageManager) != null) {
            useFallback = false
            return photoPickerIntent
        }
        useFallback = true
        return Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result {
        val uri = if (useFallback) {
            if (resultCode == RESULT_OK) intent?.data else null
        } else {
            delegate.parseResult(resultCode, intent)
        }
        return Result(requestCode, uri)
    }

    data class Result(
        val requestCode: Int?,
        val uri: Uri? = null
    )

}

class StartActivityContract(private val cls: Class<*>) :
    ActivityResultContract<(Intent.() -> Unit)?, ActivityResult>() {

    override fun createIntent(context: Context, input: (Intent.() -> Unit)?): Intent {
        val intent = Intent(context, cls)
        input?.let {
            intent.apply(input)
        }
        return intent
    }

    override fun parseResult(
        resultCode: Int, intent: Intent?
    ): ActivityResult {
        return ActivityResult(resultCode, intent)
    }

}
