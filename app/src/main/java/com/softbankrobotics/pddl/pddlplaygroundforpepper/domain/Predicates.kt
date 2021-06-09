package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain

import com.softbankrobotics.pddlplanning.*
import com.softbankrobotics.pddlplanning.utils.Index
import com.softbankrobotics.pddlplanning.utils.indexOf

const val CAN_BE_ENGAGED_PREDICATE = "can_be_engaged"
const val ENGAGES_PREDICATE = "engages"
const val LOOKING_AT_PREDICATE = "looking_at"
const val NEXT_ACTION_PREDICATE = "next_action"
const val PREFERRED_TO_BE_ENGAGED = "preferred_to_be_engaged"
const val WAS_GOODBYED = "was_goodbyed"
const val IS_CLOSE_TO_PREDICATE = "is_close_to"
const val IS_DISENGAGING = "is_disengaging"

//
// Human/Robot interaction facts.
//
/** Declares that the human is interested. */
fun is_interested(h: Human) = Expression("is_interested", h)

/** Human shows signs of disengagement to the robot.
 * Note 1: signs of disengagement are only tracked for humans engaged by the robot
 * Note 2: if human is disengaging, we assume that he is not interested
 * Note 3: to not be disengaging anymore, human needs to re-approach the robot with
 * interest (seeking engagement state) */
fun is_disengaging(h: Human) = Expression(IS_DISENGAGING, h)

/** Declares that the human is suitable for engagement. */
fun can_be_engaged(h: Human) = Expression(CAN_BE_ENGAGED_PREDICATE, h)

/** Declares that the human is the preferred human to engage. */
fun preferred_to_be_engaged(h: Human) = Expression(PREFERRED_TO_BE_ENGAGED, h)

/** Human has been greeted by the robot. */
fun was_greeted(h: Human) = Expression("was_greeted", h)

/** The engager is currently engaged with the engagee. */
fun engages(engager: AgentivePhysicalObject, engagee: AgentivePhysicalObject) =
    Expression(ENGAGES_PREDICATE, engager, engagee)

val predicateIndex: Index<Expression> by lazy {
    val a1 = AgentivePhysicalObject("?a1")
    val a2 = AgentivePhysicalObject("?a2")
    val h = Human("?h")

    indexOf(
        { it.word },
        is_interested(h),
        is_disengaging(h),
        can_be_engaged(h),
        preferred_to_be_engaged(h),
        engages(a1, a2),
        was_greeted(h)
    )
}