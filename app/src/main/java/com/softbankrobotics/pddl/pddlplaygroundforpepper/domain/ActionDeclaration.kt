package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain

import android.content.Context
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.conversation.Topic
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.createTopicFromResource
import com.softbankrobotics.pddlplanning.Action
import com.softbankrobotics.pddlplanning.Instance
import com.softbankrobotics.pddlplanning.Task
import com.softbankrobotics.pddlplanning.utils.Named
import java.util.*

abstract class ActionDeclaration: Named {

    abstract val pddl: Action
    abstract val createProposal: ((Locale) -> String)?

    /**
     * Provides human-readable trigger for the action.
     * It's a text that can be printed on a display to trigger the action.
     * It's also something that the user can say to trigger the action.
     */
    abstract val createLabel: ((Context) -> String)?

    enum class ChatState {
        RUNNING,
        STOPPED,
        INDIFFERENT
    }

    abstract val chatState: ChatState

    /** Get the list of topics and the list of associated dynamic concepts that have to be set by the configuration. */
    abstract val createTopics: (suspend (QiContext, Context) -> List<Topic>)?

    /**
     * Creates the action.
     * Consider passing the required arguments at construction of the ActionDeclaration,
     * or by injection.
     */
    abstract val createAction: ((ActionDeclaration) -> PlannableAction)?

    /**
     * If this is true, since the action is in the plan,
     * we will keep the human in memory. (list of known human)
     */
    abstract val keepHumanInMemory: Boolean

    /**
     * Derives the given action declaration with the given factory.
     * Useful to specify the factory later, only if required.
     */
    fun withCreateAction(factory: ((ActionDeclaration) -> PlannableAction)?): ActionDeclaration {
        return object : ActionDeclaration() {
            override val name = this@ActionDeclaration.name
            override val pddl = this@ActionDeclaration.pddl
            override val createProposal = this@ActionDeclaration.createProposal
            override val createLabel = this@ActionDeclaration.createLabel
            override val chatState = this@ActionDeclaration.chatState
            override val createTopics: (suspend (QiContext, Context) -> List<Topic>)? = this@ActionDeclaration.createTopics
            override val createAction = factory
            override val keepHumanInMemory = this@ActionDeclaration.keepHumanInMemory
        }
    }

    /**
     * Give a new name to the action, including to its PDDL representation.
     */
    fun withName(newName: String): ActionDeclaration {
        val oldPddl = pddl
        return object : ActionDeclaration() {
            override val name = newName
            override val pddl =
                Action(newName, oldPddl.parameters, oldPddl.precondition, oldPddl.effect)
            override val createProposal = this@ActionDeclaration.createProposal
            override val createLabel = this@ActionDeclaration.createLabel
            override val chatState = this@ActionDeclaration.chatState
            override val createTopics: (suspend (QiContext, Context) -> List<Topic>)? = this@ActionDeclaration.createTopics
            override val createAction = this@ActionDeclaration.createAction
            override val keepHumanInMemory = this@ActionDeclaration.keepHumanInMemory
        }
    }

    /**
     * Derives the given action declaration with the given PDDL.
     * Useful to adapt the PDDL to a configuration.
     */
    fun withPDDL(newPddl: Action): ActionDeclaration {
        return object : ActionDeclaration() {
            override val name = this@ActionDeclaration.name
            override val pddl = newPddl
            override val createProposal = this@ActionDeclaration.createProposal
            override val createLabel = this@ActionDeclaration.createLabel
            override val chatState = this@ActionDeclaration.chatState
            override val createTopics: (suspend (QiContext, Context) -> List<Topic>)? = this@ActionDeclaration.createTopics
            override val createAction = this@ActionDeclaration.createAction
            override val keepHumanInMemory = this@ActionDeclaration.keepHumanInMemory
        }
    }
}

/**
 * Returns a topic factory suitable for action declarations from a resource.
 */
suspend fun topicFactoryWithoutDynamicConcept(resource: Int): suspend (QiContext, Context) -> Pair<List<Topic>, List<String>> {
    return topicFactoryWithoutDynamicConcept(listOf(resource))
}

/**
 * Returns a topic factory suitable for action declarations from multiple resources.
 */
suspend fun topicFactoryWithoutDynamicConcept(resources: List<Int>): suspend (QiContext, Context) -> Pair<List<Topic>, List<String>> {
    return { qiContext: QiContext, context: Context ->
        Pair(resources.map { createTopicFromResource(context, qiContext, it) }, listOf())
    }
}

/** Helper to define tasks with action declaration and instances. */
fun Task.Companion.create(action: ActionDeclaration, vararg instances: Instance): Task =
    create(action.pddl.name, *instances.map(Instance::name).toTypedArray())
