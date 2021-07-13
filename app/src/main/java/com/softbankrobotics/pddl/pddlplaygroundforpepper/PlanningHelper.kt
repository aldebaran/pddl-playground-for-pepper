package com.softbankrobotics.pddl.pddlplaygroundforpepper

import android.content.Context
import android.content.Intent
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.Result
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.WorldState
import com.softbankrobotics.pddlplanning.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Remembers a planning domain, a goal, to ease re-planning for different states.
 */
class PlanningHelper(
    private val planSearchFunction: PlanSearchFunction,
    private val domain: Domain,
    initialGoal: Goal = Goal()
) {
    private var goal = initialGoal

    /**
     * Waits for the current planning calls to complete before changing the goal.
     */
    suspend fun setGoal(value: Goal) = mutex.withLock {
        goal = value
    }

    private val mutex = Mutex()

    /**
     * Search plan method.
     * Used internally to avoid double-locking the mutex.
     */
    suspend fun searchPlan(state: WorldState): Result<Tasks> = mutex.withLock {
        val startTime = System.currentTimeMillis()

        /*
         * The problem is updated every time, to account for changes in states or goals.
         */
        val problem = Problem(
            state.objects - domain.constants, // prevents duplicate declaration to interfere
            state.facts,
            goal
        )

        return try {
            val plan = planSearchFunction.invoke(domain.toString(), problem.toString(), null)
            val planTime = System.currentTimeMillis() - startTime
            if (planTime > 2000) {
                Timber.w("Planning took especially long!")
            }

            if (plan.isEmpty()) {
                Timber.d("Found empty plan in $planTime ms, there is nothing to do!")
            } else {
                Timber.d("Found plan in $planTime ms:\n${plan.joinToString("\n")}")
            }
            Result.success(plan)
        } catch (t: Throwable) {
            val planTime = System.currentTimeMillis() - startTime
            Timber.e(t, "Planning error after $planTime ms: ${t.message}")
            Result.failure(t) // there is no next plan, we are blocked
        }
    }
}

suspend fun getDefaultPlanner(context: Context): PlanSearchFunction {
    val intent = Intent(IPDDLPlannerService.ACTION_SEARCH_PLANS_FROM_PDDL)
    intent.`package` = "com.softbankrobotics.fastdownward"
    return createPlanSearchFunctionFromService(context, intent)
}