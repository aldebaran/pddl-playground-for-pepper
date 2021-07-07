package com.softbankrobotics.pddl.pddlplaygroundforpepper.mock

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.aldebaran.qi.AnyObject
import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.Property
import com.aldebaran.qi.QiService
import com.aldebaran.qi.sdk.`object`.context.RobotContext
import com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk.qiObjectCast

/**
 * KLUDGE: make an instance to keep alive the properties, because
 * DynamicObjectBuilder.advertiseProperty(name, property) does not.
 */
internal class ContextKeepAlive : QiService() {
    val focus = Property(AnyObject::class.java)
    val identity = Property(String::class.java)

    @Suppress("unused")
    fun kludge() {
    }
}

/**
 * Produce a RobotContext object, the right way (no RPC involved).
 */
fun makeRobotContext(): RobotContext {
    val contextImpl = ContextKeepAlive()
    val objectBuilder = DynamicObjectBuilder()
    objectBuilder.advertiseMethod("kludge::v()", contextImpl, "Don't look, I'm a kludge!")
    objectBuilder.advertiseProperty("focus", contextImpl.focus)
    objectBuilder.advertiseProperty("identity", contextImpl.identity)
    return qiObjectCast(objectBuilder.`object`())
}

/**
 * Produce a RobotContext object, the right way (no RPC involved).
 */
@SuppressLint("HardwareIds")
fun makeRobotContextWithIdentity(context: Context): RobotContext {

    val contextImpl = ContextKeepAlive()
    val objectBuilder = DynamicObjectBuilder()
    objectBuilder.advertiseMethod("kludge::v()", contextImpl, "Don't look, I'm a kludge!")
    objectBuilder.advertiseProperty("focus", contextImpl.focus)
    objectBuilder.advertiseProperty("identity", contextImpl.identity)
    val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    val packageId = context.packageName
    contextImpl.identity.setValue("$deviceId:$packageId")
    return qiObjectCast(objectBuilder.`object`())
}
