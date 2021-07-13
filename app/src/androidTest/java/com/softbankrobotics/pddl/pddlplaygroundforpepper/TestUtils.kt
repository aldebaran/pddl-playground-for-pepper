package com.softbankrobotics.pddl.pddlplaygroundforpepper

import android.content.Context
import android.content.Intent
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.Observable
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.ActionDeclaration
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.isTypeCompatible
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.WorldChange
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.WorldState
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.effectToWorldChange
import com.softbankrobotics.pddlplanning.*
import com.softbankrobotics.pddlplanning.utils.Index
import com.softbankrobotics.pddlplanning.utils.createDomain
import com.softbankrobotics.pddlplanning.utils.createProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import timber.log.Timber
import kotlin.reflect.full.companionObjectInstance

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
 * Asserts that an expression is true in a given world state.
 */
fun assert(expression: Expression, state: WorldState) =
    assertTrue(
        "Expression $expression not true in state:\n$state",
        evaluateExpression(expression, state)
    )

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

/**
 * Short for deducing the world state after the given task is performed.
 * @throws AssertionError if the precondition of the action is not met.
 */
fun WorldState.after(
    actionDeclaration: ActionDeclaration,
    vararg args: Instance,
    log: LogFunction? = null
): WorldState {
    val effect = checkPreconditionAndComputeEffect(this, actionDeclaration, args, log)
    return updated(effect)
}

private fun checkPreconditionAndComputeEffect(
    state: WorldState,
    actionDeclaration: ActionDeclaration,
    args: Array<out Instance>,
    log: LogFunction? = null
): WorldChange {
    val actionName = actionDeclaration.name

    // Check args
    val parameters = actionDeclaration.pddl.parameters
    if (parameters.size != args.size)
        throw RuntimeException("Wrong parameter arity for $actionName (expected ${parameters.size}, got ${args.size})")
    val parametersToValue = parameters.zip(args)
    parametersToValue.forEachIndexed { index, (key, value) ->
        if (!isTypeCompatible(value.type, key.type))
            throw RuntimeException("Incompatible type of value (${value.declaration()}) to specify parameter #$index (${key.declaration()})")
    }

    // Check precondition
    val precondition =
        applyParameters(actionDeclaration.pddl.precondition, parametersToValue.toMap())
    log?.let { it("Checking precondition of $actionName in world:\n$state") }
    val preconditionFulfilled =
        precondition.isEmpty() || evaluateExpression(precondition, state, log)
    if (!preconditionFulfilled)
        throw RuntimeException("Precondition of $actionName is not fulfilled in the world")

    // Check effect
    log?.let { it("Checking constants of $actionName in ${actionDeclaration.pddl.effect}...") }
    val newConstants = extractObjects(actionDeclaration.pddl.effect)
        .filter { !it.name.startsWith('?') }
        .filter { !state.objects.contains(it) }
    val addingNewConstants = WorldChange.ofAddedObjects(*newConstants.toTypedArray())

    // Compute effect
    val effect = effectToWorldChange(actionDeclaration.pddl, args)
    val effectWithConstants = addingNewConstants.mergedWith(effect)
    log?.let { it("Forecast effect of $actionName:\n$effectWithConstants") }
    return effectWithConstants
}