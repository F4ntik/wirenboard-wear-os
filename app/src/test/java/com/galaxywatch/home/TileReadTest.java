package com.galaxywatch.home;

import static com.galaxywatch.home.RoutePlan.Route.CLOUD;
import static com.galaxywatch.home.RoutePlan.Route.HOME;
import static org.junit.Assert.*;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.junit.Test;

public class TileReadTest {
    private static final List<Rooms.Light> LIGHTS = Rooms.ALL.get(0).lights();
    private final TestEnvironment env = new TestEnvironment();
    private final TileRead reader = new TileRead(env);

    @Test public void duplicateRequestsAndFailuresShareTheSameThrottle() {
        reader.request("first", List.of());
        assertEquals(0, env.opens);
        reader.request("first", List.of(HOME, CLOUD));
        reader.request("first", List.of(HOME, CLOUD));
        assertEquals(2, env.opens);

        env.attempts.get(HOME).disconnected();
        env.attempts.get(CLOUD).authenticationFailed();
        env.advance(0);
        env.advance(TileRead.MIN_INTERVAL_MS - 1);
        reader.request("first", List.of(HOME, CLOUD));
        assertEquals(2, env.opens);
        assertEquals(0, env.saves);

        env.advance(TileRead.MIN_INTERVAL_MS);
        reader.request("first", List.of(HOME));
        assertEquals(3, env.opens);
    }

    @Test public void acceptsPartialMqttAfterHandshakeAndKeepsUnknownDistinct() {
        reader.request("first", List.of(HOME, CLOUD));
        TileRead.Attempt cloud = env.attempts.get(CLOUD);
        TileRead.Attempt home = env.attempts.get(HOME);
        assertTrue(cloud.attach(env.links.get(CLOUD)));
        assertTrue(home.attach(env.links.get(HOME)));
        cloud.state(Rooms.VACATION, null, true);
        cloud.state(LIGHTS.get(2).stateTopic(), false, true);
        cloud.connected();
        env.advance(0);
        assertTrue(env.links.get(CLOUD).closed);

        env.advance(1_500);
        home.state(LIGHTS.get(0).stateTopic(), true, true);
        home.state(LIGHTS.get(1).stateTopic(), null, true);
        env.advance(1_500);
        assertEquals(0, env.saves);
        home.connected();
        env.advance(1_500);
        env.advance(TileRead.TIMEOUT_MS);

        assertTrue(env.links.get(HOME).closed);
        assertEquals(1, env.saves);
        assertEquals(Long.valueOf(100_000), env.observedAt);
        assertEquals(Boolean.TRUE, env.cached.get(LIGHTS.get(0)));
        assertTrue(env.cached.containsKey(LIGHTS.get(1)));
        assertNull(env.cached.get(LIGHTS.get(1)));
        assertFalse(env.cached.containsKey(LIGHTS.get(2)));
    }

    @Test public void closesLosingRouteAndKeepsOnlyTheCompleteCloudResponse() {
        reader.request("first", List.of(HOME, CLOUD));
        TileRead.Attempt cloud = env.attempts.get(CLOUD);
        TileRead.Attempt home = env.attempts.get(HOME);
        assertTrue(home.attach(env.links.get(HOME)));
        home.state(LIGHTS.get(0).stateTopic(), true, true);

        cloud.state(Rooms.VACATION, false, true);
        int expected = 0;
        for (Rooms.Room room : Rooms.ALL) {
            for (Rooms.Light light : room.lights()) {
                cloud.state(light.stateTopic(), false, true);
                expected++;
            }
        }
        cloud.connected();
        home.connected();
        env.advance(0);

        assertTrue(env.links.get(HOME).closed);
        assertEquals(1, env.saves);
        assertEquals(expected, env.cached.size());
        assertEquals(Boolean.FALSE, env.cached.get(LIGHTS.get(0)));
        env.advance(TileRead.TIMEOUT_MS);
        assertEquals(1, env.saves);
    }

    @Test public void persistsConfirmedPartialStateOnDisconnectOnlyOnce() {
        reader.request("first", List.of(HOME));
        TileRead.Attempt first = env.attempts.get(HOME);
        first.state(LIGHTS.get(0).stateTopic(), true, true);
        first.connected();
        first.disconnected();
        first.state(LIGHTS.get(1).stateTopic(), true, true);
        env.advance(0);
        env.advance(TileRead.TIMEOUT_MS);
        assertEquals(1, env.saves);
        assertEquals(Map.of(LIGHTS.get(0), true), env.cached);
    }

