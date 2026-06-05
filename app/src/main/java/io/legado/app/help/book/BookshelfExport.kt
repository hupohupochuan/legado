package io.legado.app.help.book

import android.content.Context
import com.google.gson.stream.JsonWriter
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

object BookshelfExport {

    fun export(context: Context, books: List<Book>?): File {
        if (books.isNullOrEmpty()) {
            throw IllegalArgumentException("书籍不能为空")
        }
        val path = "${context.filesDir}/bookshelf.json"
        io.legado.app.utils.FileUtils.delete(path)
        val file = io.legado.app.utils.FileUtils.createFileWithReplace(path)
        FileOutputStream(file).use { out ->
            val writer = JsonWriter(OutputStreamWriter(out, "UTF-8"))
            writer.setIndent("  ")
            writer.beginArray()
            books.forEach {
                val bookMap = mapOf(
                    "bookUrl" to it.bookUrl,
                    "tocUrl" to it.tocUrl,
                    "origin" to it.origin,
                    "originName" to it.originName,
                    "name" to it.name,
                    "author" to it.author,
                    "kind" to it.kind,
                    "coverUrl" to it.coverUrl,
                    "customCoverUrl" to it.customCoverUrl,
                    "intro" to it.intro,
                    "customIntro" to it.customIntro,
                    "type" to it.type,
                    "wordCount" to it.wordCount
                )
                GSON.toJson(bookMap, bookMap::class.java, writer)
            }
            writer.endArray()
            writer.close()
        }
        return file
    }
}