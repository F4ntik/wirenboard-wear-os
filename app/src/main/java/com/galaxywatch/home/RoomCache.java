package com.galaxywatch.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.wear.tiles.TileService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class RoomCache {
    static final long FRESH_MS = 120_000;
    private static final long RENEW_MS = 40_000;
    private static boolean updateQueued;
    private static long lastTimelineUpdate;

    record Reading(Boolean value, long at) {}
    record Snapshot(Map<Rooms.Light, Reading> readings, boolean demo) {
        Map<Rooms.Light, Boolean> statesAt(long now) {
            Map<Rooms.Light, Boolean> result = new HashMap<>();
            for (var entry : readings.entrySet()) {
                Reading reading = entry.getValue();
                if (demo || (now >= reading.at() && now - reading.at() < FRESH_MS)) {
                    result.put(entry.getKey(), reading.value());
                }
            }
            return result;
        }

        List<Long> expiriesAfter(long now) {
            TreeSet<Long> times = new TreeSet<>();
            if (!demo) for (Reading reading : readings.values()) {
                if (reading.value() != null && reading.at() <= now && now - reading.at() < FRESH_MS) {
                    times.add(reading.at() + FRESH_MS);
                }
            }
            return new ArrayList<>(times);
        }
    }

    static String scope(Context context) {
        SharedPreferences s = context.getSharedPreferences("home", Context.MODE_PRIVATE);
        if (s.getBoolean("demo", HomeConnections.DEFAULT_DEMO)) return "demo";
        // The import id stays stable across normal cloud token rotation.
        return String.join("|", "live", HomeConnections.address(s),
            Boolean.toString(HomeConnections.automatic(s)), HomeConnections.manualRoute(s).name(),
            s.getString(WbCloud.CONFIG_REVISION, s.getString(WbCloud.IMPORT_ID, "")), s.getString(RemoteSettings.ORIGIN, ""),
            s.getString(RemoteSettings.REVISION, ""));
    }

    static synchronized Snapshot read(Context context) {
        String scope = scope(context);
        boolean demo = scope.equals("demo");
        Map<Rooms.Light, Reading> result = new HashMap<>();
        SharedPreferences p = prefs(context, demo);
        if (demo || scope.equals(p.getString("source", ""))) {
            Map<String, ?> saved = p.getAll();
            for (Rooms.Light light : Rooms.MASTER_LIGHTS) {
                String key = light.stateTopic();
                if (demo) result.put(light, new Reading(Boolean.TRUE.equals(saved.get(key)), 0));
                else if (saved.get("at:" + key) instanceof Long time) {
                    result.put(light, new Reading((Boolean) saved.get(key), time));
                }
            }
        }
        return new Snapshot(result, demo);
    }

    static synchronized void save(Context context, String source, Map<Rooms.Light, Boolean> values, long at) {
        if (values.isEmpty() || !source.equals(scope(context)) || source.equals("demo")) return;
        long now = System.currentTimeMillis();
        if (at > now || now - at >= FRESH_MS) return;
        SharedPreferences p = prefs(context, false);
        boolean same = source.equals(p.getString("source", ""));
        SharedPreferences.Editor edit = p.edit();
        if (!same) edit.clear().putString("source", source);
        boolean changed = !same;
        boolean updateTimeline = !same;
        for (var entry : values.entrySet()) {
            String key = entry.getKey().stateTopic();
            Boolean value = entry.getValue();
            long oldAt = same ? p.getLong("at:" + key, 0) : 0;
            if (oldAt > at && oldAt <= now) continue;
            Boolean old = same && p.contains(key) ? p.getBoolean(key, false) : null;
            boolean different = !java.util.Objects.equals(old, value);
            if (at == oldAt && !different) continue;
            updateTimeline |= different || oldAt == 0 || oldAt > now || at - oldAt >= FRESH_MS;
            if (value == null) edit.remove(key);
            else edit.putBoolean(key, value);
            edit.putLong("at:" + key, at);
            changed = true;
        }
        if (changed) {
            edit.apply();
            long elapsed = android.os.SystemClock.elapsedRealtime();
            if (updateTimeline || elapsed - lastTimelineUpdate >= RENEW_MS) {
                lastTimelineUpdate = elapsed;
                requestUpdate(context);
            }
        }
    }

    static void saveDemo(Context context, Map<Rooms.Light, Boolean> values) {
        SharedPreferences.Editor edit = prefs(context, true).edit();
        for (var entry : values.entrySet()) edit.putBoolean(entry.getKey().stateTopic(), Boolean.TRUE.equals(entry.getValue()));
        edit.apply();
        requestUpdate(context);
    }

    private static SharedPreferences prefs(Context context, boolean demo) {
        return context.getSharedPreferences(demo ? "demo_lights" : "confirmed_lights", Context.MODE_PRIVATE);
    }

    static synchronized void requestUpdate(Context context) {
        if (updateQueued) return;
        updateQueued = true;
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            synchronized (RoomCache.class) { updateQueued = false; }
            TileService.getUpdater(app).requestUpdate(HomeTile.class);
        }, 300);
    }
}
