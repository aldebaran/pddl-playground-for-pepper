package com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.extractors

import com.aldebaran.qi.AnyObject
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.*
import com.aldebaran.qi.sdk.BuildConfig
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.human.EngagementIntentionState
import com.softbankrobotics.pddl.pddlplaygroundforpepper.applyParameters
import com.softbankrobotics.pddl.pddlplaygroundforpepper.await
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.extractors.HumanExtractor.Companion.QISDK_HUMAN_DATA_KEY
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.ProxyProperty
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.asAnyObject
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.isInArc
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.qiObjectCast
import com.softbankrobotics.pddl.pddlplaygroundforpepper.splitFactsByPolarity
import com.softbankrobotics.pddlplanning.*
import com.softbankrobotics.pddlplanning.utils.evaluateExpression
import kotlinx.coroutines.*
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt
import com.aldebaran.qi.sdk.`object`.human.Human as QiHuman
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.Human as PDDLHuman

/**
 * Tracks human-related events in the real world using the Qi SDK,
 * and associates them to symbolic statements in the PDDL world, via the WorldData.
 * Assesses whether humans are present, engageable or engaged,
 * and updates the world with this information.
 * Implements an engagement policy by flagging the best human with (preferred_human_to_engage ?h).
 * @param qiContext Access to NAOqi.
 * @param world The PDDL world.
 * @param touched Signals touch events.
 * @param tasks The current tasks of the robot.
 * @param initialFacts Facts to add for every human when they are met. Human parameter "?h" is replaced by the actual human object.
 */
