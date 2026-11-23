package com.galaxywatch.home;

import static com.galaxywatch.home.RoutePlan.Route.*;
import static org.junit.Assert.assertEquals;
import java.util.List;
import org.junit.Test;

public class RoutePlanTest {
    @Test public void autoStartsBothAvailableRoutesRegardlessOfPreviousManualChoice() {
        for (var manual : RoutePlan.Route.values()) {
            assertEquals(List.of(HOME, CLOUD), RoutePlan.candidates(true, manual, true, true, false));
        }
    }

    @Test public void autoUsesTheOnlyConfiguredRoute() {
        assertEquals(List.of(HOME), RoutePlan.candidates(true, CLOUD, true, false, false));
        assertEquals(List.of(CLOUD), RoutePlan.candidates(true, HOME, false, true, false));
    }

    @Test public void manualModeUsesOnlyTheSelectedRoute() {
        for (var manual : RoutePlan.Route.values()) {
            assertEquals(List.of(manual), RoutePlan.candidates(false, manual, true, true, false));
        }
    }

    @Test public void rapidDropsBackOffOnlyTheFlappingRoute() {
        RoutePlan plan = new RoutePlan();
        long now = 0;
        for (int pause : new int[] {5000, 10_000, 20_000, 30_000, 30_000}) {
            plan.connected(now);
            plan.lost(CLOUD, ++now);
            assertEquals(pause, plan.retryIn(CLOUD, now));
            assertEquals(0, plan.retryIn(HOME, now));
            now += pause;
            assertEquals(0, plan.retryIn(CLOUD, now));
        }
        plan.connected(now);
        now += RoutePlan.RECHECK_MS;
        plan.lost(CLOUD, now);
        assertEquals(0, plan.retryIn(CLOUD, now));
    }

    @Test public void installerMustReadTheCloudEvenWhenHomeIsAvailable() {
        assertEquals(List.of(CLOUD), RoutePlan.candidates(true, HOME, true, true, true));
        assertEquals(List.of(CLOUD), RoutePlan.candidates(false, HTTPS, true, true, true));
    }
}
