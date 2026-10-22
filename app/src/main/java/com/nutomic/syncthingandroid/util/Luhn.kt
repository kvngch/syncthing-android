package com.nutomic.syncthingandroid.util

class Luhn {
    /**
     * The check character of [s], made of characters of the base 32 alphabet; null if a
     * character is not in it. This is not the actual Luhn algorithm: see
     * https://forum.syncthing.net/t/v0-9-0-new-node-id-format/478/6
     */
    fun generate(s: ByteArray): String? {
        var factor = 1
        var sum = 0
        val n = LUHN_ALPHABET.length
        for (byte in s) {
            val codepoint = LUHN_ALPHABET.indexOf(byte.toInt().toChar())
            if (codepoint == -1) {
                return null
            }
            var addend = factor * codepoint
            factor = if (factor == 2) 1 else 2
            addend = addend / n + addend % n
            sum += addend
        }
        val checkCodepoint = (n - sum % n) % n
        return LUHN_ALPHABET[checkCodepoint].toString()
    }

    private companion object {
        /**
         * The digits of base 32.
         */
        const val LUHN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}
