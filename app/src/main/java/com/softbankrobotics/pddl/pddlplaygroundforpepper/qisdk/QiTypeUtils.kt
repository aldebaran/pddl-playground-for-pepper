package com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk

import com.aldebaran.qi.AnyObject
import com.aldebaran.qi.sdk.`object`.AnyObjectWrapperConverter
import com.aldebaran.qi.sdk.`object`.actuation.ActuationConverter
import com.aldebaran.qi.sdk.`object`.autonomousabilities.AutonomousabilitiesConverter
import com.aldebaran.qi.sdk.`object`.camera.CameraConverter
import com.aldebaran.qi.sdk.`object`.context.ContextConverter
import com.aldebaran.qi.sdk.`object`.conversation.ConversationConverter
import com.aldebaran.qi.sdk.`object`.focus.FocusConverter
import com.aldebaran.qi.sdk.`object`.human.HumanConverter
import com.aldebaran.qi.sdk.`object`.humanawareness.HumanawarenessConverter
import com.aldebaran.qi.sdk.`object`.image.ImageConverter
import com.aldebaran.qi.sdk.`object`.knowledge.KnowledgeConverter
import com.aldebaran.qi.sdk.`object`.power.PowerConverter
import com.aldebaran.qi.sdk.`object`.streamablebuffer.StreamablebufferConverter
import com.aldebaran.qi.sdk.`object`.touch.TouchConverter
import com.aldebaran.qi.sdk.serialization.EnumConverter
import com.aldebaran.qi.serialization.QiSerializer


/**
 * Initialize Qi SDK converters and set them to the default serializer.
 */
private val defaultSerializer by lazy {
    // KLUDGE: have the Qi SDK expose this initialization
    val qiSerializer = QiSerializer.getDefault()

    qiSerializer.addConverter(EnumConverter())
    qiSerializer.addConverter(AnyObjectWrapperConverter())
    qiSerializer.addConverter(ContextConverter())
    qiSerializer.addConverter(FocusConverter())

    qiSerializer.addConverter(ActuationConverter())
    qiSerializer.addConverter(AutonomousabilitiesConverter())
    qiSerializer.addConverter(ConversationConverter())
    qiSerializer.addConverter(HumanConverter())
    qiSerializer.addConverter(TouchConverter())
    qiSerializer.addConverter(KnowledgeConverter())
    qiSerializer.addConverter(HumanawarenessConverter())
    qiSerializer.addConverter(CameraConverter())
    qiSerializer.addConverter(ImageConverter())
    qiSerializer.addConverter(PowerConverter())
    qiSerializer.addConverter(StreamablebufferConverter())

    qiSerializer
}

/**
 * Cast an AnyObject to a specialized object type.
 */
fun <T> qiObjectCast(obj: AnyObject, clazz: Class<T>): T {
    val deserialized = defaultSerializer.deserialize(obj, clazz)
    return clazz.cast(deserialized)!!
}

/**
 * Cast an AnyObject to a specialized object type.
 */
inline fun <reified T> qiObjectCast(obj: AnyObject): T {
    return qiObjectCast(obj, T::class.java)
}

/**
 * Cast an AnyObject to a specialized object type.
 */
inline fun <reified T> qiObjectCast(obj: QiObjectImpl): T {
    return qiObjectCast(obj.asAnyObject(), T::class.java)
}

/**
 * Cast a specialized proxy back to its underlying AnyObject.
 */
fun <T> asAnyObject(obj: T): AnyObject {
    return QiSerializer.getDefault().serialize(obj) as AnyObject
}

/**
 * Cast an AnyObject to a specialized object type.
 */
fun <T> qiValueCast(value: Any, clazz: Class<T>): T {
    val deserialized = QiSerializer.getDefault().deserialize(value, clazz)
    return clazz.cast(deserialized)!!
}

/**
 * Cast an AnyObject to a specialized object type.
 */
inline fun <reified T> qiValueCast(value: Any): T {
    return qiValueCast(value, T::class.java)
}
