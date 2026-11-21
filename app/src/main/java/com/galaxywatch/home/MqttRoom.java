package com.galaxywatch.home;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
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
    private static final String SERIAL = "/devices/system/controls/Short SN";
    private static final int AUTO_TIMEOUT_MS = 5000;

    private final String address;
    private final String[] topics;
    private final Events events;
    private final Readiness readiness;
    private final Socket socket = new Socket();
    private volatile boolean closed;
    private MqttAsyncClient client;
    private long deadline;
    private boolean opening;
    private boolean acknowledged;
    private boolean active;

    MqttRoom(String address, List<Rooms.Light> lights, Events events) {
        this(address, lights, null, events);
    }

    MqttRoom(String address, List<Rooms.Light> lights, Events events, String expectedSerial) {
        this(address, lights, new Readiness(lights, expectedSerial), events);
    }

    private MqttRoom(String address, List<Rooms.Light> lights, Readiness readiness, Events events) {
        this.address = address;
        this.events = events;
        this.readiness = readiness;
        List<String> subscriptions = new ArrayList<>();
        subscriptions.add(Rooms.VACATION);
        for (Rooms.Light light : lights) subscriptions.add(light.stateTopic());
        if (readiness != null && readiness.expectedSerial != null) subscriptions.add(SERIAL);
        topics = subscriptions.toArray(new String[0]);
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
        long started = System.nanoTime();
        try {
            IMqttToken transport = start();
            if (transport == null) return;
            long connectDeadline = started + TimeUnit.MILLISECONDS.toNanos(readiness == null ? 6000 : AUTO_TIMEOUT_MS);
            long connectTime = TimeUnit.NANOSECONDS.toMillis(connectDeadline - System.nanoTime());
            if (connectTime <= 0) throw new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT);
            transport.waitForCompletion(connectTime);
            synchronized (this) {
                if (closed) return;
                deadline = readiness == null ? System.nanoTime() + TimeUnit.SECONDS.toNanos(3) : connectDeadline;
                client.subscribe(topics, new int[topics.length], null, new IMqttActionListener() {
                    @Override public void onSuccess(IMqttToken token) { acknowledge(token.getGrantedQos()); }
                    @Override public void onFailure(IMqttToken token, Throwable cause) { finish(true); }
                });
                long remaining;
                while (!closed && !active && (remaining = deadline - System.nanoTime()) > 0) {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                }
                if (closed || active) return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (MqttException | IllegalArgumentException failure) {
            // Transport and subscription failures share the same offline handling.
        }
        finish(true);
    }

    private synchronized IMqttToken start() throws MqttException {
        if (closed || client != null) return null;
        client = new MqttAsyncClient(brokerUri(address), MqttAsyncClient.generateClientId(),
            new MemoryPersistence());
        client.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) { finish(true); }

            @Override public void messageArrived(String topic, MqttMessage message) {
                receive(topic, new String(message.getPayload(), StandardCharsets.UTF_8), message.isRetained());
            }

            @Override public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(10);
        options.setCleanSession(true);
        options.setSocketFactory(new ConnectionSocketFactory(socket));
        IMqttToken token = client.connect(options, null, new IMqttActionListener() {
            @Override public void onSuccess(IMqttToken token) { transportFinished(); }
            @Override public void onFailure(IMqttToken token, Throwable cause) { transportFinished(); }
        });
        opening = true;
        return token;
    }

    private void transportFinished() {
        synchronized (this) {
            opening = false;
            if (!closed) return;
        }
        release(client);
    }

    static boolean subscriptionAccepted(int[] grants, int count) {
        if (grants == null || grants.length != count) return false;
        for (int grant : grants) if (grant != 0) return false;
        return true;
    }

    private void acknowledge(int[] grants) {
        if (!subscriptionAccepted(grants, topics.length)) {
            finish(true);
            return;
        }
        synchronized (this) {
            if (closed) return;
            acknowledged = true;
        }
        activateIfReady();
    }

    private void receive(String topic, String value, boolean retained) {
        if (readiness != null && readiness.expectedSerial != null && SERIAL.equals(topic)) {
            boolean matching;
            synchronized (this) {
                if (closed) return;
                matching = readiness.matchSerial(value);
                if (!matching) active = false;
            }
            if (!matching) finish(true);
            else activateIfReady();
            return;
        }
        Boolean state = binary(value);
        boolean report;
        synchronized (this) {
            if (closed) return;
            report = readiness == null || active;
            if (!report) readiness.cache(topic, state, retained);
        }
        if (report) events.state(topic, state, retained);
        else activateIfReady();
    }

    // SUBACK and state messages share Paho's callback thread, preserving state delivery order.
    private void activateIfReady() {
        List<State> snapshots;
        synchronized (this) {
            if (closed || active || !acknowledged || deadline - System.nanoTime() <= 0
                || (readiness != null && !readiness.hasHouseState()) || !client.isConnected()) return;
            snapshots = readiness == null ? List.of() : readiness.snapshots();
            active = true;
            notifyAll();
        }
        for (State state : snapshots) {
            if (closed) return;
            events.state(state.topic(), state.value(), state.retained());
        }
        if (!closed) events.connected();
    }

    @Override public void send(Rooms.Light light, boolean value) throws MqttException {
        publish(light.commandTopic(), value);
    }

    @Override public void sendAllOff() throws MqttException {
        publish(Rooms.ALL_OFF, true);
    }

    private synchronized void publish(String topic, boolean value) throws MqttException {
        MqttAsyncClient mqtt = client;
        if (closed || mqtt == null || (readiness != null && !active) || !mqtt.isConnected()) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
        byte[] payload = (value ? "1" : "0").getBytes(StandardCharsets.UTF_8);
        mqtt.publish(topic, payload, 0, false);
    }

    @Override public void close() {
        finish(false);
    }

    private void finish(boolean failure) {
        MqttAsyncClient mqtt;
        boolean abortOpening;
        synchronized (this) {
            if (closed) return;
            closed = true;
            notifyAll();
            mqtt = client;
            abortOpening = opening;
        }
        if (abortOpening) {
            // Paho rejects close() during CONNECT. Closing the socket lets its callback finish cleanup.
            new Thread(this::closeSocket, "home-mqtt-cancel-connect").start();
        } else if (mqtt != null) release(mqtt);
        if (failure) events.disconnected();
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The MQTT session may already have closed the socket.
        }
    }

    private void release(MqttAsyncClient mqtt) {
        new Thread(() -> {
            try {
                if (mqtt.isConnected()) mqtt.disconnectForcibly(0, 150, true);
            } catch (MqttException ignored) {
                // Still release the socket and the client when sending DISCONNECT fails.
            }
            closeSocket();
            try {
                mqtt.close(true);
            } catch (MqttException ignored) {
                // The session may already have dropped off Wi-Fi.
            }
        }, "home-mqtt-disconnect").start();
    }

    record State(String topic, Boolean value, boolean retained) {}

    static final class Readiness {
        private final String expectedSerial;
        private final Map<String, State> lights = new LinkedHashMap<>();
        private State vacation = new State(Rooms.VACATION, null, false);
        private boolean serialMatched;

        Readiness(List<Rooms.Light> lights, String expectedSerial) {
            this.expectedSerial = expectedSerial;
            serialMatched = expectedSerial == null;
            for (Rooms.Light light : lights) {
                String topic = light.stateTopic();
                this.lights.put(topic, new State(topic, null, false));
            }
        }

        boolean matchSerial(String value) {
            serialMatched = expectedSerial == null || !expectedSerial.isEmpty() && expectedSerial.equalsIgnoreCase(value);
            return serialMatched;
        }

        void cache(String topic, Boolean value, boolean retained) {
            if (Rooms.VACATION.equals(topic)) vacation = new State(topic, value, retained);
            else if (lights.containsKey(topic)) lights.put(topic, new State(topic, value, retained));
        }

        boolean hasHouseState() {
            return serialMatched && vacation.value() != null
                && lights.values().stream().anyMatch(state -> state.value() != null);
        }

        List<State> snapshots() {
            List<State> snapshots = new ArrayList<>();
            snapshots.add(vacation);
            for (State state : lights.values()) {
                if (state.value() != null) snapshots.add(state);
            }
            return snapshots;
        }
    }

    private static final class ConnectionSocketFactory extends SocketFactory {
        private final Socket socket;

        ConnectionSocketFactory(Socket socket) { this.socket = socket; }

        @Override public Socket createSocket() { return socket; }
        @Override public Socket createSocket(String host, int port) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(InetAddress host, int port) { throw new UnsupportedOperationException(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) {
            throw new UnsupportedOperationException();
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) {
            throw new UnsupportedOperationException();
        }
    }
}
