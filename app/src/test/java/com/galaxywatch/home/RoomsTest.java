package com.galaxywatch.home;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class RoomsTest {
    @Test public void allowsOnlyPrivateIpv4AndValidPorts() {
        assertEquals("tcp://10.20.30.40:1883", MqttRoom.brokerUri("10.020.030.040"));
        assertEquals("tcp://172.31.1.2:1884", MqttRoom.brokerUri("172.31.1.2:1884"));
        for (String value : new String[]{"172.32.0.1", "192.168.0.256", "10.20.30.40:0",
            "10.20.30.40:65536", "8.8.8.8", "localhost", "10.20.30.40/path", "10.0.0.2\n"}) {
            assertThrows(value, IllegalArgumentException.class, () -> MqttRoom.brokerUri(value));
        }
    }

    @Test public void invalidStatesRemainUnknown() {
        assertEquals(Boolean.TRUE, MqttRoom.binary("1"));
        assertEquals(Boolean.FALSE, MqttRoom.binary("0"));
        assertNull(MqttRoom.binary(""));
        assertNull(MqttRoom.binary("on"));
        assertNull(Rooms.find("unknown"));
    }

    @Test public void kitchenAndLivingRoomShareExactlyThreeOutputs() {
        Rooms.Room group = Rooms.find("living");
        assertSame(group, Rooms.find("kitchen"));
        assertEquals("Кухня-гостиная", group.label());
        assertEquals(3, group.lights().size());
        Map<String, String> channels = Map.of(
            "В гостиной", "K1", "У дивана", "K2", "На кухне", "K3");
        for (Rooms.Light light : group.lights()) {
            String state = "/devices/wb-mr6c_101/controls/" + channels.get(light.label());
            assertEquals(state, light.stateTopic());
            assertEquals(state + "/on", light.commandTopic());
        }
        assertEquals(3, group.lights().stream().map(Rooms.Light::stateTopic).distinct().count());
    }

    @Test public void hallwayOffTargetsOnlyItsTwoOutputs() {
        Map<String, String> expected = Map.of("Вход", "101/K6", "Коридор", "102/K1");
        Rooms.Room hallway = Rooms.find("hall");
        assertEquals(2, hallway.lights().size());
        for (Rooms.Light light : hallway.lights()) {
            assertEquals(expected.get(light.label()), light.relay() + "/" + light.channel());
        }
    }

    @Test public void bathroomAndShowerHaveIndependentOutputs() {
        Rooms.Room bathroom = Rooms.find("wc");
        assertEquals("Санузел", bathroom.label());
        assertEquals(1, bathroom.lights().size());
        assertEquals("/devices/wb-mr6c_102/controls/K2", bathroom.lights().get(0).stateTopic());
        Rooms.Room shower = Rooms.find("shower");
        assertEquals("Душевая", shower.label());
        assertEquals(1, shower.lights().size());
        assertEquals("/devices/wb-mr6c_102/controls/K3", shower.lights().get(0).stateTopic());
    }

    @Test public void masterOffTracksEveryRelayInTheExistingRule() {
        Set<String> topics = new HashSet<>();
        for (Rooms.Light light : Rooms.MASTER_LIGHTS) {
            assertTrue(topics.add(light.stateTopic()));
        }
        assertEquals(12, topics.size());
        for (String relay : new String[]{"101", "102"}) {
            for (int channel = 1; channel <= 6; channel++) {
                assertTrue(topics.contains("/devices/wb-mr6c_" + relay + "/controls/K" + channel));
            }
        }
        assertEquals("/devices/example_home/controls/AllOff/on", Rooms.ALL_OFF);
    }
}
