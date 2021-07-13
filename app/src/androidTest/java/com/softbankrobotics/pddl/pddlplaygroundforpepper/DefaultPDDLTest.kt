package com.softbankrobotics.pddl.pddlplaygroundforpepper

import androidx.test.platform.app.InstrumentationRegistry
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.getOrThrow
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions.GreetAction
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions.StartEngageAction
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.WorldState
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.humansAreGreeted
import com.softbankrobotics.pddlplanning.Task
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DefaultPDDLTest {

    /**
     * Test showing that if a human is found and is eligible for engagement,
     * they are going to be greeted by the robot.
     */
    @Test
    fun greetsPreferredHuman() = runBlocking { // `runBlocking` because planning is `suspend`
        val world = minimalState.plus(
            knows_path(self, self, user),
            can_be_engaged(user),
            preferred_to_be_engaged(user)
        )
        val plan = p.searchPlan(world).getOrThrow()
        assertBeginsWith(
            listOf(
                Task.create(StartEngageAction, user),
                Task.create(GreetAction, user)
            ), plan
        )
    }

    companion object {

        // Instrumentation context is retrieved lazily, when really required, but remains valid all along.
        private val context by lazy {
            InstrumentationRegistry.getInstrumentation().targetContext
        }

        /**
         * A planning helper doing the annoying work translating the current state to a planning problem.
         */
        private val p by lazy {
            // We use the default planner here, but it is easy to swap it with a custom one.
            val planSearchFunction = runBlocking { getDefaultPlanner(context) }
            // Goal is set once here, but can be changed at any moment.
            val goal = humansAreGreeted.goal
            // We use the default domain here, but it is easy to swap it with a custom one.
            PlanningHelper(planSearchFunction, defaultDomain, goal)
        }

        /**
         * The state the robot is when "nothing" happens.
         * May vary depending on your domain, if the robot knows things of the world by design.
         */
        private val minimalState by lazy {
            // It is lazy initialized to be computed only when needed, and only once.
            WorldState()
        }

        private val user by lazy {
            generateInstance<Human>()
        }
    }
}