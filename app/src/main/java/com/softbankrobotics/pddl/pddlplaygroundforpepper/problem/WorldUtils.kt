package com.softbankrobotics.pddl.pddlplaygroundforpepper.problem

import com.softbankrobotics.pddl.pddlplaygroundforpepper.applyParameters
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.SetDelta
import com.softbankrobotics.pddl.pddlplaygroundforpepper.effectToFactDelta
import com.softbankrobotics.pddl.pddlplaygroundforpepper.expressionToFactDelta
import com.softbankrobotics.pddlplanning.Action
import com.softbankrobotics.pddlplanning.Expression
import com.softbankrobotics.pddlplanning.Instance

/**
 * Computes the world change corresponding to the declared effect of the given action.
 */
fun effectToWorldChange(action: Action, args: Array<out Instance>): WorldChange {
    return WorldChange(SetDelta(), effectToFactDelta(action, args))
}

/**
 * Computes the world change computed by applying the given parameters to the given effect expression.
 */
fun effectToWorldChange(effect: Expression, parameters: Map<Instance, Instance>): WorldChange {
    val appliedEffect = applyParameters(effect, parameters)
    return WorldChange(SetDelta(), expressionToFactDelta(appliedEffect))
}
