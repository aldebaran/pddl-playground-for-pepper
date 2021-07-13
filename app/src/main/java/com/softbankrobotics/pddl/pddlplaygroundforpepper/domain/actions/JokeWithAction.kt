package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions

import android.content.Context
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.conversation.Topic
import com.aldebaran.qi.sdk.builder.SayBuilder
import com.aldebaran.qi.sdk.util.IOUtils
import com.softbankrobotics.pddl.pddlplaygroundforpepper.R
import com.softbankrobotics.pddl.pddlplaygroundforpepper.await
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.createView
import com.softbankrobotics.pddl.pddlplaygroundforpepper.databinding.JokeWithActionBinding
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.effectToWorldChange
import com.softbankrobotics.pddlplanning.Action
import com.softbankrobotics.pddlplanning.and
import com.softbankrobotics.pddlplanning.not
import java.util.*

object JokeWithAction : ActionDeclaration() {

    override val name: String = "joke_with"
    override val pddl: Action by lazy {
        val h = Human("?h")
        Action(
            name,
            listOf(h),
            engages(self, h),
            and(not(feels(h, neutral)), not(feels(h, sad)), feels(h, happy))
        )
    }

    override val createProposal: ((Locale) -> String)? = null
    override val createLabel: ((Context) -> String)? = null
    override val chatState = ChatState.STOPPED // Because this action uses a Say.
    override val createTopics: (suspend (QiContext, Context) -> List<Topic>)? = null
    override val createAction: ((ActionDeclaration) -> PlannableAction)? = null
    override val keepHumanInMemory = false

    fun createActionFactory(
        qiContext: QiContext
    ): (ActionDeclaration) -> PlannableAction {
        return { actionDeclaration ->
            createAction(
                actionDeclaration,
                qiContext
            )
        }
    }

    private fun createAction(
        actionDeclaration: ActionDeclaration,
        qiContext: QiContext
    ): PlannableAction = PlannableAction.createSuspendWithDisposables(
        actionDeclaration.pddl,
        JokeWithActionBinding.bind(createView(R.layout.joke_with_action, qiContext)).root
    ) { _, started, args ->
        val jokes = IOUtils.fromRaw(qiContext, R.raw.jokes).lines()
        val say = SayBuilder.with(qiContext)
            .withText(jokes.random())
            .buildAsync().await()
        started.emit(Unit)
        say.async().run().await()
        effectToWorldChange(actionDeclaration.pddl, args)
    }
}