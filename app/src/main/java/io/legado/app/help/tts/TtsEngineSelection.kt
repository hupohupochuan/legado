package io.legado.app.help.tts

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Persisted selection for an Android system TTS engine.
 *
 * The JSON field names intentionally remain `title` and `value` for compatibility with
 * configurations written by the former `SelectItem<String>` representation. Encoding and
 * decoding use Gson's JSON tree API instead of reflective generic deserialization so Release/R8
 * cannot erase the value type to `Serializable`.
 */
data class TtsEngineSelection(
    val title: String,
    val value: String,
) {

    fun toJson(): String = JsonObject().apply {
        addProperty(TITLE, title)
        addProperty(VALUE, value)
    }.toString()

    companion object {
        private const val TITLE = "title"
        private const val VALUE = "value"

        fun fromJson(json: String?): TtsEngineSelection? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val jsonObject = JsonParser.parseString(json).asJsonObject
                val title = jsonObject.get(TITLE)?.asString
                    ?: error("TTS engine title is missing")
                val value = jsonObject.get(VALUE)?.asString
                    ?: error("TTS engine value is missing")
                TtsEngineSelection(title, value)
            }.getOrNull()
        }
    }
}
