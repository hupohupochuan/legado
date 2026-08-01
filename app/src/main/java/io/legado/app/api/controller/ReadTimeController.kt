package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.model.WebReadTimeSession
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object ReadTimeController {

    internal data class SaveReadTimePayload(
        val bookName: String,
        val durationMs: Long,
    )

    internal fun parsePayload(postData: String?): SaveReadTimePayload? {
        if (postData == null) return null
        val payload = GSON.fromJsonObject<Map<String, *>>(postData).getOrNull() ?: return null
        val bookName = payload["bookName"] as? String ?: return null
        val durationMs = payload["durationMs"] as? Long ?: return null
        return SaveReadTimePayload(bookName, durationMs)
    }

    /**
     * Web 端章节阅读时长上报。
     * 前端已完成计时，后端只做校验和落盘；跨天按自然日拆分写入 readRecord。
     */
    suspend fun saveReadTime(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val payload = parsePayload(postData)
            ?: return returnData.setErrorMsg("格式不对")
        val bookName = payload.bookName
        if (bookName.isBlank()) return returnData.setErrorMsg("书名不能为空")
        if (!AppConfig.enableReadRecord) {
            return returnData.setErrorMsg("阅读记录未开启")
        }
        val durationMs = payload.durationMs
        if (durationMs < WebReadTimeSession.MIN_DURATION_MS) {
            return returnData.setErrorMsg("时长过短")
        }
        if (durationMs > WebReadTimeSession.MAX_DURATION_MS) {
            return returnData.setErrorMsg("时长过长")
        }
        val range = WebReadTimeSession.fromDuration(durationMs, System.currentTimeMillis())
            ?: return returnData.setErrorMsg("时长无效")
        if (!ReadTimeRecorder.recordWebSession(bookName, range.startSec, range.endSec)) {
            return returnData.setErrorMsg("阅读记录未开启")
        }
        return returnData.setData("")
    }
}
