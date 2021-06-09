package com.softbankrobotics.pddl.pddlplaygroundforpepper

import com.softbankrobotics.pddlplanning.Instance

class WorldData {
    /**
     * Actual data storage.
     */
    private val data = mutableMapOf<Instance, MutableMap<String, Any>>()

    /**
     * Retrieve all the data stored, in a read-only form.
     */
    fun getAll(): Map<Instance, Map<String, Any>> = data

    /**
     * Retrieve the data stored for a given PDDL object.
     */
    fun get(obj: Instance): Map<String, Any>? {
        return data[obj]
    }

    /**
     * Retrieve a single piece of data associated to a given PDDL object.
     */
    inline fun <reified T> get(obj: Instance, key: String): T? {
        val objData = get(obj)
        val objDatum = objData?.get(key)
        if (objDatum == null) return objDatum
        if (objDatum is T) return objDatum
        else throw RuntimeException("Data \"${key}\" for \"${obj}\" is not of type \"${T::class.java.name}\"")
    }

    /**
     * Associate a piece of data to a PDDL object.
     */
    fun <T> set(obj: Instance, key: String, datum: T) {
        val objData = data[obj] ?: data.getOrPut(obj) { mutableMapOf() }
        objData[key] = datum as Any
    }

    /**
     * Dissociate a piece of data to a PDDL object.
     */
    fun remove(obj: Instance, key: String) {
        data[obj]?.remove(key)
    }

    /**
     * Drop all data associated to a PDDL object.
     */
    fun remove(obj: Instance) {
        data.remove(obj)
    }
}

const val QI_OBJECT = "qiObject"