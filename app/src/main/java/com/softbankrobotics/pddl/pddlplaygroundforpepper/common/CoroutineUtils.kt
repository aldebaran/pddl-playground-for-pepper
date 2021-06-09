package com.softbankrobotics.pddl.pddlplaygroundforpepper.common

import kotlinx.coroutines.*
import timber.log.Timber

/**
 * A coroutine exception handler that forwards the issue to the logs.
 */
fun createLoggingCouroutineExceptionHandler(tag: String): CoroutineExceptionHandler {
    return CoroutineExceptionHandler { _, throwable ->
        Timber.tag(tag).w(throwable, "Uncaught exception")
    }
}

/**
 * Creates a couroutine scope configured to not favor specific threads,
 * and is therefore not recommended for blocking calls.
 * Exceptions are automatically caught.
 */
fun createAsyncCoroutineScope(tag: String): CoroutineScope {
    return CoroutineScope(Dispatchers.IO + SupervisorJob() + createLoggingCouroutineExceptionHandler(tag))
}

/**
 * Creates a couroutine scope configured to not favor specific threads,
 * and is therefore not recommended for blocking calls.
 * Exceptions are automatically caught.
 * The tag is automatically computed from the class name of the method enclosing the caller.
 */
fun createAsyncCoroutineScope(): CoroutineScope {
    val callerClassName = Thread.currentThread().stackTrace[1].className.substringAfterLast('.')
    return createAsyncCoroutineScope(callerClassName)
}

/**
 * Try a suspend call, and eat all exceptions.
 */
suspend fun callWithoutException(block: suspend () -> Unit) {
    try {
        block.invoke()
    } catch (e: CancellationException) {
        Timber.d("Call was cancelled")
    } catch (e: Throwable) {
        Timber.d(e, "Caught unimportant error")
    }
}
