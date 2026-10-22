package com.nutomic.syncthingandroid.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nutomic.syncthingandroid.model.Gui;
import com.nutomic.syncthingandroid.model.Options;

import org.junit.Test;

public class RestApiTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void mergeKeepsFieldsUnknownToTheModel() {
        JsonObject base = json("{"
                + "\"ldap\":{\"address\":\"ldap.example\"},"
                + "\"options\":{\"stunServers\":[\"stun.example:3478\"],\"listenAddresses\":[\"default\"]},"
                + "\"gui\":{\"authMode\":\"ldap\",\"user\":\"old\"}"
                + "}");
        JsonObject update = json("{"
                + "\"options\":{\"listenAddresses\":[\"tcp://0.0.0.0:22000\"]},"
                + "\"gui\":{\"user\":\"new\"}"
                + "}");

        JsonObject merged = RestApi.mergeConfigJson(base, update);

        assertEquals(json("{"
                + "\"ldap\":{\"address\":\"ldap.example\"},"
                + "\"options\":{\"stunServers\":[\"stun.example:3478\"],\"listenAddresses\":[\"tcp://0.0.0.0:22000\"]},"
                + "\"gui\":{\"authMode\":\"ldap\",\"user\":\"new\"}"
                + "}"), merged);
    }

    @Test
    public void mergeMatchesFoldersAndDevicesById() {
        JsonObject base = json("{"
                + "\"folders\":["
                + "  {\"id\":\"a\",\"label\":\"old\",\"xattrFilter\":{\"maxTotalSize\":1024},"
                + "   \"devices\":[{\"deviceID\":\"D1\",\"futureField\":true}]},"
                + "  {\"id\":\"removed\",\"label\":\"x\"}"
                + "],"
                + "\"devices\":[{\"deviceID\":\"D1\",\"name\":\"phone\",\"maxRequestKiB\":512}]"
                + "}");
        JsonObject update = json("{"
                + "\"folders\":["
                + "  {\"id\":\"new\",\"label\":\"added\"},"
                + "  {\"id\":\"a\",\"label\":\"renamed\",\"devices\":[{\"deviceID\":\"D1\",\"introducedBy\":\"\"}]}"
                + "],"
                + "\"devices\":[{\"deviceID\":\"D1\",\"name\":\"tablet\"}]"
                + "}");

        JsonObject merged = RestApi.mergeConfigJson(base, update);

        assertEquals(json("{"
                + "\"folders\":["
                + "  {\"id\":\"new\",\"label\":\"added\"},"
                + "  {\"id\":\"a\",\"label\":\"renamed\",\"xattrFilter\":{\"maxTotalSize\":1024},"
                + "   \"devices\":[{\"deviceID\":\"D1\",\"futureField\":true,\"introducedBy\":\"\"}]}"
                + "],"
                + "\"devices\":[{\"deviceID\":\"D1\",\"name\":\"tablet\",\"maxRequestKiB\":512}]"
                + "}"), merged);
    }

    @Test
    public void mergeWithoutBaseReturnsUpdate() {
        JsonObject update = json("{\"version\":52}");
        assertEquals(update, RestApi.mergeConfigJson(null, update));
    }

    @Test
    public void xmlNamedFieldsAreNotSentAsJson() {
        Gson gson = new Gson();
        JsonObject options = gson.toJsonTree(new Options()).getAsJsonObject();
        JsonObject gui = gson.toJsonTree(new Gui()).getAsJsonObject();
        assertFalse(options.has("stunServer"));
        assertFalse(options.has("featureFlag"));
        assertFalse(options.has("unackedNotificationID"));
        assertFalse(gui.has("insecureSkipHostCheck"));
    }
}
