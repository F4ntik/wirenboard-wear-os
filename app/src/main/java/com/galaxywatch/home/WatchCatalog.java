package com.galaxywatch.home;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class WatchCatalog {
    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_LIGHTS = 128;
    private static final int MAX_ROOMS = 32;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,48}");
    private static final Pattern REVISION = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern SERIAL = Pattern.compile("[A-Za-z0-9]{6,32}");
    private static final Pattern TOPIC = Pattern.compile("/devices/[^/+#\\p{Cntrl}]+/controls/[^/+#\\p{Cntrl}]+");
    private static final Pattern RGB = Pattern.compile("[0-9]{1,3};[0-9]{1,3};[0-9]{1,3}");

    static final class Room {
        public final String id, label, theme;
        public final List<Light> lights;

        private Room(String id, String label, String theme, List<Light> lights) {
            this.id = id;
            this.label = label;
            this.theme = theme;
            this.lights = List.copyOf(lights);
        }
    }

    static final class Light {
        public final String id, label, theme, power, brightness, color;

        private Light(String id, String label, String theme, String power, String brightness, String color) {
            this.id = id;
            this.label = label;
            this.theme = theme;
            this.power = power;
            this.brightness = brightness;
            this.color = color;
        }
    }

    public final String revision, controller, vacation, allOff;
    public final List<Room> rooms;
    public final Map<String, Light> lights;
    final String json;
    private final String layoutKey;

    private WatchCatalog(String revision, String controller, List<Room> rooms, Map<String, Light> lights,
                         String vacation, String allOff, String json, String layoutKey) {
        this.revision = revision;
        this.controller = controller;
        this.rooms = List.copyOf(rooms);
        this.lights = Collections.unmodifiableMap(new LinkedHashMap<>(lights));
        this.vacation = vacation;
        this.allOff = allOff;
        this.json = json;
        this.layoutKey = layoutKey;
    }

    static WatchCatalog parse(String json) throws JSONException {
        JSONObject source = object(json);
        Object schema = source.opt("schema");
        String revision = matching(source, "revision", REVISION);
        String controller = matching(source, "controller", SERIAL);
        JSONArray inputRooms = source.getJSONArray("rooms");
        if (!(schema instanceof Integer) || ((Integer) schema) != 1 || inputRooms.length() > MAX_ROOMS) throw invalid();

        Set<String> roomIds = new HashSet<>();
        Set<String> topics = new HashSet<>();
        String vacation = topic(source, "vacation", topics, false);
        String allOff = topic(source, "allOff", topics, false);
        List<Room> rooms = new ArrayList<>();
        Map<String, Light> lights = new LinkedHashMap<>();
        JSONArray canonicalRooms = new JSONArray();
        for (int r = 0; r < inputRooms.length(); r++) {
            JSONObject room = inputRooms.getJSONObject(r);
            String id = matching(room, "id", ID);
            if (!roomIds.add(id)) throw invalid();
            JSONArray inputLights = room.getJSONArray("lights");
            if (inputLights.length() == 0 || inputLights.length() + lights.size() > MAX_LIGHTS) throw invalid();
            List<Light> roomLights = new ArrayList<>();
            JSONArray canonicalLights = new JSONArray();
            for (int l = 0; l < inputLights.length(); l++) {
                JSONObject item = inputLights.getJSONObject(l);
                Light light = new Light(matching(item, "id", ID), label(item), theme(item),
                    topic(item, "power", topics, true), topic(item, "brightness", topics, false),
                    topic(item, "color", topics, false));
                if (lights.put(light.id, light) != null) throw invalid();
                roomLights.add(light);
                canonicalLights.put(entry(light.id, light.label, light.theme).put("power", light.power)
                    .put("brightness", light.brightness).put("color", light.color));
            }
            Room parsed = new Room(id, label(room), theme(room), roomLights);
            rooms.add(parsed);
            canonicalRooms.put(entry(id, parsed.label, parsed.theme).put("lights", canonicalLights));
        }
        String canonical = new JSONObject().put("schema", 1).put("revision", revision)
            .put("controller", controller).put("rooms", canonicalRooms).put("vacation", vacation)
            .put("allOff", allOff).toString();
        return new WatchCatalog(revision, controller, rooms, lights, vacation, allOff, json, canonical);
    }

    static JSONObject object(String json) throws JSONException {
        if (json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
        JSONTokener parser = new JSONTokener(json);
        Object value = parser.nextValue();
        if (!(value instanceof JSONObject object) || parser.nextClean() != 0) throw invalid();
        return object;
    }

    boolean sameLayout(WatchCatalog other) { return other != null && layoutKey.equals(other.layoutKey); }

    boolean matchesSerial(String serial) {
        return validSerial(serial) && controller.equalsIgnoreCase(serial);
    }

    static boolean validSerial(String serial) {
        return serial != null && SERIAL.matcher(serial).matches();
    }

    static boolean validRevision(String revision) {
        return revision != null && REVISION.matcher(revision).matches();
    }

    static Integer brightness(Object value) {
        if (value instanceof Integer number && number >= 0 && number <= 100) return number;
        return null;
    }

    static Integer mqttBrightness(String value) {
        if (value == null || !value.matches("[0-9]{1,3}(?:\\.0{1,8})?")) return null;
        int dot = value.indexOf('.');
        return brightness(Integer.parseInt(dot < 0 ? value : value.substring(0, dot)));
    }

    static String color(Object value) {
        if (!(value instanceof String text) || !RGB.matcher(text).matches()) return null;
        String[] components = text.split(";");
        int red = Integer.parseInt(components[0]);
        int green = Integer.parseInt(components[1]);
        int blue = Integer.parseInt(components[2]);
        if (red > 255 || green > 255 || blue > 255) return null;
        return red + ";" + green + ";" + blue;
    }

    private static JSONObject entry(String id, String label, String theme) throws JSONException {
        return new JSONObject().put("id", id).put("label", label).put("theme", theme);
    }

    private static String label(JSONObject value) throws JSONException {
        String label = string(value, "label");
        if (label.trim().isEmpty() || label.codePointCount(0, label.length()) > 80
            || label.getBytes(StandardCharsets.UTF_8).length > 320 || label.chars().anyMatch(Character::isISOControl)) throw invalid();
        return label;
    }

    private static String theme(JSONObject value) throws JSONException {
        return matching(value, "theme", ID);
    }

    private static String topic(JSONObject value, String key, Set<String> topics, boolean required) throws JSONException {
        if (!required && !value.has(key)) return null;
        String topic = matching(value, key, TOPIC);
        if (topic.getBytes(StandardCharsets.UTF_8).length > 256 || topic.startsWith("/devices/wb-wear-light-gateway/")
            || MqttRoom.SERIAL.equals(topic) || !topics.add(topic)) throw invalid();
        return topic;
    }

    private static String matching(JSONObject value, String key, Pattern pattern) throws JSONException {
        String text = string(value, key);
        if (!pattern.matcher(text).matches()) throw invalid();
        return text;
    }

    private static String string(JSONObject value, String key) throws JSONException {
        Object text = value.opt(key);
        if (text instanceof String string) return string;
        throw invalid();
    }

    private static JSONException invalid() { return new JSONException("Invalid controller catalog"); }
}
