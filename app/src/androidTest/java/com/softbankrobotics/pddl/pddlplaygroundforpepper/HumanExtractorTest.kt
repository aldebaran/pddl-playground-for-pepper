package com.softbankrobotics.pddl.pddlplaygroundforpepper

import com.aldebaran.qi.sdk.`object`.geometry.TransformTime
import com.aldebaran.qi.sdk.`object`.human.EngagementIntentionState
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.ObservableProperty
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.Signal
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.StoredProperty
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.*
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions.StartEngageAction
import com.softbankrobotics.pddl.pddlplaygroundforpepper.mock.FakeEngageHuman
import com.softbankrobotics.pddl.pddlplaygroundforpepper.mock.QiContextMockHelper
import com.softbankrobotics.pddl.pddlplaygroundforpepper.mock.QiContextMockHelper.Companion.createFakeHuman
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.MutableWorld
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.WorldData
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.WorldState
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.extractors.HumanExtractor
import com.softbankrobotics.pddl.pddlplaygroundforpepper.problem.extractors.getQiHuman
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.asAnyObject
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.qiObjectCast
import com.softbankrobotics.pddlplanning.*
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import com.aldebaran.qi.sdk.`object`.human.Human as QiHuman
import com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.Human as PDDLHuman

class HumanExtractorTest {

    @Test
    fun humanIsFoundAtStart() = runBlocking {

        // Set a human around.
        val (fakeHuman) = createFakeHuman()
        val qiHuman = qiObjectCast<QiHuman>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // Start the human extractor to see humans being acknowledged.
        val futureWorld = world.state.waitForNextValueAsync()
        humanExtractor.start()

        // The human is located but cannot be engaged yet.
        val world = futureWorld.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val pddlHuman = assertUniqueHuman(world, qiHuman)
        val facts = world.facts
        assertIn(knows_path(self, self, pddlHuman), facts)
        assertNotIn(can_be_engaged(pddlHuman), facts)
    }

    @Test
    fun humanInitialFacts() = runBlocking {

        // We use an alternate human extractor that also publishes initial facts for all humans.
        val h = PDDLHuman("?h")
        val initialFacts = setOf(
            feels(h, neutral)
        )
        humanExtractor = HumanExtractor(q.qiContext, world, data, touch, tasks, initialFacts)

        // Set a human around.
        val (fakeHuman) = createFakeHuman()
        val qiHuman = qiObjectCast<QiHuman>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // Start the human extractor to see humans being acknowledged.
        val futureWorld = world.state.waitForNextValueAsync()
        humanExtractor.start()

        // The human is located but cannot be engaged yet.
        val state = futureWorld.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val pddlHuman = assertUniqueHuman(state, qiHuman)
        val facts = state.facts
        assertIn(knows_path(self, self, pddlHuman), facts)
        assertNotIn(can_be_engaged(pddlHuman), facts)

        // Also check that the initial facts were published for this human.
        assertTrue(initialFacts.all { fact ->
            val applied = applyParameters(fact, mapOf(h to pddlHuman))
            evaluateExpression(applied, state)
        })
    }

    @Test
    fun humanAppears() = runBlocking {

        // Start the human extractor to see humans being acknowledged.
        val futureWorld = world.state.waitForNextValueAsync()
        humanExtractor.start()

        // Set a human around.
        val (fakeHuman) = createFakeHuman()
        val qiHuman = qiObjectCast<QiHuman>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // The human is located but cannot be engaged yet.
        val world = futureWorld.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val pddlHuman = assertUniqueHuman(world, qiHuman)
        val facts = world.facts
        assertIn(knows_path(self, self, pddlHuman), facts)
        assertNotIn(can_be_engaged(pddlHuman), facts)
    }

    @Test
    fun interestedHumanCannotBeEngaged() = runBlocking {

        val (fakeHuman) = createFakeHuman()
        fakeHuman.engagementIntention.setValue(EngagementIntentionState.INTERESTED)
        val qiHuman = qiObjectCast<QiHuman>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // Start the human extractor to see humans being acknowledged.
        // Wait for a set of facts related to the interest of the human.
        val futureWorld = waitForStateWithHuman(qiHuman) { world, h ->
            evaluateExpression(
                and(knows_path(self, self, h), is_interested(h)),
                world
            )
        }
        humanExtractor.start()
        val world = futureWorld.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val pddlHuman = assertUniqueHuman(world, qiHuman)
        // The human is located but cannot be engaged yet.
        assertTrue(evaluateExpression(not(can_be_engaged(pddlHuman)), world))
    }

