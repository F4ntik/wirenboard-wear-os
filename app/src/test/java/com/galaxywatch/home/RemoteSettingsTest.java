package com.galaxywatch.home;

import static org.junit.Assert.*;

import org.junit.Test;

public class RemoteSettingsTest {
    @Test public void acceptsHttpsOriginAndNormalizesTrailingSlash() {
        assertEquals("https://lights.example.net", RemoteSettings.origin(" https://lights.example.net/ "));
        assertEquals("https://lights.example.net:8443", RemoteSettings.origin("https://lights.example.net:8443"));
    }

    @Test public void rejectsNonTlsOrAmbiguousDestinations() {
        for (String value : new String[]{"http://lights.example.net", "lights.example.net",
            "https://lights.example.net@wrong.example.net", "https://lights.example.net#x",
            "https://lights.example.net?other=host", "https://lights.example.net/service",
            "https://lights.example.net:0", "https://lights.example.net:70000", "https://%2Flights.example.net"}) {
            assertThrows(value, IllegalArgumentException.class, () -> RemoteSettings.origin(value));
        }
    }

    @Test public void acceptsOnlyTokenSafeCharactersAndLength() {
        assertTrue(RemoteSettings.validToken("aB_0-".repeat(9)));
        assertFalse(RemoteSettings.validToken("a".repeat(31)));
        assertFalse(RemoteSettings.validToken("a".repeat(129)));
        assertFalse(RemoteSettings.validToken("a".repeat(40) + "\r\n"));
    }
}
