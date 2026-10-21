package com.cragnet.wysawyg

/** Result and death arbitrate on one lock. A complete accepted result cannot be replaced by death.
 * The receive block reads the entire bounded regular file while holding the lock. */
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
