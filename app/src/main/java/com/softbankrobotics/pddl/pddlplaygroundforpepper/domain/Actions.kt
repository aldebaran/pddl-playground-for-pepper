package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain

import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.conversation.QiChatbot
import com.aldebaran.qi.sdk.`object`.conversation.Topic
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions.GreetAction
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions.PlaceholderAction
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions.StartEngageAction
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions.StopEngageAction
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.MutableWorld
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.WorldData
import com.softbankrobotics.pddlplanning.utils.indexOf
import timber.log.Timber

/**
 * The index of all declared actions.
 */
val actionsIndex = indexOf(
    GreetAction,
    StartEngageAction,
    StopEngageAction
)

/**
 * Resolves the action factory by name, and creates it using the provided arguments.
 */
fun createActionFactory(
    actionDeclaration: ActionDeclaration,
    qiContext: QiContext,
    localChatbotProvider: () -> QiChatbot,
    localTopicsProvider: (String) -> List<Topic>,
    data: WorldData,
    world: MutableWorld
): (ActionDeclaration) -> PlannableAction {

    val createFallbackActionFactory = {
        Timber.w("no action factory for action \"${actionDeclaration.name}\", falling back on a fake action")
        val placeholderFactory = { it: ActionDeclaration -> PlaceholderAction(it.pddl, qiContext) }
        placeholderFactory
    }

    return when (actionDeclaration) {
        is GreetAction -> actionDeclaration.createActionFactory(
            localChatbotProvider,
            localTopicsProvider
        )

        // Durative actions.
        is StartEngageAction -> actionDeclaration.createActionFactory(qiContext, world, data)
        is StopEngageAction -> actionDeclaration.createActionFactory()

        else -> actionDeclaration.createAction ?: createFallbackActionFactory()
    }
}

/**
 * Assigns the action factory assigned to the given action declaration,
 * using the provided arguments.
 */
fun plusActionFactory(
    actionDeclaration: ActionDeclaration,
    qiContext: QiContext,
    localChatbotProvider: () -> QiChatbot,
    localTopicsProvider: (String) -> List<Topic>,
    data: WorldData,
    world: MutableWorld
): ActionDeclaration {

    val actionFactory =
        createActionFactory(
            actionDeclaration,
            qiContext,
            localChatbotProvider,
            localTopicsProvider,
            data,
            world
        )

    return actionDeclaration.withCreateAction(actionFactory)
}
