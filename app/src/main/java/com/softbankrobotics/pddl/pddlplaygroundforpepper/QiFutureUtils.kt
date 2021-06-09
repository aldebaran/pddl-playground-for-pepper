package com.softbankrobotics.pddl.pddlplaygroundforpepper

import android.os.Handler
import android.os.Looper
import com.aldebaran.qi.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.Disposables
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.ExecutionException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Translates a future into a suspend function call.
 * @return the value of the future upon success.
 * @throws ExecutionException if the future finishes with an error.
 * @throws CancellationException if the future was interrupted with no result,
 * but the calling coroutine context is left untouched.
 * @throws IllegalStateException if the error is not running or is invalid.
 */
suspend fun <T> Future<T>.await(): T {
    return await { continuation, originalStackTrace, disposables ->
        val future = thenConsume { resumeFromFuture(continuation, it, originalStackTrace) }
        disposables.add { future.requestCancellationWithoutException() }
    }
}

/**
 * Await the future with a timeout, using coroutines.
 */
suspend fun <T> Future<T>.await(timeoutMillis: Long): T {
    return await { continuation, originalStackTrace, disposables ->

        // When timeout is reached, if the future is not finished, throw.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isDone)
                resumeWithAdaptedException(
                    continuation,
                    RuntimeException("timeout"),
                    originalStackTrace
                )
        }, timeoutMillis)

        // Otherwise, forward the future's result.
        val future = thenConsume { resumeFromFuture(continuation, it, originalStackTrace) }
        disposables.add { future.requestCancellationWithoutException() }
    }
}

/**
 * Base implementation of the await function on Qi Futures, allowing any suspension block.
 */
internal suspend fun <T> Future<T>.await(
    block: (CancellableContinuation<T>, Array<StackTraceElement>, Disposables) -> Unit
): T {
    if (isDone)
        return value

    val originalStackTrace =
        Thread.currentThread().stackTrace.toList().let { it.subList(4, it.size) }.toTypedArray()

    val disposables = Disposables()
    try {
        return suspendCancellableCoroutine {
            block(it, originalStackTrace, disposables)
        }
    } finally {
        // If the future has finished, requestCancellation will not do anything.
        // If the coroutine is cancelled, requestCancellation will attempt the cancellation of
        // the original future, and prevent the continuation to be resumed anymore, preventing
        // at the same time to produce exceptions that cannot not be caught (or almost).
        disposables.dispose()
    }
}

/**
 * Resume a continuation according to a future's state.
 * Future must not be running.
 */
internal fun <T> resumeFromFuture(
    continuation: Continuation<T>,
    future: Future<T>,
    originalStackTrace: Array<StackTraceElement>
) {
    fun resumeWithException(exception: Throwable) {
        resumeWithAdaptedException(continuation, exception, originalStackTrace)
    }
    when {
        !future.isDone -> resumeWithException(IllegalStateException("future is running"))
        future.isSuccess -> continuation.resume(future.value)
        future.isCancelled -> resumeWithException(CancellationException())
        future.hasError() -> resumeWithException(future.error)
        else -> resumeWithException(IllegalStateException("invalid future"))
    }
}

/**
 * Suspends until the end of a future, without forwarding return value or error.
 * @throws CancellationException if the calling coroutine context was cancelled.
 * [CancellationException] coming from the [Future] are not forwarded.
 */
suspend fun <T> Future<T>.join() {
    if (!currentCoroutineContext().isActive) {
        throw CancellationException()
    }
    try {
        await()
    } catch (c: CancellationException) {
        // logging that would not be informative
    } catch (t: Throwable) {
        Timber.d(t, "Caught error while joining future")
    }
}

/**
 * Suspends until the end of a future, without forwarding return value or error.
 * @throws CancellationException if the calling coroutine context was cancelled.
 * [CancellationException] coming from the [Future] are not forwarded.
 */
suspend fun <T> Future<T>.cancelAndJoin() {
    requestCancellationWithoutException()
    join()
}

/**
 * Helper around Future<T>.requestCancellation, which may throw otherwise.
 */
fun <T> Future<T>.requestCancellationWithoutException(log: (Throwable) -> Unit = {}) {
    try {
        requestCancellation()
    } catch (t: Throwable) {
        // Can throw if future has already finished, this is not an issue.
        log(t)
    }
}

/**
 * Same as setValue but we print an error instead of throwing IllegalStateException.
 */
fun <T> Promise<T>.setValueWithoutException(
    value: T,
    log: (IllegalStateException) -> Unit = { e -> Timber.w(e) }
) {
    try {
        setValue(value)
    } catch (e: IllegalStateException) {
        log(e)
    }
}

/**
 * Same as setValue but we print an error instead of throwing IllegalStateException.
 */
fun Promise<Void>.setValueWithoutException(
    log: (IllegalStateException) -> Unit = { e -> Timber.w(e) }
) {
    try {
        setValue(null)
    } catch (e: IllegalStateException) {
        log(e)
    }
}

/**
 * Same as setCancelled but we print an error instead of throwing IllegalStateException.
 */
fun <T> Promise<T>.setCancelledWithoutException(
    log: (IllegalStateException) -> Unit = { e -> Timber.w(e) }
) {
    try {
        setCancelled()
    } catch (e: IllegalStateException) {
        log(e)
    }
}

/**
 * Same as setCancelled but we print an error instead of throwing IllegalStateException.
 */
fun <T> Promise<T>.setErrorWithoutException(
    error: String,
    log: (IllegalStateException) -> Unit = { e -> Timber.w(e) }
) {
    try {
        this.setError(error)
    } catch (e: IllegalStateException) {
        log(e)
    }
}

/**
 * Forward an exception to a continuation, while adapting its stack trace.
 */
internal fun <T> resumeWithAdaptedException(
    continuation: Continuation<T>,
    exception: Throwable,
    stackTrace: Array<StackTraceElement>
) {
    exception.stackTrace = stackTrace
    continuation.resumeWithException(exception)
}

fun <T> propagateCancelAndWait(
    futureToPropagate: Future<T>,
    future: Future<Void>
): Future<Void> {
    if (futureToPropagate.isCancelled)
        future.requestCancellation()
    return future
}

/**
 * Runs the given suspend function in the given coroutine scope,
 * and translates its result into a Qi Future.
 * The future supports cancellation and errors,
 * by catching respectively CancellationException and Throwable.
 */
fun <T> toFuture(coroutineScope: CoroutineScope, suspendFunction: suspend () -> T): Future<T> {
    val promise = Promise<T>()
    val deferred = coroutineScope.async {
        try {
            val result = suspendFunction.invoke()
            promise.setValue(result)
        } catch (e: CancellationException) {
            promise.setCancelled()
        } catch (e: Throwable) {
            promise.setError(e.message)
        }
    }
    promise.setOnCancel { deferred.cancel() }
    return promise.future
}

/**
 * Runs the given suspend function in the given coroutine scope,
 * and translates its result into a Qi Future.
 * The future supports cancellation and errors,
 * by catching respectively CancellationException and Throwable.
 */
fun toFutureVoid(coroutineScope: CoroutineScope, suspendFunction: suspend () -> Unit): Future<Void> {
    val promise = Promise<Void>()
    val deferred = coroutineScope.async {
        try {
            suspendFunction.invoke()
            promise.setValue(null)
        } catch (e: CancellationException) {
            promise.setCancelled()
        } catch (e: Throwable) {
            promise.setError(e.message)
        }
    }
    promise.setOnCancel { deferred.cancel() }
    return promise.future
}

fun Future<Unit>.toVoid(): Future<Void> = this.andThenConsume { }
