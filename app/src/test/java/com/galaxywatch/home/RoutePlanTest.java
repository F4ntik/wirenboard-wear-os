package com.galaxywatch.home;

import static org.junit.Assert.assertEquals;
import static com.galaxywatch.home.RoutePlan.Route.*;
import org.junit.Test;

public class RoutePlanTest {
    private final RoutePlan plan = new RoutePlan();

    @Test public void prefersHomeFallsBackAndChecksHomeAgain() {
        assertEquals(HOME, plan.select(true, CLOUD, true, true, false, 10));
        plan.homeFailed(20);
        assertEquals(CLOUD, plan.select(true, HOME, true, true, false, 21));
        assertEquals(HOME, plan.select(true, CLOUD, true, true, false, 20 + RoutePlan.RECHECK_MS));
    }

    @Test public void cloudReceiptUsesCloudAndFailedCloudDoesNotPreventHome() {
        assertEquals(CLOUD, plan.select(true, HOME, true, true, true, 10));
        plan.cloudFailed(20);
        assertEquals(HOME, plan.select(true, HOME, true, true, false, 21));
        assertEquals(CLOUD, plan.select(false, HOME, true, true, true, 21));
        plan.homeFailed(22);
        assertEquals(HOME, plan.select(true, HOME, true, true, false, 23));
        assertEquals(CLOUD, plan.select(true, HOME, true, true, true, 20 + RoutePlan.RECHECK_MS));
    }

    @Test public void handlesSingleConfiguredRouteAndRespectsManualSelection() {
        assertEquals(CLOUD, plan.select(true, HOME, false, true, false, 10));
        assertEquals(HOME, plan.select(true, CLOUD, true, false, false, 10));
        plan.homeFailed(20);
        for (var manual : RoutePlan.Route.values())
            assertEquals(manual, plan.select(false, manual, true, true, false, 21));
    }
}
