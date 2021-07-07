package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.QiObjectImpl
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.advertiseMethod

abstract class FakeActuation : QiObjectImpl() {

    abstract fun robotFrame(): Frame

    override fun advertise(objectBuilder: DynamicObjectBuilder): DynamicObjectBuilder {
        objectBuilder.advertiseMethod(this, FakeActuation::robotFrame)
        return objectBuilder
    }
}
