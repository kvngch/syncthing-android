package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class Ignores {
    @JvmField var line: MutableList<String>? = null
}
