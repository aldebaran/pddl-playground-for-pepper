package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions

import android.content.Context
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.conversation.Topic
import com.aldebaran.qi.sdk.`object`.human.Human
import com.aldebaran.qi.sdk.builder.EngageHumanBuilder
import com.softbankrobotics.pddl.pddlplaygroundforpepper.PlannableAction
import com.softbankrobotics.pddl.pddlplaygroundforpepper.await
import com.softbankrobotics.pddl.pddlplaygroundforpepper.cancelAndJoin
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.effectToWorldChange
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.MutableWorld
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.WorldData
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.extractors.getQiHuman
import com.softbankrobotics.pddlplanning.Action
import com.softbankrobotics.pddlplanning.and
import com.softbankrobotics.pddlplanning.exists
import com.softbankrobotics.pddlplanning.not
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.Human as PDDLHuman

/**
 * The shared data for the durative engage action.
 */
private object EngageDurativeAction {
    val coroutineScope = createAsyncCoroutineScope()
    val subscriptions = DisposablesSuspend()
}

/**
 * Starts the durative engage action.
 */
object StartEngageAction : ActionDeclaration() {

    override val name: String = "start_engage"
    override val pddl: Action by lazy {
        val h = PDDLHuman("?h")
        val other = PDDLHuman("?other")
        Action(
            name,
            listOf(h),
            and(
                can_be_engaged(h),
                not(exists(other, engages(self, other)))
            ),
            engages(self, h)
        )
    }

    override val createProposal: ((Locale) -> String)? = null
    override val createLabel: ((Context) -> String)? = null
    override val chatState = ChatState.INDIFFERENT
    override val createTopics: (suspend (QiContext, Context) -> List<Topic>)? =
        null
    override val createAction: ((ActionDeclaration) -> PlannableAction)? = null
    override val keepHumanInMemory = false

    private var engagedHuman: Human? = null

    fun createActionFactory(
        qiContext: QiContext,
        world: MutableWorld,
        data: WorldData
    ): (ActionDeclaration) -> PlannableAction {
        return { actionDeclaration ->
            createAction(actionDeclaration, qiContext, world, data)
        }
    }

    fun createAction(
        actionDeclaration: ActionDeclaration,
        qiContext: QiContext,
        world: MutableWorld,
        data: WorldData
    ): PlannableAction {

        suspend fun engage(
            pddlHuman: PDDLHuman,
            human: Human
        ) {
            val engageHuman = EngageHumanBuilder.with(qiContext)
                .withHuman(human)
                .buildAsync()
                .await()

            engageHuman.async().addOnHumanIsDisengagingListener {
                world.updateFacts(
                    SetDelta(added = setOf(is_disengaging(pddlHuman)))
                )
            }.await()

            val running = engageHuman.async().run()
            EngageDurativeAction.subscriptions.add { running.cancelAndJoin() }
            running.await()
        }

        return PlannableAction.createSuspend(actionDeclaration.pddl, null) { _, started, args ->
            val pddlHuman = PDDLHuman(args[0].name)
            world.subscribeAndGet {
                logTimeExceeding(WORLD_CALLBACKS_TIME_LIMIT_MS) {
                    synchronized(this) {
                        val pddlHumans = it.objects.filterIsInstance<PDDLHuman>()
                        if (pddlHuman in pddlHumans) {
                            val qiHuman = data.getQiHuman(pddlHuman)
                            if (qiHuman != null && qiHuman != engagedHuman) {
                                engagedHuman = qiHuman
                                EngageDurativeAction.coroutineScope.launch {
                                    try {
                                        engage(pddlHuman, qiHuman)
                                    } catch (e: CancellationException) {
                                        Timber.d("Physical engagement was cancelled")
                                    } catch (e: Throwable) {
                                        Timber.d("Physical engagement finished with an error: $e")
                                    } finally {
                                        engagedHuman = null
                                    }
                                }
                            }
                        } else {
                            EngageDurativeAction.coroutineScope.launch {
                                EngageDurativeAction.subscriptions.dispose()
                            }
                        }
                    }
                }
            }.addTo(EngageDurativeAction.subscriptions)
            started.emit(Unit)
            effectToWorldChange(actionDeclaration.pddl, args)
        }
    }
}

/**
 * Stops the durative engage action.
 */
object StopEngageAction : ActionDeclaration() {
    override val name: String = "stop_engage"
    override val pddl: Action by lazy {
        val h = PDDLHuman("?h")
        Action(
            name,
            listOf(h),
            engages(self, h),
            not(engages(self, h))
        )
    }

    override val createProposal: ((Locale) -> String)? = null
    override val createLabel: ((Context) -> String)? = null
    override val chatState = ChatState.INDIFFERENT
    override val createTopics: (suspend (QiContext, Context) -> List<Topic>)? =
        null

    override val createAction: ((ActionDeclaration) -> PlannableAction)? = null
    override val keepHumanInMemory = false

    fun createActionFactory(): (ActionDeclaration) -> PlannableAction {
        return { actionDeclaration ->
            createAction(actionDeclaration)
        }
    }

    private fun createAction(
        actionDeclaration: ActionDeclaration
    ): PlannableAction {
        return PlannableAction.createSuspend(actionDeclaration.pddl, null) { _, started, args ->
            started.emit(Unit)
            EngageDurativeAction.subscriptions.dispose()
            effectToWorldChange(actionDeclaration.pddl, args)
        }
    }
}