    @Test
    fun humanSeekingEngagementCannotBeEngaged() = runBlocking {

        val (fakeHuman) = createFakeHuman()
        fakeHuman.engagementIntention.setValue(EngagementIntentionState.SEEKING_ENGAGEMENT)
        val qiHuman = qiObjectCast<QiHuman>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // Start the human extractor to see humans being acknowledged.
        // Wait for a set of facts related to the interest of the human.
        val futureWorld = waitForStateWithHuman(qiHuman) { world, h ->
            evaluateExpression(
                and(knows_path(self, self, h), is_interested(h), engages(h, self)),
                world
            )
        }
        humanExtractor.start()
        val world = futureWorld.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val pddlHuman = assertUniqueHuman(world, qiHuman)
        // The human is located but cannot be engaged yet.
        assertTrue(evaluateExpression(not(can_be_engaged(pddlHuman)), world))
    }

    @Test
    fun touchWhenNobodyCreatesAnEngagedHuman() = runBlocking {
        humanExtractor.start()
        val futureState = world.state.waitForNextValueAsync()
        touch.emit(Unit)
        val state = futureState.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val user = assertUniqueHuman(state.objects, null)
        assertTrue(
            evaluateExpression(
                and(
                    is_interested(user),
                    can_be_engaged(user),
                    engages(user, self)
                ),
                state
            )
        )
    }

