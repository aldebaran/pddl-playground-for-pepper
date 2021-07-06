package com.softbankrobotics.pddl.pddlplaygroundforpepper.qisdk

import com.aldebaran.qi.AnyObject
import com.aldebaran.qi.DynamicObjectBuilder
import com.aldebaran.qi.Property
import com.aldebaran.qi.QiService
import com.aldebaran.qi.serialization.SignatureUtilities
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaMethod

/**
 * Extension of QiService to grant access to the underlying AnyObject.
 */
abstract class QiObjectImpl: QiService() {
    /**
     * Advertises the public members of the Qi Object this implements.
     * @param objectBuilder The object builder on which to advertise members (methods, signals, properties).
     * @return The object builder after advertisement is complete.
     */
    abstract fun advertise(objectBuilder: DynamicObjectBuilder): DynamicObjectBuilder

    /**
     * KLUDGE: QiService hides its associated AnyObject for no good reason, let's show it up.
     */
    fun asAnyObject(): AnyObject {
        if (self == null) {
            val dynamicObjectBuilder = DynamicObjectBuilder()
            advertise(dynamicObjectBuilder)
            self = dynamicObjectBuilder.`object`()
        }
        return self
    }
}

/** Checks that the character at the given position is '('. */
private fun assertOpenParenthesis(pddl: String, position: Int) {
    assert(pddl[position] == '(') {
        "parenthesis location $position does not point at a parenthesis, instead points at ${pddl[position]}"
    }
}
/** Find first child expression. */
private fun firstChildExpression(pddl: String, position: Int): Int? {
    pddl.substring(position).forEachIndexed { i, c ->
        when (c) {
            '(' -> if (i != 0) return position + i
            ')' -> return null
        }
    }
    throw RuntimeException("malformed PDDL: parenthesis opened at $position is never closed")
}

private fun expressionRangeAt(pddl: String, position: Int): IntRange {
    assertOpenParenthesis(pddl, position)
    var depth = 0
    pddl.substring(position).forEachIndexed { i, c ->
        when (c) {
            '(' -> ++depth
            ')' -> if (--depth == 0) return IntRange(position, position + i)
        }
    }
    throw RuntimeException("malformed PDDL: parenthesis opened at $position is never closed")
}

fun DynamicObjectBuilder.advertiseMethod(obj: QiService, method: KFunction<*>) {
    var signature = SignatureUtilities.computeSignatureForMethod(method.javaMethod)

    // KLUDGE: libQi has a bug on return types with imbricated structs, we must flatten the struct.
    // The signature may look like: functionName::o(o).
    // With a struct as a return type, it may look like: functionName::(o)(o).
    // For imbricated structs, it looks like: functionName::((o)(o))(o).
    val firstParenthesis = firstChildExpression(signature, 0)!!
    val range = expressionRangeAt(signature, firstParenthesis)
    if (range.last < signature.length) {
        // This is the signature of the return type, not of the parameters.
        val childParenthesis = firstChildExpression(signature, range.first)
        if (childParenthesis != null) {
            // This is an imbricated struct, replace it with a trivial struct.
            signature = signature.replaceRange(range, "(i)")
        }
    }

    this.advertiseMethod(signature, obj, "")
}

fun DynamicObjectBuilder.advertiseMethodWithoutLibqiKludge(obj: QiService, method: KFunction<*>) {
    val signature = SignatureUtilities.computeSignatureForMethod(method.javaMethod)
    this.advertiseMethod(signature, obj, "")
}

fun <T> DynamicObjectBuilder.advertiseProperty(obj: QiService, property: KProperty<Property<T>>) {
    this.advertiseProperty(property.name, property.getter.call(obj))
}
