package com.nutomic.syncthingandroid.model

class IgnoredFolder {
    @JvmField var id: String = ""
    @JvmField var label: String = ""
    @JvmField var time: String = ""

    /**
     * The folder label, or the first characters of the ID if the label is empty.
     */
    val displayLabel: String
        get() = if (label.isNullOrEmpty()) id.take(7) else label
}
