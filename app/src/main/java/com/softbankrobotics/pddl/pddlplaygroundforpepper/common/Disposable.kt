package com.softbankrobotics.pddl.pddlplaygroundforpepper.common

import kotlinx.coroutines.*
import timber.log.Timber


/**
 * Something you can dispose of, like a subscription for example.
 */
interface Disposable {
    fun dispose()
}

/**
 * Create a disposable from a disposal function.
 */
fun disposableOf(doDispose: () -> Unit): Disposable {
    return object : Disposable {
        override fun dispose() {
            doDispose()
        }
    }
}

/**
 * Associates an object to keep alive with a disposable.
 */
class DisposableWrap<T>(val obj: T, private val doDispose: () -> Unit) : Disposable {
    override fun dispose() {
        doDispose()
    }
}

/**
 * A disposable container of disposables.
 */
class Disposables : Disposable {
    private val disposables = mutableListOf<Disposable>()

    /**
     * Add a disposable.
     */
    fun add(disposable: Disposable) {
        disposables.add(disposable)
    }

    /**
     * Add a disposable directly from a disposal function.
     */
    fun add(doDispose: () -> Unit) {
        disposables.add(disposableOf(doDispose))
    }

    /**
     * Dispose of every disposable.
     */
    override fun dispose() {
        disposables.forEach {
            try {
                it.dispose()
            } catch (t: Throwable) {
                Timber.e(t)
                throw t
            }
        }
        disposables.clear()
    }

    fun isEmpty(): Boolean { return disposables.isEmpty() }
    fun isNotEmpty(): Boolean { return disposables.isNotEmpty() }
}

// Suspend version!
/**
 * Something you can dispose of, like a subscription for example.
 */
interface DisposableSuspend {
    suspend fun dispose()
}

/**
 * Create a disposable from a disposal function.
 */
fun disposableSuspendOf(doDispose: suspend () -> Unit): DisposableSuspend {
    return object : DisposableSuspend {
        override suspend fun dispose() {
            doDispose()
        }
    }
}
// We do not add the overload taking a non-suspend function,
// because it would cause the lambda syntax to be ambiguous:
// val d = disposableSuspendOf { ... } // suspend or not?

/**
 * Create a disposable from a disposal function.
 */
fun disposableSuspendOf(disposable: Disposable): DisposableSuspend {
    return disposableSuspendOf { disposable.dispose() }
}

/**
 * A disposable container of disposables.
 */
class DisposablesSuspend : DisposableSuspend {
    private val disposables = mutableListOf<DisposableSuspend>()
    private val supervisorJob = SupervisorJob()

    /**
     * Add a disposable.
     */
    fun add(disposable: DisposableSuspend) = synchronized(this) {
        disposables.add(disposable)
    }

    /**
     * Add a disposable.
     */
    fun add(disposable: Disposable) = synchronized(this) {
        disposables.add(disposableSuspendOf(disposable))
    }

    /**
     * Add a disposable directly from a disposal function.
     */
    fun add(doDispose: suspend () -> Unit) = synchronized(this) {
        disposables.add(disposableSuspendOf(doDispose))
    }
    // We do not add the overload taking a non-suspend function,
    // because it would cause the lambda syntax to be ambiguous:
    // disposables.add { ... } // suspend or not?

    /**
     * Dispose of every disposable.
     */
    override suspend fun dispose() {
        val toDispose = synchronized(this) {
            val toDispose = disposables.toList() // makes a new list
            disposables.clear()
            toDispose
        }
        supervisorScope {
            toDispose.forEach {
                withContext(Dispatchers.Default) {
                    try {
                        it.dispose()
                    } catch (t: Throwable) {
                        Timber.e(t)
                    }
                }
            }
        }
    }
}

/**
 * Helper to add a disposable with a nicer syntax:
 * `complicatedCall { /* oh so complicated */ }.addTo(disposables)`
 * instead of `disposables.add(complicatedCall { /* oh so complicated */ })`
 */
fun DisposableSuspend.addTo(disposablesSuspend: DisposablesSuspend) {
    disposablesSuspend.add(this)
}

/**
 * Helper to add a disposable with a nicer syntax:
 * `complicatedCall { /* oh so complicated */ }.addTo(disposables)`
 * instead of `disposables.add(complicatedCall { /* oh so complicated */ })`
 */
fun Disposable.addTo(disposablesSuspend: DisposablesSuspend) {
    disposablesSuspend.add(this)
}

/**
 * Helper to add a disposable with a nicer syntax:
 * `complicatedCall { /* oh so complicated */ }.addTo(disposables)`
 * instead of `disposables.add(complicatedCall { /* oh so complicated */ })`
 */
fun Disposable.addTo(disposablesSuspend: Disposables) {
    disposablesSuspend.add(this)
}

/**
 * Shortcut for a common pattern to ensure disposables are properly called.
 */
suspend fun <R> withDisposablesSuspend(block: suspend (DisposablesSuspend) -> R): R {
    val disposables = DisposablesSuspend()
    try {
        return block(disposables)
    } finally {
        withContext(NonCancellable) {
            disposables.dispose()
        }
    }
}