class HumanExtractor(
    private val qiContext: QiContext,
    private val world: MutableWorld,
    private val worldData: WorldData,
    private val touched: Observable<Unit>,
    private val tasks: ObservableProperty<Tasks?>,
    private val initialFacts: Set<Fact> = setOf()
) {
    val scope = createAsyncCoroutineScope()

    /**
     * Actions that, if they are part of the current plan,
     * would prevent disengagement from leading to its disappearing.
     */
    var actionsKeepingHuman: Set<String> = setOf()
        set(value) = worldTransaction { field = value } // worldTransaction locks and forces reevaluation.

    private val robotFrame: Future<Frame> = qiContext.actuationAsync.andThenCompose {
        it.async().robotFrame()
    }

    /**
     * Internal data and subscriptions about humans.
     */
    private class HumanData(
        var qi: QiHuman?,
        val pddl: PDDLHuman
    ) {
        /**
         * Per-human subscriptions.
         */
        val subscriptions = Disposables()

        /**
         * Whether the human seeks engagement.
         */
        var seeksEngagement = Timestamped.of(false)

        /**
         * Whether the human was seen.
         */
        var visible: Timestamped<Boolean?> = Timestamped.of(null)

        /**
         * Whether the human was seen disengaging.
         */
        var isDisengaging = false

        /**
         * Last time the user touched us.
         */
        var lastTouch = 0L

        /**
         * Last time the user told us something.
         */
        var lastSpeech = 0L

        /**
         * The distance of this human.
         */
        var distance: Timestamped<Double?> = Timestamped.of(null)

        /**
         * Whether the human is in ZoI (if visible).
         */
        var inZoi: Timestamped<Boolean?> = Timestamped.of(null)

        /**
         * Whether the human is involved in the current plan.
         */
        var isInPlan: Timestamped<Boolean?> = Timestamped.of(null)

        /**
         * Whether the human is involved in a task that cannot stop because the human leave.
         */
        var isKeptByATask = false
    }

    /**
     * Top-level subscriptions.
     */
    private val subscriptions = Disposables()

    /**
     * Currently known humans, indexed by Qi Object.
     */
    private val knownHumans = mutableSetOf<HumanData>()

    /**
     * Human currently engaged by the robot.
     */
    private var engagedHuman: PDDLHuman? = null

    val isStarted: Boolean
        get() {
            return subscriptions.isNotEmpty()
        }

    /**
     * Forgets about all known humans.
     */
    fun setNoHuman() = worldTransaction {
        val instancesToRemove = knownHumans.map(HumanData::pddl).toSet()
        addWorldChange {
            knownHumans.clear()
            instancesToRemove.forEach { toRemove -> worldData.remove(toRemove) }
            WorldChange(objects = SetDelta(removed = instancesToRemove))
        }
        updatePreferredHuman()
    }

    fun start() = worldTransaction {
        Timber.d("Starting human extractor")
        val humanAwareness = qiContext.humanAwareness
        val humansAroundProperty =
            ProxyProperty.create<List<AnyObject>>(humanAwareness, "humansAround", emptyList())
        humansAroundProperty.subscribeAndGet { anyHumans ->
            processHumansAround(anyHumans.map { qiObjectCast(it) })
        }.addTo(subscriptions)

        // When tablet is touched, we know there is an interested human, but:
        // - we do not have a QiHuman associated to it.
        // - we don't know how to reach it.
        subscriptions.add(touched.subscribe {
            scope.launch {
                worldTransaction {
                    addWorldChange { world ->

                        // Whoever that was, that human is considered interested and engaged.
                        // Reconsider engagement later.
                        val toucher = deduceEngagedHuman(world)
                        toucher.lastTouch = System.nanoTime()
                        val timeout =
                            if (toucher.visible.value != null) TOUCH_ONCE_SEEN_ENGAGEMENT_TIMEOUT_NS else TOUCH_ENGAGEMENT_TIMEOUT_NS
                        scheduleEngagementUpdate(toucher, timeout)
                        WorldChange(facts = SetDelta(createHumanEngagesFacts(toucher.pddl)))
                    }
                    updatePreferredHuman()
                }
            }
        })

        subscriptions.add(tasks.subscribeAndGet { currentTasks ->
            synchronized(this) {
                knownHumans.forEach { human ->
                    human.isKeptByATask = false
                    var isInPlan = false
                    if (currentTasks != null) {
                        for (task in currentTasks) {
                            if (task.parameters.any { p -> p == human.pddl.name }) {
                                isInPlan = true
                                human.isKeptByATask =
                                    actionsKeepingHuman.any { nsa -> nsa == task.action }
                                if (human.isKeptByATask)
                                    break
                            }
                        }
                    }
                    human.isInPlan = Timestamped.of(isInPlan)
                    scheduleEngagementUpdate(human, IS_IN_PLAN_TIMEOUT_NS)
                }
            }
        })

        // Same thing for when speech is heard.
        try {
            val subscribingToSpeech = qiContext.conversation.async().status(qiContext.robotContext)
                .andThenCompose { conversationStatus ->
                    conversationStatus.async().addOnHeardListener {
                        worldTransaction {
                            addWorldChange { world ->
                                // Whoever spoke, that human is considered interested and engaged.
                                // Reconsider engagement later.
                                val speaker = deduceEngagedHuman(world)
                                speaker.lastSpeech = System.nanoTime()
                                val timeout =
                                    if (speaker.visible.value != null) SPEECH_ONCE_SEEN_ENGAGEMENT_TIMEOUT_NS else SPEECH_ENGAGEMENT_TIMEOUT_NS
                                scheduleEngagementUpdate(speaker, timeout)
                                WorldChange(facts = SetDelta(createHumanEngagesFacts(speaker.pddl)))
                            }
                            updatePreferredHuman()
                        }
                    }.andThenConsume {
                        subscriptions.add { conversationStatus.async().removeAllOnHeardListeners() }
                    }
                }
            subscriptions.add { subscribingToSpeech.requestCancellation() }
        } catch (e: Exception) {
            Timber.e(e)
        }

        // On world change...
        subscriptions.add(world.subscribeAndGet { world ->
            logTimeExceeding(WORLD_CALLBACKS_TIME_LIMIT_MS) {
                val disengagingHumans = world.facts.filter { it.word == IS_DISENGAGING }
                    .map { PDDLHuman(it.args[0].word) }.toSet()
                if (disengagingHumans.isNotEmpty()) {
                    scope.launch { // To avoid disturbing the world state's notification mechanism.
                        worldTransaction {
                            knownHumans.forEach { human ->
                                if (human.pddl in disengagingHumans && !human.isDisengaging) {
                                    human.isDisengaging = true
                                    human.seeksEngagement = Timestamped.of(false)
                                    addWorldChange(
                                        WorldChange(
                                            facts = SetDelta(removed = setOf(is_interested(human.pddl)))
                                        )
                                    )
                                    updateEngagement(human)
                                }
                            }
                        }
                    }
                }

                val newEngagedHumans = world.facts.filter {
                    it.word == ENGAGES_PREDICATE &&
                            it.args.size > 1 && it.args[0] == self
                }
                    .map { PDDLHuman(it.args[1].word) }.toSet()
                // Only one human should be engaged by the robot
                val newEngagedHuman = if (newEngagedHumans.isNotEmpty()) {
                    newEngagedHumans.first()
                } else {
                    null
                }

                scope.launch { // To avoid disturbing the world state's notification mechanism.
                    worldTransaction {
                        if (engagedHuman != newEngagedHuman) {
                            if (engagedHuman != null) {
                                val humanData = knownHumans.find { it.pddl == engagedHuman }
                                if (humanData != null) {
                                    humanData.subscriptions.dispose()
                                    Timber.d("${humanData.pddl} is disengaged and will now be forgotten")
                                    knownHumans.remove(humanData)

                                    addWorldChange {
                                        worldData.remove(humanData.pddl)
                                        WorldChange(
                                            objects = SetDelta(removed = setOf(humanData.pddl))
                                        )
                                    }

                                    updatePreferredHuman()

                                    launch {
                                        // Reconsider humans around again.
                                        // Also updates the preferred human to engage.
                                        val humansAround =
                                            humanAwareness.async().humansAround.await()
                                        processHumansAround(humansAround)
                                    }
                                }
                            }
                            engagedHuman = newEngagedHuman
                        }
                    }
                }
            }
        })
    }

    fun stop() = synchronized(this) {
        subscriptions.dispose()
        knownHumans.forEach { it.subscriptions.dispose() }
        knownHumans.clear()
    }

    private fun processHumansAround(currentHumans: List<QiHuman>) = worldTransaction {

        // Remove humans that disappeared.
        val previousHumansIt = knownHumans.iterator()
        while (previousHumansIt.hasNext()) {
            val human = previousHumansIt.next()
            val qiHuman = human.qi
            if (qiHuman != null && qiHuman !in currentHumans) {
                Timber.d("Human is not visible anymore: ${human.pddl}")

                // Dissociate the Qi SDK object.
                // Note that for an instant (VISIBLE_TIMEOUT_NS),
                // the human is still considered present, even though not visible.
                addWorldChange {
                    human.subscriptions.dispose()
                    human.qi = null
                    human.visible = Timestamped.of(false)
                    human.distance = Timestamped.of(null)
                    human.seeksEngagement = Timestamped.of(false)
                    human.isDisengaging = false
                    scheduleEngagementUpdate(human, VISIBLE_TIMEOUT_NS)
                    worldData.remove(human.pddl, QISDK_HUMAN_DATA_KEY)
                    WorldChange(
                        facts = SetDelta(
                            added = setOf(),
                            removed = setOf(
                                knows_path(self, self, human.pddl),
                                is_disengaging(human.pddl)
                            )
                        )
                    )
                }
            }
        }

        // Add humans that appeared.
        Timber.d("There are ${currentHumans.size} humans around")
        for (qiHuman in currentHumans) {
            if (knownHumans.none { it.qi == qiHuman }) {
                // Add it in the cache and to the next world change.

                var alreadyKnownData: HumanData? = null
                // Is there an engaged "invisible" human?
                // Could this new human be the same?
                for (knownHuman in knownHumans) {
                    var foundMatchingHuman = false
                    if (knownHuman.qi == null) {
                        Timber.d("Replacing invisible human by visible human: $knownHuman")
                        knownHuman.qi = qiHuman
                        alreadyKnownData = knownHuman
                        // If yes, also replace the object binding.
                        worldData.set(knownHuman.pddl, QISDK_HUMAN_DATA_KEY, qiHuman)
                        updatePreferredHuman()
                        foundMatchingHuman = true
                    }
                    if (foundMatchingHuman)
                        break
                }

                // The human data associated to the Qi SDK human.
                val data = alreadyKnownData ?: ensureHumanIsKnown(qiHuman)
                val associatedHuman = data.qi!!

                // New humans have been seen so their location is known relative to the robot's.
                data.visible = Timestamped.of(true)
                addWorldChange(WorldChange.of(knows_path(self, self, data.pddl)))

                // Track the engagement to assess whether use is interested.
                val engagement =
                    ProxyProperty.create<EngagementIntentionState>(
                        qiHuman,
                        "engagementIntention",
                        EngagementIntentionState.UNKNOWN
                    )

                data.subscriptions.add(engagement.subscribeAndGet {
                    worldTransaction {
                        val factChange = when (it) {
                            EngagementIntentionState.INTERESTED -> {
                                data.seeksEngagement = Timestamped.of(true)
                                SetDelta(added = setOf(is_interested(data.pddl)))
                            }
                            EngagementIntentionState.SEEKING_ENGAGEMENT -> {
                                // Not only the human is interested but they engage us.
                                data.seeksEngagement = Timestamped.of(true)
                                data.isDisengaging = false
                                SetDelta(
                                    added = setOf(
                                        is_interested(data.pddl),
                                        engages(data.pddl, self)
                                    ),
                                    removed = setOf(is_disengaging(data.pddl))
                                )
                            }
                            else -> {
                                SetDelta()
                            }
                        }

                        addWorldChange(WorldChange(facts = factChange))
                        updatePreferredHuman()
                    }
                })

                // Track the location of the human to assess whether they can be engaged.
                val engagementAssessmentJob = scope.launch {
                    while (true) {
                        val (isInZoi, d, tft) = try {
                            val headFrame = qiHuman.async().headFrame.await()
                            val tft = headFrame.async()
                                .computeTransform(robotFrame.await()).await()
                            val d = tft.transform.translation.run {
                                sqrt(x.pow(2) + y.pow(2))
                            }
                            val isInZoi = isInArc(tft.transform, 2.0, PI / 4)
                            Triple(isInZoi, d, tft)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            // Catch unexpected errors to avoid possible crashes du to concurrent access.
                            Timber.e("Error computing location of ${data.pddl}")
                            return@launch
                        }

                        synchronized(this@HumanExtractor) {
                            if (data.qi != associatedHuman) {
                                throw CancellationException("Human was dissociated during analysis")
                            }
                            val wasInZoi = data.inZoi.value
                            if (wasInZoi != isInZoi) {
                                when {
                                    wasInZoi == null -> {
                                        Timber.d("${data.pddl} is${if (!isInZoi) " not" else ""} in ZoI")
                                    }
                                    wasInZoi -> { // implies inZoi == false
                                        Timber.d("${data.pddl} has left the ZoI")
                                    }
                                    else -> { // wasInZoi == false && inZoi == true
                                        Timber.d("${data.pddl} has entered the ZoI")
                                    }
                                }
                            }
                            data.inZoi = Timestamped.of(isInZoi)
                            data.distance = Timestamped(d, tft.time)
                            worldTransaction {
                                val factChange = if (isInZoi) {
                                    SetDelta(added = setOf(can_be_engaged(data.pddl)))
                                } else {
                                    SetDelta(removed = setOf(can_be_engaged(data.pddl)))
                                }
                                addWorldChange(WorldChange(facts = factChange))
                                updatePreferredHuman()
                            }
                        }
                        delay(250L)
                    }
                }
                data.subscriptions.add {
                    engagementAssessmentJob.cancel()
                }
            }
        }

        updatePreferredHuman()
    }

    /**
     * Deduce which human is most probably the one who interacts with us.
     * If no human is around, a new human is created.
     */
    private fun deduceEngagedHuman(state: WorldState): HumanData {

        // Assess the situation.
        val engagedHumans = mutableSetOf<PDDLHuman>()
        val engageableHumans = mutableSetOf<PDDLHuman>()
        state.facts.forEach {
            val (destination, human) = when (it.word) {
                CAN_BE_ENGAGED_PREDICATE -> engageableHumans to PDDLHuman(it.args[0].word)
                ENGAGES_PREDICATE -> {
                    if (it.args.size > 1 && it.args[0] == self)
                        engagedHumans to PDDLHuman(it.args[1].word)
                    else
                        null to null
                }
                else -> null to null
            }
            if (destination != null && human != null) {
                if (knownHumans.any { it.pddl == human }) {
                    destination.add(human)
                } else {
                    Timber.w("Human $human is involved in facts but is not known")
                }
            }
        }

        // "Who interacted with me?
        val interactee =
            when {
                // Most probably the engaged user, if any."
                engagedHumans.size == 1 -> {
                    Timber.d("Blind interaction deduced to be from the engaged human")
                    engagedHumans.first()
                }
                // Else choose one person I could engage."
                engageableHumans.size >= 1 -> {
                    Timber.d("Blind interaction deduced to be from the engageable human")
                    engageableHumans.first()
                }
                // Else there is someone here I did not see."
                else -> {
                    Timber.d("Blind interaction deduced to be from a new human")
                    declareNewHuman().pddl
                }
            }

        return knownHumans.find { it.pddl == interactee }!!
    }

    /**
     * Creates and stores a new PDDL instance representing a human with no Qi SDK handle.
     * Useful when a human presence can be assessed, but the vision cannot confirm.
     */
    private fun declareNewHuman(): HumanData = synchronized(this) {
        val newData = HumanData(null, generateUniqueHuman())
        knownHumans.add(newData)
        addWorldChange(newHumanWorldChange(newData.pddl))
        // No associated Qi SDK human in the world data
        newData
    }

    /**
     * Retrieves the PDDL instance representing the provided Human Qi Object.
     * If no PDDL instance is associated, it is created.
     */
    private fun ensureHumanIsKnown(qiHuman: QiHuman): HumanData = synchronized(this) {
        knownHumans.find { it.qi == qiHuman } ?: run {
            val newHuman = HumanData(qiHuman, generateUniqueHuman())
            addWorldChange(newHumanWorldChange(newHuman.pddl))
            knownHumans.add(newHuman)

            // Also associate the Qi SDK Human object to the PDDL object using the WorldData.
            worldData.set(newHuman.pddl, QISDK_HUMAN_DATA_KEY, qiHuman)

            Timber.d("New human visible: ${newHuman.pddl}")
            newHuman
        }
    }

    /**
     * Compute the initial facts for the given human.
     */
    private fun newHumanWorldChange(pddlHuman: PDDLHuman): WorldChange {
        val parameters = mapOf<Instance, Instance>(h to pddlHuman)
        val appliedInitialFacts = initialFacts.map { applyParameters(it, parameters) }.toSet()
        return WorldChange(
            objects = SetDelta.of(pddlHuman),
            facts = splitFactsByPolarity(appliedInitialFacts)
        )
    }

    /**
     * Reevaluates whether disengagement can be confirmed.
     */
    private fun updateEngagement(data: HumanData) = synchronized(this) {
        // If the user left, no need to reevaluate disengagement
        if (!knownHumans.contains(data))
            return

        // If user is visibly engaged, engagement is confirmed.
        if (data.seeksEngagement.value)
            return

        // If user is implicated in a not stoppable task, we don't disengage.
        if (data.isKeptByATask) {
            Timber.i("Not disengaging ${data.pddl.name} because the flow is critic")
            return
        }

        // Otherwise check when last we noticed the human's engagement.
        // Multiple modalities are involved.
        val now = System.nanoTime()
        val timeSinceVisibleEngagement = now - data.seeksEngagement.timestamp
        val timeSinceVisible = now - data.visible.timestamp
        val timeSinceLastTouch = now - data.lastTouch
        val timeSinceLastSpeech = now - data.lastSpeech
        Timber.d("${data.pddl.name} last visibility: ${timeSinceVisibleEngagement / NOF_NS_PER_S}s, touch: ${timeSinceLastTouch / NOF_NS_PER_S}s, speech: ${timeSinceLastSpeech / NOF_NS_PER_S}s")

        // Timeouts may differ depending on modalities.
        val isVisible = data.visible.value
        var isDisengaged = if (isVisible != null) {
            if (isVisible) {
                data.isDisengaging
            } else {
                timeSinceVisible >= VISIBLE_TIMEOUT_NS
                        && timeSinceLastTouch >= TOUCH_ONCE_SEEN_ENGAGEMENT_TIMEOUT_NS
                        && timeSinceLastSpeech >= SPEECH_ONCE_SEEN_ENGAGEMENT_TIMEOUT_NS
            }
        } else {
            timeSinceLastTouch >= TOUCH_ENGAGEMENT_TIMEOUT_NS
                    && timeSinceLastSpeech >= SPEECH_ENGAGEMENT_TIMEOUT_NS
        }
        if (isDisengaged && data.isInPlan.value == true) {
            val timeSinceIsInPlan = now - data.isInPlan.timestamp
            isDisengaged = timeSinceIsInPlan >= IS_IN_PLAN_TIMEOUT_NS
            if (!isDisengaged)
                Timber.i("Can disengage ${data.pddl.name} but we wait a little more because he is involved in the plan of the robot")
        }

        if (isDisengaged) {
            Timber.d("${data.pddl} is now considered disengaged")
            // Disengagement is deduced.
            // If the human is not visible, we can even assume it disappeared.
            val objectsChange = if (data.qi == null) {
                Timber.d("${data.pddl} is not visible and is now considered absent")
                data.subscriptions.dispose()
                knownHumans.remove(data)
                SetDelta(removed = setOf<Instance>(data.pddl))
            } else SetDelta()

            worldTransaction {
                addWorldChange(
                    WorldChange(
                        facts = SetDelta(
                            removed = createHumanEngagesFacts(data.pddl)
                        ),
                        objects = objectsChange
                    )
                )
                // Remove data associations.
                objectsChange.removed.forEach { worldData.remove(it) }

                updatePreferredHuman()
            }
        }
    }

    /**
     * Shortcut to schedule the re-evaluation of existence and engagement.
     */
    private fun scheduleEngagementUpdate(human: HumanData, delayTimeNs: Long) {
        scope.launch { // TODO: cancel previously scheduled evaluations according to timeouts.
            if (delayTimeNs > 0L) {
                delay(delayTimeNs / NOF_NS_PER_MS)
                updateEngagement(human)
            }
        }
    }

    private fun updatePreferredHuman() = worldTransaction {

        // This is computed as a world change so that it queues up properly after other planned world changes.
        addWorldChange { world ->

            // Preferred to be engaged human is the one that fulfills the conditions to be engaged
            // or is already engaged by the robot and doesn't show the signs of disengagement
            // (for more info check evaluateDisengagement function)
            val suitableHumans = knownHumans.filter {
                val h = it.pddl
                evaluateExpression(
                    and(
                        or(can_be_engaged(h), engages(self, h)),
                        not(is_disengaging(h))
                    ),
                    world.objects,
                    world.facts
                )
            }

            val preferred: PDDLHuman? = when (suitableHumans.size) {
                // Human is preferred to engage or to continue being engaged if...
                0 -> null
                1 -> suitableHumans.first().pddl
                else -> {
                    // Priority is given to the human already engaged by the robot if they still
                    // satisfy the conditions
                    if (engagedHuman != null && engagedHuman in suitableHumans.map(HumanData::pddl)
                            .toSet()
                    ) {
                        engagedHuman
                    } else {
                        // Otherwise the preferred human is the one closest to the robot
                        val located = suitableHumans.filter { it.distance.value != null }
                        when (located.size) {
                            0 -> null
                            1 -> located.first().pddl
                            else -> {
                                located.minByOrNull { it.distance.value!! }?.pddl
                            }
                        }
                    }
                }
            }

            // Replace currently preferred human to engage with the new one (exclusive).
            val toPrefer = if (preferred != null) {
                setOf(preferred_to_be_engaged(preferred))
            } else {
                setOf()
            }
            val previouslyPreferred =
                world.facts.filter { it.word == PREFERRED_TO_BE_ENGAGED }.toSet()
            WorldChange(
                facts = SetDelta(
                    toPrefer - previouslyPreferred,
                    previouslyPreferred - toPrefer
                )
            )
        }
    }

    /**
     * Check that the known humans match the ones found in the world.
     */
    private fun checkHumansMatchWorld() = synchronized(this) {
        val state = world.get()
        for (human in knownHumans) {
            if (human.pddl !in state.objects)
                error("Human ${human.pddl} is known but is not found in the world state")
            val anyHuman = human.qi?.let { asAnyObject(it) }
            val anyHumanInWorld = worldData.get<QiHuman>(human.pddl, QISDK_HUMAN_DATA_KEY)
            if (anyHumanInWorld != anyHuman) {
                error(
                    "Human ${human.pddl} is known but its object does not match world state's.\nWorld's:\n" +
                            "$anyHumanInWorld\nHuman Extractor's:\n$anyHuman"
                )
            }
        }
    }

    // TODO: factorize this in a Transaction.kt file.
    /**
     * World changes that have been accumulated and should be processed.
     * Should be reset to null after use.
     */
    private var pendingWorldChanges = mutableListOf<WorldChangeFunction>()

    /**
     * The number of transaction scopes that are currently open.
     */
    private var transactionDepth = 0

    /**
     * Scopes a transaction.
     * At the end of the scope, if it is the last one, the transaction is commited.
     */
    private fun <R> worldTransaction(block: () -> R): R {
        return synchronized(this) {
            ++transactionDepth
            val result = block()
            --transactionDepth
            if (transactionDepth == 0) {
                commitWorldChange()
            }
            result
        }
    }

    /**
     * Queues the given predetermined world change for the next world update.
     */
    private fun addWorldChange(change: WorldChange): Unit = synchronized(this) {
        pendingWorldChanges.add { change }
    }

    /**
     * Queues the given world change function for the next world update.
     */
    private fun addWorldChange(changeFunction: WorldChangeFunction): Unit = synchronized(this) {
        pendingWorldChanges.add(changeFunction)
    }

    /**
     * Applies the pending world change.
     */
    private fun commitWorldChange(): Unit = synchronized(this) {
        var state = world.get()
        var worldChange = WorldChange()

        // Note that it is possible to add pending changes within a change function.
        while (pendingWorldChanges.isNotEmpty()) {
            val pendingChangeFunctions = pendingWorldChanges.toList()
            pendingWorldChanges.clear()
            pendingChangeFunctions.forEach { pendingChangeFunction: WorldChangeFunction ->
                val pendingChange = pendingChangeFunction(state)
                state = state.updated(pendingChange)
                worldChange = worldChange.mergedWith(pendingChange)
            }
        }

        if (!worldChange.isEmpty())
            Timber.d("Human extractor publishes world change: $worldChange")

        world.update(worldChange)
        worldChange.objects.removed.forEach {
            worldData.remove(it)
        }
        if (BuildConfig.DEBUG) {
            checkHumansMatchWorld()
        }
    }

    /**
     * Generates a unique PDDL human.
     */
    private fun generateUniqueHuman(): PDDLHuman {
        fun extractIndex(name: String): Int {
            return name.substring(name.lastIndexOf("_") + 1).toInt()
        }

        val humanIndexes = knownHumans.map { extractIndex(it.pddl.name) }.toSet()
        val lowestFreeIndex = if (humanIndexes.isEmpty()) 1
        else (1..humanIndexes.size + 1).find { i ->
            i !in humanIndexes
        }
            ?: error("no free index available (current indexes: $humanIndexes)") // theoretically impossible
        return PDDLHuman("human_$lowestFreeIndex")
    }

    companion object {

        private const val NOF_MS_PER_S = 1000
        private const val NOF_NS_PER_MS = 1000000
        private const val NOF_NS_PER_S = NOF_MS_PER_S * NOF_NS_PER_MS
        const val VISIBLE_TIMEOUT_MS = 1000L
        const val VISIBLE_TIMEOUT_NS = VISIBLE_TIMEOUT_MS * NOF_NS_PER_MS
        const val TOUCH_ENGAGEMENT_TIMEOUT_MS = 30000L
        const val TOUCH_ENGAGEMENT_TIMEOUT_NS = TOUCH_ENGAGEMENT_TIMEOUT_MS * NOF_NS_PER_MS
        const val TOUCH_ONCE_SEEN_ENGAGEMENT_TIMEOUT_NS = 5000L * NOF_NS_PER_MS
        const val SPEECH_ENGAGEMENT_TIMEOUT_MS = 30000L
        const val SPEECH_ENGAGEMENT_TIMEOUT_NS = SPEECH_ENGAGEMENT_TIMEOUT_MS * NOF_NS_PER_MS
        const val SPEECH_ONCE_SEEN_ENGAGEMENT_TIMEOUT_NS = 5000L * NOF_NS_PER_MS
        const val IS_IN_PLAN_TIMEOUT_MS = 0L
        const val IS_IN_PLAN_TIMEOUT_NS = IS_IN_PLAN_TIMEOUT_MS * NOF_NS_PER_MS

        const val QISDK_HUMAN_DATA_KEY = "qisdk.human"

        /**
         * The usual human parameter.
         */
        private val h = PDDLHuman("?h")

        /**
         * Returns the facts describing the engagement of a human.
         */
        fun createHumanEngagesFacts(h: PDDLHuman): Set<Fact> {
            return setOf(
                is_interested(h),
                engages(h, self),
                can_be_engaged(h)
            )
        }

        /**
         * Transforms a function that accepts a recall of previous value
         * into a function that only receives the next value.
         */
        fun <T> withRecall(function: (T, com.aldebaran.qi.Optional<T>) -> Unit): (T) -> Unit {
            var recall = com.aldebaran.qi.Optional.empty<T>()
            return {
                function(it, recall)
                recall = com.aldebaran.qi.Optional.of(it)
            }
        }
    }
}

/**
 * Helper for quickly getting the Qi SDK Human associated to the given PDDL Human.
 */
fun WorldData.getQiHuman(h: PDDLHuman): QiHuman? = get(h, QISDK_HUMAN_DATA_KEY)