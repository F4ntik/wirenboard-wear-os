package com.galaxywatch.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.Test;

public class MqttRoomTest {
    private static final Rooms.Light LIGHT = new Rooms.Light("Свет", "85", "K1");

    @Test public void waitsForKnownLightAndBothValidVacationValues() {
        for (boolean vacation : new boolean[]{true, false}) {
            MqttRoom.Readiness pending = new MqttRoom.Readiness(List.of(LIGHT), null);
            pending.cache(Rooms.VACATION, vacation, true);
            pending.cache("another/light", true, true);
            pending.cache(LIGHT.stateTopic(), MqttRoom.binary("on"), false);
            assertFalse(pending.hasHouseState());

            pending.cache(LIGHT.stateTopic(), false, false);
            assertTrue(pending.hasHouseState());
            assertEquals(List.of(new MqttRoom.State(Rooms.VACATION, vacation, true),
                new MqttRoom.State(LIGHT.stateTopic(), false, false)), pending.snapshots());

            pending.cache(Rooms.VACATION, null, false);
            assertFalse(pending.hasHouseState());
        }
    }

    @Test public void requiresExactSerialWhenPinnedAndRejectsItsLoss() {
        MqttRoom.Readiness pending = new MqttRoom.Readiness(List.of(LIGHT), "Ab12Cd");
        pending.cache(Rooms.VACATION, false, true);
        pending.cache(LIGHT.stateTopic(), true, true);
        assertFalse(pending.hasHouseState());
        assertFalse(pending.matchSerial("Ab12Cd\n"));
        assertFalse(pending.matchSerial("Ab12Cd99"));
        assertFalse(pending.matchSerial("Ab12Ce"));
        assertTrue(pending.matchSerial("aB12cD"));
        assertTrue(pending.hasHouseState());
        assertFalse(pending.matchSerial(""));
        assertFalse(pending.hasHouseState());
    }

    @Test public void requiresEveryQosZeroSubscription() {
        assertTrue(MqttRoom.subscriptionAccepted(new int[]{0, 0, 0}, 3));
        assertFalse(MqttRoom.subscriptionAccepted(new int[]{0, 128, 0}, 3));
        assertFalse(MqttRoom.subscriptionAccepted(new int[]{0, 0}, 3));
        assertFalse(MqttRoom.subscriptionAccepted(null, 3));
    }

    @Test public void emitsSingleFailureAndBlocksPublishAfterFailedOrCancelledAttempt() {
        RecordingEvents failedEvents = new RecordingEvents();
        MqttRoom failed = new MqttRoom("invalid", List.of(LIGHT), failedEvents, "AB12CD");
        assertThrows(MqttException.class, failed::sendAllOff);
        failed.connect();
        failed.connect();
        failed.close();
        assertThrows(MqttException.class, () -> failed.send(LIGHT, true));
        assertEquals(1, failedEvents.disconnected);

        RecordingEvents cancelledEvents = new RecordingEvents();
        MqttRoom cancelled = new MqttRoom("invalid", List.of(LIGHT), cancelledEvents);
        cancelled.close();
        cancelled.connect();
        assertThrows(MqttException.class, cancelled::sendAllOff);
        assertEquals(0, cancelledEvents.disconnected);
    }

    private static final class RecordingEvents implements HomeLink.Events {
        int disconnected;

        @Override public void disconnected() { disconnected++; }
        @Override public void connected() { fail("Unexpected MQTT connection"); }
        @Override public void state(String topic, Boolean value, boolean snapshot) { fail("Unexpected MQTT state"); }
        @Override public void authenticationFailed() { fail("Unexpected authentication callback"); }
        @Override public void commandUnconfirmed() { fail("Unexpected command callback"); }
    }
}
