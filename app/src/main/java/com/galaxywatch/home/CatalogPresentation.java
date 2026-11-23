package com.galaxywatch.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Local display preferences and the last confirmed power readings for a controller-defined home. */
final class CatalogPresentation {
    static final String ENTRY = "catalog-room:";
    private static final long RECHECK_MS = 40_000;
    private static boolean reading, commanding;
    private static long generation, lastStart, lastRedraw;
    private static String lastController = "";

    private CatalogPresentation() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("catalog_display", Context.MODE_PRIVATE);
    }

    static List<WatchCatalog.Room> favorites(Context context, WatchCatalog home) {
        List<WatchCatalog.Room> rooms = new ArrayList<>();
        // A configured home can retain the existing watch's four positions when room IDs match.
        String previous = context.getSharedPreferences("home", Context.MODE_PRIVATE).getString("favorite_rooms", "");
        String saved = prefs(context).getString("favorites." + home.controller, previous);
        for (String id : saved.split(",")) {
            for (WatchCatalog.Room room : home.rooms) {
                if (rooms.size() < RoomOverview.SLOTS && id.equals(room.id) && !rooms.contains(room)) rooms.add(room);
            }
        }
        for (WatchCatalog.Room room : home.rooms) {
            if (rooms.size() == RoomOverview.SLOTS) break;
            if (!rooms.contains(room)) rooms.add(room);
        }
        return rooms;
    }

    static void setFavorite(Context context, WatchCatalog home, int slot, WatchCatalog.Room choice) {
        List<WatchCatalog.Room> rooms = favorites(context, home);
        if (slot < 0 || slot >= rooms.size() || !home.rooms.contains(choice)) return;
        int before = rooms.indexOf(choice);
        if (before >= 0) rooms.set(before, rooms.get(slot));
        rooms.set(slot, choice);
        List<String> ids = new ArrayList<>();
        for (WatchCatalog.Room room : rooms) ids.add(room.id);
        prefs(context).edit().putString("favorites." + home.controller, String.join(",", ids)).apply();
        RoomCache.requestUpdate(context);
    }

    static RoomOverview.Status status(WatchCatalog.Room room, Map<String, Boolean> powers) {
        int lit = 0, unknown = 0;
        for (WatchCatalog.Light light : room.lights) {
            Boolean value = powers.get(light.id);
            if (value == null) unknown++;
            else if (value) lit++;
        }
        return new RoomOverview.Status(lit, unknown);
    }

    static String outsideHint(List<WatchCatalog.Room> all, List<WatchCatalog.Room> pinned, Map<String, Boolean> powers) {
        int lit = 0;
        boolean incomplete = false;
        String last = "";
        for (WatchCatalog.Room room : all) {
            if (pinned.contains(room)) continue;
            var states = status(room, powers);
            incomplete |= states.unknown() > 0;
            if (states.on() > 0) { lit++; last = room.label; }
        }
        if (lit == 0) return incomplete ? "Не всё известно" : "";
        if (lit == 1) return switch (last) {
            case "Кухня-гостиная" -> "Ещё в кухне-гостиной";
            case "Спальня" -> "Ещё горит в спальне";
            case "Душевая" -> "Ещё горит в душевой";
            case "Санузел" -> "Ещё горит в санузле";
            case "Коридор" -> "Ещё горит в коридоре";
            case "Кабинет" -> "Ещё горит в кабинете";
            case "Детская" -> "Ещё горит в детской";
            default -> last.length() <= 13 ? "Ещё · " + last : "Ещё в 1 комнате";
        };
        return "Ещё в " + lit + (lit % 10 == 1 && lit % 100 != 11 ? " комнате" : " комнатах");
    }

    record Readings(long observed, Map<String, Boolean> powers) {
        boolean fresh(long now) {
            return observed > 0 && now >= observed && now - observed < RoomCache.FRESH_MS;
        }
    }

    static synchronized Readings readings(Context context, WatchCatalog home) {
        var p = prefs(context);
        Map<String, Boolean> values = new HashMap<>();
        if (home == null || !home.revision.equals(p.getString("revision", ""))
            || !home.controller.equals(p.getString("controller", ""))) return new Readings(0, values);
        for (WatchCatalog.Room room : home.rooms) for (WatchCatalog.Light light : room.lights) {
            String key = "light." + light.id;
            if (p.contains(key)) values.put(light.id, p.getBoolean(key, false));
        }
        return new Readings(p.getLong("observed", 0), values);
    }

    // Call on a worker or the UI thread with the time the read started, including after a confirmed command.
    static synchronized void save(Context context, CatalogLink.Result result, long observed) {
        if (commanding || result == null || result.error != null || result.catalog == null || result.state == null) return;
        WatchCatalog home = CatalogLink.cached(context);
        if (home == null || !home.controller.equals(result.catalog.controller)
            || !home.revision.equals(result.state.revision)) return;
        var p = prefs(context);
        Readings old = readings(context, home);
        long now = System.currentTimeMillis();
        if (observed > now || now - observed >= RoomCache.FRESH_MS || old.observed > observed && old.observed <= now) return;
        var edit = p.edit();
        for (String key : p.getAll().keySet()) if (key.startsWith("light.")) edit.remove(key);
        boolean changed = !old.fresh(now);
        for (WatchCatalog.Room room : home.rooms) for (WatchCatalog.Light light : room.lights) {
            var state = result.state.lights.get(light.id);
            Boolean power = state == null ? null : state.power;
            if (power != null) edit.putBoolean("light." + light.id, power);
            changed |= !Objects.equals(old.powers.get(light.id), power);
        }
        edit.putString("controller", home.controller).putString("revision", home.revision).putLong("observed", observed).apply();
        long elapsed = SystemClock.elapsedRealtime();
        if (changed || elapsed - lastRedraw >= RECHECK_MS) {
            lastRedraw = elapsed;
            RoomCache.requestUpdate(context);
        }
    }

    static synchronized void commandPending(Context context, boolean pending) {
        generation++;
        commanding = pending;
        if (pending) {
            prefs(context).edit().remove("observed").apply();
            RoomCache.requestUpdate(context);
        }
    }

    static synchronized void refresh(Context context) {
        Context app = context.getApplicationContext();
        if (reading || commanding || !CatalogLink.paired(app)) return;
        WatchCatalog home = CatalogLink.cached(app);
        long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        String controller = home == null ? "" : home.controller;
        if (controller.equals(lastController) && lastStart > 0 && elapsed - lastStart < RECHECK_MS) return;
        Readings old = readings(app, home);
        if (home != null && old.fresh(now) && now - old.observed < RECHECK_MS) return;
        lastStart = elapsed;
        lastController = controller;
        reading = true;
        long started = generation;
        new Thread(() -> {
            try {
                CatalogLink.Result result = CatalogLink.read(app, home);
                synchronized (CatalogPresentation.class) {
                    if (started == generation) save(app, result, now);
                }
                if (result.catalog != null && (home == null || !home.revision.equals(result.catalog.revision))) {
                    RoomCache.requestUpdate(app);
                }
            } finally {
                synchronized (CatalogPresentation.class) { reading = false; }
            }
        }, "wear-catalog-tile").start();
    }
}
