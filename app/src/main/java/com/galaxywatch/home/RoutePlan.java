package com.galaxywatch.home;

/** All times are elapsed realtime, so a clock adjustment cannot delay a retry. */
final class RoutePlan {
    static final String AUTOMATIC = "automatic_connection";
    enum Route {
        HOME("Домашняя сеть"), CLOUD("WB Cloud"), HTTPS("Свой сервер");
        final String label;
        Route(String label) { this.label = label; }
    }

    static final long RECHECK_MS = 30_000;
    private long nextHome;
    private long nextCloud;

    Route select(boolean automatic, Route manual, boolean home, boolean cloud, boolean verifyCloud, long now) {
        if (verifyCloud && cloud) return Route.CLOUD;
        if (!automatic) return manual;
        if (!cloud) return Route.HOME;
        if (!home) return Route.CLOUD;
        if (now < nextCloud) return Route.HOME;
        if (now < nextHome) return Route.CLOUD;
        return Route.HOME;
    }

    void homeFailed(long now) { nextHome = now + RECHECK_MS; }
    void cloudFailed(long now) { nextHome = 0; nextCloud = now + RECHECK_MS; }
    boolean cloudAvailable(long now) { return now >= nextCloud; }
    long homeDelay(long now) { return Math.max(0, nextHome - now); }
    void reset() { nextHome = 0; nextCloud = 0; }
}
