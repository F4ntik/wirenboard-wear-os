package com.galaxywatch.home;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.json.JSONException;
import org.json.JSONObject;

final class RemoteRoom implements HomeLink {
    private record Snapshot(Boolean vacation, Map<Rooms.Light, Boolean> states) {}

    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { this.status = status; }
    }

    private final RemoteSettings.Config config;
    private final WbCloud cloud;
    private final List<Rooms.Light> lights;
    private final Events events;
    private final ScheduledExecutorService requests = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean connected;
    private volatile boolean closed;
    private volatile HttpsURLConnection inFlight;

    RemoteRoom(RemoteSettings.Config config, List<Rooms.Light> lights, Events events) {
        this(config, null, lights, events);
    }

    RemoteRoom(WbCloud cloud, List<Rooms.Light> lights, Events events) {
        this(null, cloud, lights, events);
    }

    private RemoteRoom(RemoteSettings.Config config, WbCloud cloud, List<Rooms.Light> lights, Events events) {
        this.config = config;
        this.cloud = cloud;
        this.lights = lights;
        this.events = events;
    }

    @Override public void connect() {
        try {
            requests.scheduleWithFixedDelay(this::poll, 0, 2, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // The screen was closed before its connection started.
        }
    }

    void readOnce() {
        try {
            requests.execute(this::poll);
        } catch (RejectedExecutionException ignored) {
            // The reader was closed before its request started.
        }
    }

    private void poll() {
        if (closed) return;
        try {
            Snapshot snapshot = request("/v1/state", null);
            if (closed) return;
            boolean firstRead = !connected;
            connected = true;
            emit(snapshot, Set.of());
            if (firstRead) events.connected();
        } catch (IOException | JSONException failure) {
            failed(failure);
        }
    }

    @Override public void send(Rooms.Light light, boolean value) throws MqttException {
        submit(new JSONObject(Map.of("lights", Map.of(key(light), value))), Set.of(light));
    }

    @Override public void sendOff(Collection<Rooms.Light> targets) throws MqttException {
        Map<String, Boolean> values = new HashMap<>();
        for (Rooms.Light light : targets) values.put(key(light), false);
        submit(new JSONObject(Map.of("lights", values)), Set.copyOf(targets));
    }

    @Override public void sendAllOff() throws MqttException {
        submit(new JSONObject(Map.of("allOff", true)), Set.copyOf(lights));
    }

    private void submit(JSONObject command, Set<Rooms.Light> targets) throws MqttException {
        if (closed || !connected) throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            requests.execute(() -> {
                if (closed || !connected) return;
                if (System.nanoTime() >= deadline) {
                    events.commandUnconfirmed();
                    return;
                }
                try {
                    Snapshot snapshot = request("/v1/lights", command);
                    if (!closed) emit(snapshot, targets);
                } catch (IOException | JSONException failure) {
                    if (closed) return;
                    if (failure instanceof HttpFailure http
                        && (http.status == 403 || http.status == 409 || http.status == 504)) {
                        events.commandUnconfirmed();
                    } else failed(failure);
                }
            });
        } catch (RejectedExecutionException closed) {
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }
    }

    private Snapshot request(String path, JSONObject body) throws IOException, JSONException {
        try {
            return exchangeSnapshot(path, body);
        } catch (WbCloud.RejectedTunnel expired) {
            if (body != null || closed) throw expired;
            try {
                return exchangeSnapshot(path, null);
            } catch (WbCloud.RejectedTunnel rejected) {
                throw new WbCloud.LoginRequired();
            }
        }
    }

    private Snapshot exchangeSnapshot(String path, JSONObject body) throws IOException, JSONException {
        HttpsURLConnection request = cloud == null ? open(config.origin(), path) : cloud.open(path, body == null, () -> closed);
        inFlight = request;
        try {
            if (closed) throw new IOException();
            request.setRequestProperty("Authorization", "Bearer " + (cloud == null ? config.token() : cloud.gatewayKey()));
            JSONObject json = exchange(request, body);
            JSONObject values = json.getJSONObject("lights");
            Map<Rooms.Light, Boolean> states = new HashMap<>();
            for (Rooms.Light light : lights) states.put(light, binary(values.get(key(light))));
            return new Snapshot(binary(json.get("vacation")), states);
        } catch (HttpFailure failure) {
            throw cloud == null || closed ? failure : cloud.gatewayRejected(failure);
        } finally {
            inFlight = null;
            request.disconnect();
        }
    }

    static String pair(String origin, String code) throws IOException, JSONException {
        HttpsURLConnection request = open(origin, "/v1/pair");
        try {
            return exchange(request, new JSONObject(Map.of("code", code))).getString("token");
        } finally {
            request.disconnect();
        }
    }

    static HttpsURLConnection open(String origin, String path) throws IOException {
        HttpsURLConnection request = (HttpsURLConnection) URI.create(origin + path).toURL().openConnection();
        request.setInstanceFollowRedirects(false);
        request.setUseCaches(false);
        request.setConnectTimeout(3500);
        request.setReadTimeout(4500);
        request.setRequestProperty("Accept", "application/json");
        return request;
    }

    static JSONObject exchange(HttpsURLConnection request, JSONObject body) throws IOException, JSONException {
        return new JSONObject(exchangeBody(request, body, 8192));
    }

    static String exchangeBody(HttpsURLConnection request, JSONObject body, int maxResponseBytes) throws IOException {
        if (body != null) {
            byte[] json = body.toString().getBytes(StandardCharsets.UTF_8);
            request.setRequestMethod("POST");
            request.setRequestProperty("Content-Type", "application/json");
            request.setFixedLengthStreamingMode(json.length);
            request.setDoOutput(true);
            try (OutputStream output = request.getOutputStream()) {
                output.write(json);
            }
        }
        int status = request.getResponseCode();
        if (status != 200) throw new HttpFailure(status);
        try (InputStream input = request.getInputStream()) {
            return readResponse(input, maxResponseBytes);
        }
    }

    static String readResponse(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (output.size() + count > limit) throw new IOException("Response exceeds protocol limit");
            output.write(buffer, 0, count);
        }
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString();
    }

    private static Boolean binary(Object value) throws JSONException {
        if (value == JSONObject.NULL) return null;
        if (value instanceof Boolean state) return state;
        throw new JSONException("Expected a boolean state");
    }

    private void emit(Snapshot snapshot, Set<Rooms.Light> confirmed) {
        events.state(Rooms.VACATION, snapshot.vacation(), true);
        for (Rooms.Light light : lights) {
            events.state(light.stateTopic(), snapshot.states().get(light), true);
        }
        for (Rooms.Light light : confirmed) events.state(light.stateTopic(), snapshot.states().get(light), false);
    }

    private static String key(Rooms.Light light) { return light.relay() + "/" + light.channel(); }

    private void failed(Exception failure) {
        if (closed) return;
        close();
        if (failure instanceof WbCloud.StorageUnavailable) events.storageUnavailable();
        else if (failure instanceof WbCloud.LoginRequired
            || (failure instanceof HttpFailure http && http.status == 401)) events.authenticationFailed();
        else events.disconnected();
    }

    @Override public boolean isReady() { return connected && !closed; }

    @Override public int confirmationTimeoutMs() { return 11000; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        connected = false;
        // A token refresh already on the wire must finish storing its replacement.
        requests.shutdown();
        HttpsURLConnection request = inFlight;
        if (request != null) new Thread(request::disconnect, "home-https-disconnect").start();
    }
}
