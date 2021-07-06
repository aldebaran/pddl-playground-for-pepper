package com.softbankrobotics.pddl.pddlplaygroundforpepper.common

import kotlin.reflect.KProperty

interface Readable<T> {
    /**
     * Get the current value.
     */
    fun get(): T

    /**
     * Compatibility with Kotlin properties.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return get()
    }
}

/**
 * "Property" is a big name for a readable entity.
 */
interface Property<T> : Readable<T>

interface Mutable<T> : Property<T> {

    /**
     * Sets the current value.
     * In single-threaded context, calling `get()` after `set(value)` must return `value`.
     */
    fun set(value: T)

    /**
     * Compatibility with Kotlin properties.
     */
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        set(value)
    }
}

interface ObservableProperty<T> : Property<T>, Observable<T> {
    /**
     * Subscribe to value changes and get current value.
     * Callback is called at subscription and when value changes.
     */
    fun subscribeAndGet(callback: (T) -> Unit): Disposable
}

interface MutableProperty<T> : Property<T>, Mutable<T>
interface MutableObservableProperty<T> : MutableProperty<T>, ObservableProperty<T>

/**
 * Local properties are usually interesting to provide observable data.
 */
abstract class ObservablePropertyBase<T> : ObservableProperty<T>, ObservableBase<T>() {
    override fun subscribeAndGet(callback: (T) -> Unit): Disposable {
        val disposable = subscribe(callback)
        callback(get())
        return disposable
    }
}

/**
 * For local properties, you usually need to be able to set it.
 */
abstract class MutableObservablePropertyBase<T> :
    ObservablePropertyBase<T>(),
    MutableObservableProperty<T> {

    /**
     * Sets the current value.
     * Implementations must not forget to call notifySubscribers(value) after value was updated.
     * In single-threaded context, calling `get()` after `set(value)` must return `value`.
     * There is no guarantee that all subscribers are called before `set(value)` returns.
     */
    abstract override fun set(value: T)
}

/**
 * A very basic property type, with storage embedded in it.
 */
class StoredProperty<T>(initialValue: T) : MutableObservablePropertyBase<T>() {
    private var stored = initialValue

    override fun get(): T = synchronized(this) {
        return stored
    }

    override fun set(value: T) {
        synchronized(this) { stored = value }
        notifySubscribers(value)
    }

    fun update(updater: (T) -> T) {
        val notify = synchronized(this) {
            val value = updater(stored)
            if (value != stored) {
                stored = value
                { notifySubscribers(value) }
            } else {
                {}
            }
        }
        notify()
    }
}

/**
 * A property type that supports custom accessors.
 */
class CustomStoredProperty<T>(private val getter: () -> T, private val setter: (T) -> Unit) :
    MutableObservablePropertyBase<T>() {
    private var stored
        get() = getter()
        set(value) = setter(value)

    override fun get(): T {
        return stored
    }

    override fun set(value: T) {
        stored = value
        notifySubscribers(value)
    }
}

/**
 * An observable based on another one and a transform.
 */
internal class TransformedObservableProperty<T, U>(
    property: ObservableProperty<T>,
    transform: (T) -> U
) : ObservablePropertyBase<U>() {

    private var value: U = transform(property.get())

    init {
        // At initialization we get the value and transform it twice.
        // This is sub-optimal, but it ensures the subscription is done properly.
        // If we removed the prior transformation, it would not build.
        property.subscribeAndGet {
            val nextValue = transform(it)
            if (value != nextValue) {
                value = nextValue
                notifySubscribers(value)
            }
        }
    }

    override fun get(): U {
        return value
    }
}

/**
 * Constructs an observable based on this one, applying the given transform.
 */
fun <T, U> ObservableProperty<T>.transformed(transform: (T) -> U): ObservableProperty<U> =
    TransformedObservableProperty(this, transform)

/**
 * Generic property composition.
 */
open class ComposedObservableProperty<T, U>(
    properties: List<ObservableProperty<U>>,
    compositor: (List<U>) -> T
) : ObservablePropertyBase<T>() {

    private val valuesIn = MutableList(properties.size) { properties[it].get() }
    private var valueOut = compositor(valuesIn)

    init {
        properties.forEachIndexed { index, property ->
            property.subscribe {
                valuesIn[index] = it
                val nextValueOut = compositor(valuesIn)
                if (valueOut != nextValueOut) {
                    valueOut = nextValueOut
                    notifySubscribers(valueOut)
                }
            }
        }
    }

    override fun get(): T {
        return valueOut
    }
}
