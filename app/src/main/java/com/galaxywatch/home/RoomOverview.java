package com.galaxywatch.home;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class RoomOverview {
    private static final String FAVORITES = "favorite_rooms";
    static final int SLOTS = 4;

    record Status(int on, int unknown) {
        String mark() {
            if (on == 0) return unknown == 0 ? "" : "?";
            return Integer.toString(on) + (unknown == 0 ? "" : "+");
        }

        String description() {
            if (on == 0) return unknown == 0 ? "Всё погашено" : "Нет полных данных";
            if (unknown > 0) return "Точно горит: " + on + ". По остальным нет данных";
            return on == 1 ? "Горит одна лампа" : "Включено ламп: " + on;
        }
    }

    static Status status(Rooms.Room room, Map<Rooms.Light, Boolean> values) {
        int on = 0, unknown = 0;
        for (Rooms.Light light : room.lights()) {
            Boolean value = values.get(light);
            if (value == null) unknown++;
            else if (value) on++;
        }
        return new Status(on, unknown);
    }

    static List<Rooms.Room> favorites(SharedPreferences settings) {
        return favorites(settings.getString(FAVORITES, ""));
    }

    static List<Rooms.Room> favorites(String saved) {
        List<Rooms.Room> result = new ArrayList<>();
        for (String id : saved.split(",")) {
            Rooms.Room room = Rooms.find(id);
            if (room != null && !result.contains(room)) result.add(room);
            if (result.size() == SLOTS) break;
        }
        for (Rooms.Room room : Rooms.ALL) {
            if (result.size() == SLOTS) break;
            if (!result.contains(room)) result.add(room);
        }
        return result;
    }

    static List<Rooms.Room> replaceSlot(List<Rooms.Room> favorites, int slot, Rooms.Room choice) {
        List<Rooms.Room> result = new ArrayList<>(favorites);
        int previous = result.indexOf(choice);
        if (previous >= 0) result.set(previous, result.get(slot));
        result.set(slot, choice);
        return result;
    }

    static void saveFavorites(SharedPreferences settings, List<Rooms.Room> favorites) {
        List<String> ids = new ArrayList<>();
        for (Rooms.Room room : favorites) ids.add(room.id());
        settings.edit().putString(FAVORITES, String.join(",", ids)).apply();
    }

    static String outsideHint(List<Rooms.Room> favorites, Map<Rooms.Light, Boolean> values) {
        int count = 0;
        Rooms.Room last = null;
        boolean incomplete = false;
        for (Rooms.Room room : Rooms.ALL) {
            if (favorites.contains(room)) continue;
            Status status = status(room, values);
            incomplete |= status.unknown() > 0;
            if (status.on() > 0) {
                count++;
                last = room;
            }
        }
        if (count == 0) return incomplete ? "Не всё известно" : "";
        if (count > 1) return "Ещё в " + count + " комнатах";
        return switch (last.id()) {
            case "living" -> "Ещё в кухне-гостиной";
            case "bedroom" -> "Ещё горит в спальне";
            case "hall" -> "Ещё горит в коридоре";
            case "kids" -> "Ещё горит в детской";
            case "office" -> "Ещё горит в кабинете";
            case "wc" -> "Ещё горит в санузле";
            case "shower" -> "Ещё горит в душевой";
            default -> "Ещё в 1 комнате";
        };
    }
}
