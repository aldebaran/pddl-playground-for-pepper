package com.softbankrobotics.pddl.pddlplaygroundforpepper.common

/**
 * A generic interface for the notion of "Observable".
 */
interface Observable<T> {
    /**
     * Subscribe to value changes.
     * Callback is called when value changes.
     */
    fun subscribe(callback: (T) -> Unit): Disposable

    /**
     * Remove a subscription.
     */
    fun unsubscribe(callback: (T) -> Unit)
}

open class ObservableBase<T> : Observable<T> {

    /**
     * The subscribers to notify at each event.
     */
    private var subscribers = mutableListOf<(T) -> Unit>()

    /**
     * The queued notifications.
     */
    private val notificationQueue = ArrayDeque<T>()

    override fun subscribe(callback: (T) -> Unit): Disposable {
        synchronized(this) { subscribers.add(callback) }
        return disposableOf { unsubscribe(callback) }
    }

    override fun unsubscribe(callback: (T) -> Unit) {
        synchronized(this) { subscribers.remove(callback) }
    }

    /**
     * To be called by implementation when an event occurs, such as a value change,
     * outside the `synchronized(this)` scope.
     * In order to preserve the order of events,
     * recursive notifications are prevented.
     * It is done by queuing recursive notifications.
     * When this happens, it is the top call that ends up notifying for the newer events,
     * whereas the sub-calls return without having notified the subscribers.
     */
    protected fun notifySubscribers(value: T) {
        synchronized(this) {
            val isNotifying = notificationQueue.isNotEmpty()
            notificationQueue.addLast(value)
            if (isNotifying) // Notification will be processed by the parent call
                return
            // Else, we will process the notifications here.
        }

        var keepOnNotifying = true
        while (keepOnNotifying) {
            val notify = synchronized(this) {
                val notificationValue = notificationQueue.first()
                val subscribersCopy = subscribers.toList();
                { subscribersCopy.forEach { it(notificationValue) } }
            }
            notify() // subscribers are called outside synchronization scope
            synchronized(this) {
                notificationQueue.removeFirst()
                if (notificationQueue.isEmpty()) {
                    keepOnNotifying = false
                }
            }
        }
    }
}

class Signal<T> : ObservableBase<T>() {

    /**
     * Call this to notify subscribers.
     */
    fun emit(value: T) {
        notifySubscribers(value)
    }
}
