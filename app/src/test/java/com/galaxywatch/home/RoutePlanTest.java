package com.galaxywatch.home;

import static org.junit.Assert.assertEquals;
import static com.galaxywatch.home.RoutePlan.Route.*;
import org.junit.Test;

public class RoutePlanTest {
    private final RoutePlan plan = new RoutePlan(false);

    private RoutePlan.Route auto() { return plan.select(true, HOME, true, true, false); }

    @Test public void foreignLanThenTemporaryCloudFailureRetriesCloudFirst() {
        assertEquals(HOME, auto());
        plan.attemptingHome();
        plan.homeFailed(1200);
        assertEquals(CLOUD, auto());
        plan.cloudFailed(1600);
        assertEquals(CLOUD, auto());
        assertEquals(0, plan.homeDelay(1601));
        plan.cloudFailed(4100);
        assertEquals(HOME, auto());
        assertEquals(5000, plan.homeTimeout(true, 4101));
        plan.homeFailed(9000);
        assertEquals(CLOUD, auto());
    }

    @Test public void navigationDoesNotRestartAnUnconfirmedHomeAttempt() {
        plan.attemptingHome();
        assertEquals(CLOUD, auto());
        plan.confirmed(HOME);
        assertEquals(HOME, auto());
    }

    @Test public void lastVerifiedCloudServesNewScreensWhileHomeIsCheckedInBackground() {
        RoutePlan reopened = new RoutePlan(true);
        assertEquals(CLOUD, reopened.select(true, HOME, true, true, false));
        assertEquals(0, reopened.homeDelay(10));
        reopened.homeFailed(20);
        assertEquals(RoutePlan.RECHECK_MS, reopened.homeDelay(20));
        assertEquals(0, reopened.homeDelay(20 + RoutePlan.RECHECK_MS));
        assertEquals(CLOUD, reopened.select(true, HOME, true, true, false));
        reopened.confirmed(HOME);
        assertEquals(HOME, reopened.select(true, CLOUD, true, true, false));
    }

    @Test public void givesHomeAHeadStartOnlyIfCloudIsConfiguredAndUsable() {
        assertEquals(5000, plan.homeTimeout(false, 10));
        assertEquals(1200, plan.homeTimeout(true, 10));
        plan.cloudFailed(20);
        assertEquals(5000, plan.homeTimeout(true, 21));
        plan.confirmed(HOME);
        assertEquals(1200, plan.homeTimeout(true, 24));
    }

    @Test public void forcedCloudReceiptAndManualSelectionArePreserved() {
        assertEquals(CLOUD, plan.select(true, HOME, true, true, true));
        assertEquals(CLOUD, plan.select(false, HOME, true, true, true));
        assertEquals(CLOUD, plan.select(true, HOME, false, true, false));
        assertEquals(HOME, plan.select(true, CLOUD, true, false, false));
        plan.homeFailed(20);
        for (var manual : RoutePlan.Route.values())
            assertEquals(manual, plan.select(false, manual, true, true, false));
    }
}
