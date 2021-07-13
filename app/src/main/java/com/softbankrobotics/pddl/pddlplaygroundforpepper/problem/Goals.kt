package com.softbankrobotics.pddl.pddlplaygroundforpepper.problem

import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.Human
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.feels
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.happy
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.was_greeted
import com.softbankrobotics.pddlplanning.Goal
import com.softbankrobotics.pddlplanning.forall
import com.softbankrobotics.pddlplanning.utils.Named
import com.softbankrobotics.pddlplanning.utils.indexOf

class NamedGoal(override val name: String, val goal: Goal) : Named

/*
 * Some of the goals may use universal operators (forall, exists), so define parameters locally.
 * By convention, we use the same parameter names for every goal, just for readability.
 * That does not change the behavior of these goals.
 */
private val h = Human("?h")

val humansAreGreeted = NamedGoal(
    "Humans are greeted",
    forall(h, was_greeted(h))
)

val humansAreHappy = NamedGoal(
    "Make humans happy",
    forall(h, feels(h, happy))
)

/**
 * The index of all declared goals.
 */
val goalsIndex = indexOf(
    humansAreGreeted,
    humansAreHappy
)