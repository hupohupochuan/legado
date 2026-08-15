package io.legado.app.lib.webdav

open class WebDavException(msg: String) : Exception(msg) {

    override fun fillInStackTrace(): Throwable {
        return this
    }

}

class ObjectNotFoundException(msg: String) : WebDavException(msg)

internal fun webDavStatusException(safePath: String, statusCode: Int): WebDavException? {
    return when (statusCode) {
        404 -> ObjectNotFoundException("$safePath doesn't exist. code:$statusCode")
        else -> null
    }
}
