package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.sdk.`object`.context.RobotContext
import com.aldebaran.qi.sdk.`object`.conversation.ConversationStatus
import com.aldebaran.qi.sdk.`object`.conversation.QiChatbot
import com.aldebaran.qi.sdk.`object`.conversation.Topic
import com.aldebaran.qi.sdk.`object`.locale.Locale
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.QiObjectImpl
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.advertiseMethod

abstract class FakeConversation : QiObjectImpl() {

    abstract fun status(context: RobotContext): ConversationStatus
    abstract fun makeTopic(qiChat: String): Topic
    abstract fun makeQiChatbot(
        context: RobotContext,
        topics: List<Topic>,
        locale: Locale
    ): QiChatbot

    override fun advertise(objectBuilder: DynamicObjectBuilder): DynamicObjectBuilder {
        objectBuilder.advertiseMethod(this, FakeConversation::status)
        objectBuilder.advertiseMethod(this, FakeConversation::makeTopic)
        objectBuilder.advertiseMethod(this, FakeConversation::makeQiChatbot)
        return objectBuilder
    }
}