    @Test
    fun touchWhenHumansCanBeEngagedAssociatesToOneOfThem() = runBlocking {
        // Set 2 humans seeking engagement inside ZOI.
        val (fakeHuman1, fakeFrame1) = createFakeHuman()
        val (fakeHuman2, fakeFrame2) = createFakeHuman()
        fakeHuman1.engagementIntention.setValue(EngagementIntentionState.SEEKING_ENGAGEMENT)
        every { fakeFrame1.computeTransform(any()) } returns
                TransformTime(TransformBuilder().from2DTranslation(1.0, 0.2), 0)
        fakeHuman2.engagementIntention.setValue(EngagementIntentionState.SEEKING_ENGAGEMENT)
        every { fakeFrame2.computeTransform(any()) } returns
                TransformTime(TransformBuilder().from2DTranslation(1.0, -0.2), 0)
        val qiHuman1 = qiObjectCast<QiHuman>(fakeHuman1)
        val qiHuman2 = qiObjectCast<QiHuman>(fakeHuman2)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman1, qiHuman2))

        // Starting the extractor.
        val initializingState = world.state.waitUntilAsync { state ->
            Timber.d("World changed: $state")
            val h1 = assertUniqueHuman(state, qiHuman1)
            val h2 = assertUniqueHuman(state, qiHuman2)
            evaluateExpression(
                and(is_interested(h1), can_be_engaged(h1), is_interested(h2), can_be_engaged(h2)),
                state
            )
        }
        humanExtractor.start()
        initializingState.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        touch.emit(Unit)
        delay(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val stateThen = world.state.get()
        val pddlHumans = stateThen.objects.filter { it.type == PDDLHuman.type }
        assertEquals(2, pddlHumans.size)
    }

    @Test
    fun alreadyEngagedHumanIsPreferred() = runBlocking {
        val futureStateWithPreferredHuman = world.state.waitUntilAsync { world ->
            Timber.d("World changed: $world")
            world.facts.any { it.word == PREFERRED_TO_BE_ENGAGED }
        }
        humanExtractor.start()

        // Set a human in front of the robot within ZOI.
        val (fakeHuman1, fakeFrame1) = createFakeHuman()
        every { fakeFrame1.computeTransform(any()) } returns
                TransformTime(TransformBuilder().from2DTranslation(1.0, 0.2), 0)
        val qiHuman1 = qiObjectCast<QiHuman>(fakeHuman1)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman1))

        // Human Extractor reacts and produces a human that is preferred to be engaged.
        val worldWithPreferredHuman =
            futureStateWithPreferredHuman.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val pddlHuman = assertUniqueHuman(worldWithPreferredHuman, qiHuman1)
        assertIn(preferred_to_be_engaged(pddlHuman), worldWithPreferredHuman.facts)
        // Engage this human
        world.ensureFact(engages(self, pddlHuman))

        // Set another human in front of the robot within ZOI.
        val (fakeHuman2, fakeFrame2) = createFakeHuman()
        every { fakeFrame2.computeTransform(any()) } returns
                TransformTime(TransformBuilder().from2DTranslation(0.5, -0.2), 0)
        val qiHuman2 = qiObjectCast<QiHuman>(fakeHuman2)
        // Wait for human to be acknowledged.
        val futureState = world.state.waitForNextValueAsync()
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman1, qiHuman2))
        val state = futureState.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)

        // First human should continue being the preferred one although the other one is better
        // positioned.
        val pddlHuman2 = assertUniqueHuman(state, qiHuman2)
        assertIn(preferred_to_be_engaged(pddlHuman), state.facts)
        assertNotIn(preferred_to_be_engaged(pddlHuman2), state.facts)
    }

    @Test
    fun blinkingHumanIsTheSame() = runBlocking {

        // Set an interested human one meter in front of us.
        val (fakeHuman, fakeFrame) = createFakeHuman()
        every { fakeFrame.computeTransform(any()) } returns
                TransformTime(TransformBuilder().fromXTranslation(1.0), 0)
        fakeHuman.engagementIntention.setValue(EngagementIntentionState.INTERESTED)
        val qiHuman = qiObjectCast<QiHuman>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // Starting the extractor.
        val initializingState = world.state.waitUntilAsync { world ->
            Timber.d("World changed:\n$world")
            world.facts.any { it.word == PREFERRED_TO_BE_ENGAGED }
        }
        humanExtractor.start()
        val initialWorld = initializingState.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val initialHuman = assertUniqueHuman(initialWorld, qiHuman)

        // Human disappears: it's still considered for engagement.
        q.fakeHumanAwareness.humansAround.setValue(listOf())
        delay(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val stateThen = world.state.get()
        assertTrue(
            evaluateExpression(
                preferred_to_be_engaged(initialHuman),
                stateThen
            )
        )

        // Human re-appears: it is assumed to be the previous one.
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))
        delay(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val stateFinally = world.state.get()
        assertEquals(2, stateFinally.objects.size)
        assertTrue(
            evaluateExpression(
                preferred_to_be_engaged(initialHuman),
                stateFinally
            )
        )
    }

    @Test
    fun toleranceToBlinkingInterest() = runBlocking {

        // Set an interested human one meter in front of us.
        val (fakeHuman, fakeFrame) = createFakeHuman()
        every { fakeFrame.computeTransform(any()) } returns
                TransformTime(TransformBuilder().fromXTranslation(1.0), 0)
        fakeHuman.engagementIntention.setValue(EngagementIntentionState.INTERESTED)
        val qiHuman = qiObjectCast<QiHuman>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // Starting the extractor.
        val initializingState = world.state.waitUntilAsync { world ->
            Timber.d("World changed: $world")
            world.facts.any { it.word == PREFERRED_TO_BE_ENGAGED }
        }
        humanExtractor.start()
        val initialWorld = initializingState.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val initialHuman = assertUniqueHuman(initialWorld, qiHuman)

        // Human does not seem interested anymore.
        fakeHuman.engagementIntention.setValue(EngagementIntentionState.NOT_INTERESTED)
        delay(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val stateThen = world.state.get()
        assertTrue(
            evaluateExpression(
                preferred_to_be_engaged(initialHuman),
                stateThen
            )
        )

        // Human is again interested.
        fakeHuman.engagementIntention.setValue(EngagementIntentionState.INTERESTED)
        delay(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val stateFinally = world.state.get()
        assertEquals(2, stateFinally.objects.size)
        assertTrue(
            evaluateExpression(
                preferred_to_be_engaged(initialHuman),
                stateFinally
            )
        )
    }

    @Test
    fun touchAfterDisengagement() = runBlocking {

        // Set an interested human one meter in front of us.
        val (fakeHuman, fakeFrame) = createFakeHuman()
        every { fakeFrame.computeTransform(any()) } returns
                TransformTime(TransformBuilder().fromXTranslation(1.0), 0)
        fakeHuman.engagementIntention.setValue(EngagementIntentionState.INTERESTED)
        val qiHuman = qiObjectCast<QiHuman>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // Starting the extractor.
        val initializingState = world.state.waitUntilAsync { state ->
            Timber.d("World changed: $state")
            val h = assertUniqueHuman(state, qiHuman)
            evaluateExpression(
                and(is_interested(h), can_be_engaged(h), knows_path(self, self, h)),
                state
            )
        }
        humanExtractor.start()
        val initialWorld = initializingState.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val initialHuman = assertUniqueHuman(initialWorld, qiHuman)

        // Human is disengaged, the human is renewed.
        val duringEngage = world.state.waitUntilAsync { state ->
            Timber.d("World changed: $state")
            val h = assertUniqueHuman(state, qiHuman)
            evaluateExpression(
                and(
                    is_interested(h),
                    can_be_engaged(h),
                    knows_path(self, self, h),
                    engages(self, h)
                ),
                state
            )
        }
        world.ensureFact(engages(self, initialHuman))
        duringEngage.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)

        world.removeFact(engages(self, initialHuman))
        delay(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val stateAfterEngage = world.state.get()
        val newHuman = assertUniqueHuman(stateAfterEngage, qiHuman)

        // A touch is detected.
        val futureState = world.state.waitForNextValueAsync()
        touch.emit(Unit)
        val stateAfterTouch = futureState.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val finalHuman = assertUniqueHuman(stateAfterTouch, qiHuman)
        assertEquals(newHuman, finalHuman)
        assertTrue(
            evaluateExpression(
                and(
                    is_interested(finalHuman),
                    can_be_engaged(finalHuman),
                    knows_path(self, self, finalHuman)
                ),
                stateAfterTouch
            )
        )
    }

    @Test
    fun humanInCriticalPathNotDisengaged() = runBlocking {
        // Set an interested human one meter in front of us.
        val (fakeHuman, fakeFrame) = createFakeHuman()
        every { fakeFrame.computeTransform(any()) } returns
                TransformTime(TransformBuilder().fromXTranslation(1.0), 0)
        fakeHuman.engagementIntention.setValue(EngagementIntentionState.INTERESTED)
        val qiHuman = qiObjectCast<QiHuman>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // Starting the extractor.
        val initializingWorld = waitForStateWithHuman(qiHuman) { state, h ->
            evaluateExpression(
                and(is_interested(h), can_be_engaged(h), knows_path(self, self, h)),
                state
            )
        }

        val mockActionName = "mock_action"
        humanExtractor.actionsKeepingHuman = setOf(mockActionName)
        humanExtractor.start()
        Timber.d("Waiting for initial world...")
        val initialState = initializingWorld.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val initialHuman = assertUniqueHuman(initialState, qiHuman)
        mutableTasks.set(
            listOf(
                Task.create(mockActionName, initialHuman.name)
            )
        )

        // Human disappears: after some time the user is sill here because the is a check in in the plan.
        q.fakeHumanAwareness.humansAround.setValue(listOf())
        delay(HumanExtractor.VISIBLE_TIMEOUT_MS + USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val stateThen = world.state.get()
        assertTrue(stateThen.objects.any { it.type == PDDLHuman.type })
    }


    @Test
    fun lostHumanIsNotFoundAgain() = runBlocking {
        // Set an interested human one meter in front of us.
        val (fakeHuman, fakeFrame) = createFakeHuman()
        every { fakeFrame.computeTransform(any()) } returns
                TransformTime(TransformBuilder().fromXTranslation(1.0), 0)
        fakeHuman.engagementIntention.setValue(EngagementIntentionState.INTERESTED)
        val qiHuman = qiObjectCast<QiHuman>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // Starting the extractor.
        val initializingWorld = waitForStateWithHuman(qiHuman) { state, h ->
            evaluateExpression(
                and(is_interested(h), can_be_engaged(h), knows_path(self, self, h)),
                state
            )
        }
        humanExtractor.start()
        val initialWorld = initializingWorld.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        assertUniqueHuman(initialWorld, qiHuman)

        // Human disappears: after some time the user is confirmed to be away.
        q.fakeHumanAwareness.humansAround.setValue(listOf())
        delay(HumanExtractor.VISIBLE_TIMEOUT_MS + USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val stateThen = world.state.get()
        assertTrue(stateThen.objects.none { it.type == PDDLHuman.type })

        // A touch is detected.
        touch.emit(Unit)
        delay(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val stateFinally = world.state.get()
        val finalHuman = findHuman(stateFinally, null)!!

        assertTrue(
            evaluateExpression(
                and(
                    is_interested(finalHuman),
                    can_be_engaged(finalHuman),
                    not(knows_path(self, self, finalHuman))
                ),
                stateFinally
            )
        )
    }

    @Test
    fun disengagingHumanIsDisengaged() = runBlocking {
        // Set an interested human one meter in front of us.
        val (fakeHuman, fakeFrame) = createFakeHuman()
        every { fakeFrame.computeTransform(any()) } returns
                TransformTime(TransformBuilder().fromXTranslation(1.0), 0)
        fakeHuman.engagementIntention.setValue(EngagementIntentionState.INTERESTED)
        val qiHuman = qiObjectCast<com.aldebaran.qi.sdk.`object`.human.Human>(fakeHuman)
        q.fakeHumanAwareness.humansAround.setValue(listOf(qiHuman))

        // Create a fake EngageHuman action.
        val fakeEngageHuman = spyk<FakeEngageHuman>()
        every { q.fakeHumanAwareness.makeEngageHuman(any(), any()) } returns qiObjectCast(
            fakeEngageHuman
        )

        // Starting the extractor.
        val initializingWorld = waitForStateWithHuman(qiHuman) { world, h ->
            evaluateExpression(
                and(is_interested(h), can_be_engaged(h), knows_path(self, self, h)),
                world
            )
        }
        humanExtractor.start()
        val initialWorld = initializingWorld.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val initialHuman = assertUniqueHuman(initialWorld, qiHuman)

        // Start the StartEngageAction with the fake EngageHuman action.
        val startEngageAction =
            StartEngageAction.createAction(StartEngageAction, q.qiContext, world, data)
        val worldChange = startEngageAction.runWithDebugLogs(initialHuman)
        world.update(worldChange)

        // This delay is necessary to ensure that the listener of humanIsDisengaging signal is set
        // before the signal is triggered.
        delay(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        val stateThen = world.state.get()
        assertIn(engages(self, initialHuman), stateThen.facts)

        // Human shows signs of disengagement.
        val futureState = world.state.waitUntilAsync { world ->
            world.facts.any { it.word == IS_DISENGAGING }
        }
        fakeEngageHuman.asAnyObject().post("humanIsDisengaging")
        val worldAfter = futureState.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        assertIn(is_disengaging(initialHuman), worldAfter.facts)

        val finalWorld = waitForStateWithHuman(qiHuman) { world, h ->
            evaluateExpression(
                and(not(is_interested(h)), is_disengaging(h)),
                world
            )
        }
        finalWorld.await(USUAL_FUTURE_TIMEOUT_MS_IN_TESTS)
        Unit
    }

    private lateinit var q: QiContextMockHelper
    private lateinit var touch: Signal<Unit>
    private val mutableTasks = StoredProperty<Tasks?>(null)
    private val tasks = mutableTasks as ObservableProperty<Tasks?>
    private lateinit var world: MutableWorld
    private lateinit var data: WorldData
    private lateinit var humanExtractor: HumanExtractor

    @Before
    fun setupUnit() {

        q = QiContextMockHelper()

        // Create a human extractor, using a mock touch observable.
        touch = Signal()
        world = spyk(MutableWorld())
        data = WorldData()
        humanExtractor = HumanExtractor(q.qiContext, world, data, touch, tasks)
    }

    @After
    fun tearDownUnit() {
        mutableTasks.set(null)
        humanExtractor.stop()
        humanExtractor.actionsKeepingHuman = setOf()
    }

    /**
     * Asserts that the future world contains a PDDL human associated to the given Qi human,
     * and returns it.
     */
    private fun findHuman(
        world: WorldState,
        qiHuman: QiHuman?
    ): PDDLHuman? {
        val anyHuman = qiHuman?.let { asAnyObject(qiHuman) }
        val matches = world.objects.filter {
            it.type == PDDLHuman.type && data.getQiHuman(it as PDDLHuman) == qiHuman
        }

        return when {
            matches.isEmpty() -> {
                null
            }
            matches.size == 1 -> {
                val h = matches.first()
                h as PDDLHuman
            }
            else -> {
                throw RuntimeException(
                    "Several Qi object matches several PDDL objects: " + matches.joinToString(", ")
                )
            }
        }
    }

    /**
     * Asserts that the world contains a PDDL human associated to the given Qi human,
     * and returns it.
     */
    private fun assertUniqueHuman(
        state: WorldState,
        qiHuman: QiHuman?
    ): PDDLHuman {
        val pddlHuman = findHuman(state, qiHuman)
        assertNotNull(pddlHuman)
        return pddlHuman!!
    }

    /**
     * Asserts that the future set of objects is associated contains
     * a PDDL human associated to the given Qi human,
     * and returns it.
     */
    private fun assertUniqueHuman(
        knownObjects: Set<Instance>,
        qiHuman: QiHuman?
    ): PDDLHuman {
        val knownHumans = knownObjects.filter { it.type == PDDLHuman.type }
        val pddlHuman = knownHumans.first() as PDDLHuman
        assertEquals(1, knownHumans.size)
        if (qiHuman == null)
            assertNull(data.getQiHuman(pddlHuman))
        else
            assertEquals(qiHuman, data.getQiHuman(pddlHuman))
        return pddlHuman
    }

    /**
     * Shortcut to wait for a certain condition about a human.
     */
    private fun waitForStateWithHuman(
        qiHuman: QiHuman,
        predicate: (WorldState, PDDLHuman) -> Boolean
    ): Deferred<WorldState> {
        return world.state.waitUntilAsync { state ->
            Timber.d("World changed: $state")
            val h = findHuman(state, qiHuman)
            if (h == null) {
                false
            } else {
                predicate(state, h)
            }
        }
    }
}
