package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain

import android.view.View
import com.aldebaran.qi.Future
import com.softbankrobotics.pddl.pddlplaygroundforpepper.await
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.DisposablesSuspend
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.Observable
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.Signal
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.withDisposablesSuspend
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.WorldChange
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.effectToWorldChange
import com.softbankrobotics.pddlplanning.Instance
import com.softbankrobotics.pddlplanning.Task
import com.softbankrobotics.pddlplanning.utils.Named
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.supervisorScope
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import com.softbankrobotics.pddlplanning.Action as PDDLAction

/*
 * Note that signatures of functions accepting varargs
 * are not compatible with those of functions without varargs.
 * To make sure we can accept both function types,
 * we need two implementations of the class.
 */

/**
 * Class to encapsulate actions that can be planned and executed
 */
abstract class PlannableAction(val pddl: PDDLAction, val view: View? = null) : Named {

    override val name: String = pddl.name

    /** Emit when the action is started. */
    protected val emittableStarted = Signal<Unit>()

    /** Be notified when the action is started. */
    val started = emittableStarted as Observable<Unit>

    /** Runs the action. */
    abstract suspend fun runSuspend(vararg args: Instance): WorldChange

    /**
     * Runs the action.
     * And add debug logs at the end.
     */
    suspend fun runWithDebugLogs(vararg args: Instance): WorldChange {
        return try {
            val change = runSuspend(*args)
            Timber.d("Action \"${name}\" is completed.")
            change
        } catch (e: CancellationException) {
            Timber.d("Action \"${name}\" is cancelled.")
            throw e
        } catch (e: Throwable) {
            Timber.d(e, "Action \"${name}\" ended with error: $e")
            throw e
        }
    }

    /**
     * Runs an action in an usual context with a frame layout to support the view.
     */
    suspend fun runWithOnStarted(
        args: Array<out Instance> = arrayOf(),
        onStarted: () -> Unit = {}
    ): WorldChange =
        runCustom(args, { sameArgs -> runSuspend(*sameArgs) }, onStarted)

    /**
     * Runs an action in an usual context with a frame layout to support the view.
     */
    suspend fun runCustom(
        args: Array<out Instance> = arrayOf(),
        runFunction: suspend (Array<out Instance>) -> WorldChange,
        onStarted: () -> Unit = {}
    ): WorldChange {
        val startedSubscription = started.subscribe { onStarted() }
        return try {
            runFunction(args)
        } finally {
            startedSubscription.dispose()
        }
    }

    /**
     * Runs an action in an usual context with a frame layout to support the view.
     */
    suspend fun runActionWithOnStartedAndDebugLogs(
        args: Array<out Instance> = arrayOf(),
        onStarted: () -> Unit = {}
    ): WorldChange =
        runCustom(args, { sameArgs -> runWithDebugLogs(*sameArgs) }, onStarted)


    companion object {

        /**
         * Shortcut to avoid sub-classing PlannableAction by yourself.
         * The result PlannableAction prevents concurrent runs by forbidding new runs until
         * previous run is done.
         */
        fun create(
            pddl: PDDLAction,
            view: View?,
            runFunction: (Signal<Unit>, Array<out Instance>) -> Future<WorldChange>
        ): PlannableAction {
            return object : PlannableAction(pddl, view) {
                var running = Future.cancelled<WorldChange>()
                override suspend fun runSuspend(vararg args: Instance): WorldChange =
                    synchronized(running) {
                        if (!running.isDone)
                            throw RuntimeException("action \"${pddl.name}\" is already running")
                        running = runFunction(emittableStarted, args)
                        running
                    }.await()

            }
        }

        /**
         * Shortcut to avoid sub-classing PlannableAction by yourself.
         * The result PlannableAction prevents concurrent runs by forbidding new runs until
         * previous run is done.
         */
        fun createSuspend(
            pddl: PDDLAction,
            view: View?,
            runFunction: suspend (CoroutineScope, Signal<Unit>, Array<out Instance>) -> WorldChange
        ): PlannableAction {
            return object : PlannableAction(pddl, view) {
                var running = AtomicBoolean(false)
                override suspend fun runSuspend(vararg args: Instance): WorldChange {
                    if (!running.compareAndSet(false, true)) {
                        throw RuntimeException("action \"${pddl.name}\" is already running")
                    }
                    return supervisorScope {
                        try {
                            runFunction(this, emittableStarted, args)
                        } finally {
                            running.set(false)
                        }
                    }
                }
            }
        }

        /**
         * Creates a PlannableAction from a suspend function accepting an extraneous DisposablesSuspend.
         * The suspend function is executed on a new IO coroutine context,
         * dedicated to the current run of the function.
         * When the function returns, the disposables are disposed automatically.
         * The result is automatically translated into a Future<WorldChange>.
         */
        fun createSuspendWithDisposables(
            pddl: PDDLAction,
            view: View?,
            runFunction: suspend (DisposablesSuspend, Signal<Unit>, Array<out Instance>) -> WorldChange
        ): PlannableAction =
            createSuspendWithDisposables(pddl, view) { _, disposables, emittableStarted, args ->
                runFunction(disposables, emittableStarted, args)
            }

        /**
         * Creates a PlannableAction from a suspend function accepting
         * an extraneous CoroutineScope and DisposablesSuspend.
         * The suspend function is executed on a new IO coroutine context,
         * dedicated to the current run of the function.
         * When the function returns, the disposables are disposed automatically.
         * The result is automatically translated into a Future<WorldChange>.
         */
        fun createSuspendWithDisposables(
            pddl: PDDLAction,
            view: View?,
            runFunction: suspend (CoroutineScope, DisposablesSuspend, Signal<Unit>, Array<out Instance>) -> WorldChange
        ): PlannableAction =
            createSuspend(pddl, view) { coroutineScope, emittableStarted, args ->
                withDisposablesSuspend { disposables ->
                    runFunction(coroutineScope, disposables, emittableStarted, args)
                }
            }

        /** Creates an action that only produces the effects it states in the PDDL. */
        fun createDeterministic(pddl: PDDLAction): PlannableAction {
            return object : PlannableAction(pddl) {
                override suspend fun runSuspend(vararg args: Instance): WorldChange {
                    emittableStarted.emit(Unit)
                    return effectToWorldChange(pddl, args)
                }
            }
        }
    }
}

/** Helper to define tasks with plannable action and instances. */
fun Task.Companion.create(action: PlannableAction, vararg instances: Instance): Task =
    create(action.pddl.name, *instances.map(Instance::name).toTypedArray())
