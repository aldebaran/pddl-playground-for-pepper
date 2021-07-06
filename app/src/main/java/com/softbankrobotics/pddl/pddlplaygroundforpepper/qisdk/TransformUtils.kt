package com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk

import com.aldebaran.qi.sdk.`object`.geometry.Transform
import kotlin.math.acos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Checks if a transform is inside an arc with a given radius and angle.
 */
fun isInArc(transform: Transform, radius: Double, angle: Double): Boolean {
    val t = transform.translation
    // We are interested in humans that are somewhat close to us.
    val d = sqrt(t.x.pow(2) + t.y.pow(2))
    return if (d < radius) {
        // We are interested by humans that are somewhat facing us.
        val theta = acos(t.x / d)
        theta < angle
    } else false
}