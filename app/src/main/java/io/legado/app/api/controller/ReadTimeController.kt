package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadTimeRecorder
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object ReadTimeController {

    private data class SaveReadTimePayload(
        val bookName: String,
        val durationMs: Long,
        val timestamp: Long?,
    )

    /**
     * Web 端章节阅读时长上报。
     * 前端已完成计时，后端只做校验和落盘；跨天按自然日拆分写入 readRecord。
     */
    fun saveReadTime(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val payload = GSON.fromJsonObject<SaveReadTimePayload>(postData).getOrNull()
            ?: return returnData.setErrorMsg("格式不对")
        val bookName = payload.bookName
        if (bookName.isBlank()) return returnData.setErrorMsg("书名不能为空")
        if (!AppConfig.enableReadRecord) {
            return returnData.setErrorMsg("阅读记录未开启")
        }
        val durationMs = payload.durationMs
        if (durationMs < 5000) return returnData.setErrorMsg("时长过短")

        val endAtMs = payload.timestamp ?: System.currentTimeMillis()
        val endSec = endAtMs / 1000
        val startSec = endSec - durationMs / 1000
        if (endSec - startSec < 5) return returnData.setErrorMsg("时长过短")

        ReadTimeRecorder.recordWebSession(bookName, startSec, endSec)
        return returnData.setData("")
    }
}
