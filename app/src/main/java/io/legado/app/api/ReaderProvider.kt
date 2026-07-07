/*
 * Copyright (C) 2020 w568w
 */
package io.legado.app.api

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.google.gson.Gson
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import kotlinx.coroutines.runBlocking

/**
 * Export book data to other app.
 */
class ReaderProvider : ContentProvider() {
    private enum class RequestCode {
        SaveBookSource, SaveBookSources, DeleteBookSources, GetBookSource, GetBookSources,
        SaveRssSource, SaveRssSources, DeleteRssSources, GetRssSource, GetRssSources,
        SaveBook, GetBookshelf, RefreshToc, GetChapterList, GetBookContent, GetBookCover,
        SaveBookProgress
    }

    private val postBodyKey = "json"
    private val sMatcher by lazy {
        UriMatcher(UriMatcher.NO_MATCH).apply {
            "${context?.applicationInfo?.packageName}.readerProvider".also { authority ->
                addURI(authority, "bookSource/insert", RequestCode.SaveBookSource.ordinal)
                addURI(authority, "bookSources/insert", RequestCode.SaveBookSources.ordinal)
                addURI(authority, "bookSources/delete", RequestCode.DeleteBookSources.ordinal)
                addURI(authority, "bookSource/query", RequestCode.GetBookSource.ordinal)
                addURI(authority, "bookSources/query", RequestCode.GetBookSources.ordinal)
                addURI(authority, "rssSource/insert", RequestCode.SaveBookSource.ordinal)
                addURI(authority, "rssSources/insert", RequestCode.SaveBookSources.ordinal)
                addURI(authority, "rssSources/delete", RequestCode.DeleteBookSources.ordinal)
                addURI(authority, "rssSource/query", RequestCode.GetBookSource.ordinal)
                addURI(authority, "rssSources/query", RequestCode.GetBookSources.ordinal)
                addURI(authority, "book/insert", RequestCode.SaveBook.ordinal)
                addURI(authority, "books/query", RequestCode.GetBookshelf.ordinal)
                addURI(authority, "book/refreshToc/query", RequestCode.RefreshToc.ordinal)
                addURI(authority, "book/chapter/query", RequestCode.GetChapterList.ordinal)
                addURI(authority, "book/content/query", RequestCode.GetBookContent.ordinal)
                addURI(authority, "book/cover/query", RequestCode.GetBookCover.ordinal)
            }
        }
    }

    private fun requestCode(uri: Uri): RequestCode? {
        return RequestCode.entries.getOrNull(sMatcher.match(uri))
    }

    private fun queryParameters(uri: Uri): MutableMap<String, ArrayList<String>> {
        val map: MutableMap<String, ArrayList<String>> = HashMap()
        uri.getQueryParameter("url")?.let {
            map["url"] = arrayListOf(it)
        }
        uri.getQueryParameter("index")?.let {
            map["index"] = arrayListOf(it)
        }
        uri.getQueryParameter("path")?.let {
            map["path"] = arrayListOf(it)
        }
        return map
    }

    private fun errorData(error: Throwable): ReturnData {
        return ReturnData().setErrorMsg(error.localizedMessage ?: "请求处理失败")
    }

    override fun onCreate(): Boolean {
        context?.let { context ->
            ShortCuts.buildShortCuts(context)
        }
        return false
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        val requestCode = requestCode(uri) ?: return -1
        try {
            when (requestCode) {
                RequestCode.DeleteBookSources,
                RequestCode.DeleteRssSources -> BookSourceController.deleteSources(selection)
                else -> return 0
            }
        } catch (_: Exception) {
            return 0
        }
        return 0
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val requestCode = requestCode(uri) ?: return null
        runBlocking {
            try {
                when (requestCode) {
                    RequestCode.SaveBookSource -> values?.let {
                        BookSourceController.saveSource(values.getAsString(postBodyKey))
                    }

                    RequestCode.SaveBookSources -> values?.let {
                        BookSourceController.saveSources(values.getAsString(postBodyKey))
                    }

                    RequestCode.SaveBook -> values?.let {
                        BookController.saveBook(values.getAsString(postBodyKey))
                    }

                    RequestCode.SaveBookProgress -> values?.let {
                        BookController.saveBookProgress(values.getAsString(postBodyKey))
                    }

                    else -> Unit
                }
            } catch (_: Exception) {
                Unit
            }
        }
        return null
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val requestCode = requestCode(uri) ?: return null
        val map = try {
            queryParameters(uri)
        } catch (e: Exception) {
            return SimpleCursor(errorData(e))
        }
        return try {
            when (requestCode) {
                RequestCode.GetBookSource -> SimpleCursor(BookSourceController.getSource(map))
                RequestCode.GetBookSources -> SimpleCursor(BookSourceController.sources)
                RequestCode.GetBookshelf -> SimpleCursor(BookController.getBooks(mapOf()))
                RequestCode.GetBookContent -> SimpleCursor(BookController.getBookContent(map))
                RequestCode.RefreshToc -> SimpleCursor(BookController.refreshToc(map))
                RequestCode.GetChapterList -> SimpleCursor(BookController.getChapterList(map))
                RequestCode.GetBookCover -> SimpleCursor(BookController.getCover(map))
                else -> SimpleCursor(ReturnData().setErrorMsg("不支持的请求"))
            }
        } catch (e: Exception) {
            SimpleCursor(errorData(e))
        }
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ) = 0


    /**
     * Simple inner class to deliver json callback data.
     *
     * Only getString() makes sense.
     */
    private class SimpleCursor(data: ReturnData?) : MatrixCursor(arrayOf("result"), 1) {

        private val mData: String = Gson().toJson(data)

        init {
            addRow(arrayOf(mData))
        }

    }
}
