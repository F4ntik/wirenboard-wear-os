package com.galaxywatch.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Rooms {
    static final String EXTRA = "room";
    static final String TILE_HOME = "tile-home";
    static final String VACATION = "/devices/example_home/controls/VacationActive";
    static final String ALL_OFF = "/devices/example_home/controls/AllOff/on";

    record Light(String label, String relay, String channel, int artwork) {
        Light(String label, String relay, String channel) {
            this(label, relay, channel, 0);
        }

        String stateTopic() {
            return "/devices/wb-mr6c_" + relay + "/controls/" + channel;
        }

        String commandTopic() {
            return stateTopic() + "/on";
        }
    }

    record Room(String id, String label, List<Light> lights) {}

    static final List<Room> ALL = List.of(
        new Room("living", "Кухня-гостиная", List.of(
            new Light("В гостиной", "101", "K1", R.drawable.fixture_pendant),
            new Light("У дивана", "101", "K2", R.drawable.fixture_sconce),
            new Light("На кухне", "101", "K3", R.drawable.fixture_kitchen))),
        new Room("bedroom", "Спальня", List.of(
            new Light("Основной", "101", "K4", R.drawable.fixture_pendant),
            new Light("Гардероб", "101", "K5", R.drawable.fixture_wardrobe))),
        new Room("hall", "Коридор", List.of(
            new Light("Вход", "101", "K6", R.drawable.fixture_entry),
            new Light("Коридор", "102", "K1", R.drawable.fixture_track),
            new Light("Зеркало", "102", "K2", R.drawable.fixture_mirror),
            new Light("Душевая", "102", "K3", R.drawable.fixture_shower))),
        new Room("kids", "Детская", List.of(
            new Light("Основной", "102", "K5", R.drawable.fixture_pendant))),
        new Room("office", "Кабинет", List.of(
            new Light("Основной", "102", "K6", R.drawable.fixture_pendant)))
    );

    static final List<Light> MASTER_LIGHTS = masterLights();

    private static List<Light> masterLights() {
        List<Light> lights = new ArrayList<>();
        for (Room room : ALL) lights.addAll(room.lights());
        // Example of a group-only output included in the controller master command.
        lights.add(new Light("Резерв", "102", "K4"));
        return Collections.unmodifiableList(lights);
    }

    static Room find(String id) {
        String roomId = "kitchen".equals(id) ? "living" : id;
        return ALL.stream().filter(room -> room.id().equals(roomId)).findFirst().orElse(null);
    }
}
