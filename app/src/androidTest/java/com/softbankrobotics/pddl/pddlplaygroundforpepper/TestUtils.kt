package com.softbankrobotics.pddl.pddlplaygroundforpepper

import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.Observable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue

const val USUAL_FUTURE_TIMEOUT_MS_IN_TESTS = 500L

/**
 * Asserts that the first element equals the expected one.
 */
fun <T> assertBeginsWith(expected: T, actual: List<T>) {
    assertBeginsWith(listOf(expected), actual)
}

/**
 * Asserts that the first element matches the expected one.
 */
fun <T> assertBeginsWith(expected: T, actual: List<T>, matcher: (T, T) -> Boolean) {
    assertBeginsWith(listOf(expected), actual, matcher)
}

/**
 * Asserts that the first elements equal the expected ones.
 */
fun <T> assertBeginsWith(expected: List<T>, actual: List<T>) {
    assertBeginsWith(expected, actual) { lhs: T, rhs: T -> lhs == rhs }
}

/**
 * Asserts that the first elements match the expected ones.
 */
fun <T> assertBeginsWith(expected: List<T>, actual: List<T>, matcher: (T, T) -> Boolean) {
    assertTrue(
        "Expected start sequence is larger than actual total sequence.\nExpected: $expected\nActual: $actual",
        expected.size <= actual.size
    )
    expected.forEachIndexed { index, it ->
        assertTrue(
            "Mismatch at index $index: $it vs. ${actual[index]}.\nExpected: $expected\nActual: $actual",
            matcher(it, actual[index])
        )
    }
}

/**
 * Asserts that a single element is found in the actual collection.
 */
fun <T> assertIn(expected: T, actual: Collection<T>) {
    assertTrue(
        "Some expected elements are missing from the actual collection.\n" +
                "Expected: $expected\nActual: $actual",
        actual.contains(expected)
    )
}

/**
 * Asserts that all expected elements are found in the actual collection.
 */
fun <T> assertIn(expected: Collection<T>, actual: Collection<T>) {
    assertTrue(
        "Some expected elements are missing from the actual collection.\n" +
                "Expected: $expected\nActual: $actual",
        actual.containsAll(expected)
    )
}

/**
 * Asserts that a single element is absent in the actual collection.
 */
fun <T> assertNotIn(expected: T, actual: Collection<T>) {
    assertTrue(
        "Some expected elements are found in the actual collection.\n" +
                "Expected: $expected\nActual: $actual",
        !actual.contains(expected)
    )
}

/**
 * Asserts that none of the expected elements are found in the actual collection.
 */
fun <T> assertNotIn(expected: Collection<T>, actual: Collection<T>) {
    assertTrue(
        "Some expected elements are missing from the actual collection.\n" +
                "Expected: $expected\nActual: $actual",
        expected.none { it in actual }
    )
}

/**
 * Wait for a value that respects a certain predicate.
 */
fun <T> Observable<T>.waitUntilAsync(predicate: (T) -> Boolean): Deferred<T> {
    val completable = CompletableDeferred<T>()
    val subscription = subscribe {
        if (predicate(it))
            completable.complete(it)
    }
    completable.invokeOnCompletion { subscription.dispose() }
    return completable
}

/**
 * Waits for the next value.
 */
fun <T> Observable<T>.waitForNextValueAsync(): Deferred<T> {
    return waitUntilAsync { true }
}

/**
 * Waits for the next time the observable produces the given value.
 */
fun <T> Observable<T>.waitForValueAsync(value: T): Deferred<T> {
    return waitUntilAsync { it == value }
}

/**
 * Waits for any deferred value for a given time, in milliseconds.
 */
suspend fun <T> Deferred<T>.await(durationMs: Long): T {
    return withTimeout(durationMs) { await() }
}
