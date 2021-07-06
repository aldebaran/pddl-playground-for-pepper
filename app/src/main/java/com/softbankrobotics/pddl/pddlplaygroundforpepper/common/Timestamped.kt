package com.softbankrobotics.pddl.pddlplaygroundforpepper.common

/**
 * Associates time information to a value.
 * Uses System.nanoTime() by default to get a monotonic-clock timestamp.
 */
data class Timestamped<T>(val value: T, val timestamp: Long) {
    constructor(value: T) : this(value, System.nanoTime())
    companion object {
        fun <T> of(value: T): Timestamped<T> {
            return Timestamped(value, System.nanoTime())
        }
    }
}
