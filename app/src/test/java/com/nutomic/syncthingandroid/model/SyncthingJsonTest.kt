package com.nutomic.syncthingandroid.model

import com.nutomic.syncthingandroid.service.RestApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncthingJsonTest {

    private val raw: JsonObject = SyncthingJson.parseToJsonElement(CONFIG).jsonObject
    private val config: Config = SyncthingJson.decodeFromJsonElement(raw)

    @Test
    fun configFieldsAreRead() {
        val folder = config.folders!!.single()
        assertEquals("Photos", folder.label)
        assertEquals("~/Photos", folder.path)
        assertEquals(10f, folder.fsWatcherDelayS)
        assertEquals("staggered", folder.versioning!!.type)
        assertEquals("31536000", folder.versioning!!.params["maxAge"])
        assertEquals(3, folder.minDiskFree!!.value.toInt())
        assertNotNull(folder.getDevice(DEVICE_ID))

        val device = config.devices!!.single()
        assertEquals("phone", device.name)
        assertEquals(listOf("dynamic", "tcp://192.168.1.2:22000"), device.addresses)
        assertEquals("Ignored", device.ignoredFolders!!.single().label)
        assertEquals(512, device.maxRecvKbps)

        assertTrue(config.gui!!.useTLS)
        assertEquals("apikey", config.gui!!.apiKey)
        assertEquals(-1, config.options!!.urAccepted)
        assertEquals(listOf("default"), config.options!!.listenAddresses!!.toList())
        assertEquals("phone-old", config.remoteIgnoredDevices!!.single().name)
    }

    @Test
    fun configSentBackKeepsFieldsUnknownToTheModel() {
        config.folders!!.single().label = "Pictures"
        val merged = RestApi.mergeConfigJson(raw, SyncthingJson.encodeToJsonElement(config).jsonObject)

        val folder = merged.getValue("folders").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("Pictures"), folder["label"])
        assertEquals(raw.getValue("folders").jsonArray.single().jsonObject["xattrFilter"], folder["xattrFilter"])
        assertEquals(raw["ldap"], merged["ldap"])
        assertEquals(JsonPrimitive("ldap"), merged.getValue("gui").jsonObject["authMode"])
        assertEquals(raw.getValue("options").jsonObject["stunServers"], merged.getValue("options").jsonObject["stunServers"])
        // Fields of the XML only are not sent, their JSON counterparts stay.
        assertFalse("stunServer" in merged.getValue("options").jsonObject)
    }

    @Test
    fun nullForANonNullablePropertyGivesItsDefault() {
        val device = SyncthingJson.decodeFromString<Device>(
            """{"deviceID": "$DEVICE_ID", "name": null, "addresses": null, "maxSendKbps": null, "compression": null}"""
        )
        assertEquals("", device.name)
        assertEquals("metadata", device.compression)
        assertNull(device.addresses)
        assertNull(device.maxSendKbps)
    }

    @Test
    fun eventDataIsKeptAsJson() {
        val event = SyncthingJson.decodeFromString<Event>(
            """{"id": 12, "globalID": 34, "time": "2024-01-01T00:00:00Z", "type": "ItemFinished",
                "data": {"item": "a.txt", "folder": "abcd", "error": null, "action": "update", "items": 3}}"""
        )
        assertEquals(12, event.id)
        assertEquals("ItemFinished", event.type)
        assertEquals("a.txt", event.data!!.getValue("item").jsonPrimitive.content)
        assertEquals(JsonNull, event.data!!["error"])
        assertEquals(3.0, event.data!!.getValue("items").jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun deepCopyIsIndependent() {
        val folder = config.folders!!.single()
        val copy = folder.deepCopy()
        copy.label = "Copy"
        copy.versioning!!.params["maxAge"] = "1"
        assertEquals("Photos", folder.label)
        assertEquals("31536000", folder.versioning!!.params["maxAge"])
        assertEquals(folder.sharedWithDevices.single().deviceID, copy.sharedWithDevices.single().deviceID)
    }

    private companion object {
        const val DEVICE_ID = "P56IOI7-MZJNU2Y-IQGDREY-DM2MGTI-MGL3BXN-PQ6W5BM-TBBZ4TJ-XZWICQ2"

        /**
         * A config as Syncthing's /rest/system/config returns it, shortened, with fields the model
         * does not know.
         */
        val CONFIG = """
            {
              "version": 37,
              "folders": [{
                "id": "abcd-1234", "label": "Photos", "filesystemType": "basic", "path": "~/Photos",
                "type": "sendreceive",
                "devices": [{"deviceID": "$DEVICE_ID", "introducedBy": "", "encryptionPassword": ""}],
                "rescanIntervalS": 3600, "fsWatcherEnabled": true, "fsWatcherDelayS": 10,
                "ignorePerms": false, "autoNormalize": true,
                "minDiskFree": {"value": 3, "unit": "%"},
                "versioning": {"type": "staggered", "params": {"maxAge": "31536000", "cleanInterval": "3600"},
                               "cleanupIntervalS": 3600, "fsPath": "", "fsType": "basic"},
                "copiers": 0, "pullerMaxPendingKiB": 0, "hashers": 0, "order": "random",
                "ignoreDelete": false, "scanProgressIntervalS": 0, "pullerPauseS": 0, "maxConflicts": 10,
                "disableSparseFiles": false, "disableTempIndexes": false, "paused": false,
                "weakHashThresholdPct": 25, "markerName": ".stfolder", "copyOwnershipFromParent": false,
                "modTimeWindowS": 0, "maxConcurrentWrites": 2, "disableFsync": false,
                "blockPullOrder": "standard", "copyRangeMethod": "standard", "caseSensitiveFS": false,
                "junctionsAsDirs": false, "syncOwnership": false, "sendOwnership": false,
                "syncXattrs": false, "sendXattrs": false,
                "xattrFilter": {"entries": [], "maxSingleEntrySize": 1024, "maxTotalSize": 4096}
              }],
              "devices": [{
                "deviceID": "$DEVICE_ID", "name": "phone", "addresses": ["dynamic", "tcp://192.168.1.2:22000"],
                "compression": "metadata", "certName": "", "introducer": false,
                "skipIntroductionRemovals": false, "introducedBy": "", "paused": false,
                "allowedNetworks": [], "autoAcceptFolders": false, "maxSendKbps": 0, "maxRecvKbps": 512,
                "ignoredFolders": [{"time": "2024-01-01T00:00:00Z", "id": "ign-1", "label": "Ignored"}],
                "maxRequestKiB": 0, "untrusted": false, "remoteGUIPort": 0, "numConnections": 0
              }],
              "gui": {
                "enabled": true, "address": "127.0.0.1:8384", "unixSocketPermissions": "",
                "user": "syncthing", "password": "hash", "authMode": "ldap", "useTLS": true,
                "apiKey": "apikey", "insecureAdminAccess": false, "theme": "default", "debugging": false,
                "insecureSkipHostcheck": false, "insecureAllowFrameLoading": false,
                "sendBasicAuthPrompt": false
              },
              "ldap": {"address": "ldap.example:389", "bindDN": "cn=%s", "transport": "plain"},
              "options": {
                "listenAddresses": ["default"], "globalAnnounceServers": ["default"],
                "globalAnnounceEnabled": true, "localAnnounceEnabled": true, "localAnnouncePort": 21027,
                "localAnnounceMCAddr": "[ff12::8384]:21027", "maxSendKbps": 0, "maxRecvKbps": 0,
                "reconnectionIntervalS": 60, "relaysEnabled": true, "relayReconnectIntervalM": 10,
                "startBrowser": false, "natEnabled": true, "natLeaseMinutes": 60, "natRenewalMinutes": 30,
                "natTimeoutSeconds": 10, "urAccepted": -1, "urSeen": 3, "urUniqueId": "",
                "urURL": "https://data.syncthing.net/newdata", "urPostInsecurely": false,
                "urInitialDelayS": 1800, "autoUpgradeIntervalH": 0, "upgradeToPreReleases": false,
                "keepTemporariesH": 24, "cacheIgnoredFiles": false, "progressUpdateIntervalS": 5,
                "limitBandwidthInLan": false, "minHomeDiskFree": {"value": 1, "unit": "%"},
                "releasesURL": "https://upgrades.syncthing.net/meta.json", "alwaysLocalNets": [],
                "overwriteRemoteDeviceNamesOnConnect": false, "tempIndexMinBlocks": 10,
                "unackedNotificationIDs": [], "trafficClass": 0, "setLowPriority": true,
                "maxFolderConcurrency": 0, "crURL": "https://crash.syncthing.net/newcrash",
                "crashReportingEnabled": true, "stunKeepaliveStartS": 180, "stunKeepaliveMinS": 20,
                "stunServers": ["default"], "maxConcurrentIncomingRequestKiB": 0,
                "announceLANAddresses": true, "sendFullIndexOnUpgrade": false, "featureFlags": [],
                "connectionLimitEnough": 0, "connectionLimitMax": 0, "insecureAllowOldTLSVersions": false
              },
              "remoteIgnoredDevices": [{"time": "2024-01-01T00:00:00Z", "deviceID": "$DEVICE_ID",
                                        "name": "phone-old", "address": "192.168.1.3:22000"}],
              "defaults": {"folder": {"id": "", "label": "", "path": "~"}, "ignores": {"lines": []}}
            }
        """.trimIndent()
    }
}
