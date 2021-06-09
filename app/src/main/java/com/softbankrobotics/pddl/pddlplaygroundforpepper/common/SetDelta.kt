package com.softbankrobotics.pddl.pddlplaygroundforpepper.common

/**
 * Represents an atomic change in a set, including added and removed elements.
 * Added and removed cannot not have common elements.
 */
data class SetDelta<T>(val added: Set<T> = setOf(), val removed: Set<T> = setOf()) {
    init {
        val conflicts = added.filter { it in removed }
        if (conflicts.isNotEmpty())
            error("elements $conflicts are both added and removed")
    }

    fun isEmpty(): Boolean {
        return added.isEmpty() && removed.isEmpty()
    }

    /**
     * Merge two deltas as if they were applied consecutively.
     * The operation is not commutative.
     */
    fun merge(other: SetDelta<T>): SetDelta<T> {
        return SetDelta(
            added.minus(other.removed).plus(other.added),
            removed.minus(other.added).plus(other.removed)
        )
    }

    fun plus(element: T): SetDelta<T> {
        return SetDelta(added.plus(element), removed.minus(element))
    }

    fun plus(set: Set<T>): SetDelta<T> {
        return SetDelta(added.plus(set), removed.minus(set))
    }

    fun minus(element: T): SetDelta<T> {
        return SetDelta(added.minus(element), removed.plus(element))
    }

    fun minus(set: Set<T>): SetDelta<T> {
        return SetDelta(added.minus(set), removed.plus(set))
    }

    companion object {

        /** Shortcut to create a change with added elements exclusively. */
        fun <T> of(vararg elements: T): SetDelta<T> {
            return SetDelta(added = elements.toSet())
        }
    }
}

/** Update a set with a delta. */
fun <T> MutableSet<T>.update(change: SetDelta<T>): Boolean {
    val removed = this.removeAll(change.removed)
    val added = this.addAll(change.added)
    return removed || added
}

/** Update a set with a delta. */
fun <T> Set<T>.updated(change: SetDelta<T>): MutableSet<T> {
    val mutableThis = toMutableSet()
    mutableThis.removeAll(change.removed)
    mutableThis.addAll(change.added)
    return mutableThis
}
