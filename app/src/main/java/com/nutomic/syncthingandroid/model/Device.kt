package com.nutomic.syncthingandroid.model

import com.nutomic.syncthingandroid.util.Luhn
import java.util.Locale

class Device {
    @JvmField var deviceID: String? = null
    @JvmField var name: String = ""
    @JvmField var addresses: List<String>? = null
    @JvmField var allowedNetworks: List<String>? = null
    @JvmField var compression: String = "metadata"
    @JvmField var certName: String? = null
    @JvmField var introducedBy: String = ""
    @JvmField var introducer: Boolean = false
    @JvmField var paused: Boolean = false
    @JvmField var ignoredFolders: MutableList<IgnoredFolder>? = null
    @JvmField var autoAcceptFolders: Boolean = false
    @JvmField var maxRecvKbps: Int? = 0
    @JvmField var maxSendKbps: Int? = 0

    // Since v1.12.0
    // See https://github.com/syncthing/syncthing/pull/7055
    @JvmField var untrusted: Boolean = false

    // Since v1.25.0
    @JvmField var numConnections: Int? = 0

    /**
     * The device name, or the first characters of the ID if the name is empty.
     */
    val displayName: String
        get() = if (name.isNullOrEmpty()) deviceID.orEmpty().take(7) else name

    /**
     * Whether the device ID is well formed, the way Syncthing reads it: case, separators and
     * the digits that look like letters do not matter, and the optional check characters must
     * match. See https://github.com/syncthing/syncthing/blob/main/lib/protocol/deviceid.go
     */
    fun checkDeviceID(): Boolean {
        var id = (deviceID ?: return false)
            .replace("=", "")
            .uppercase(Locale.ROOT)
            .replace('1', 'I')
            .replace('0', 'O')
            .replace('8', 'B')
            .replace("-", "")
            .replace(" ", "")
        when (id.length) {
            56 -> {
                // Four groups of 13 characters, each followed by its Luhn check character.
                val stripped = StringBuilder()
                for (i in 0 until 4) {
                    val group = id.substring(i * 14, i * 14 + 13)
                    val check = Luhn().generate(group.toByteArray()) ?: return false
                    if (id.substring(i * 14 + 13, i * 14 + 14) != check) {
                        return false
                    }
                    stripped.append(group)
                }
                id = stripped.toString()
            }
            52 -> Unit
            else -> return false
        }
        // 52 characters of the base 32 alphabet.
        return id.all { it in 'A'..'Z' || it in '2'..'7' }
    }

    /**
     * Whether each address is "dynamic" or a well formed tcp, quic or relay URL. It catches the
     * most common mistakes, not all of them.
     * See https://docs.syncthing.net/users/config.html#device-element
     */
    fun checkDeviceAddresses(): Boolean = addresses?.all { checkDeviceAddress(it) } ?: false

    companion object {
        private val supportedProtocols = Regex("(tcp[46]?|relay|quic[46]?)://.*")

        internal fun checkDeviceAddress(address: String): Boolean {
            if (address == "dynamic") {
                return true
            }
            if (!supportedProtocols.matches(address)) {
                return false
            }
            // Protocol, then host and port. A protocol given twice ("tcp://tcp://") splits in three.
            val parts = splitDroppingTrailingEmpty(address, "://")
            if (parts.size != 2) {
                return false
            }
            val (protocol, rest) = parts
            return checkHostAndPort(rest, lastPart = !protocol.startsWith("relay"))
        }

        /**
         * Rejects an empty host or a port outside 1..65535. A part ending in "]" closes an IPv6
         * address rather than giving a port.
         *
         * @param lastPart reads the port after the last colon (tcp, quic) or after the first (relay).
         */
        private fun checkHostAndPort(address: String, lastPart: Boolean): Boolean {
            // "tcp://myserver:" has a colon and no port.
            if (address.endsWith(":")) {
                return false
            }
            val hostAndPort = splitDroppingTrailingEmpty(address, ":")
            if (hostAndPort.size <= 1) {
                return true
            }
            if (hostAndPort[0].isEmpty()) {
                return false
            }
            val potentialPort = (if (lastPart) hostAndPort.last() else hostAndPort[1]).substringBefore('/')
            if (potentialPort.endsWith("]")) {
                return true
            }
            return (potentialPort.toIntOrNull() ?: 0) in 1..65535
        }

        /**
         * String.split of Java, which drops the trailing empty strings: "tcp://" gives ["tcp"].
         */
        private fun splitDroppingTrailingEmpty(value: String, delimiter: String): List<String> =
            value.split(delimiter).dropLastWhile { it.isEmpty() }
    }
}