    @Test public void configurationChangeCancelsPreviousAttemptAndDiscardsLateResults() {
        reader.request("first", List.of(HOME));
        TileRead.Attempt first = env.attempts.get(HOME);
        first.state(LIGHTS.get(0).stateTopic(), false, true);
        first.connected();
        env.scope = "second";
        env.advance(0);

        ProbeLink lateConnection = new ProbeLink();
        assertFalse(first.attach(lateConnection));
        assertTrue(lateConnection.closed);
        reader.request("second", List.of(HOME));
        assertEquals(2, env.opens);
        assertEquals(0, env.saves);

        first.state(LIGHTS.get(0).stateTopic(), false, true);
        TileRead.Attempt second = env.attempts.get(HOME);
        second.state(LIGHTS.get(1).stateTopic(), true, true);
        second.connected();
        env.advance(0);
        env.advance(TileRead.TIMEOUT_MS);
        assertEquals(1, env.saves);
        assertEquals("second", env.savedScope);
        assertEquals(Map.of(LIGHTS.get(1), true), env.cached);
    }

    @Test public void rejectsQueuedResultsIfDispatcherWasBlockedPastDeadline() {
        reader.request("first", List.of(HOME));
        TileRead.Attempt home = env.attempts.get(HOME);
        home.state(LIGHTS.get(0).stateTopic(), false, true);
        home.connected();
        env.advance(TileRead.TIMEOUT_MS);
        assertEquals(0, env.saves);
    }

    @Test public void commandsCancelConfirmedPartialReadAndBlockNewReadsUntilSettled() {
        reader.request("first", List.of(HOME));
        TileRead.Attempt active = env.attempts.get(HOME);
        ProbeLink link = env.links.get(HOME);
        assertTrue(active.attach(link));
        active.state(LIGHTS.get(0).stateTopic(), false, true);
        active.connected();
        env.advance(0);

        reader.setCommandsPending(true);
        assertTrue(link.closed);
        active.state(LIGHTS.get(1).stateTopic(), false, true);
        env.advance(TileRead.MIN_INTERVAL_MS);
        reader.request("first", List.of(HOME));
        assertEquals(1, env.opens);
        assertEquals(0, env.saves);

        reader.setCommandsPending(false);
        reader.request("first", List.of(HOME));
        assertEquals(2, env.opens);
    }

    @Test public void skipsOnlyARecentAndCompleteSetOfRoomReadings() {
        long now = 100_000;
        long fresh = now - TileRead.MIN_INTERVAL_MS + 1;
        Map<Rooms.Light, RoomCache.Reading> readings = new HashMap<>();
        for (Rooms.Room room : Rooms.ALL) {
            for (Rooms.Light light : room.lights()) readings.put(light, new RoomCache.Reading(false, fresh));
        }
        RoomCache.Snapshot snapshot = new RoomCache.Snapshot(readings, false);
        assertTrue(TileRead.recentlyRead(snapshot, now));
        assertFalse(TileRead.recentlyRead(snapshot, fresh + TileRead.MIN_INTERVAL_MS));
        readings.put(LIGHTS.get(0), new RoomCache.Reading(null, now));
        assertFalse(TileRead.recentlyRead(snapshot, now));
        readings.remove(LIGHTS.get(0));
        assertFalse(TileRead.recentlyRead(snapshot, now));
    }

    private static final class TestEnvironment implements TileRead.Environment {
        private record Task(long time, long order, Runnable action) {}
        private final PriorityQueue<Task> tasks = new PriorityQueue<>(Comparator.comparingLong(Task::time)
            .thenComparingLong(Task::order));
        final Map<RoutePlan.Route, TileRead.Attempt> attempts = new EnumMap<>(RoutePlan.Route.class);
        final Map<RoutePlan.Route, ProbeLink> links = new EnumMap<>(RoutePlan.Route.class);
        private long now;
        private long order;
        String scope = "first";
        int opens;
        int saves;
        String savedScope;
        Map<Rooms.Light, Boolean> cached;
        Long observedAt;

        void advance(long elapsed) {
            now = elapsed;
            while (!tasks.isEmpty() && tasks.peek().time() <= now) tasks.remove().action().run();
        }

        @Override public String scope() { return scope; }
        @Override public long elapsedMillis() { return now; }
        @Override public long epochMillis() { return 100_000 + now; }
        @Override public void post(long delay, Runnable action) { tasks.add(new Task(now + delay, order++, action)); }
        @Override public void open(RoutePlan.Route route, TileRead.Attempt attempt) {
            opens++;
            attempts.put(route, attempt);
            links.put(route, new ProbeLink());
        }
        @Override public void save(String scope, Map<Rooms.Light, Boolean> values, long observedAt) {
            saves++;
            savedScope = scope;
            cached = new HashMap<>(values);
            this.observedAt = observedAt;
        }
    }

    private static final class ProbeLink implements HomeLink {
        boolean closed;
        @Override public void connect() {}
        @Override public boolean isReady() { return !closed; }
        @Override public void send(Rooms.Light light, boolean value) { fail("Tile read sent a light command"); }
        @Override public void sendAllOff() { fail("Tile read sent a master command"); }
        @Override public void close() { closed = true; }
    }
}
