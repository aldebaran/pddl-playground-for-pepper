package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain

import com.softbankrobotics.pddlplanning.Instance
import com.softbankrobotics.pddlplanning.Type
import com.softbankrobotics.pddlplanning.Typed
import com.softbankrobotics.pddlplanning.utils.indexOf
import java.util.*
import kotlin.math.pow
import kotlin.reflect.full.companionObjectInstance

/**
 * The index of all declared types.
 */
val typesIndex = indexOf(
    Predicate.type,
    PhysicalObject.type,
    AgentivePhysicalObject.type,
    Human.type,
    Emotion.type
)

/** Reflexive type to refer to PDDL predicates. */
class Predicate(name: String) : Instance(name) {
    override val type: Type = Companion.type

    companion object : Typed {
        override val type = Type("predicate", null) { Predicate(it) }
    }
}

/** Something that has a physical presence in the world. */
open class PhysicalObject(name: String) : Instance(name) {
    override val type: Type = Companion.type

    companion object : Typed {
        override val type = Type("physical_object", null) { PhysicalObject(it) }
    }
}

/** Something that can believe, desire, intend. */
open class AgentivePhysicalObject(name: String) : PhysicalObject(name) {
    override val type: Type = Companion.type

    companion object : Typed {
        override val type = Type("social_agent", PhysicalObject.type) { AgentivePhysicalObject(it) }
    }
}

/** A human as the robot can see them. */
class Human(name: String) : AgentivePhysicalObject(name) {
    override val type: Type = Companion.type

    companion object : Typed {
        override val type = Type("human", AgentivePhysicalObject.type) { Human(it) }
    }
}

/** An emotion that humans can feel. */
class Emotion(name: String) : Instance(name) {
    override val type: Type = Companion.type

    companion object : Typed {
        override val type = Type("emotion", Instance.type) { Emotion(it) }
    }
}


// TODO: the following functions should be moved to the PDDL Planning library, which should support extension.
/**
 * Generates a random PDDL object of any instance type.
 */
inline fun <reified T : Instance> generateInstance(): T {
    val strId = randomNumberString(5)
    // The code below seems risky, but in fact if T is derived from Instance,
    // it should definitely announce the related PDDL type in its companion.
    // The PDDL type object contains a factory that is supposed to return a T, seen as an Instance.
    val companion = T::class.companionObjectInstance!! as Typed
    val typeName = companion.type.name
    return companion.type.createInstance("${typeName}_$strId") as T
}

/**
 * Generates a random number string of N digits.
 */
fun randomNumberString(n: Int): String {
    val max = 10.0.pow(n.toDouble()) - 1
    val id = Random().nextInt(max.toInt())
    return id.toString().padStart(n, '0')
}

/**
 * Checks whether the given type is a sub-type of another type.
 */
fun isTypeCompatible(typeToCheck: Type?, against: Type?): Boolean {
    return if (against == null) {
        return true
    } else {
        if (typeToCheck == null) {
            false
        } else {
            if (typeToCheck == against) {
                true
            } else {
                isTypeCompatible(typeToCheck.parent, against)
            }
        }
    }
}
