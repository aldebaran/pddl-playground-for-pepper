package com.softbankrobotics.pddl.pddlplaygroundforpepper

import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.conversation.Chat
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.databinding.PlaceholderActionBinding
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.extractors.HumanExtractor
import com.softbankrobotics.pddlplanning.*
import com.softbankrobotics.pddlplanning.utils.Index
import com.softbankrobotics.pddlplanning.utils.createDomain
import com.softbankrobotics.pddlplanning.utils.createProblem
import com.softbankrobotics.pddlplanning.utils.toIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class Controller(
    private val actions: Index<ActionAndDeclaration>,
    private val world: MutableWorld,
    private val data: WorldData,
    private val domain: Domain,
    private val context: Context,
    private val frame: FrameLayout,
    private val screenTouched: Observable<Unit>,
    private val qiContext: QiContext,
    private val chat: Chat,
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

    /**
     * Default view.
     */
    private val defaultView =
        createView(R.layout.placeholder_action, context).apply {
            val view = PlaceholderActionBinding.bind(this)
            view.title.text = context.getString(R.string.welcome)
        }

    private var chatRunning: Future<Void> = Future.of(null)

    /** A local worker to queue plan searches safely, and avoid redundant planning attempts. */
    private val planningWorker = SingleTaskQueueWorker()

    private var skipCurrentPlanning = false

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
     * Future holding the current action
     */
    private var runningCurrentTask: Deferred<Unit> = CompletableDeferred(Unit)

    /**
     * True when no plan has been done since last start (or restart), false otherwise
     */
    private var hasAlreadyDoneAPlan = false

    /** The function used to search plans solving the problem. */
    private val planSearchFunction by lazy {
        runBlocking {
            val intent = Intent(IPDDLPlannerService.ACTION_SEARCH_PLANS_FROM_PDDL)
            intent.`package` = "com.softbankrobotics.fastdownward"
            createPlanSearchFunctionFromService(context, intent)
        }
    }

    private var goal = Expression()

    suspend fun setGoal(expression: Expression) {
        stop()
        goal = expression
        start()
    }

    suspend fun start() = mutex.withLock {
        hasAlreadyDoneAPlan = false
        if (isRunning) {
            Timber.w("Controller was started but it was already running.")
            return@withLock
        }

        val humanExtractor = HumanExtractor(qiContext, world, data, screenTouched, mutableTasks)
        humanExtractor.start()
        disposables.add { humanExtractor.stop() }

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
    suspend fun searchPlan(worldState: WorldState): Result<Tasks> = mutex.withLock {
        searchPlanPrivate(worldState)
    }

    /**
     * Unsafe version of the search plan method.
     * Used internally to avoid double-locking the mutex.
     */
    private suspend fun searchPlanPrivate(state: WorldState): Result<Tasks> {
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
            val instances = (state.objects + domain.constants).toIndex()
            switchTask(newTask, state) {
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
        state: WorldState,
        resolveObject: (String) -> Instance
    ) {
        val previousTask = currentTask.get()
        if (previousTask != null)
            error("cannot start task \"$task\" because \"$previousTask\" is still running")
        mutableCurrentTask.set(task)
        Timber.i("Starting ${actionNameOrNothing(currentTask.get())}")

        // Looking up the action referred by the task.
        val actionAndDeclaration = task?.let { actions[it.action] }
        if (task != null && actionAndDeclaration == null)
            throw Exception("Action name \"${task.action}\" does not exist")

        // Updating the state of the chat to match next action's requirements.
        val switchingChatState = if (actionAndDeclaration != null) {
            when (actionAndDeclaration.declaration.chatState) {
                ActionDeclaration.ChatState.RUNNING -> chat.async().run().also { chatRunning = it }
                ActionDeclaration.ChatState.STOPPED -> chatRunning.also { it.requestCancellationWithoutException() }
                ActionDeclaration.ChatState.INDIFFERENT -> Future.of<Void>(null)
            }
        } else {
            chatRunning.also { it.requestCancellationWithoutException() }
        }
        switchingChatState.await()

        if (actionAndDeclaration != null) {
            val parameters = task.parameters.map(resolveObject).toTypedArray()
            val starting = CompletableDeferred<Unit>()
            val view = actionAndDeclaration.action.view ?: defaultView
            runningCurrentTask = scope.async {
                try {

                    val worldChange = actionAndDeclaration.action.runWithOnStarted(parameters) {
                        runBlocking {
                            onUiThread {
                                switchView(view, actionAndDeclaration.action.name, frame)
                            }
                            starting.complete(Unit)
                        }
                    }
                    Timber.d("\"${task.action}\" was successful")
                    Timber.d("\"${task.action}\" produced $worldChange")

                    scope.launch {
                        updateWorldAndTryStartNextTask(
                            state, worldChange, task,
                            actionAndDeclaration.action.pddl, parameters,
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
                switchView(defaultView, "defaultView", frame)
            }
            Timber.d("Back to default view")
        }
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
        state: WorldState,
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
            worldChange == effectToWorldChange(action, parameters)
        ) {
            val tasks = tasks.get()
            val nextTaskIndex = 1
            if (tasks != null &&
                tasks.size > nextTaskIndex
            ) {
                Timber.i("Automatically start next action without replanning because the action \"${action.name}\" finished as expected and we are still in the same PDDL subproblem")
                switchTask(tasks[nextTaskIndex], state, resolveObject)
                isResumingPlan = true
                mutableTasks.set(tasks.subList(nextTaskIndex, tasks.size))
            }
        }
        mutableLastFinishedTask.set(expectedCurrentTask)
        if (isResumingPlan)
            skipCurrentPlanning = true
        world.update(worldChange)
        skipCurrentPlanning = false
    }

    data class Domain(
        val types: Set<Type>,
        val constants: Set<Instance>,
        val predicates: Set<Expression>,
        val actions: Set<Action>
    ) {
        private val string: String by lazy {
            createDomain(types, constants, predicates, actions)
        }

        override fun toString(): String = string
    }

    data class Problem(
        val objects: Set<Instance>,
        val init: Set<Fact>,
        val goal: Expression
    ) {
        private val string: String by lazy {
            val splitGoals = if (goal.word == and_operator_name)
                goal.args.toList()
            else
                listOf(goal)
            createProblem(objects, init, splitGoals)
        }

        override fun toString(): String = string
    }

    data class ActionAndDeclaration(
        val action: PlannableAction,
        val declaration: ActionDeclaration
    )

    companion object {

        suspend fun createController(
            context: Context,
            frame: FrameLayout,
            screenTouched: Observable<Unit>,
            qiContext: QiContext
        ): Controller {
            val world = MutableWorld()
            val data = WorldData()

            /*
             * The domain includes every type, constant, predicate or action we declared.
             */
            val domain = Domain(
                typesIndex.values.toSet(),
                constantsIndex.values.toSet(),
                predicatesIndex.values.toSet(),
                actionsIndex.values.map { it.pddl }.toSet()
            )

            val allTopics = actionsIndex.flatMap { (_, declaration) ->
                declaration.createTopics?.invoke(qiContext, context) ?: listOf()
            }
            val conversation = qiContext.conversation.async()
            val chatbot = conversation.makeQiChatbot(qiContext.robotContext, allTopics).await()
            val chat = conversation.makeChat(qiContext.robotContext, listOf(chatbot)).await()
            val actionsAndDeclarations = actionsIndex.map { (_, declaration) ->
                val actionFactory = createActionFactory(
                    declaration, qiContext,
                    { chatbot }, { allTopics },
                    data, world
                )
                val action = actionFactory.invoke(declaration)
                action.name to ActionAndDeclaration(action, declaration)
            }.toMap()
            return Controller(
                actionsAndDeclarations, world, data, domain,
                context, frame, screenTouched,
                qiContext, chat
            )
        }

        private fun actionNameOrNothing(task: Task?): String {
            return if (task != null) "\"$task\"" else "nothing"
        }
    }
}