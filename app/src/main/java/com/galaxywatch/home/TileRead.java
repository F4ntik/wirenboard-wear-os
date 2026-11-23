package com.galaxywatch.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TileRead {
    static final long TIMEOUT_MS = 8_000;
    static final long MIN_INTERVAL_MS = 40_000;
    private static final List<Rooms.Light> LIGHTS = allLights();
    private static final Map<String, Rooms.Light> BY_TOPIC = lightTopics();
    // The reader has process lifetime and only holds the application context.
    @SuppressLint("StaticFieldLeak")
    private static OnWatch onWatch;
    private static boolean pendingBeforeFirstRead;

    interface Environment {
        String scope();
        long elapsedMillis();
        long epochMillis();
        void post(long delay, Runnable action);
        void open(RoutePlan.Route route, Attempt attempt);
        void save(String scope, Map<Rooms.Light, Boolean> values, long observedAt);
    }

    private final Environment environment;
    private Read active;
    private String lastScope;
    private long lastStart;
    private boolean commandBarrier;

    TileRead(Environment environment) { this.environment = environment; }

    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        OnWatch.MAIN.post(() -> {
            if (onWatch == null) {
                if (pendingBeforeFirstRead) return;
                onWatch = new OnWatch(app);
            }
            if (onWatch.reader.commandBarrier) return;
            onWatch.reader.request(onWatch.scope(), onWatch.routes());
        });
    }

    // Called on the main thread before sending a command and after the last command settles.
    static void commandsPending(boolean pending) {
        if (onWatch == null) pendingBeforeFirstRead = pending;
        else onWatch.reader.setCommandsPending(pending);
    }

    void setCommandsPending(boolean pending) {
        commandBarrier = pending;
        if (pending && active != null) finish(active, false);
    }

    private static List<Rooms.Light> allLights() {
        List<Rooms.Light> lights = new ArrayList<>();
        for (Rooms.Room room : Rooms.ALL) lights.addAll(room.lights());
        return lights;
    }

    private static Map<String, Rooms.Light> lightTopics() {
        Map<String, Rooms.Light> topics = new HashMap<>();
        for (Rooms.Light light : LIGHTS) topics.put(light.stateTopic(), light);
        return topics;
    }

    static boolean recentlyRead(RoomCache.Snapshot snapshot, long now) {
        for (Rooms.Light light : LIGHTS) {
            RoomCache.Reading reading = snapshot.readings().get(light);
            if (reading == null || reading.value() == null || reading.at() > now || now - reading.at() >= MIN_INTERVAL_MS) {
                return false;
            }
        }
        return true;
    }

    // All request, timeout and callback handling is serialized on the same dispatcher.
    void request(String scope, List<RoutePlan.Route> routes) {
        if (commandBarrier || !scope.equals(environment.scope())) return;
        if (active != null) {
            if (scope.equals(active.scope)) return;
            finish(active, false);
        }
        long now = environment.elapsedMillis();
        if (routes.isEmpty() || (scope.equals(lastScope) && now - lastStart < MIN_INTERVAL_MS)) return;
        lastScope = scope;
        lastStart = now;
        Read read = new Read(scope, now + TIMEOUT_MS, environment.epochMillis(), routes);
        active = read;
        environment.post(TIMEOUT_MS, () -> finish(read, true));
        for (Attempt attempt : read.attempts.values()) environment.open(attempt.route, attempt);
    }

    private boolean takesEvents(Attempt attempt) {
        Read read = attempt.read;
        if (read != active || attempt.closed) return false;
        if (!read.scope.equals(environment.scope())) {
            finish(read, false);
            return false;
        }
        if (environment.elapsedMillis() >= read.deadline) {
            finish(read, true);
            return false;
        }
        return true;
    }

    private void confirm(Attempt attempt) {
        if (!attempt.ready || attempt.values.isEmpty()) return;
        Read read = attempt.read;
        if (read.winner == null) {
            if (attempt.route == RoutePlan.Route.CLOUD && read.attempts.size() > 1
                && (attempt.vacation == null || attempt.values.values().stream().noneMatch(value -> value != null))) {
                // A reachable cloud tunnel without house data cannot win the automatic race.
                failed(attempt);
                return;
            }
            read.winner = attempt;
            for (Attempt other : read.attempts.values()) if (other != attempt) other.close();
        }
        if (attempt.values.size() == BY_TOPIC.size()) finish(read, true);
    }

    private void failed(Attempt attempt) {
        if (!takesEvents(attempt)) return;
        Read read = attempt.read;
        attempt.close();
        if (read.winner == attempt || read.attempts.values().stream().allMatch(other -> other.closed)) finish(read, true);
    }

    private void finish(Read read, boolean save) {
        if (active != read) return;
        active = null;
        for (Attempt attempt : read.attempts.values()) attempt.close();
        Attempt winner = read.winner;
        if (save && winner != null && read.scope.equals(environment.scope())) {
            // A response can contain a snapshot taken before a newer event received by the app.
            environment.save(read.scope, winner.values, read.startedAt);
        }
    }

    private final class Read {
        final String scope;
        final long deadline;
        final long startedAt;
        final EnumMap<RoutePlan.Route, Attempt> attempts = new EnumMap<>(RoutePlan.Route.class);
        Attempt winner;

        Read(String scope, long deadline, long startedAt, List<RoutePlan.Route> routes) {
            this.scope = scope;
            this.deadline = deadline;
            this.startedAt = startedAt;
            for (RoutePlan.Route route : routes) attempts.put(route, new Attempt(this, route));
        }
    }

    final class Attempt implements HomeLink.Events {
        private final Read read;
        private final RoutePlan.Route route;
        private final Map<Rooms.Light, Boolean> values = new HashMap<>();
        private Boolean vacation;
        private boolean ready;
        private volatile boolean closed;
        private HomeLink link;

        private Attempt(Read read, RoutePlan.Route route) {
            this.read = read;
            this.route = route;
        }

        synchronized boolean attach(HomeLink client) {
            if (closed) {
                client.close();
                return false;
            }
            link = client;
            return true;
        }

        private synchronized void close() {
            closed = true;
            if (link != null) {
                link.close();
                link = null;
            }
        }

        @Override public void connected() {
            environment.post(0, () -> {
                if (!takesEvents(this)) return;
                ready = true;
                confirm(this);
            });
        }

        @Override public void state(String topic, Boolean value, boolean snapshot) {
            environment.post(0, () -> {
                if (!takesEvents(this)) return;
                if (Rooms.VACATION.equals(topic)) vacation = value;
                Rooms.Light light = BY_TOPIC.get(topic);
                if (light != null) values.put(light, value);
                confirm(this);
            });
        }

        @Override public void disconnected() { environment.post(0, () -> failed(this)); }
        @Override public void authenticationFailed() { disconnected(); }
        @Override public void commandUnconfirmed() {}
    }

    private static final class OnWatch implements Environment {
        static final Handler MAIN = new Handler(Looper.getMainLooper());
        private final Context app;
        private final SharedPreferences settings;
        private final TileRead reader = new TileRead(this);

        OnWatch(Context app) {
            this.app = app;
            settings = app.getSharedPreferences("home", Context.MODE_PRIVATE);
        }

        List<RoutePlan.Route> routes() {
            if (settings.getBoolean("demo", HomeConnections.DEFAULT_DEMO)) return List.of();
            if (recentlyRead(RoomCache.read(app), epochMillis())) return List.of();
            return RoutePlan.candidates(HomeConnections.automatic(settings), HomeConnections.manualRoute(settings),
                !HomeConnections.address(settings).isEmpty(), settings.contains(WbCloud.PROFILE), false);
        }

        @Override public String scope() { return RoomCache.scope(app); }
        @Override public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
        @Override public long epochMillis() { return System.currentTimeMillis(); }
        @Override public void post(long delay, Runnable action) { MAIN.postDelayed(action, delay); }

        @Override public void open(RoutePlan.Route route, Attempt attempt) {
            new Thread(() -> {
                if (attempt.closed) return;
                try {
                    if (!attempt.read.scope.equals(scope())) {
                        attempt.disconnected();
                        return;
                    }
                    HomeLink link = HomeConnections.open(settings, route, LIGHTS, attempt);
                    if (!attempt.attach(link)) return;
                    if (link instanceof RemoteRoom remote) remote.readOnce();
                    else link.connect();
                } catch (IllegalArgumentException | GeneralSecurityException | IOException | JSONException failure) {
                    attempt.disconnected();
                }
            }, "tile-read-" + route.name()).start();
        }

        @Override public void save(String scope, Map<Rooms.Light, Boolean> values, long observedAt) {
            RoomCache.save(app, scope, values, observedAt);
        }
    }
}
