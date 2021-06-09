package com.softbankrobotics.pddl.pddlplaygroundforpepper

import android.content.Intent
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.conversation.Chat
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actionIndex
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.createActionFactory
import com.softbankrobotics.pddlplanning.*
import com.softbankrobotics.pddlplanning.utils.Index
import com.softbankrobotics.pddlplanning.utils.createDomain
import com.softbankrobotics.pddlplanning.utils.createProblem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class Controller(
    private val qiContext: QiContext,
    private val actions: Index<PlannableAction>,
    private val world: MutableWorld,
    private val data: WorldData,
    private val chat: Chat,
    private val domain: Domain,
    private val baseProblem: Problem
) {
    private val scope = createAsyncCoroutineScope()
    private val disposables = DisposablesSuspend()

    /**
     * Lock to protect the start & stop of the controller
     */
    private val mutex = Mutex()

    /**
     * Boolean for controller state
     */
    private var isRunning = false

    /** A local worker to queue plan searches safely, and avoid redundant planning attempts. */
    private val planningWorker = SingleTaskQueueWorker()

    private val skipCurrentPlanning = false

    /**
     * Store the last finished task.
     * TODO: store also the resulted world change for optimization if a planning is running.
     */
    private val mutableLastFinishedTask = StoredProperty<Task?>(null)
    private val lastFinishedTask =
        mutableLastFinishedTask as ObservableProperty<Task?>

    /**
     * The plan (list of tasks), to be shown in the Navigation View
     */
    private val mutableTasks = StoredProperty<Tasks?>(null)
    val tasks = mutableTasks as ObservableProperty<Tasks?>

    /**
     * The current task, describe in PDDL terms.
     */
    private val mutableCurrentTask = StoredProperty<Task?>(null)
    private val currentTask = mutableCurrentTask as ObservableProperty<Task?>

    /** This attribute point to our current position in the tasks list */
    private var currentTaskIndex = 0

    /**
     * True when no plan has been done since last start (or restart), false otherwise
     */
    private var hasAlreadyDoneAPlan = false

    data class Domain(
        val types: Set<Type>,
        val constants: Set<Instance>,
        val predicates: Set<Expression>,
        val actions: Set<Action>
    )

    data class Problem(
        val objects: Set<Instance>,
        val init: Set<Fact>,
        val goal: Expression
    )

    /** The function used to search plans solving the problem. */
    private val planSearchFunction by lazy {
        runBlocking {
            val intent = Intent(IPDDLPlannerService.ACTION_SEARCH_PLANS_FROM_PDDL)
            intent.`package` = "com.softbankrobotics.fastdownward"
            createPlanSearchFunctionFromService(qiContext, intent)
        }
    }

    suspend fun start() = mutex.withLock {
        hasAlreadyDoneAPlan = false
        if (isRunning)
            Timber.w("Controller was started but it was already running")
        world.subscribeAndGet {
            logTimeExceeding(WORLD_CALLBACKS_TIME_LIMIT_MS) {
                scope.launch {
                    mutex.withLock {
                        planningWorker.queue {
                            mutex.withLock {
                                searchPlanAndRun()
                            }
                        }
                    }
                }
            }
        }.addTo(disposables)
        isRunning = true
    }

    suspend fun stop() = mutex.withLock {
        isRunning = false
        disposables.dispose()
        hasAlreadyDoneAPlan = false
    }


    /**
     * Search plan given the current configuration and the provided facts and goals.
     */
    suspend fun searchPlan(
        worldState: WorldState
    ): Result<Tasks> = mutex.withLock {
        searchPlanPrivate(worldState)
    }

    private suspend fun searchPlanPrivate(
        state: WorldState
    ): Result<Tasks> {
        val startTime = System.currentTimeMillis()

        val objects = state.objects - domain.constants // avoid duplicates
        val facts = state.facts
        val goals = if (baseProblem.goal.word == and_operator_name)
            baseProblem.goal.args
        else
            arrayOf(baseProblem.goal)

        val problem = createProblem(objects, facts, goals.toList())
        val domain = createDomain(domain.types, domain.constants, domain.predicates, domain.actions)

        return try {
            val plan = planSearchFunction.invoke(domain, problem, null)
            val planTime = System.currentTimeMillis() - startTime
            if (planTime > 2000) {
                Timber.w("Planning took especially long!")
            }

            if (plan.isEmpty()) {
                Timber.d("Found empty in $planTime ms, there is nothing to do!")
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

    /**
     * Search for a plan and execute it.
     * If the planned task matches the current one, the current one keeps running.
     */
    private suspend fun searchPlanAndRun() {
        if (!isRunning || skipCurrentPlanning)
            return

        // Make sure to work on the latest state, based the current state of the world.
        val state = world.get()

        val finishedTasksDuringThePlanning = mutableListOf<Task?>()
        val currentRunActionResultSubcription = lastFinishedTask.subscribe {
            if (it != null)
                finishedTasksDuringThePlanning.add(it)
        }

        // Find a plan, either from cache or by running the planner.
        val planningResult = searchPlanPrivate(state)
        val plan = planningResult.getOrNull()

        currentRunActionResultSubcription.dispose()
        if (plan != null) {
            // If more tasks finished than the size of our plan,
            // we are sure we will not be able to jump to the next action by looking at the plan
            if (finishedTasksDuringThePlanning.size > plan.size) {
                Timber.d("Skip the outdated plan because more actions finished than the size of the plan")
                return
            }

            if (finishedTasksDuringThePlanning.isNotEmpty()) {
                // TODO: try to jump to the good position in the plan if possible
                // To do it we will need to modify the property lastFinishedTask to add the world changed of the tasks.
                Timber.d("This plan seems too old in doubt we don't consider it")
                return
            }
        } else {
            if (finishedTasksDuringThePlanning.isNotEmpty()) {
                Timber.d("Skip the outdated empty plan because at least one task finished since the begin of the planning")
                return
            }
        }
        mutableTasks.set(plan)
        currentTaskIndex = 0
        val newTask = plan?.firstOrNull()

        if (!hasAlreadyDoneAPlan || newTask != currentTask.get()) {
            hasAlreadyDoneAPlan = true
            switchTask(newTask, currentPddlProblem, state) {
                instances[it] ?: error("no object or constant named \"$it\"")
            }
        }
    }

    /**
     * Stops the current task and set it to null.
     * Not thread-safe.
     */
    private suspend fun stopCurrentTask() {
        Timber.i("Stopping ${actionNameOrNothing(currentTask.get())}")
        cycleHistory.add("task cancel")
        runningCurrentTask.cancelAndJoin()
        mutableCurrentTask.set(null)
    }

    /**
     * Sets a task as the current task and starts it.
     * The current task must be null.
     * Starting includes:
     * - updating the state of the chat controller
     * - switching view
     * Returns when the task has started or if an error occurred.
     * Not thread-safe.
     */
    private suspend fun startTask(
        task: Task?,
        pddlProblem: PddlProblem,
        state: PDDLWorldState,
        resolveObject: (String) -> Instance
    ) {
        val previousTask = currentTask.get()
        if (previousTask != null)
            error("cannot start task \"$task\" because \"$previousTask\" is still running")
        mutableCurrentTask.set(task)
        Timber.i("Starting ${actionNameOrNothing(currentTask.get())}")

        // Try to match the engaged human's language preference.
        val preferredLocale = preferredLocaleOfEngagedHuman(state, dataAccess)
        if (preferredLocale.language != locale.get().language
            && hasContentForLocale(preferredLocale)
        ) {
            Timber.i("Engaged human's preferred language is available and differs from current one, switching...")
            cycleHistory.add("switching locale")
            mutableLocale.set(preferredLocale)
            return // Switching locale triggers the restart of the controller, and starts the task again.
        }

        cycleHistory.add("task start")
        // Looking up the action referred by the task.
        val localizedContent = currentLocalizedContent
            ?: error("No content for the current locale: \"$locale\"")
        val actionAndDeclaration = task?.let { localizedContent.actions[it.action] }
        if (task != null && actionAndDeclaration == null)
            throw Exception("Action name \"${task.action}\" does not exist")

        // Updating the state of the chat to match next action's requirements.
        val chatController = localizedContent.chatControllerPtr
            ?: error("Chat controller for locale \"${locale}\"was not initialized yet")
        val switchingChatState = if (actionAndDeclaration != null) {
            when (actionAndDeclaration.declaration.chatState) {
                ActionDeclaration.ChatState.RUNNING -> chatController.startChat()
                ActionDeclaration.ChatState.STOPPED -> chatController.stopChat()
                ActionDeclaration.ChatState.INDIFFERENT -> Future.of<Void>(null)
            }
        } else {
            chatController.stopChat()
        }
        switchingChatState.await()

        if (actionAndDeclaration != null) {
            val parameters = task.parameters.map(resolveObject).toTypedArray()
            val starting = CompletableDeferred<Unit>()
            val view = actionAndDeclaration.action.view ?: localizedContent.defaultView
            runningCurrentTask = controllerScope.async {
                try {
                    val worldChange = runAction(actionAndDeclaration.action, parameters) {
                        runBlocking {
                            onUiThread {
                                switchView(view, actionAndDeclaration.action.pddlName, frameLayout)
                                cycleHistory.add("task view switched")
                            }
                            starting.complete(Unit)
                        }
                    }
                    Timber.d("\"${task.action}\" was successful")
                    Timber.d("\"${task.action}\" produced $worldChange")

                    controllerScope.launch {
                        updateWorldAndTryStartNextTask(
                            state, worldChange, task,
                            actionAndDeclaration.action.pddl, parameters, pddlProblem,
                            resolveObject
                        )
                    }
                } catch (e: CancellationException) {
                    Timber.d("${task.action} was cancelled")
                } catch (e: Throwable) {
                    Timber.d(e, "${task.action} finished with error")
                } finally {
                    starting.complete(Unit)
                }
            }
            starting.await()
            Timber.d("${task.action} was started")
        } else {
            onUiThread {
                switchView(localizedContent.defaultView, "defaultView", frameLayout)
                cycleHistory.add("task view switched")
            }
            Timber.d("Back to default view")
        }
        cycleHistory.add("task started")

        Timber.i("Performance report:\n${reportDurations(cycleHistory.toHistory())}")
        cycleHistory.clear()
    }

    /**
     * Stops the current task then starts the next one.
     */
    private suspend fun switchTask(
        nextTask: Task?,
        state: WorldState,
        resolveObject: (String) -> Instance
    ) {
        stopCurrentTask()
        startTask(nextTask, state, resolveObject)
    }

    private suspend fun updateWorldAndTryStartNextTask(
        state: Worl,
        worldChange: WorldChange,
        expectedCurrentTask: Task?,
        action: Action,
        parameters: Array<out Instance>,
        resolveObject: (String) -> Instance
    ) = mutex.withLock {
        var isResumingPlan = false
        val currentTask = currentTask.get()
        if (isRunning &&
            expectedCurrentTask == currentTask &&
            worldChange == effectToWorldChange(action, parameters) &&
            pddlProblem == currentPddlProblem // The sub problem should still be the same
        ) {
            val tasks = tasks.get()
            val nextTaskIndex = 1
            if (tasks != null &&
                tasks.size > nextTaskIndex
            ) {
                Timber.i("Automatically start next action without replanning because the action \"${action.name}\" finished as expected and we are still in the same PDDL subproblem")
                switchTask(tasks[nextTaskIndex], pddlProblem, state, resolveObject)
                isResumingPlan = true
                mutableTasks.set(tasks.subList(nextTaskIndex, tasks.size))
            }
        }
        mutableLastFinishedTask.set(expectedCurrentTask)
        if (isResumingPlan)
            skipCurrentPlanning = true
        worldState.update(worldChange)
        skipCurrentPlanning = false
    }

    companion object {
        suspend fun createController(qiContext: QiContext): Controller {
            val world = MutableWorld()
            val data = WorldData()

            val allTopics = actionIndex.flatMap { (_, declaration) ->
                declaration.createTopics?.invoke(qiContext, qiContext) ?: listOf()
            }
            val conversation = qiContext.conversation.async()
            val chatbot = conversation.makeQiChatbot(qiContext.robotContext, allTopics).await()
            val chat = conversation.makeChat(qiContext.robotContext, listOf(chatbot)).await()
            val plannableActions = actionIndex.map { (_, declaration) ->
                val actionFactory = createActionFactory(
                    declaration, qiContext,
                    { chatbot }, { allTopics },
                    data, world
                )
                val plannableAction = actionFactory.invoke(declaration)
                plannableAction.name to plannableAction
            }.toMap()
            return Controller(qiContext, plannableActions, world, data, chat)
        }
    }
}