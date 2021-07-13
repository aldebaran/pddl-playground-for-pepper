package com.softbankrobotics.pddl.pddlplaygroundforpepper.problem

import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.*
import com.softbankrobotics.pddlplanning.Goal
import com.softbankrobotics.pddlplanning.forall
import com.softbankrobotics.pddlplanning.imply
import com.softbankrobotics.pddlplanning.utils.Named
import com.softbankrobotics.pddlplanning.utils.indexOf

class NamedGoal(override val name: String, val goal: Goal) : Named

/*
 * Some of the goals may use universal operators (forall, exists), so define parameters locally.
 * By convention, we use the same parameter names for every goal, just for readability.
 * That does not change the behavior of these goals.
 */
private val h = Human("?h")

val engageableHumansAreGreeted = NamedGoal(
    "Engageable humans are greeted",
    forall(h, imply(can_be_engaged(h), was_greeted(h)))
)

val engageableHumansAreHappy = NamedGoal(
    "Engageable humans are happy",
    forall(h, imply(can_be_engaged(h), feels(h, happy)))
)

/**
 * The index of all declared goals.
 */
val goalsIndex = indexOf(
    engageableHumansAreGreeted,
    engageableHumansAreHappy
)