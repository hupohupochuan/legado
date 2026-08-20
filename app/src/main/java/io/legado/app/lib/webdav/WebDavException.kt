package io.legado.app.lib.webdav

import java.net.UnknownHostException

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

internal fun Throwable.isWebDavDnsResolutionFailure(): Boolean {
    return generateSequence(this) { it.cause }.any { error ->
        error is UnknownHostException ||
            error.message?.contains("ERR_NAME_NOT_RESOLVED", ignoreCase = true) == true
    }
}
