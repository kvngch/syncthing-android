package com.nutomic.syncthingandroid.util

import java.io.File

/**
 * Masks what identifies peers in a log before it is shared: full device IDs keep their first
 * group only, IPv4 addresses with a port keep their first two bytes, IPv6 addresses with a port
 * are hidden. Loopback and unspecified addresses are kept, they say nothing about the peers.
 */
object LogMask {
    private val deviceId = Regex("\\b([A-Z2-7]{7})(?:-[A-Z2-7]{7}){7}\\b")
    private val ipv4WithPort = Regex("\\b(\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}(:\\d{1,5})\\b")
    private val ipv6WithPort = Regex("\\[([0-9a-fA-F:.%]*:[0-9a-fA-F:.%]*)\\](:\\d{1,5})")

    @JvmStatic
    fun maskLine(line: String): String {
        val withoutIds = deviceId.replace(line, "\$1-XXXXXXX-...")
        val withoutIpv4 = ipv4WithPort.replace(withoutIds) { match ->
            val address = match.value
            if (address.startsWith("127.") || address.startsWith("0.0.0.0:")) {
                address
            } else {
                match.groupValues[1] + ".x.x" + match.groupValues[2]
            }
        }
        return ipv6WithPort.replace(withoutIpv4) { match ->
            val host = match.groupValues[1]
            if (host == "::1" || host == "::") match.value else "[x:x]" + match.groupValues[2]
        }
    }

    /**
     * Writes source to target line by line through [maskLine].
     */
    @JvmStatic
    fun maskFile(source: File, target: File) {
        source.bufferedReader().use { reader ->
            target.bufferedWriter().use { writer ->
                reader.forEachLine { line ->
                    writer.write(maskLine(line))
                    writer.write("\n")
                }
            }
        }
    }
}
