package com.nutomic.syncthingandroid.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The JSON of Syncthing's REST API. Fields unknown to the model are ignored, a null received for a
 * non-nullable property gives its default, every field is written with its value, null ones left
 * out: the config sent back holds what the model knows.
 */
val SyncthingJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * A deep copy through JSON. Transient properties get their default.
 */
inline fun <reified T> T.deepCopy(): T = SyncthingJson.decodeFromJsonElement(SyncthingJson.encodeToJsonElement(this))
