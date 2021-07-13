package com.softbankrobotics.pddl.pddlplaygroundforpepper.problem

import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.MutableObservablePropertyBase
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.SetDelta
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.update
import com.softbankrobotics.pddl.pddlplaygroundforpepper.extractObjects
import com.softbankrobotics.pddlplanning.Fact
import com.softbankrobotics.pddlplanning.Instance
import com.softbankrobotics.pddlplanning.negationOf

/**
 * A snapshot of a PDDL world.
 * A distinct state of a world, with only PDDL contents.
 */
data class WorldState(
    val objects: Set<Instance>,
    val facts: Set<Fact>
) {
    /**
     * With this constructor, the objects are deduced from the facts.
     */
    constructor(vararg facts: Fact) :
            this(facts.flatMap { extractObjects(it) }.toSet(), facts.toSet())

    /**
     * With this constructor, the objects are deduced from the facts.
     */
    constructor(objects: Set<Instance>, vararg facts: Fact) :
            this(
                objects.plus(facts.flatMap { extractObjects(it) }),
                facts.toSet()
            )

    override fun toString(): String {
        return "objects:\n${objects.joinToString("\n  ", "  ", "\n") { it.declaration() }}" +
                "facts:\n${facts.joinToString("\n  ", "  ", "\n")}"
    }

    /**
     * Compute a new world given a previous world and a change.
     */
    fun updated(change: WorldChange): WorldState {

        val updatedObjects = objects
            .plus(change.objects.added)
            .minus(change.objects.removed)

        val updatedFactChange = applyDefaultRules(facts, change.facts)
        val updatedFacts = facts
            .plus(updatedFactChange.added)
            .minus(updatedFactChange.removed)

        return WorldState(updatedObjects, updatedFacts)
    }

    fun plus(vararg facts: Fact): WorldState {
        val involvedObjects = facts.flatMap { extractObjects(it) }
        val newObjects = involvedObjects - this.objects
        return updated(
            WorldChange(
                SetDelta.of(*newObjects.toTypedArray()),
                SetDelta.of(*facts)
            )
        )
    }

    fun minus(vararg facts: Fact): WorldState {
        return updated(WorldChange(SetDelta(), SetDelta(removed = facts.toSet())))
    }

    operator fun minus(otherState: WorldState): WorldChange = diff(otherState, this)
}

/**
 * Computes the difference between two state.
 */
fun diff(from: WorldState, to: WorldState): WorldChange {
    val addedObjects = to.objects - from.objects
    val removedObjects = from.objects - to.objects
    val addedFacts = to.facts - from.facts
    val removedFacts = from.facts - to.facts
    return WorldChange(
        SetDelta(addedObjects, removedObjects),
        SetDelta(addedFacts, removedFacts)
    )
}

data class WorldChange(
    val objects: SetDelta<Instance> = SetDelta(),
    val facts: SetDelta<Fact> = SetDelta()
) {
    /**
     * Combines two world changes.
     * The operation is not commutative:
     * the given world change is applied on top of this world change.
     */
    fun mergedWith(other: WorldChange): WorldChange {
        return WorldChange(
            objects.merge(other.objects),
            facts.merge(other.facts)
        )
    }

    fun isEmpty(): Boolean = objects.isEmpty() && facts.isEmpty()

    override fun toString(): String = string

    private val string: String by lazy {
        var str = if (objects.added.isNotEmpty())
            objects.added.joinToString(" +", "+", " ")
        else String()

        str += if (objects.removed.isNotEmpty())
            objects.removed.joinToString(" -", "-", " ")
        else String()

        str += if (facts.added.isNotEmpty())
            facts.added.joinToString(" +", "+", " ")
        else String()

        str += if (facts.removed.isNotEmpty())
            facts.removed.joinToString(" -", "-", " ")
        else String()
        str
    }

    companion object {

        /**
         * Shortcut to create a change with added facts exclusively.
         */
        fun of(vararg facts: Fact): WorldChange {
            return WorldChange(SetDelta(), SetDelta.of(*facts))
        }

        /**
         * Shortcut to create a world change from new objects (with no Qi Object associated).
         */
        fun ofAddedObjects(vararg objects: Instance): WorldChange {
            return WorldChange(
                objects = SetDelta.of(*objects)
            )
        }

        /**
         * Shortcut to create a world change from removed objects.
         */
        fun ofRemovedObjects(vararg objects: Instance): WorldChange {
            return WorldChange(objects = SetDelta(removed = setOf(*objects)))
        }
    }
}

