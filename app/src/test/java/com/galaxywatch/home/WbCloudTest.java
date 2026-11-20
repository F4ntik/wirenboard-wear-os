package com.galaxywatch.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class WbCloudTest {
    private static final String ORIGIN = "https://serial01-8765.apps.wirenboard.cloud";

    @Test public void acceptsSameServiceHttpsUrls() throws IOException {
        assertEquals(URI.create(ORIGIN + "/?grant=temporary"),
            WbCloud.tunnelUri(ORIGIN, "/?grant=temporary"));
        assertEquals(URI.create(ORIGIN + "/v1/state"), WbCloud.tunnelUri(ORIGIN, ORIGIN + "/v1/state"));
    }

    @Test public void rejectsRedirectsAwayFromDesignatedService() {
        for (String link : new String[]{
            "http://serial01-8765.apps.wirenboard.cloud/",
            "https://serial01-8766.apps.wirenboard.cloud/",
            "https://serial01-8765.apps.wirenboard.cloud.attacker.example/",
            "//attacker.example/",
            "https://wirenboard.cloud/",
            "https://user@serial01-8765.apps.wirenboard.cloud/",
            ORIGIN + ":444/",
            ORIGIN + "/#fragment",
        }) {
            assertThrows(IOException.class, () -> WbCloud.tunnelUri(ORIGIN, link));
        }
    }

    @Test public void retriesSavingRotatedSessionWithSameRefreshToken() throws IOException {
        String gatewayKey = "example-gateway-key-1234567890-abcd";
        WbCloud.Profile previous = new WbCloud.Profile("SERIAL01", 8765, gatewayKey, "old.refresh.token");
        WbCloud.Profile rotated = new WbCloud.Profile("SERIAL01", 8765, gatewayKey, "new.refresh.token");
        WbCloud.Session session = new WbCloud.Session(previous);
        List<String> writes = new ArrayList<>();
        session.rotate(rotated, "new.access.token");

        assertThrows(WbCloud.StorageUnavailable.class, () -> session.persist(profile -> {
            writes.add(profile.refresh());
            throw new WbCloud.StorageUnavailable();
        }));
        assertEquals("new.refresh.token", session.profile.refresh());
        assertEquals("new.access.token", session.access);
        session.persist(profile -> writes.add(profile.refresh()));
        session.persist(profile -> writes.add(profile.refresh()));
        assertEquals(List.of("new.refresh.token", "new.refresh.token"), writes);
    }
}
