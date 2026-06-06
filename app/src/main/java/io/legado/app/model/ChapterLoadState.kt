package io.legado.app.model

class ChapterLoadState {

    enum class Status {
        Idle,
        Loading,
        Failed
    }

    private val states = hashMapOf<Int, Status>()

    @Synchronized
    fun tryStart(index: Int): Boolean {
        if (states[index] == Status.Loading) return false
        states[index] = Status.Loading
        return true
    }

    @Synchronized
    fun finish(index: Int) {
        states.remove(index)
    }

    @Synchronized
    fun fail(index: Int) {
        states[index] = Status.Failed
    }

    @Synchronized
    fun clear() {
        states.clear()
    }

    @Synchronized
    fun isLoading(index: Int): Boolean {
        return status(index) == Status.Loading
    }

    @Synchronized
    fun isFailed(index: Int): Boolean {
        return status(index) == Status.Failed
    }

    @Synchronized
    fun status(index: Int): Status {
        return states[index] ?: Status.Idle
    }
}
