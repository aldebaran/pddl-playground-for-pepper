package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.geometry.TransformTime
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.QiObjectImpl
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.advertiseMethod

abstract class FakeFrame : QiObjectImpl() {

    abstract fun computeTransform(other: Frame): TransformTime

    override fun advertise(objectBuilder: DynamicObjectBuilder): DynamicObjectBuilder {
        objectBuilder.advertiseMethod(this, FakeFrame::computeTransform)
        return objectBuilder
    }
}
