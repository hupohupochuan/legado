package io.legado.app.model

class ChapterLoadState {

    private val loadingIndexes = linkedSetOf<Int>()

    @Synchronized
    fun tryStart(index: Int): Boolean {
        if (loadingIndexes.contains(index)) return false
        loadingIndexes.add(index)
        return true
    }

    @Synchronized
    fun finish(index: Int) {
        loadingIndexes.remove(index)
    }

    @Synchronized
    fun clear() {
        loadingIndexes.clear()
    }

    @Synchronized
    fun isLoading(index: Int): Boolean {
        return loadingIndexes.contains(index)
    }
}