/**
 * Sometimes it is convenient to defer world changes as functions.
 */
typealias WorldChangeFunction = (WorldState) -> WorldChange

/**
 * A mutable state of the world.
 */
class MutableWorld: MutableObservablePropertyBase<WorldState>() {

    /**
     * Current objects, mapped to AnyObject.
     */
    private val objects = mutableSetOf<Instance>()

    /**
     * Current facts.
     */
    private val facts = mutableSetOf<Fact>()

    /**
     * Updates the world according to a predetermined change.
     * Returns true if anything has effectively changed.
     */
    fun update(change: WorldChange): Boolean = synchronized(this) {

        if (change.isEmpty())
            return false

        // Add new objects first.
        val objectsAdded = objects.addAll(change.objects.added)
        var changed = objectsAdded

        // Update facts.
        if (!change.facts.isEmpty()) {
            var updatedChange = change.facts
            updatedChange = applyDefaultRules(facts, updatedChange)
            val factsChanged = facts.update(updatedChange)
            changed = changed || factsChanged
        }

        // Remove objects last.
        val objectsRemoved = objects.removeAll(change.objects.removed)
        changed = changed || objectsRemoved

        if (changed) {
            notifySubscribers(get())
        }
        return changed
    }

    /**
     * Applies the given change in an atomic operation.
     */
    fun updateFacts(change: SetDelta<Fact>) {
        update(WorldChange(SetDelta(), change))
    }

    /**
     * Adds a fact if not already present.
     */
    fun ensureFact(fact: Fact) {
        update(WorldChange(SetDelta(), SetDelta.of(fact)))
    }

    /**
     * Removes a fact if not already absent.
     */
    fun removeFact(fact: Fact) {
        update(WorldChange(SetDelta(), SetDelta(removed = setOf(fact))))
    }

    override fun get(): WorldState = synchronized(this) {
        // Using toSet() creates a copy of the array so that the result really is a constant
        WorldState(objects.toSet(), facts.toSet())
    }

    override fun set(value: WorldState): Unit = synchronized(this) {
        val change = diff(get(), value)
        update(change)
    }
}


/**
 * Applies all default rules to compute a new change.
 */
private fun applyDefaultRules(facts: Set<Fact>, change: SetDelta<Fact>): SetDelta<Fact> {
    var finalChange = change
    finalChange = minusContradictions(finalChange)
    return finalChange
}

/**
 * Computes existing facts that contradict newcoming facts,
 * and include them in the list of facts to remove.
 */
private fun minusContradictions(change: SetDelta<Fact>): SetDelta<Fact> {

    // If no new fact is added, no contradiction is possible,
    // given that the facts current facts do not contradict each other already.
    val newFacts = change.added
    if (newFacts.isEmpty())
        return change

    // Looking for contradictions of the new facts in the current facts.
    val negatedFacts = newFacts.map(::negationOf)
    // It is not clear if it is more optimal to substract the removed facts first, but the result is the same.
    val contradictoryFacts = newFacts.intersect(negatedFacts).toMutableSet()
    if (contradictoryFacts.isNotEmpty()) {
        throw RuntimeException(
            "Tried to insert facts negating each other: ${
                contradictoryFacts.joinToString("; ")
            }"
        )
    }
    return SetDelta(newFacts, change.removed.plus(negatedFacts))
}
