package io.legado.app.help.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TtsEngineSelectionTest {

    @Test
    fun roundTripPreservesInstalledEngine() {
        val selection = TtsEngineSelection(
            title = "TTS Engine: Next-gen Kaldi",
            value = "com.k2fsa.sherpa.onnx.tts.engine",
        )

        assertEquals(selection, TtsEngineSelection.fromJson(selection.toJson()))
    }

    @Test
    fun readsLegacyPrettyPrintedSelectItemJson() {
        val legacyJson = """
            {
              "title": "Google 语音合成",
              "value": "com.google.android.tts"
            }
        """.trimIndent()

        assertEquals(
            TtsEngineSelection("Google 语音合成", "com.google.android.tts"),
            TtsEngineSelection.fromJson(legacyJson),
        )
    }

    @Test
    fun systemDefaultKeepsEmptyEngineName() {
        val selection = TtsEngineSelection("系统默认", "")

        assertEquals(selection, TtsEngineSelection.fromJson(selection.toJson()))
    }

    @Test
    fun httpIdsAndMalformedJsonAreNotSystemEngineSelections() {
        assertNull(TtsEngineSelection.fromJson(null))
        assertNull(TtsEngineSelection.fromJson("1"))
        assertNull(TtsEngineSelection.fromJson("{\"title\":\"missing value\"}"))
        assertNull(TtsEngineSelection.fromJson("not json"))
    }

    @Test
    fun ttsRuntimeAvoidsGenericGsonSelectionParsingAndRefreshesActualRows() {
        val appDir = sequenceOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main/java").isDirectory }
            ?: error("Unable to locate app/src/main/java")
        val runtimeFiles = listOf(
            "src/main/java/io/legado/app/ui/book/read/config/SpeakEngineDialog.kt",
            "src/main/java/io/legado/app/ui/book/read/config/ReadAloudConfigDialog.kt",
            "src/main/java/io/legado/app/service/TTSReadAloudService.kt",
        ).map { File(appDir, it).readText() }
        val speakEngineDialog = runtimeFiles.first()

        assertTrue(
            "System TTS selections must not depend on R8-sensitive SelectItem<T> reflection",
            runtimeFiles.none { it.contains("fromJsonObject<SelectItem") },
        )
        assertTrue(
            "Only real HTTP TTS rows should be refreshed after an engine selection",
            speakEngineDialog.contains(
                "notifyItemRangeChanged(adapter.getHeaderCount(), adapter.getActualItemCount())"
            ),
        )
        assertTrue(
            "TTS save actions must state their scope instead of using generic Book/General labels",
            speakEngineDialog.contains("R.string.tts_use_for_current_book") &&
                speakEngineDialog.contains("R.string.tts_use_globally") &&
                !speakEngineDialog.contains("tvFooterLeft.setText(R.string.book)") &&
                !speakEngineDialog.contains("tvOk.setText(R.string.general)"),
        )
    }
}
