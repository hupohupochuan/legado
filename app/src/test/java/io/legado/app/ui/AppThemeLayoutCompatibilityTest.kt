package io.legado.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppThemeLayoutCompatibilityTest {

    @Test
    fun appCompatLayoutsDoNotInflateMaterialButtons() {
        val appDir = sequenceOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main/res/layout").isDirectory }
            ?: error("Unable to locate app/src/main/res/layout")
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
}
