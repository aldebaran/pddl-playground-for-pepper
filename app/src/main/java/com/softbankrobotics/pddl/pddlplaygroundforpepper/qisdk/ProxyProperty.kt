package com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk

import com.aldebaran.qi.AnyObject
import com.aldebaran.qi.serialization.QiSerializer
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.MutableObservableProperty
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.MutableObservablePropertyBase
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.ObservableProperty
import timber.log.Timber

/**
 * A property that is a proxy to another property held by a QiObject.
 */
class ProxyProperty<T>(
    private val qiObject: AnyObject,
    private val name: String,
    defaultValue: T,
    clazz: Class<T>
) : MutableObservablePropertyBase<T>() {

    private var cache: T = defaultValue

    init {
        qiObject.connect(name) {
            if (it.isEmpty())
                throw RuntimeException("no property value")
            val anyValue = it.first()
            val value = if (anyValue == null) {
                null as T
            } else {
                if (anyValue is AnyObject) {
                    qiObjectCast(anyValue, clazz)
                } else {
                    qiValueCast(anyValue, clazz)
                }
            }
            update(value)
        }.future.thenConsume {
            if (it.hasError()) {
                Timber.e("Failed to connect to property \"$name\": ${it.error}")
            }

            qiObject.getProperty(clazz, name).thenConsume { prop ->
                if (prop.hasError()) {
                    Timber.e("Failed to read property \"$name\": ${prop.error}")
                } else {
                    update(prop.value)
                }
            }
        }
    }

    private fun update(value: T) {
        synchronized(this) {
            cache = value
        }
        notifySubscribers(cache)
    }

    companion object {
        /**
         * Create a mutable proxy property.
         */
        inline fun <reified T> createMutable(
            qiObject: Any,
            name: String,
            defaultValue: T
        ): MutableObservableProperty<T> {
            val anyObj = if (qiObject is AnyObject) {
                qiObject
            } else {
                asAnyObject(qiObject)
            }
            return ProxyProperty(anyObj, name, defaultValue, T::class.java)
        }

        /**
         * Create a read-only proxy property.
         */
        inline fun <reified T> create(
            qiObject: Any,
            name: String,
            defaultValue: T
        ): ObservableProperty<T> {
            return createMutable(qiObject, name, defaultValue)
        }
    }

    /**
     * Reads the current value of the property, as stored in the local cache.
     * The cache is updated when the QiObject tells us the property has changed.
     */
    override fun get(): T = synchronized(this) {
        return cache
    }

    /**
     * Sets the value of the target property.
     * The cache may not be updated right away, it has to wait for target property to notify its change.
     */
    override fun set(value: T) {
        qiObject.setProperty(QiSerializer.getDefault(), name, value) // asynchronous
    }
}
