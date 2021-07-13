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

    /** The function used to search plans solving the problem. */
    private val planSearchFunction by lazy {
        runBlocking {
            val intent = Intent(IPDDLPlannerService.ACTION_SEARCH_PLANS_FROM_PDDL)
            intent.`package` = "com.softbankrobotics.fastdownward"
            createPlanSearchFunctionFromService(context, intent)
        }
    }

    private val planningHelper = PlanningHelper(planSearchFunction, domain)

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

    suspend fun setGoal(goal: Expression) = mutex.withLock {
        val wasRunning = isRunning
        stopUnsafe()
        planningHelper.setGoal(goal)
        if (wasRunning)
            startUnsafe()
    }

    suspend fun start() = mutex.withLock { startUnsafe() }

    suspend fun startUnsafe() {
        hasAlreadyDoneAPlan = false
        if (isRunning) {
            Timber.w("Controller was started but it was already running.")
            return
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

    suspend fun stop() = mutex.withLock { stopUnsafe() }

    private suspend fun stopUnsafe() {
        isRunning = false
        disposables.dispose()
        hasAlreadyDoneAPlan = false
    }

    /**
     * Search plan given the current configuration and the provided facts and goals.
     */
    suspend fun searchPlan(worldState: WorldState): Result<Tasks> = mutex.withLock {
        planningHelper.searchPlan(worldState)
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
        Timber.d("Planning for world:\n$state")

        val finishedTasksDuringThePlanning = mutableListOf<Task?>()
        val currentRunActionResultSubcription = lastFinishedTask.subscribe {
            if (it != null)
                finishedTasksDuringThePlanning.add(it)
        }

        // Find a plan, either from cache or by running the planner.
        val planningResult = planningHelper.searchPlan(state)
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
        if (actionAndDeclaration != null) {
            when (actionAndDeclaration.declaration.chatState) {
                ActionDeclaration.ChatState.RUNNING -> {
                    if (chatRunning.isDone) {
                        val waitingForStarted = CompletableDeferred<Unit>()
                        chat.async().addOnStartedListener {
                            waitingForStarted.complete(Unit)
                        }.await()
                        chatRunning = chat.async().run()
                        waitingForStarted.await()
                    }
                }
                ActionDeclaration.ChatState.STOPPED -> chatRunning.cancelAndJoin()
                ActionDeclaration.ChatState.INDIFFERENT -> {}
            }
        }

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
                    Timber.d("$task was successful")
                    Timber.d("$task produced $worldChange")

                    scope.launch {
                        updateWorldAndTryStartNextTask(
                            state, worldChange, task,
                            actionAndDeclaration.action.pddl, parameters,
                            resolveObject
                        )
                    }
                } catch (e: CancellationException) {
                    Timber.d("$task was cancelled")
                } catch (e: Throwable) {
                    Timber.d(e, "$task finished with error")
                } finally {
                    starting.complete(Unit)
                }
            }
            starting.await()
            Timber.d("$task was started")
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

    data class ActionAndDeclaration(
        val action: PlannableAction,
        val declaration: ActionDeclaration
    )

    companion object {

        suspend fun createDefaultController(
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
            val domain = defaultDomain

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
            return task?.toString() ?: "nothing"
        }
    }
}