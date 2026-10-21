package com.cragnet.wysawyg

internal class RequestCompletion<T>(private val requestId: Long, private val finish: (Result<T>) -> Unit) {
    private var finished = false
    @Synchronized fun result(id: Long, receive: () -> T): Boolean {
        if (id != requestId || finished) return false
        val result = runCatching(receive)
        finished = true
        finish(result)
        return true
    }
    @Synchronized fun failed(error: Exception) {
        if (finished) return
        finished = true
        finish(Result.failure(error))
    }
}
