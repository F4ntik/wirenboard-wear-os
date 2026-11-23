package com.galaxywatch.home;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class CatalogMqtt implements AutoCloseable {
    static final String CATALOG = "/devices/wb-wear-light-gateway/controls/Catalog";
    static final String REVISION = CATALOG + "Revision";
    private static final Set<String> IDENTITY = Set.of(MqttRoom.SERIAL, REVISION);
    private static final Set<String> METADATA = Set.of(MqttRoom.SERIAL, REVISION, CATALOG);
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    private final Socket socket;
    private final MqttAsyncClient client;
    private final Map<String, Value> messages = new HashMap<>();
    private final Map<String, String> commandTypes = new HashMap<>();
    private final Set<String> writableControls = new HashSet<>();
    private final Set<String> subscriptions = new HashSet<>(IDENTITY);
    private final Set<String> retainedMetadata = new HashSet<>();
    private boolean closed;
    private boolean disconnected;
    private long sequence;
    private Metadata verified;

    private record Value(String text, boolean retained, long sequence) {}
    private record Metadata(String serial, String catalog, String revision) {}
    private record Published(IMqttDeliveryToken token, long after) {}

    CatalogMqtt(String address) throws MqttException {
        this(address, new Socket());
    }

    CatalogMqtt(String address, Socket socket) throws MqttException {
        this.socket = socket;
        client = new MqttAsyncClient(MqttRoom.brokerUri(address), MqttAsyncClient.generateClientId(),
            new MemoryPersistence());
        client.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable failure) {
                synchronized (CatalogMqtt.this) {
                    disconnected = true;
                    CatalogMqtt.this.notifyAll();
                }
            }

            @Override public void messageArrived(String topic, MqttMessage message) { receive(topic, message); }
            @Override public void deliveryComplete(IMqttDeliveryToken token) {}
        });
    }

    void connect() throws IOException, MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setConnectionTimeout(4);
        options.setKeepAliveInterval(10);
        options.setCleanSession(true);
        options.setSocketFactory(new MqttRoom.ConnectionSocketFactory(socket));
        client.connect(options, null, new IMqttActionListener() {
            @Override public void onSuccess(IMqttToken token) { closeLateConnection(); }
            @Override public void onFailure(IMqttToken token, Throwable failure) { closeLateConnection(); }
        }).waitForCompletion(remainingMillis(deadline));
        subscribe(IDENTITY);
    }

    WatchCatalog layout(String pinned, WatchCatalog cached) throws IOException, JSONException, InterruptedException, MqttException {
        boolean needsSubscription;
        synchronized (this) {
            Metadata candidate = awaitMetadata(pinned, false);
            if (cached != null && cached.matchesSerial(candidate.serial()) && cached.revision.equals(candidate.revision())
                && !subscriptions.contains(CATALOG)) {
                verified = candidate;
                return cached;
            }
            needsSubscription = subscriptions.add(CATALOG);
        }
        if (needsSubscription) subscribe(Set.of(CATALOG));
        synchronized (this) {
            do {
                Metadata candidate = awaitMetadata(pinned, true);
                WatchCatalog catalog = WatchCatalog.parse(candidate.catalog());
                if (!catalog.matchesSerial(candidate.serial())) throw new CatalogLink.WrongController();
                if (catalog.revision.equals(candidate.revision())) {
                    verified = candidate;
                    return catalog;
                }
            } while (await(deadline));
        }
        throw new IOException("Catalog revision unavailable");
    }

    private Metadata awaitMetadata(String pinned, boolean requireCatalog) throws IOException, InterruptedException {
        do {
            requireConnected();
            Metadata candidate = metadata();
            if (candidate.serial() != null && pinned != null && !pinned.equalsIgnoreCase(candidate.serial())) {
                throw new CatalogLink.WrongController();
            }
            if (retainedMetadata.contains(REVISION) && WatchCatalog.validSerial(candidate.serial())
                && WatchCatalog.validRevision(candidate.revision())
                && (!requireCatalog || (retainedMetadata.contains(CATALOG) && candidate.catalog() != null))) return candidate;
        } while (await(deadline));
        throw new IOException("Catalog metadata unavailable");
    }

    CatalogLink.State state(WatchCatalog catalog) throws IOException, MqttException, InterruptedException {
        Set<String> topics = new HashSet<>();
        if (catalog.vacation != null) topics.add(catalog.vacation);
        for (WatchCatalog.Light light : catalog.lights.values()) {
            topics.add(light.power);
            if (light.brightness != null) topics.add(light.brightness);
            if (light.color != null) topics.add(light.color);
        }
        synchronized (this) {
            requireLayout();
            for (String topic : topics) messages.remove(topic);
            subscriptions.addAll(topics);
        }
        if (!topics.isEmpty()) subscribe(topics);
        long stateDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200));
        synchronized (this) {
            while (!messages.keySet().containsAll(topics)) {
                requireLayout();
                if (!await(stateDeadline)) break;
            }
            requireLayout();
            return snapshot(catalog);
        }
    }

    CatalogLink.State send(WatchCatalog catalog, Map<String, CatalogLink.Change> changes,
                           boolean allOff, CatalogLink.SendGuard guard)
        throws IOException, MqttException, InterruptedException {
        CatalogLink.State before;
        synchronized (this) {
            requireLayout();
            before = snapshot(catalog);
        }
        CatalogLink.requireKnown(before, changes);
        Map<String, String> metadata = commandMetadata(catalog, changes, allOff);
        loadCommandMetadata(metadata);
        Map<String, String> needed = new LinkedHashMap<>();
        for (Map.Entry<String, CatalogLink.Change> entry : changes.entrySet()) {
            CatalogLink.Change change = entry.getValue();
            if (!change.matches(before.lights.get(entry.getKey()))) {
                needed.put(change.topic(catalog.lights.get(entry.getKey())), change.payload());
            }
        }
        Map<String, Long> sentAfter = new LinkedHashMap<>();
        long confirmBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        if (allOff && catalog.allOff != null) {
            Published master = checkedPublish(catalog, catalog.allOff, "1", metadata, guard);
            master.token().waitForCompletion(remainingMillis(confirmBy));
            for (String topic : needed.keySet()) sentAfter.put(topic, master.after());
        } else {
            for (Map.Entry<String, String> target : needed.entrySet()) {
                sentAfter.put(target.getKey(), checkedPublish(catalog, target.getKey(), target.getValue(), metadata, guard).after());
            }
        }
        synchronized (this) {
            do {
                requireLayout();
                requireMetadata(metadata);
                if (receivedEcho(sentAfter)) {
                    CatalogLink.State confirmed = snapshot(catalog);
                    if (CatalogLink.matches(confirmed, changes)) return confirmed;
                }
            } while (await(confirmBy));
        }
        throw new IOException("MQTT confirmation unavailable");
    }

    private synchronized Published checkedPublish(WatchCatalog catalog, String topic, String payload,
                                                  Map<String, String> metadata, CatalogLink.SendGuard guard)
        throws IOException, MqttException {
        guard.check();
        requireLayout();
        requireMetadata(metadata);
        if (!Boolean.FALSE.equals(vacation(catalog))) throw new CatalogLink.Vacation();
        IMqttDeliveryToken token = client.publish(topic + "/on", payload.getBytes(StandardCharsets.UTF_8), 0, false);
        return new Published(token, sequence);
    }

    private static Map<String, String> commandMetadata(WatchCatalog catalog, Map<String, CatalogLink.Change> changes,
                                                       boolean allOff) {
        Map<String, String> required = new LinkedHashMap<>();
        for (Map.Entry<String, CatalogLink.Change> target : changes.entrySet()) {
            CatalogLink.Change change = target.getValue();
            required.put(change.topic(catalog.lights.get(target.getKey())) + "/meta", change.controlType());
        }
        if (catalog.vacation != null) required.put(catalog.vacation + "/meta", "vacation");
        if (allOff && catalog.allOff != null) required.put(catalog.allOff + "/meta", "pushbutton");
        return required;
    }

    private void loadCommandMetadata(Map<String, String> metadata) throws IOException, MqttException, InterruptedException {
        synchronized (this) {
            requireLayout();
            commandTypes.putAll(metadata);
            subscriptions.addAll(metadata.keySet());
        }
        subscribe(metadata.keySet());
        synchronized (this) {
            while (!messages.keySet().containsAll(metadata.keySet())) {
                requireLayout();
                if (!await(deadline)) break;
            }
            requireLayout();
            requireMetadata(metadata);
        }
    }

    private void requireMetadata(Map<String, String> metadata) throws CatalogLink.Stale {
        if (!writableControls.containsAll(metadata.keySet())) throw new CatalogLink.Stale();
    }

    static boolean validMetadata(String type, String json) {
        if (json == null) return false;
        try {
            JSONObject meta = WatchCatalog.object(json);
            boolean observingVacation = "vacation".equals(type);
            if (!(observingVacation ? "switch" : type).equals(meta.opt("type"))) return false;
            if (!observingVacation && meta.has("readonly") && !Boolean.FALSE.equals(meta.opt("readonly"))) return false;
            if (!"range".equals(type)) return true;
            Object min = meta.has("min") ? meta.opt("min") : 0;
            return min instanceof Number low && low.doubleValue() == 0
                && meta.opt("max") instanceof Number high && high.doubleValue() == 100;
        } catch (JSONException failure) {
            return false;
        }
    }

    private boolean receivedEcho(Map<String, Long> sentAfter) {
        for (Map.Entry<String, Long> target : sentAfter.entrySet()) {
            Value value = messages.get(target.getKey());
            if (value == null || value.retained() || value.sequence() <= target.getValue()) return false;
        }
        return true;
    }

    private CatalogLink.State snapshot(WatchCatalog catalog) {
        Map<String, CatalogLink.LightState> lights = new LinkedHashMap<>();
        for (WatchCatalog.Light light : catalog.lights.values()) {
            lights.put(light.id, new CatalogLink.LightState(binary(text(light.power)), WatchCatalog.mqttBrightness(text(light.brightness)),
                WatchCatalog.color(text(light.color))));
        }
        return new CatalogLink.State(catalog.revision, vacation(catalog), lights);
    }

    private Boolean vacation(WatchCatalog catalog) {
        return catalog.vacation == null ? Boolean.FALSE : binary(text(catalog.vacation));
    }

    private static Boolean binary(String value) { return value == null ? null : MqttRoom.binary(value); }

    private void subscribe(Set<String> topics) throws IOException, MqttException {
        IMqttToken token = client.subscribe(topics.toArray(new String[0]), new int[topics.size()]);
        token.waitForCompletion(remainingMillis(deadline));
        if (!MqttRoom.subscriptionAccepted(token.getGrantedQos(), topics.size())) throw new IOException("Subscription refused");
    }

    private synchronized void receive(String topic, MqttMessage message) {
        if (closed || !subscriptions.contains(topic)) return;
        if (message.isRetained() && METADATA.contains(topic)) retainedMetadata.add(topic);
        Value prior = messages.get(topic);
        if (prior != null && !prior.retained() && message.isRetained()) return;
        byte[] bytes = message.getPayload();
        String value = null;
        int maxBytes = CATALOG.equals(topic) ? WatchCatalog.MAX_BYTES : topic.endsWith("/meta") ? 8192 : 128;
        if (bytes.length <= maxBytes) {
            try {
                value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ignored) {
                // An invalid update also invalidates any earlier value for this topic.
            }
        }
        messages.put(topic, new Value(value, message.isRetained(), ++sequence));
        String type = commandTypes.get(topic);
        if (type != null) {
            if (validMetadata(type, value)) writableControls.add(topic);
            else writableControls.remove(topic);
        }
        notifyAll();
    }

    private Metadata metadata() { return new Metadata(text(MqttRoom.SERIAL), text(CATALOG), text(REVISION)); }

    private String text(String topic) {
        Value value = messages.get(topic);
        return value == null ? null : value.text();
    }

    private void requireLayout() throws IOException {
        requireConnected();
        if (!verified.equals(metadata())) throw new CatalogLink.Stale();
    }

    private void requireConnected() throws IOException {
        if (closed || disconnected || !client.isConnected() || Thread.currentThread().isInterrupted()) {
            throw new IOException("MQTT not connected");
        }
    }

    private boolean await(long until) throws InterruptedException {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) return false;
        TimeUnit.NANOSECONDS.timedWait(this, remaining);
        return true;
    }

    private static long remainingMillis(long until) throws IOException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("MQTT timeout");
        return remaining;
    }

    @Override public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Paho may have already closed it after a disconnect.
        }
        closeClient();
    }

    private void closeLateConnection() {
        synchronized (this) {
            if (!closed) return;
        }
        new Thread(this::closeClient, "catalog-mqtt-close").start();
    }

    private void closeClient() {
        try {
            client.close(true);
        } catch (MqttException ignored) {
            // The connect callback closes the client once the socket interruption is handled.
        }
    }
}
