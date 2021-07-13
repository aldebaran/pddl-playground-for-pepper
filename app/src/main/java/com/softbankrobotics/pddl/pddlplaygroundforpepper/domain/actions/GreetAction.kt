package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions

import android.content.Context
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.conversation.QiChatbot
import com.aldebaran.qi.sdk.`object`.conversation.Topic
import com.softbankrobotics.pddl.pddlplaygroundforpepper.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.addTo
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.effectToWorldChange
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.createTopicsFromResources
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.enableTopicsSuspend
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.goToStartBookmark
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.waitForFinishedBookmark
import com.softbankrobotics.pddlplanning.Action
import com.softbankrobotics.pddlplanning.and
import java.util.*

object GreetAction : ActionDeclaration() {

    override val name: String = "greet"
    override val pddl: Action by lazy {
        val h = Human("?h")
        Action(
            name,
            listOf(h),
            and(engages(self, h)),
            was_greeted(h)
        )
    }

    override val createProposal: ((Locale) -> String)? = null
    override val createLabel: ((Context) -> String)? = null
    override val chatState = ChatState.RUNNING
    override val createTopics: suspend (QiContext, Context) -> List<Topic> =
        { qiContext, context -> createTopicsFromResources(context, qiContext, R.raw.greet) }

    override val createAction: ((ActionDeclaration) -> PlannableAction)? = null
    override val keepHumanInMemory = false

    fun createActionFactory(
        localChatbotProvider: () -> QiChatbot,
        localTopicsProvider: (String) -> List<Topic>
    ): (ActionDeclaration) -> PlannableAction {
        return { actionDeclaration ->
            createAction(
                actionDeclaration,
                localChatbotProvider(),
                localTopicsProvider(actionDeclaration.name)
            )
        }
    }

    private fun createAction(
        actionDeclaration: ActionDeclaration,
        localQiChatbot: QiChatbot,
        topics: List<Topic>
    ): PlannableAction = PlannableAction.createSuspendWithDisposables(
        actionDeclaration.pddl,
        null
    ) { disposables, started, args ->

        val waitForFinished = waitForFinishedBookmark(localQiChatbot, topics)
        disposables.add { waitForFinished.cancelAndJoin() }
        enableTopicsSuspend(localQiChatbot, topics).addTo(disposables)
        started.emit(Unit)

        goToStartBookmark(localQiChatbot, topics)
        effectToWorldChange(actionDeclaration.pddl, args)
    }
}
