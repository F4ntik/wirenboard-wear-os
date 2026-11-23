package com.galaxywatch.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import org.junit.Test;

public class WbCloudTest {
    private static final String ORIGIN = "https://serial01-8765.apps.wirenboard.cloud";

    @Test public void sendsPlainTunnelCookieOnFollowingRequest() throws IOException {
        var cookies = WbCloud.serviceCookieJar();
        URI origin = URI.create(ORIGIN);
        cookies.put(origin.resolve("/authorize"), Map.of("Set-Cookie", List.of(
            "session=example; Max-Age=600; Path=/; Secure; HttpOnly",
            "foreign=other; Domain=external.example; Path=/")));
        assertEquals(List.of("session=example"), cookies.get(origin.resolve("/v1/state"), Map.of()).get("Cookie"));
    }

    @Test public void acceptsSameServiceHttpsUrls() throws IOException {
        assertEquals(URI.create(ORIGIN + "/?grant=temporary"),
            WbCloud.tunnelUri(ORIGIN, "/?grant=temporary"));
        assertEquals(URI.create(ORIGIN + "/v1/state"), WbCloud.tunnelUri(ORIGIN, ORIGIN + "/v1/state"));
    }

    @Test public void onlyGatewayJsonNotFoundProvesV1Compatibility() throws IOException {
        String missing = "{\"error\":\"not_found\"}";
        assertTrue(WbCloud.isGatewayNotFound(response("application/json; charset=utf-8", missing), 404));
        assertFalse(WbCloud.isGatewayNotFound(response("text/html", missing), 404));
        assertFalse(WbCloud.isGatewayNotFound(response("application/json", "{\"error\":\"unknown\"}"), 404));
        assertFalse(WbCloud.isGatewayNotFound(response("application/json", missing), 503));
    }

    private static HttpsURLConnection response(String type, String body) throws IOException {
        return new HttpsURLConnection(URI.create(ORIGIN).toURL()) {
            @Override public String getContentType() { return type; }
            @Override public InputStream getErrorStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
            @Override public String getCipherSuite() { return null; }
            @Override public Certificate[] getLocalCertificates() { return null; }
            @Override public Certificate[] getServerCertificates() { return null; }
            @Override public void disconnect() {}
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() {}
        };
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
