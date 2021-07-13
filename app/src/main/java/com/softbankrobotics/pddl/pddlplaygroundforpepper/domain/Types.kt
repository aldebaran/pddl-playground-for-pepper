package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain

import com.softbankrobotics.pddlplanning.Instance
import com.softbankrobotics.pddlplanning.Type
import com.softbankrobotics.pddlplanning.Typed
import com.softbankrobotics.pddlplanning.utils.indexOf

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

/**
 * The index of all declared types.
 */
val typesIndex = indexOf(
    Predicate.type, PhysicalObject.type, AgentivePhysicalObject.type, Human.type,
    Emotion.type
)