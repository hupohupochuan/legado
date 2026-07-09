package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.constant.AppLog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object ReaderLogController {

    private const val MAX_LOG_LENGTH = 500

    fun saveReaderLog(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val map = GSON.fromJsonObject<Map<String, *>>(postData).getOrNull()
            ?: return returnData.setErrorMsg("格式不对")
        val message = (map["message"] as? String)
            ?.take(MAX_LOG_LENGTH)
            ?: return returnData.setErrorMsg("message不能为空或类型错误")
        AppLog.putDebug("Web阅读性能 $message")
        return returnData.setData("ok")
    }
}
