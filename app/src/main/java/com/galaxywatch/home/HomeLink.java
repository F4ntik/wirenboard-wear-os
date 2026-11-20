package com.galaxywatch.home;

import java.util.Collection;

import org.eclipse.paho.client.mqttv3.MqttException;

interface HomeLink extends AutoCloseable {
    interface Events {
        void connected();
        void state(String topic, Boolean value, boolean snapshot);
        void disconnected();
        default void storageUnavailable() { disconnected(); }
        void authenticationFailed();
        void commandUnconfirmed();
    }

    void connect();
    void send(Rooms.Light light, boolean value) throws MqttException;
    void sendAllOff() throws MqttException;

    default void sendOff(Collection<Rooms.Light> lights) throws MqttException {
        for (Rooms.Light light : lights) send(light, false);
    }

    default int confirmationTimeoutMs() { return 4500; }

    @Override void close();
}
