package io.legado.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppThemeLayoutCompatibilityTest {

    private val appDir: File by lazy {
        sequenceOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main/res/layout").isDirectory }
            ?: error("Unable to locate app/src/main/res/layout")
    }

    @Test
    fun appCompatLayoutsDoNotInflateMaterialButtons() {
        val layoutDir = File(appDir, "src/main/res/layout")
        val materialButton = Regex(
            """<\s*(com\.google\.android\.material\.button\.MaterialButton)\b"""
        )
        val offenders = layoutDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .flatMap { file ->
                materialButton.findAll(file.readText()).map { match ->
                    "${file.relativeTo(appDir).path}: ${match.groupValues[1]}"
                }
            }
            .toList()

        assertTrue(
            "Base.AppTheme inherits Theme.AppCompat; MaterialButton crashes during inflation. " +
                "Use AppCompat/project widgets or migrate the app theme first:\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    @Test
    fun readRecordToggleLivesInPageHeaderNotOverflowMenu() {
        val menu = File(appDir, "src/main/res/menu/book_read_record.xml").readText()
        val header = File(appDir, "src/main/res/layout/view_read_record_header.xml").readText()
        val switchCard = Regex(
            """<androidx\.cardview\.widget\.CardView\b[^>]*android:id="@\+id/cv_enable_record"[^>]*>"""
        ).find(header)?.value

        assertFalse(
            "The reading-record toggle belongs in the page header, not the overflow menu",
            menu.contains("menu_enable_record") || menu.contains("@string/enable_record")
        )
        assertTrue(
            "The reading-record switch card must always be visible",
            switchCard != null && !switchCard.contains("android:visibility=\"gone\"")
        )
        assertTrue(
            "The reading-record page must expose a two-way AppCompat switch",
            header.contains("<androidx.appcompat.widget.SwitchCompat") &&
                header.contains("@+id/sw_enable_record") &&
                !header.contains("@+id/btn_enable_record")
        )
    }
}
