package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain

import com.softbankrobotics.pddlplanning.Instance
import com.softbankrobotics.pddlplanning.utils.Index
import com.softbankrobotics.pddlplanning.utils.indexOf

val self = AgentivePhysicalObject("self")

/**
 * All the well-known constants.
 */
val constantIndex: Index<Instance> by lazy {
    indexOf(
        self
    )
}