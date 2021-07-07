package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.context.RobotContext
import com.aldebaran.qi.sdk.`object`.geometry.TransformTime
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.qiObjectCast
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk

class QiContextMockHelper {

    val qiContext: QiContext = mockk()
    val fakeRobotContext: RobotContext = makeRobotContext()
    val fakeRobotFrame: FakeFrame
    val fakeActuation: FakeActuation
    val fakeHumanAwareness: FakeHumanAwareness
    val fakeConversationStatus: FakeConversationStatus
    val fakeConversation: FakeConversation

    init {

        // Prepare the Qi Context
        every { qiContext.robotContext } returns fakeRobotContext

        // Actuation
        fakeRobotFrame =
            createFakeFrame()
        fakeActuation = spyk()
        every { fakeActuation.robotFrame() } returns qiObjectCast(fakeRobotFrame)
        every { qiContext.actuation } returns qiObjectCast(fakeActuation)
        every { qiContext.actuationAsync } returns Future.of(qiObjectCast(fakeActuation))

        // HumanAwareness
        fakeHumanAwareness = spyk()
        every { qiContext.humanAwareness } returns qiObjectCast(fakeHumanAwareness)
        every { qiContext.humanAwarenessAsync } returns Future.of(qiObjectCast(fakeHumanAwareness))

        // Conversation
        fakeConversationStatus = spyk()
        fakeConversation = spyk()
        every { fakeConversation.status(any()) } returns qiObjectCast(fakeConversationStatus)
        every { fakeConversation.makeTopic(any()) } returns qiObjectCast(spyk<FakeTopic>())
        every { fakeConversation.makeQiChatbot(any(), any(), any()) } returns qiObjectCast(spyk<FakeQiChatbot>())
        every { qiContext.conversation } returns qiObjectCast(fakeConversation)
        every { qiContext.conversationAsync } returns Future.of(qiContext.conversation)
    }

    companion object {

        fun createFakeFrame(): FakeFrame {
            val fakeFrame = spyk<FakeFrame>()
            every { fakeFrame.computeTransform(any()) } returns
                    TransformTime(TransformBuilder().fromXTranslation(0.0), 0)
            return fakeFrame
        }

        /**
         * Creates a fake human with a fake head frame.
         */
        fun createFakeHuman(): Pair<FakeHuman, FakeFrame> {
            val headFrame =
                createFakeFrame()
            val human = spyk<FakeHuman>()
            human.headFrame.setValue(qiObjectCast(headFrame))
            return Pair(human, headFrame)
        }
    }
}