package com.galaxywatch.home;

import org.junit.Test;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CatalogMqttTest {
    private static final String SERIAL = "Controller01";
    private static final String REVISION = "a".repeat(64);
    private static final String POWER = "/devices/relay/controls/K1";
    private static final String MASTER = "/devices/light-rules/controls/All Off";
    private static final String BODY = """
        {"schema":1,"revision":"%s","controller":"%s","rooms":[
          {"id":"hall","label":"Hall","theme":"hall","lights":[
            {"id":"lamp","label":"Lamp","theme":"plain","power":"%s"}
          ]}
        ]}
        """.formatted(REVISION, SERIAL, POWER);
    private static final Map<String, CatalogLink.Change> OFF = Map.of("lamp", CatalogLink.Change.power(false));

    @Test public void cachedReadAndConfirmedWriteUseNoCatalogSubscriptionAndOnePublish() throws Exception {
        try (Broker broker = new Broker(SERIAL, false, false)) {
            try (CatalogMqtt mqtt = broker.connect()) {
                WatchCatalog catalog = mqtt.layout(SERIAL, WatchCatalog.parse(BODY));
                assertEquals(Boolean.TRUE, mqtt.state(catalog).lights.get("lamp").power);
                assertEquals(Boolean.FALSE, mqtt.send(catalog, OFF, false, () -> {}).lights.get("lamp").power);
            }
            broker.finished.get(4, TimeUnit.SECONDS);
            assertEquals(List.of(POWER + "/on=0"), broker.commands);
            assertFalse(broker.subscriptions.contains(CatalogMqtt.CATALOG));
            assertTrue(broker.subscriptions.contains(POWER + "/meta"));
        }
    }

    @Test public void wrongControllerDoesNotSubscribeToLayoutOrState() throws Exception {
        try (Broker broker = new Broker("Controller02", false, false)) {
            try (CatalogMqtt mqtt = broker.connect()) {
                assertThrows(CatalogLink.WrongController.class, () -> mqtt.layout(SERIAL, WatchCatalog.parse(BODY)));
            }
            broker.finished.get(4, TimeUnit.SECONDS);
            assertEquals(2, broker.subscriptions.size());
            assertTrue(broker.commands.isEmpty());
        }
    }

    @Test public void changedRevisionFetchesLayoutAndMasterUsesConfiguredPushbutton() throws Exception {
        String body = new JSONObject(BODY).put("allOff", MASTER).toString();
        try (Broker broker = new Broker(SERIAL, false, false, body, "0")) {
            try (CatalogMqtt mqtt = broker.connect()) {
                WatchCatalog cached = WatchCatalog.parse(BODY.replace(REVISION, "b".repeat(64)));
                WatchCatalog current = mqtt.layout(SERIAL, cached);
                assertEquals(REVISION, current.revision);
                assertEquals(Boolean.FALSE, mqtt.state(current).lights.get("lamp").power);
                assertEquals(Boolean.FALSE, mqtt.send(current, OFF, true, () -> {}).lights.get("lamp").power);
            }
            broker.finished.get(4, TimeUnit.SECONDS);
            assertTrue(broker.subscriptions.contains(CatalogMqtt.CATALOG));
            assertEquals(List.of(MASTER + "/on=1"), broker.commands);
        }
    }

    @Test public void retiredOrReadOnlyControlNeverReceivesWrite() throws Exception {
        for (boolean changeRevision : new boolean[] {false, true}) {
            try (Broker broker = new Broker(SERIAL, !changeRevision, changeRevision)) {
                try (CatalogMqtt mqtt = broker.connect()) {
                    WatchCatalog catalog = mqtt.layout(SERIAL, null);
                    assertEquals(Boolean.TRUE, mqtt.state(catalog).lights.get("lamp").power);
                    assertThrows(CatalogLink.Stale.class, () -> mqtt.send(catalog, OFF, false, () -> {}));
                }
                broker.finished.get(4, TimeUnit.SECONDS);
                assertTrue(broker.subscriptions.contains(CatalogMqtt.CATALOG));
                assertTrue(broker.commands.isEmpty());
            }
        }
    }

    private static final class Broker implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final List<String> subscriptions = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final Future<?> finished;

        Broker(String serial, boolean readOnly, boolean clearRevision) throws IOException {
            this(serial, readOnly, clearRevision, BODY, "1");
        }

        Broker(String serial, boolean readOnly, boolean clearRevision, String catalog, String power) throws IOException {
            finished = worker.submit(() -> {
                try (Socket connection = server.accept()) {
                    connection.setSoTimeout(6500);
                    serve(connection, serial, readOnly, clearRevision, catalog, power);
                }
                return null;
            });
        }

        CatalogMqtt connect() throws Exception {
            Socket redirected = new Socket() {
                @Override public void connect(SocketAddress destination, int timeout) throws IOException {
                    super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), timeout);
                }
            };
            CatalogMqtt mqtt = new CatalogMqtt("192.168.99.2", redirected);
            try {
                mqtt.connect();
                return mqtt;
            } catch (Exception failure) {
                mqtt.close();
                throw failure;
            }
        }

        private void serve(Socket socket, String serial, boolean readonly, boolean clearRevision, String catalog, String power)
            throws IOException {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            for (int header; (header = input.read()) != -1;) {
                int length = remainingLength(input);
                DataInputStream body = new DataInputStream(new java.io.ByteArrayInputStream(input.readNBytes(length)));
                switch (header >> 4) {
                    case 1 -> packet(output, 0x20, new byte[] {0, 0});
                    case 8 -> {
                        int id = body.readUnsignedShort();
                        List<String> topics = new ArrayList<>();
                        while (body.available() > 0) {
                            topics.add(body.readUTF());
                            body.readUnsignedByte();
                        }
                        subscriptions.addAll(topics);
                        byte[] ack = new byte[2 + topics.size()];
                        ack[0] = (byte) (id >> 8);
                        ack[1] = (byte) id;
                        packet(output, 0x90, ack);
                        for (String topic : topics) {
                            switch (topic) {
                                case MqttRoom.SERIAL -> publish(output, topic, serial, true);
                                case CatalogMqtt.REVISION -> publish(output, topic, REVISION, true);
                                case CatalogMqtt.CATALOG -> publish(output, topic, catalog, true);
                                case POWER -> publish(output, topic, power, true);
                                case MASTER + "/meta" -> publish(output, topic, "{\"type\":\"pushbutton\"}", true);
                                case POWER + "/meta" -> {
                                    if (clearRevision) publish(output, CatalogMqtt.REVISION, "", false);
                                    publish(output, topic, "{\"type\":\"switch\",\"readonly\":" + readonly + "}", true);
                                }
                                default -> throw new IOException("Unexpected subscription " + topic);
                            }
                        }
                    }
                    case 3 -> {
                        assertEquals(0x30, header);
                        String topic = body.readUTF();
                        String value = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                        commands.add(topic + "=" + value);
                        if (topic.equals(POWER + "/on")) publish(output, POWER, value, false);
                    }
                    case 14 -> { return; }
                    default -> throw new IOException("Unexpected MQTT packet " + header);
                }
            }
        }

        private static int remainingLength(DataInputStream input) throws IOException {
            int length = 0;
            for (int shift = 0; shift <= 21; shift += 7) {
                int digit = input.readUnsignedByte();
                length |= (digit & 127) << shift;
                if ((digit & 128) == 0) return length;
            }
            throw new IOException("Unexpected packet length");
        }

        private static void publish(DataOutputStream output, String topic, String value, boolean retained) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(bytes);
            payload.writeUTF(topic);
            payload.write(value.getBytes(StandardCharsets.UTF_8));
            packet(output, retained ? 0x31 : 0x30, bytes.toByteArray());
        }

        private static void packet(DataOutputStream output, int header, byte[] body) throws IOException {
            output.write(header);
            int length = body.length;
            do {
                int digit = length & 127;
                length >>>= 7;
                output.write(length == 0 ? digit : digit | 128);
            } while (length != 0);
            output.write(body);
            output.flush();
        }

        @Override public void close() throws IOException {
            server.close();
            worker.shutdownNow();
        }
    }
}
