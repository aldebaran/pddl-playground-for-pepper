package com.softbankrobotics.pddl.pddlplaygroundforpepper

import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.SetDelta
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.WorldState
import com.softbankrobotics.pddlplanning.utils.evaluateExpression
import com.softbankrobotics.pddlplanning.*

/**
 * Thrown when a PDDL object with no name is encountered.
 */
class UnnamedPDDLObjectException(message: String) : Exception(message)

/**
 * Parse instance from parameter expression.
 * Useful for `forall` and `exists` statements.
 * @throws UnnamedPDDLObjectException if the object declaration is lacking the name of the object.
 */
fun parseObjectDeclaration(objectDeclaration: String): Instance {
    val matches = Regex("(\\??\\w+)?\\s?+-\\s?+(\\w+)").matchEntire(objectDeclaration)
        ?: throw IllegalArgumentException("\"$objectDeclaration\" is not a valid object declaration")
    val (name, type) = matches.groupValues.subList(1, matches.groupValues.size)
    if (name.isEmpty())
        throw UnnamedPDDLObjectException("encountered unnamed object of type $type")
    return Instance.create(name, type)
}

/**
 * Parse instance from parameter expression.
 * Useful for `forall` and `exists` statements.
 */
private fun parseParameter(expression: Expression): Instance =
    parseObjectDeclaration(expression.word)

/**
 * Extract every object involved in the expression.
 */
fun extractObjects(expression: Expression): Set<Instance> {
    return when (expression.word) {
        not_operator_name ->
            extractObjects(expression.args[0])
        and_operator_name, or_operator_name ->
            expression.args.flatMap { extractObjects(it) }.toSet()
        imply_operator_name, when_operator_name ->
            extractObjects(expression.args[1])
        forall_operator_name, exists_operator_name -> {
            val parameter = parseParameter(expression.args[0])
            extractObjects(expression.args[1]).minus(parameter)
        }
        assignment_operator_name, increase_operator_name ->
            setOf()
        else -> expression.args.map {
            try {
                it as Instance
            } catch (e: ClassCastException) {
                throw IllegalStateException("Expression $it is not an instance")
            }
        }.toSet()
    }
}

/**
 * Combine expressions with the given operator.
 * If no expression is provided, returns an empty expression.
 * If only one expression is provided, the operator is omitted.
 */
fun combineExpression(
    operator: (Array<out Expression>) -> Expression,
    vararg args: Expression
): Expression {

    return when (args.size) {
        0 -> Expression()
        1 -> args[0]
        else -> operator(args)
    }
}

fun combineExpression(
    operator: (Array<out Expression>) -> Expression,
    args: Collection<Expression>
): Expression {
    return combineExpression(operator, *args.toTypedArray())
}

/**
 * Reduce the depth of and and or trees.
 * Cancel out double negations.
 * Distribute negations down.
 * Drops empty expressions, but if it is the root one.
 */
fun simplifyExpression(expression: Expression): Expression {

    return when (expression.word) {

        and_operator_name -> {
            val flattened = expression.args.flatMap {
                val simplified = simplifyExpression(it)
                when {
                    simplified.word == and_operator_name -> simplified.args.toSet()
                    simplified.isEmpty() -> setOf()
                    else -> setOf(simplified)
                }
            }
            combineExpression(::and, *flattened.distinct().toTypedArray())
        }

        or_operator_name -> {
            val flattened = expression.args.flatMap {
                val simplified = simplifyExpression(it)
                when {
                    simplified.word == and_operator_name -> simplified.args.toSet()
                    simplified.isEmpty() -> setOf()
                    else -> setOf(simplified)
                }
            }
            combineExpression(::or, *flattened.distinct().toTypedArray())
        }

        not_operator_name -> {
            val firstArg = expression.args[0]
            if (firstArg.word == not_operator_name) {
                simplifyExpression(firstArg.args[0])
            } else {
                expression
            }
        }

        else -> expression
    }
}

/**
 * Apply parameter values.
 */
fun applyParameters(expression: Expression, parameters: Map<Instance, Instance>): Expression {
    val updatedArgs = when (expression.word) {
        not_operator_name, and_operator_name, or_operator_name, imply_operator_name, when_operator_name ->
            expression.args.map { applyParameters(it, parameters) }
        forall_operator_name, exists_operator_name ->
            listOf(expression.args[0], applyParameters(expression.args[1], parameters))
        else -> expression.args.map { parameters[it] ?: it }
    }
    return Expression(expression.word, *updatedArgs.toTypedArray())
}

/**
 * Convert an expression into facts.
 * Can be used to convert action effects to facts.
 */
fun expressionToFacts(expression: Expression): Set<Fact> {

    return when (expression.word) {
        and_operator_name -> {
            expression.args.flatMap { expressionToFacts(it) }.toSet()
        }

        not_operator_name -> {
            expressionToFacts(expression.args[0]).map(::negationOf).map(::simplifyExpression)
                .toSet()
        }

        forall_operator_name, exists_operator_name, imply_operator_name, when_operator_name, or_operator_name ->
            throw RuntimeException("expression to facts does not support operator ${expression.word}")

        increase_operator_name, "" -> setOf()

        else -> setOf(expression)
    }
}

/** Split positive and negative facts. */
fun splitFactsByPolarity(facts: Set<Fact>): SetDelta<Fact> {
    val (positiveFacts, negativeFacts) =
        facts.partition { it.word != not_operator_name }
    return SetDelta(positiveFacts.toSet(), negativeFacts.map(::negationOf).toSet())
}

/**
 * Convert an expression into a fact delta.
 * Can be used to convert action effects to facts.
 */
fun expressionToFactDelta(expression: Expression): SetDelta<Fact> =
    splitFactsByPolarity(expressionToFacts(expression))

/**
 * Applies the given arguments to the action effect.
 */
fun appliedEffect(action: Action, args: Array<out Instance>): Expression {
    return applyParameters(action.effect, action.parameters.zip(args).toMap())
}

/**
 * Translates the effect of the given action into the facts that it is supposed to produce,
 * given the values for each parameter.
 */
fun effectToFacts(action: Action, args: Array<out Instance>): Set<Fact> {
    return expressionToFacts(appliedEffect(action, args))
}

/**
 * Translates the effect of the given action into the facts that it is supposed to produce,
 * given the values for each parameter.
 */
fun effectToFactDelta(action: Action, args: Array<out Instance>): SetDelta<Fact> {
    return expressionToFactDelta(appliedEffect(action, args))
}

/**
 * Evaluate an expression given a state.
 */
fun evaluateExpression(expression: Expression, state: WorldState): Boolean =
    evaluateExpression(expression, state.objects, state.facts)
