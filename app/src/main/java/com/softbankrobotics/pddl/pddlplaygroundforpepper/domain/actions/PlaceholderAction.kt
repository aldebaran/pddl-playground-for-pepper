package com.softbankrobotics.pddl.pddlplaygroundforpepper.domain.actions

import android.content.Context
import com.softbankrobotics.pddl.pddlplaygroundforpepper.PlannableAction
import com.softbankrobotics.pddl.pddlplaygroundforpepper.WorldChange
import com.softbankrobotics.pddl.pddlplaygroundforpepper.common.createView
import com.softbankrobotics.pddl.pddlplaygroundforpepper.effectToWorldChange
import com.softbankrobotics.pddlplanning.Action
import com.softbankrobotics.pddlplanning.Instance
import com.softbankrobotics.pddl.pddlplaygroundforpepper.R
import kotlinx.android.synthetic.main.view_fake.view.*
import kotlinx.coroutines.delay

class PlaceholderAction(pddl: Action, localizedContext: Context) :
    PlannableAction(
        pddl,
        createView(R.layout.placeholder_action, localizedContext).apply { title.text = pddl.name }
    ) {
    override suspend fun runSuspend(vararg args: Instance): WorldChange {
        emittableStarted.emit(Unit)
        delay(2000)
        return effectToWorldChange(pddl, args)
    }
}
