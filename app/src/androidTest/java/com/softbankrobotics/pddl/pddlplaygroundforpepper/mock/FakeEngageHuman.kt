package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.QiObjectImpl
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.advertiseMethod

abstract class FakeEngageHuman : QiObjectImpl() {

    val p = Promise<Void>()

    open fun run(): Future<Void> {
        asAnyObject().post("started")
        asAnyObject().post("humanIsEngaged")
        return p.future
    }

    override fun advertise(objectBuilder: DynamicObjectBuilder): DynamicObjectBuilder {
        objectBuilder.advertiseMethod(this, FakeEngageHuman::run)
        objectBuilder.advertiseSignal("started::(v)")
        objectBuilder.advertiseSignal("humanIsEngaged::(v)")
        objectBuilder.advertiseSignal("humanIsDisengaging::(v)")
        return objectBuilder
    }
}