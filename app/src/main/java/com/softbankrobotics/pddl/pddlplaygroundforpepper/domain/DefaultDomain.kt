package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain

import com.softbankrobotics.pddl.pddlplaygroundforpepper.Domain

/**
 * This domain includes every type, constant, predicate or action we declared.
 */
val defaultDomain = Domain(
    typesIndex.values.toSet(),
    constantsIndex.values.toSet(),
    predicatesIndex.values.toSet(),
    actionsIndex.values.map { it.pddl }.toSet()
)