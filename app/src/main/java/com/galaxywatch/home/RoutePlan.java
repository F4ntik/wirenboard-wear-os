package com.galaxywatch.home;

/** All times are elapsed realtime, so a clock adjustment cannot delay a retry. */
final class RoutePlan {
    static final String AUTOMATIC = "automatic_connection";
    static final String LAST_CLOUD = "last_verified_auto_cloud";
    enum Route {
        HOME("Домашняя сеть"), CLOUD("WB Cloud"), HTTPS("Свой сервер");
        final String label;
        Route(String label) { this.label = label; }
    }

    static final long RECHECK_MS = 30_000;
    static final int HOME_HEAD_START_MS = 1200;
    private boolean preferCloud;
    private boolean retryCloudFirst;
    private long nextHome;
    private long allowSlowHomeUntil;

    RoutePlan(boolean lastCloud) {
        preferCloud = lastCloud;
        retryCloudFirst = lastCloud;
    }

    Route select(boolean automatic, Route manual, boolean home, boolean cloud, boolean verifyCloud) {
        if (verifyCloud && cloud) return Route.CLOUD;
        if (!automatic) return manual;
        if (!cloud) return Route.HOME;
        if (!home) return Route.CLOUD;
        return preferCloud ? Route.CLOUD : Route.HOME;
    }

    // If navigation interrupts an unconfirmed home attempt, the new screen can read cloud immediately.
    void attemptingHome() { preferCloud = true; }
    void homeFailed(long now) {
        preferCloud = true;
        retryCloudFirst = true;
        nextHome = now + RECHECK_MS;
    }
    void cloudFailed(long now) {
        allowSlowHomeUntil = now + RECHECK_MS;
        preferCloud = retryCloudFirst;
        retryCloudFirst = false;
        nextHome = 0;
    }
    void confirmed(Route route) {
        if (route == Route.HOME) reset();
        else if (route == Route.CLOUD) {
            preferCloud = true;
            retryCloudFirst = true;
        }
    }
    int homeTimeout(boolean hasCloud, long now) {
        return hasCloud && now >= allowSlowHomeUntil ? HOME_HEAD_START_MS : MqttRoom.AUTO_TIMEOUT_MS;
    }
    long homeDelay(long now) { return Math.max(0, nextHome - now); }
    void reset() {
        preferCloud = false;
        retryCloudFirst = false;
        nextHome = 0;
        allowSlowHomeUntil = 0;
    }
}
