package com.nutomic.syncthingandroid.service

import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.model.SyncthingJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RestApiTest {

    private fun json(s: String): JsonObject = SyncthingJson.parseToJsonElement(s).jsonObject

    @Test
    fun mergeKeepsFieldsUnknownToTheModel() {
        val base = json(
            """{
                "ldap": {"address": "ldap.example"},
                "options": {"stunServers": ["stun.example:3478"], "listenAddresses": ["default"]},
                "gui": {"authMode": "ldap", "user": "old"}
            }"""
        )
        val update = json(
            """{
                "options": {"listenAddresses": ["tcp://0.0.0.0:22000"]},
                "gui": {"user": "new"}
            }"""
        )

        val merged = RestApi.mergeConfigJson(base, update)

        assertEquals(
            json(
                """{
                    "ldap": {"address": "ldap.example"},
                    "options": {"stunServers": ["stun.example:3478"], "listenAddresses": ["tcp://0.0.0.0:22000"]},
                    "gui": {"authMode": "ldap", "user": "new"}
                }"""
            ),
            merged,
        )
    }

    @Test
    fun mergeMatchesFoldersAndDevicesById() {
        val base = json(
            """{
                "folders": [
                    {"id": "a", "label": "old", "xattrFilter": {"maxTotalSize": 1024},
                     "devices": [{"deviceID": "D1", "futureField": true}]},
                    {"id": "removed", "label": "x"}
                ],
                "devices": [{"deviceID": "D1", "name": "phone", "maxRequestKiB": 512}]
            }"""
        )
        val update = json(
            """{
                "folders": [
                    {"id": "new", "label": "added"},
                    {"id": "a", "label": "renamed", "devices": [{"deviceID": "D1", "introducedBy": ""}]}
                ],
                "devices": [{"deviceID": "D1", "name": "tablet"}]
            }"""
        )

        val merged = RestApi.mergeConfigJson(base, update)

        assertEquals(
            json(
                """{
                    "folders": [
                        {"id": "new", "label": "added"},
                        {"id": "a", "label": "renamed", "xattrFilter": {"maxTotalSize": 1024},
                         "devices": [{"deviceID": "D1", "futureField": true, "introducedBy": ""}]}
                    ],
                    "devices": [{"deviceID": "D1", "name": "tablet", "maxRequestKiB": 512}]
                }"""
            ),
            merged,
        )
    }

    @Test
    fun mergeWithoutBaseReturnsUpdate() {
        val update = json("""{"version": 52}""")
        assertEquals(update, RestApi.mergeConfigJson(null, update))
    }

    @Test
    fun xmlNamedFieldsAreNotSentAsJson() {
        val options = SyncthingJson.encodeToJsonElement(Options()).jsonObject
        val gui = SyncthingJson.encodeToJsonElement(Gui()).jsonObject
        assertFalse("stunServer" in options)
        assertFalse("featureFlag" in options)
        assertFalse("unackedNotificationID" in options)
        assertFalse("insecureSkipHostCheck" in gui)
    }
}
