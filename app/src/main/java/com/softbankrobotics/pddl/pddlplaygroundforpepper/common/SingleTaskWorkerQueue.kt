package com.softbankrobotics.pddl.pddlplaygroundforpepper.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

typealias WorkerTask = suspend () -> Unit

/**
 * Worker useful to recall only last planning request.
 */
class SingleTaskQueueWorker(private val scope: CoroutineScope = createAsyncCoroutineScope()) {
    private var running: WorkerTask? = null
    private var queued: WorkerTask? = null

    /**
     * Queues the given task.
     * If no task is running, immediately runs it.
     * If another task was already queued, the given task replaces it.
     */
    fun queue(task: WorkerTask): Unit = synchronized(this) {
        if (running != null) {
            queued = task
        } else {
            run(task)
        }
    }

    private fun run(task: WorkerTask): Unit = synchronized(this) {
        running = task
        scope.launch {
            try {
                task()
            } catch (t: Throwable) {
                Timber.e("Caught exception from worker: $t")
            }
            synchronized(this) {
                running = null
                val nextTask = queued
                queued = null
                nextTask?.let {
                    run(it)
                }
            }
        }
    }
}
