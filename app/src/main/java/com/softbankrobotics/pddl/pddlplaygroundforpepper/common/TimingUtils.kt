package com.softbankrobotics.pddl.pddlplaygroundforpepper.common

import timber.log.Timber
import java.util.regex.Pattern

/**
 * Extracts the name of the caller's class.
 * By default, extracts the name of the caller of the caller (height = 2) of `extractCallerClass`.
 */
private fun extractCallerClass(height: Int = 2): String {
    val callerFrame = Throwable().stackTrace[height]
    val callerName = callerFrame.className.substringAfterLast('.')

    // Remove artifacts from anonymous classes
    val m = Pattern.compile("(\\$\\d+)+$").matcher(callerName)
    return if (m.find()) m.replaceAll("") else callerName
}

/**
 * Calls the given block and measures how long it took to be executed.
 * @return a pair containing the return value and the time, in milliseconds.
 */
fun <R> timed(block: () -> R): Pair<R, Long> {
    val startTime = System.currentTimeMillis()
    val result = block()
    val endTime = System.currentTimeMillis()
    return Pair(result, endTime - startTime)
}

/**
 * Times the given code block and log the timing using the given Timber tree,
 * only if it exceeds the provided limit.
 */
fun <R> logTimeExceeding(limitMs: Long, tree: Timber.Tree, block: () -> R): R {
    val (result, time) = timed(block)
    if (time > limitMs) {
        tree.w(Throwable(), "Block took especially long: $time ms (> $limitMs ms)")
    }
    return result
}

/**
 * Times the given code block and warn if it exceeds a certain time limit.
 */
fun <R> logTimeExceeding(warningLimitMs: Long, block: () -> R): R {
    return logTimeExceeding(warningLimitMs, Timber.tag(extractCallerClass()), block)
}

/**
 * Maximum time expected to process callbacks for world changes.
 */
const val WORLD_CALLBACKS_TIME_LIMIT_MS = 100L
