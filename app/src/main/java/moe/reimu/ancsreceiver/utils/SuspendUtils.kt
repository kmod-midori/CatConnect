package moe.reimu.ancsreceiver.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.coroutines.cancellation.CancellationException

suspend fun <R> retry(
    attempts: Int = 3,
    delay: Long = 1000L,
    log: ((e: Throwable) -> Unit)? = null,
    block: suspend () -> R
): R {
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (e: Throwable) {
            if (attempt == attempts - 1) throw e
            log?.invoke(e)
            if (delay > 0) {
                kotlinx.coroutines.delay(delay)
            }
        }
    }
    throw IllegalStateException("Should not reach here")
}
