package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain

import com.softbankrobotics.pddlplanning.Instance
import com.softbankrobotics.pddlplanning.utils.Index
import com.softbankrobotics.pddlplanning.utils.indexOf

val self = AgentivePhysicalObject("self")

/*
 * Emotions
 */
val happy = Emotion("happy")
val neutral = Emotion("neutral")
val sad = Emotion("sad")

/**
 * The index of all declared constants.
 */
val constantsIndex: Index<Instance> by lazy {
    indexOf(
        self,
        happy, neutral, sad
    )
}