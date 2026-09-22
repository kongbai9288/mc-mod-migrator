package com.kongbai.modmigrator

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object Json {

    fun parse(text: String): JsonElement? =
        try {
            JsonParser.parseString(text)
        } catch (t: Throwable) {
            null
        }

    fun obj(text: String): JsonObject? {
        val e = parse(text)
        return if (e != null && e.isJsonObject) e.asJsonObject else null
    }

    fun arr(text: String): JsonArray? {
        val e = parse(text)
        return if (e != null && e.isJsonArray) e.asJsonArray else null
    }

    fun s(e: JsonElement?, key: String, def: String = ""): String {
        if (e == null || !e.isJsonObject) return def
        val v = e.asJsonObject.get(key) ?: return def
        return if (v.isJsonPrimitive) v.asString else def
    }

    fun i(e: JsonElement?, key: String, def: Int = 0): Int {
        if (e == null || !e.isJsonObject) return def
        val v = e.asJsonObject.get(key) ?: return def
        return if (v.isJsonPrimitive)
            try {
                v.asInt
            } catch (t: Throwable) {
                def
            } else def
    }

    fun b(e: JsonElement?, key: String, def: Boolean = false): Boolean {
        if (e == null || !e.isJsonObject) return def
        val v = e.asJsonObject.get(key) ?: return def
        return if (v.isJsonPrimitive)
            try {
                v.asBoolean
            } catch (t: Throwable) {
                def
            } else def
    }

    fun l(e: JsonElement?, key: String, def: Long = 0L): Long {
        if (e == null || !e.isJsonObject) return def
        val v = e.asJsonObject.get(key) ?: return def
        return if (v.isJsonPrimitive)
            try {
                v.asLong
            } catch (t: Throwable) {
                def
            } else def
    }

    fun a(e: JsonElement?, key: String): JsonArray? {
        if (e == null || !e.isJsonObject) return null
        val v = e.asJsonObject.get(key) ?: return null
        return if (v.isJsonArray) v.asJsonArray else null
    }
}
