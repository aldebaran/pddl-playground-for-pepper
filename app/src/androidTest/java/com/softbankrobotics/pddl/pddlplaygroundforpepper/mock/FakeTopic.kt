package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.Property
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.QiObjectImpl
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.advertiseProperty

class FakeTopic: QiObjectImpl()  {

    val name = Property(String::class.java)
    val bookmarks = Property(Map::class.java)
    val content = Property(String::class.java)

    override fun advertise(objectBuilder: DynamicObjectBuilder): DynamicObjectBuilder {
        objectBuilder.advertiseProperty(this, FakeTopic::name)
        objectBuilder.advertiseProperty(this, FakeTopic::bookmarks)
        objectBuilder.advertiseProperty(this, FakeTopic::content)
        return objectBuilder
    }
}