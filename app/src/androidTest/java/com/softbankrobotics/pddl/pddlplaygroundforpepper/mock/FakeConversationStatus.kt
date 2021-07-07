package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.Property
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.QiObjectImpl
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.advertiseProperty

abstract class FakeConversationStatus : QiObjectImpl() {

    val heard = Property(Frame::class.java)

    override fun advertise(objectBuilder: DynamicObjectBuilder): DynamicObjectBuilder {
        objectBuilder.advertiseProperty(this, FakeConversationStatus::heard)
        return objectBuilder
    }
}