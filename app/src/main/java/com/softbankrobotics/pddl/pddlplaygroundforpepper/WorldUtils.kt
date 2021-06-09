package com.softbankrobotics.pddl.pddlplaygroundforpepper

import com.softbankrobotics.pddlplanning.Action
import com.softbankrobotics.pddlplanning.Expression
import com.softbankrobotics.pddlplanning.Instance
import timber.log.Timber
import java.util.regex.Pattern

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
