package com.galaxywatch.home;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WatchCatalogTest {
    private static final String REVISION = "a".repeat(64);
    private static final String SERIAL = "Controller01";
    private static final String POWER = "/devices/relay/controls/K1";
    private static final String LEVEL = "/devices/dimmer/controls/Level";
    private static final String RGB = "/devices/dimmer/controls/RGB";

    @Test public void roundTripsValidatedCatalogWithStableOrderAndIdentity() throws Exception {
        WatchCatalog catalog = parse(layout(REVISION, SERIAL));
        assertEquals(catalog.json, WatchCatalog.parse(catalog.json).json);
        assertTrue(catalog.matchesSerial("controller01"));
        assertEquals("Кухня", catalog.rooms.get(0).label);
        assertEquals(POWER, catalog.lights.get("lamp_1").power);
        assertNull(catalog.allOff);
    }

    @Test public void wrongControllerOnAnotherPrivateNetworkCannotReuseCatalog() throws Exception {
        assertEquals("tcp://192.168.9.2:1883", MqttRoom.brokerUri("192.168.9.2"));
        assertThrows(IllegalArgumentException.class, () -> MqttRoom.brokerUri("8.8.8.8:1883"));
        WatchCatalog pinned = parse(layout(REVISION, SERIAL));
        WatchCatalog other = parse(layout(REVISION, "Controller02"));
        assertThrows(CatalogLink.WrongController.class, () -> CatalogLink.verify(other, pinned.controller, pinned));
    }

    @Test public void unchangedRevisionCannotCarryDifferentCommandTopics() throws Exception {
        WatchCatalog pinned = parse(layout(REVISION, SERIAL));
        JSONObject edited = layout(REVISION, SERIAL);
        light(edited).put("power", "/devices/other/controls/K1");
        WatchCatalog different = parse(edited);
        assertThrows(CatalogLink.Stale.class, () -> CatalogLink.verify(different, pinned.controller, pinned));
        assertThrows(CatalogLink.Stale.class, () -> CatalogLink.parseState(state("b".repeat(64), true), pinned));
    }

    @Test public void invalidTopicsDuplicatesAndSizeAreRejected() throws Exception {
        for (String invalid : new String[] {POWER + "/on", "/devices/+/controls/K1", MqttRoom.SERIAL, CatalogMqtt.REVISION}) {
            JSONObject edited = layout(REVISION, SERIAL);
            light(edited).put("power", invalid);
            assertThrows(JSONException.class, () -> parse(edited));
        }
        JSONObject duplicate = layout(REVISION, SERIAL).put("vacation", POWER);
        assertThrows(JSONException.class, () -> parse(duplicate));
        assertThrows(JSONException.class, () -> WatchCatalog.parse(duplicate + " ".repeat(WatchCatalog.MAX_BYTES)));
        assertThrows(JSONException.class, () -> WatchCatalog.parse(layout(REVISION, SERIAL) + " false"));
    }

    @Test public void unknownAndMistypedStatesNeverBecomeOff() throws Exception {
        WatchCatalog catalog = parse(layout(REVISION, SERIAL));
        JSONObject raw = state(REVISION, "0");
        raw.getJSONObject("lights").getJSONObject("lamp_1").put("brightness", 101).put("color", "255;256;0");
        CatalogLink.State parsed = CatalogLink.parseState(raw, catalog);
        CatalogLink.LightState lamp = parsed.lights.get("lamp_1");
        assertNull(lamp.power);
        assertNull(lamp.brightness);
        assertNull(lamp.color);
        assertThrows(CatalogLink.UnknownState.class, () -> CatalogLink.requireKnown(parsed,
            Map.of("lamp_1", CatalogLink.Change.power(true))));
    }

    @Test public void commandUsesIdsAndOneFieldWithConfirmedState() throws Exception {
        WatchCatalog catalog = parse(layout(REVISION, SERIAL));
        CatalogLink.Change turnOff = CatalogLink.Change.power(false);
        Map<String, CatalogLink.Change> change = Map.of("lamp_1", turnOff);
        assertTrue(CatalogLink.validChanges(catalog, change));
        assertFalse(CatalogLink.validChanges(catalog, Map.of("unknown", turnOff)));
        assertFalse(CatalogLink.validChanges(catalog, Map.of("lamp_1", CatalogLink.Change.brightness(101))));
        assertFalse(CatalogLink.validChanges(catalog, Map.of("lamp_1", CatalogLink.Change.color("1;2;999"))));
        JSONObject body = CatalogLink.commandJson(REVISION, change, false);
        assertEquals(REVISION, body.getString("revision"));
        JSONObject content = body.getJSONObject("changes").getJSONObject("lamp_1");
        assertEquals(1, content.length());
        assertFalse(content.getBoolean("power"));
        assertTrue(CatalogLink.commandJson(REVISION, change, true).getBoolean("allOff"));
        CatalogLink.State on = CatalogLink.parseState(state(REVISION, true), catalog);
        CatalogLink.requireKnown(on, change);
        assertFalse(CatalogLink.matches(on, change));
        assertTrue(CatalogLink.matches(CatalogLink.parseState(state(REVISION, false), catalog), change));
        JSONObject away = state(REVISION, true).put("vacation", true);
        assertThrows(CatalogLink.Vacation.class, () -> CatalogLink.requireKnown(CatalogLink.parseState(away, catalog), change));
    }

    @Test public void mqttLevelsAndColorsAreBoundedAndNormalized() {
        assertEquals(Integer.valueOf(35), WatchCatalog.mqttBrightness("35.0"));
        assertNull(WatchCatalog.mqttBrightness("101.0"));
        assertEquals("0;127;255", WatchCatalog.color("000;127;255"));
        assertNull(WatchCatalog.color("0;127;256"));
    }

    @Test public void cloudProofRequiresKnownVacationAndPowerUnlessCatalogIsEmpty() throws Exception {
        WatchCatalog catalog = parse(layout(REVISION, SERIAL));
        assertTrue(CatalogLink.readyForControl(catalog, CatalogLink.parseState(state(REVISION, false), catalog)));
        assertFalse(CatalogLink.readyForControl(catalog, CatalogLink.parseState(state(REVISION, "0"), catalog)));
        assertFalse(CatalogLink.readyForControl(catalog,
            CatalogLink.parseState(state(REVISION, true).put("vacation", JSONObject.NULL), catalog)));
        WatchCatalog empty = parse(layout(REVISION, SERIAL).put("rooms", new JSONArray()));
        JSONObject emptyState = state(REVISION, false).put("lights", new JSONObject());
        assertTrue(CatalogLink.readyForControl(empty, CatalogLink.parseState(emptyState, empty)));
        assertThrows(JSONException.class, () -> CatalogLink.parseState(state(REVISION, false), empty));
    }

    private static WatchCatalog parse(JSONObject json) throws JSONException { return WatchCatalog.parse(json.toString()); }

    private static JSONObject layout(String revision, String serial) throws JSONException {
        JSONObject light = new JSONObject().put("id", "lamp_1").put("label", "Основной").put("theme", "plain")
            .put("power", POWER).put("brightness", LEVEL).put("color", RGB);
        JSONObject room = new JSONObject().put("id", "kitchen").put("label", "Кухня").put("theme", "kitchen")
            .put("lights", new JSONArray().put(light));
        return new JSONObject().put("schema", 1).put("revision", revision).put("controller", serial)
            .put("rooms", new JSONArray().put(room));
    }

    private static JSONObject light(JSONObject layout) throws JSONException {
        return layout.getJSONArray("rooms").getJSONObject(0).getJSONArray("lights").getJSONObject(0);
    }

    private static JSONObject state(String revision, Object power) throws JSONException {
        return new JSONObject().put("revision", revision).put("vacation", false)
            .put("lights", new JSONObject().put("lamp_1", new JSONObject().put("power", power)));
    }
}
