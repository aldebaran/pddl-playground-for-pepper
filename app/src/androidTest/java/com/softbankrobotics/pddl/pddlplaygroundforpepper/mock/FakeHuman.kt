package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.Property
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.human.EngagementIntentionState
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.QiObjectImpl
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.advertiseProperty

abstract class FakeHuman : QiObjectImpl() {

    val headFrame = Property(Frame::class.java)
    val engagementIntention = Property(EngagementIntentionState::class.java)

    override fun advertise(objectBuilder: DynamicObjectBuilder): DynamicObjectBuilder {
        objectBuilder.advertiseProperty(this, FakeHuman::headFrame)
        objectBuilder.advertiseProperty(this, FakeHuman::engagementIntention)
        return objectBuilder
    }

}