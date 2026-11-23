package com.galaxywatch.home;

import java.util.EnumMap;
import java.util.List;

final class RoutePlan {
    static final String AUTOMATIC = "automatic_connection";
    static final long RECHECK_MS = 30_000;

    enum Route {
        HOME("Домашняя сеть"), CLOUD("WB Cloud"), HTTPS("Свой сервер");
        final String label;
        Route(String label) { this.label = label; }
    }

    private final EnumMap<Route, Long> retryAt = new EnumMap<>(Route.class);
    private final EnumMap<Route, Long> nextDelay = new EnumMap<>(Route.class);
    private long connectedAt;

    void connected(long now) { connectedAt = now; }

    void lost(Route route, long now) {
        if (now - connectedAt >= RECHECK_MS) {
            retryAt.remove(route);
            nextDelay.remove(route);
            return;
        }
        // A fast but unstable link must leave the other transport time to complete its handshake.
        long delay = nextDelay.getOrDefault(route, 5000L);
        retryAt.put(route, now + delay);
        nextDelay.put(route, Math.min(2 * delay, RECHECK_MS));
    }

    long retryIn(Route route, long now) { return Math.max(0, retryAt.getOrDefault(route, 0L) - now); }

    static List<Route> candidates(boolean automatic, Route manual, boolean home, boolean cloud, boolean verifyCloud) {
        if (verifyCloud && cloud) return List.of(Route.CLOUD);
        if (!automatic) return List.of(manual);
        if (!cloud) return List.of(Route.HOME);
        if (!home) return List.of(Route.CLOUD);
        return List.of(Route.HOME, Route.CLOUD);
    }
}
