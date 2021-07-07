package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.Property
import com.aldebaran.qi.sdk.`object`.context.RobotContext
import com.aldebaran.qi.sdk.`object`.human.Human
import com.aldebaran.qi.sdk.`object`.humanawareness.EngageHuman
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.QiObjectImpl
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.advertiseMethod
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.advertiseProperty

abstract class FakeHumanAwareness: QiObjectImpl() {

    val humansAround = Property<List<Human>>(listOf())
    val recommendedHumanToApproach = Property(Human::class.java)
    val recommendedHumanToEngage = Property(Human::class.java)

    abstract fun makeEngageHuman(robotContext: RobotContext, human: Human): EngageHuman

    override fun advertise(objectBuilder: DynamicObjectBuilder): DynamicObjectBuilder {
        objectBuilder.advertiseProperty(this, FakeHumanAwareness::humansAround)
        objectBuilder.advertiseProperty(this, FakeHumanAwareness::recommendedHumanToApproach)
        objectBuilder.advertiseProperty(this, FakeHumanAwareness::recommendedHumanToEngage)
        objectBuilder.advertiseMethod(this, FakeHumanAwareness::makeEngageHuman)
        return objectBuilder
    }
}