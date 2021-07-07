package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.sdk.`object`.conversation.Phrase
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.QiObjectImpl
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.advertiseMethod

abstract class FakeQiChatbot: QiObjectImpl() {
    abstract fun concept(name: String): List<Phrase>

    override fun advertise(objectBuilder: DynamicObjectBuilder): DynamicObjectBuilder {
        objectBuilder.advertiseMethod(this, FakeQiChatbot::concept)
        return objectBuilder
    }
}