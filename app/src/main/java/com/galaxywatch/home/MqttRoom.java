package com.galaxywatch.home;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

final class MqttRoom implements HomeLink {
    private static final Pattern ADDRESS = Pattern.compile(
        "(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::(\\d{1,5}))?");

    private final String address;
    private final List<Rooms.Light> lights;
    private final Events events;
    private volatile boolean closed;
    private volatile MqttAsyncClient client;

    MqttRoom(String address, List<Rooms.Light> lights, Events events) {
        this.address = address;
        this.lights = lights;
        this.events = events;
    }

    static String brokerUri(String value) {
        Matcher match = ADDRESS.matcher(value);
        if (!match.matches()) throw new IllegalArgumentException();
        int first = Integer.parseInt(match.group(1));
        int second = Integer.parseInt(match.group(2));
        int third = Integer.parseInt(match.group(3));
        int fourth = Integer.parseInt(match.group(4));
        int port = match.group(5) == null ? 1883 : Integer.parseInt(match.group(5));
        boolean privateIp = first == 10 || first == 172 && second >= 16 && second <= 31
            || first == 192 && second == 168;
        if (!privateIp || second > 255 || third > 255 || fourth > 255 || port < 1 || port > 65535) {
            throw new IllegalArgumentException();
        }
        return "tcp://" + first + "." + second + "." + third + "." + fourth + ":" + port;
    }

    static Boolean binary(String payload) {
        return switch (payload) {
            case "1" -> true;
            case "0" -> false;
            default -> null;
        };
    }

    @Override public void connect() {
        if (closed) return;
        try {
            MqttAsyncClient mqtt = new MqttAsyncClient(brokerUri(address), MqttAsyncClient.generateClientId(),
                new MemoryPersistence());
            client = mqtt;
            if (closed) {
                mqtt.close(true);
                return;
            }
            mqtt.setCallback(new MqttCallback() {
                @Override public void connectionLost(Throwable cause) { events.disconnected(); }

                @Override public void messageArrived(String topic, MqttMessage message) {
                    String value = new String(message.getPayload(), StandardCharsets.UTF_8);
                    events.state(topic, binary(value), message.isRetained());
                }

                @Override public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            MqttConnectOptions options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setConnectionTimeout(5);
            options.setKeepAliveInterval(10);
            options.setCleanSession(true);
            mqtt.connect(options).waitForCompletion(6000);
            String[] topics = new String[lights.size() + 1];
            topics[0] = Rooms.VACATION;
            for (int i = 0; i < lights.size(); i++) topics[i + 1] = lights.get(i).stateTopic();
            IMqttToken subscription = mqtt.subscribe(topics, new int[topics.length]);
            subscription.waitForCompletion(3000);
            for (int grant : subscription.getGrantedQos()) {
                if (grant == 128) throw new MqttException(MqttException.REASON_CODE_SUBSCRIBE_FAILED);
            }
            events.connected();
        } catch (MqttException | IllegalArgumentException failure) {
            events.disconnected();
            close();
        }
    }

    @Override public void send(Rooms.Light light, boolean value) throws MqttException {
        publish(light.commandTopic(), value);
    }

    @Override public void sendAllOff() throws MqttException {
        publish(Rooms.ALL_OFF, true);
    }

    private void publish(String topic, boolean value) throws MqttException {
        MqttAsyncClient mqtt = client;
        if (closed || mqtt == null || !mqtt.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        byte[] payload = (value ? "1" : "0").getBytes(StandardCharsets.UTF_8);
        mqtt.publish(topic, payload, 0, false);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        MqttAsyncClient mqtt = client;
        if (mqtt != null) new Thread(() -> disconnect(mqtt), "home-mqtt-disconnect").start();
    }

    private static void disconnect(MqttAsyncClient mqtt) {
        try {
            if (mqtt.isConnected()) mqtt.disconnectForcibly(0, 150, true);
            mqtt.close(true);
        } catch (MqttException ignored) {
            // The session may already have dropped off Wi-Fi.
        }
    }
}
