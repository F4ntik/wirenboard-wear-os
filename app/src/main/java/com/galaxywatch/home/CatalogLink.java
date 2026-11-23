package com.galaxywatch.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

final class CatalogLink {
    private static final String PIN = "controller";
    private static final String PAIRING = "pairing";
    private static final String LAYOUT = "layout";
    private static final String OFFLINE = "Нет связи с настроенным домом";
    private static final String WRONG_CONTROLLER = "Контроллер отличается от привязанного";
    private static final String STALE = "Схема дома изменилась. Проверьте комнату";
    private static final String UNCONFIRMED = "Контроллер не подтвердил команду";
    private static final String INCOMPLETE = "Дождитесь состояния света на контроллере";
    private static final String VACATION = "Управление недоступно в режиме отпуска";
    private static final String PAIR_FIRST = "Сначала подключите дом";
    private static final long READ_TIMEOUT_SECONDS = 12;
    private static final long COMMAND_TIMEOUT_SECONDS = 18;
    private static final AtomicBoolean COMMAND = new AtomicBoolean();
    private static Discovery lastDiscovery;

    private CatalogLink() {}

    static final class Result {
        public final WatchCatalog catalog;
        public final State state;
        public final RoutePlan.Route route;
        public final String error;
        public final boolean legacyGateway;
        private final Source source;
        private final String pairingId;
        private final AtomicBoolean used = new AtomicBoolean();

        private Result(WatchCatalog catalog, State state, Source source, String pairingId, String error) {
            this(catalog, state, source, pairingId, error, false);
        }

        private Result(WatchCatalog catalog, State state, Source source, String pairingId, String error, boolean legacyGateway) {
            this.catalog = catalog;
            this.state = state;
            this.source = source;
            this.route = source == null ? null : source.route();
            this.pairingId = pairingId;
            this.error = error;
            this.legacyGateway = legacyGateway;
        }
    }

    static final class State {
        public final String revision;
        public final Boolean vacation;
        public final Map<String, LightState> lights;

        State(String revision, Boolean vacation, Map<String, LightState> lights) {
            this.revision = revision;
            this.vacation = vacation;
            this.lights = Collections.unmodifiableMap(new LinkedHashMap<>(lights));
        }
    }

    static final class LightState {
        public final Boolean power;
        public final Integer brightness;
        public final String color;

        LightState(Boolean power, Integer brightness, String color) {
            this.power = power;
            this.brightness = brightness;
            this.color = color;
        }
    }

    static final class Change {
        private enum Field { POWER, BRIGHTNESS, COLOR }
        private final Field field;
        private final Object value;

        private Change(Field field, Object value) { this.field = field; this.value = value; }

        static Change power(boolean value) { return new Change(Field.POWER, value); }
        static Change brightness(int value) { return new Change(Field.BRIGHTNESS, WatchCatalog.brightness(value)); }
        static Change color(String value) { return new Change(Field.COLOR, WatchCatalog.color(value)); }

        String payload() { return field == Field.POWER ? (Boolean.TRUE.equals(value) ? "1" : "0") : value.toString(); }

        String topic(WatchCatalog.Light light) {
            return switch (field) {
                case POWER -> light.power;
                case BRIGHTNESS -> light.brightness;
                case COLOR -> light.color;
            };
        }

        Object observed(LightState light) {
            if (light == null) return null;
            return switch (field) {
                case POWER -> light.power;
                case BRIGHTNESS -> light.brightness;
                case COLOR -> light.color;
            };
        }

        boolean matches(LightState light) { return value != null && value.equals(observed(light)); }

        String controlType() {
            return switch (field) {
                case POWER -> "switch";
                case BRIGHTNESS -> "range";
                case COLOR -> "rgb";
            };
        }
    }

    static final class WrongController extends IOException {}
    static final class Vacation extends IOException {}
    static final class UnknownState extends IOException {}
    static final class Stale extends IOException {}
    private static final class LegacyGateway extends IOException {}

    @FunctionalInterface
    interface SendGuard { void check() throws IOException; }

    private record Pairing(String serial, String id, String layout) {}
    private record Discovery(WatchCatalog catalog, String pairingId, long expires) {}
    private record Source(RoutePlan.Route route, String address, String generation) {}

    static boolean paired(Context context) { return pairing(context).serial() != null; }

    static WatchCatalog cached(Context context) { return cached(pairing(context)); }

    static Result discover(Context context, String address) {
        Pairing pairing = pairing(context);
        Source source = new Source(RoutePlan.Route.HOME, address.trim(), "");
        try (CatalogMqtt mqtt = new CatalogMqtt(source.address())) {
            mqtt.connect();
            WatchCatalog catalog = mqtt.layout(pairing.serial(), null);
            synchronized (CatalogLink.class) {
                if (!pairing.id().equals(pairing(context).id())) return failed(null, STALE);
                lastDiscovery = new Discovery(catalog, pairing.id(), System.nanoTime() + TimeUnit.MINUTES.toNanos(5));
            }
            return new Result(catalog, null, source, pairing.id(), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(null, OFFLINE);
        } catch (IOException | MqttException | JSONException | IllegalArgumentException failure) {
            return failed(null, message(failure, OFFLINE));
        }
    }

    static synchronized boolean approve(Context context, WatchCatalog catalog) {
        Pairing current = pairing(context);
        if (catalog == null || lastDiscovery == null || lastDiscovery.catalog() != catalog
            || !lastDiscovery.pairingId().equals(current.id()) || System.nanoTime() > lastDiscovery.expires()
            || (current.serial() != null && !catalog.matchesSerial(current.serial()))) return false;
        lastDiscovery = null;
        return storage(context).edit().putString(PIN, catalog.controller).putString(LAYOUT, catalog.json)
            .putString(PAIRING, UUID.randomUUID().toString()).commit();
    }

    // A personal build can carry the layout its owner already approved on the controller.
    // The public APK ships without this asset and uses the interactive pairing flow.
    static synchronized void configureBundledHome(Context context) {
        if (HomeConnections.DEFAULT_DEMO || paired(context) || storage(context).getBoolean("ignore_bundle", false)) return;
        var prefs = settings(context);
        if (prefs.getBoolean("demo", false)) return;
        String address = HomeConnections.address(prefs);
        if (!HomeConnections.DEFAULT_ADDRESS.equals(address) && !(HomeConnections.DEFAULT_ADDRESS + ":1883").equals(address)) return;
        try (var input = context.getAssets().open("configured-home.json")) {
            WatchCatalog home = WatchCatalog.parse(RemoteRoom.readResponse(input, WatchCatalog.MAX_BYTES));
            String existing = prefs.getString(WbCloud.SERIAL, "");
            if (!existing.isEmpty() && !home.matchesSerial(existing)) return;
            storage(context).edit().putString(PIN, home.controller).putString(LAYOUT, home.json)
                .putString(PAIRING, UUID.randomUUID().toString()).apply();
        } catch (IOException | JSONException invalid) {
            // A locally built, unconfigured APK still supports explicit user pairing.
        }
    }

    static synchronized void forget(Context context) {
        lastDiscovery = null;
        storage(context).edit().remove(PIN).remove(PAIRING).remove(LAYOUT).putBoolean("ignore_bundle", true).apply();
    }

    static Result read(Context context, WatchCatalog cached) {
        Pairing pairing = pairing(context);
        WatchCatalog baseline = cached(pairing);
        if (baseline == null && cached != null && cached.matchesSerial(pairing.serial())) baseline = cached;
        if (pairing.serial() == null) return failed(null, PAIR_FIRST);
        List<Source> candidates = candidates(settings(context));
        if (candidates.isEmpty()) return failed(baseline, OFFLINE);
        ExecutorService executor = Executors.newFixedThreadPool(candidates.size());
        CompletionService<Result> results = new ExecutorCompletionService<>(executor);
        List<Future<Result>> probes = new ArrayList<>();
        WatchCatalog expected = baseline;
        for (Source source : candidates) probes.add(results.submit(() -> probe(context, pairing, source, expected)));
        Result best = failed(baseline, OFFLINE);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(READ_TIMEOUT_SECONDS);
        try {
            for (int i = 0; i < candidates.size(); i++) {
                Future<Result> ready = results.poll(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (ready == null) break;
                Result result = ready.get();
                if (readyForControl(result)) return save(context, pairing, result);
                if (best.state == null && (result.state != null || OFFLINE.equals(best.error)
                    || WRONG_CONTROLLER.equals(result.error))) best = result;
            }
            return best.state == null ? best : save(context, pairing, best);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(baseline, OFFLINE);
        } catch (ExecutionException failure) {
            return failed(baseline, OFFLINE);
        } finally {
            for (Future<Result> probe : probes) probe.cancel(true);
            executor.shutdownNow();
        }
    }

    static Result verifyCloud(Context context) {
        SharedPreferences settings = settings(context);
        if (!settings.contains(WbCloud.PROFILE)) return failed(null, "Настройте WB Cloud с компьютера");
        Pairing pinned = pairing(context);
        Source source = cloudSource(settings);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Result> task = executor.submit(() -> {
            try {
                Remote remote = new Remote(settings, source);
                Pairing expected = pinned.serial() == null ? new Pairing(remote.cloud.controllerSerial, pinned.id(), "") : pinned;
                Result result = remote.prove(source, expected);
                return readyForControl(result) ? result : failed(result.catalog, INCOMPLETE);
            } catch (LegacyGateway legacy) {
                return new Result(null, null, source, pinned.id(), null, true);
            } catch (IOException | GeneralSecurityException | JSONException | IllegalArgumentException failure) {
                return failed(cached(pinned), message(failure, OFFLINE));
            }
        });
        try {
            Result result = task.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            synchronized (CatalogLink.class) {
                synchronized (settings) {
                    if (!pinned.id().equals(pairing(context).id()) || !settings.contains(WbCloud.PROFILE)
                        || !source.equals(cloudSource(settings))) return failed(cached(context), STALE);
                    if (readyForControl(result)) WbCloud.confirmRead(context, settings);
                }
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(cached(pinned), OFFLINE);
        } catch (ExecutionException | TimeoutException failure) {
            return failed(cached(pinned), OFFLINE);
        } finally {
            executor.shutdownNow();
        }
    }

    static Result command(Context context, Result previous, Map<String, Change> changes) {
        return issue(context, previous, changes, false);
    }

    static Result allOff(Context context, Result previous) { return issue(context, previous, Map.of(), true); }

    private static Result issue(Context context, Result previous, Map<String, Change> requested, boolean allOff) {
        if (previous == null || previous.state == null || previous.catalog == null || previous.error != null) {
            return failed(previous == null ? null : previous.catalog, INCOMPLETE);
        }
        Pairing pairing = pairing(context);
        WatchCatalog current = cached(pairing);
        if (!previous.pairingId.equals(pairing.id()) || !previous.catalog.sameLayout(current)
            || !candidates(settings(context)).contains(previous.source)) return failed(current, STALE);
        Map<String, Change> changes = allOff ? offChanges(current)
            : requested == null ? Map.of() : new LinkedHashMap<>(requested);
        if (!validChanges(current, changes)) return failed(current, "Команда для этой лампы недоступна");
        try {
            requireKnown(previous.state, changes);
        } catch (IOException failure) {
            return failed(current, message(failure, INCOMPLETE));
        }
        if (!previous.used.compareAndSet(false, true)) return failed(current, "Сначала обновите состояние дома");
        if (!COMMAND.compareAndSet(false, true)) {
            previous.used.set(false);
            return failed(current, "Дождитесь ответа на предыдущую команду");
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean abandoned = new AtomicBoolean();
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS);
            SendGuard guard = () -> {
                if (abandoned.get() || Thread.currentThread().isInterrupted() || System.nanoTime() >= until
                    || !pairing.equals(pairing(context)) || !candidates(settings(context)).contains(previous.source)) throw new Stale();
            };
            Future<Result> operation = executor.submit(() -> {
                try {
                    return sendOnRoute(context, previous.source, pairing, current, changes, allOff, guard);
                } finally {
                    COMMAND.set(false);
                }
            });
            return operation.get(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(current, UNCONFIRMED);
        } catch (ExecutionException | TimeoutException failure) {
            return failed(current, UNCONFIRMED);
        } finally {
            abandoned.set(true);
            executor.shutdown();
        }
    }

    private static Result sendOnRoute(Context context, Source source, Pairing pairing, WatchCatalog expected,
                                      Map<String, Change> changes, boolean allOff, SendGuard guard) {
        try {
            guard.check();
            if (source.route() == RoutePlan.Route.HOME) {
                try (CatalogMqtt mqtt = new CatalogMqtt(source.address())) {
                    mqtt.connect();
                    Result fresh = localRead(mqtt, source, pairing, expected);
                    if (!fresh.catalog.revision.equals(expected.revision)) {
                        return stale(context, pairing, fresh.catalog, fresh.state, source);
                    }
                    State state = mqtt.send(fresh.catalog, changes, allOff, guard);
                    guard.check();
                    return new Result(fresh.catalog, state, source, pairing.id(), null);
                }
            }
            Remote remote = new Remote(settings(context), source);
            Result fresh = remote.read(source, pairing, expected);
            if (!fresh.catalog.revision.equals(expected.revision)) {
                return stale(context, pairing, fresh.catalog, fresh.state, source);
            }
            requireKnown(fresh.state, changes);
            guard.check();
            JSONObject json = WatchCatalog.object(remote.request("/v2/command", commandJson(expected.revision, changes, allOff), guard));
            State confirmed = parseState(json, expected);
            guard.check();
            if (!matches(confirmed, changes)) return failed(expected, UNCONFIRMED);
            return new Result(expected, confirmed, source, pairing.id(), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(expected, UNCONFIRMED);
        } catch (IOException | GeneralSecurityException | MqttException | JSONException | IllegalArgumentException failure) {
            return failed(expected, message(failure, UNCONFIRMED));
        }
    }

    private static Result probe(Context context, Pairing pairing, Source source, WatchCatalog baseline) {
        try {
            if (source.route() != RoutePlan.Route.HOME) {
                return new Remote(settings(context), source).read(source, pairing, baseline);
            }
            try (CatalogMqtt mqtt = new CatalogMqtt(source.address())) {
                mqtt.connect();
                return localRead(mqtt, source, pairing, baseline);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(baseline, OFFLINE);
        } catch (IOException | GeneralSecurityException | MqttException | JSONException | IllegalArgumentException failure) {
            return failed(baseline, message(failure, OFFLINE));
        }
    }

    private static Result localRead(CatalogMqtt mqtt, Source source, Pairing pairing, WatchCatalog baseline)
        throws IOException, JSONException, MqttException, InterruptedException {
        WatchCatalog catalog = verify(mqtt.layout(pairing.serial(), baseline), pairing.serial(), baseline);
        try {
            return new Result(catalog, mqtt.state(catalog), source, pairing.id(), null);
        } catch (Stale race) {
            catalog = verify(mqtt.layout(pairing.serial(), catalog), pairing.serial(), catalog);
            return new Result(catalog, mqtt.state(catalog), source, pairing.id(), null);
        }
    }

    private static Result stale(Context context, Pairing pairing, WatchCatalog catalog, State state, Source source) {
        return save(context, pairing, new Result(catalog, state, source, pairing.id(), STALE));
    }

    private static Result save(Context context, Pairing started, Result result) {
        synchronized (CatalogLink.class) {
            Pairing current = pairing(context);
            if (!started.id().equals(current.id()) || !candidates(settings(context)).contains(result.source)) {
                return failed(cached(current), STALE);
            }
            if (result.catalog.sameLayout(cached(current))) return result;
            if (!started.layout().equals(current.layout())) {
                return failed(cached(current), STALE);
            }
            storage(context).edit().putString(LAYOUT, result.catalog.json).apply();
        }
        return result;
    }

    private static boolean readyForControl(Result result) {
        return result.state != null && readyForControl(result.catalog, result.state);
    }

    static boolean readyForControl(WatchCatalog catalog, State state) {
        return state.vacation != null
            && (catalog.lights.isEmpty() || state.lights.values().stream().anyMatch(light -> light.power != null));
    }

    private static Result failed(WatchCatalog catalog, String error) { return new Result(catalog, null, null, "", error); }

    private static String message(Exception failure, String otherwise) {
        if (failure instanceof WrongController) return WRONG_CONTROLLER;
        if (failure instanceof Stale || failure instanceof RemoteRoom.HttpFailure http && http.status == 409) return STALE;
        if (failure instanceof Vacation || failure instanceof RemoteRoom.HttpFailure http && http.status == 403) return VACATION;
        if (failure instanceof UnknownState) return INCOMPLETE;
        if (failure instanceof WbCloud.LoginRequired || failure instanceof WbCloud.RejectedTunnel
            || failure instanceof RemoteRoom.HttpFailure http && http.status == 401) return "Проверьте доступ к дому";
        return otherwise;
    }

    static WatchCatalog verify(WatchCatalog candidate, String serial, WatchCatalog baseline) throws WrongController, Stale {
        if (!candidate.matchesSerial(serial)) throw new WrongController();
        if (baseline != null && baseline.revision.equals(candidate.revision) && !baseline.sameLayout(candidate)) throw new Stale();
        return candidate;
    }

    static State parseState(JSONObject json, WatchCatalog catalog) throws JSONException, Stale {
        if (!catalog.revision.equals(json.opt("revision"))) throw new Stale();
        JSONObject values = json.getJSONObject("lights");
        for (Iterator<String> ids = values.keys(); ids.hasNext();) {
            if (!catalog.lights.containsKey(ids.next())) throw new JSONException("Unknown light in state");
        }
        Map<String, LightState> lights = new LinkedHashMap<>();
        for (WatchCatalog.Light light : catalog.lights.values()) {
            JSONObject value = values.optJSONObject(light.id);
            lights.put(light.id, new LightState(value == null ? null : strictBoolean(value.opt("power")),
                value == null || light.brightness == null ? null : WatchCatalog.brightness(value.opt("brightness")),
                value == null || light.color == null ? null : WatchCatalog.color(value.opt("color"))));
        }
        return new State(catalog.revision, strictBoolean(json.opt("vacation")), lights);
    }

    static JSONObject commandJson(String revision, Map<String, Change> changes, boolean allOff) throws JSONException {
        JSONObject json = new JSONObject().put("revision", revision);
        if (allOff) return json.put("allOff", true);
        JSONObject updates = new JSONObject();
        for (Map.Entry<String, Change> item : changes.entrySet()) {
            Change change = item.getValue();
            String key = switch (change.field) {
                case POWER -> "power";
                case BRIGHTNESS -> "brightness";
                case COLOR -> "color";
            };
            updates.put(item.getKey(), new JSONObject().put(key, change.value));
        }
        return json.put("changes", updates);
    }

    private static Boolean strictBoolean(Object value) { return value instanceof Boolean state ? state : null; }

    static boolean validChanges(WatchCatalog catalog, Map<String, Change> changes) {
        if (changes.isEmpty() || changes.size() > WatchCatalog.MAX_LIGHTS) return false;
        for (Map.Entry<String, Change> item : changes.entrySet()) {
            WatchCatalog.Light light = catalog.lights.get(item.getKey());
            Change change = item.getValue();
            if (light == null || change == null || change.value == null || change.topic(light) == null) return false;
        }
        return true;
    }

    static void requireKnown(State state, Map<String, Change> changes) throws IOException {
        if (Boolean.TRUE.equals(state.vacation)) throw new Vacation();
        if (state.vacation == null) throw new UnknownState();
        for (Map.Entry<String, Change> change : changes.entrySet()) {
            if (change.getValue().observed(state.lights.get(change.getKey())) == null) throw new UnknownState();
        }
    }

    static boolean matches(State state, Map<String, Change> changes) {
        for (Map.Entry<String, Change> change : changes.entrySet()) {
            if (!change.getValue().matches(state.lights.get(change.getKey()))) return false;
        }
        return true;
    }

    private static Map<String, Change> offChanges(WatchCatalog catalog) {
        Map<String, Change> values = new LinkedHashMap<>();
        for (String id : catalog.lights.keySet()) values.put(id, Change.power(false));
        return values;
    }

    private static SharedPreferences settings(Context context) { return context.getSharedPreferences("home", Context.MODE_PRIVATE); }

    private static SharedPreferences storage(Context context) { return context.getSharedPreferences("home_catalog_v2", Context.MODE_PRIVATE); }

    private static synchronized Pairing pairing(Context context) {
        SharedPreferences storage = storage(context);
        return new Pairing(storage.getString(PIN, null), storage.getString(PAIRING, ""), storage.getString(LAYOUT, ""));
    }

    private static WatchCatalog cached(Pairing pairing) {
        if (pairing.serial() == null || pairing.layout().isEmpty()) return null;
        try {
            WatchCatalog catalog = WatchCatalog.parse(pairing.layout());
            return catalog.matchesSerial(pairing.serial()) ? catalog : null;
        } catch (JSONException invalid) {
            return null;
        }
    }

    private static List<Source> candidates(SharedPreferences settings) {
        List<Source> sources = new ArrayList<>();
        String address = HomeConnections.address(settings);
        boolean cloud = settings.contains(WbCloud.PROFILE);
        boolean automatic = HomeConnections.automatic(settings);
        List<RoutePlan.Route> routes = RoutePlan.candidates(automatic, HomeConnections.manualRoute(settings),
            !address.isEmpty(), cloud, false);
        for (RoutePlan.Route route : routes) {
            switch (route) {
                case HOME -> { if (!address.isEmpty()) sources.add(new Source(route, address, "")); }
                case CLOUD -> { if (cloud) sources.add(cloudSource(settings)); }
                case HTTPS -> sources.add(new Source(route, settings.getString(RemoteSettings.ORIGIN, ""),
                    settings.getString(RemoteSettings.REVISION, "")));
            }
        }
        return sources;
    }

    private static Source cloudSource(SharedPreferences settings) {
        return new Source(RoutePlan.Route.CLOUD, "", settings.getString(WbCloud.IMPORT_ID, ""));
    }

    private static final class Remote {
        private final WbCloud cloud;
        private final RemoteSettings.Config https;

        Remote(SharedPreferences settings, Source source) throws GeneralSecurityException, IOException, JSONException {
            cloud = source.route() == RoutePlan.Route.CLOUD ? HomeConnections.cloudProfile(settings) : null;
            https = cloud == null ? RemoteSettings.load(settings) : null;
        }

        Result read(Source source, Pairing pairing, WatchCatalog baseline) throws IOException, JSONException {
            if (cloud != null && !cloud.controllerSerial.equalsIgnoreCase(pairing.serial())) throw new WrongController();
            JSONObject snapshot = WatchCatalog.object(request("/v2/state", null, null));
            WatchCatalog catalog = baseline;
            if (catalog == null || !catalog.revision.equals(snapshot.opt("revision"))) catalog = layout(pairing.serial(), baseline);
            if (!catalog.revision.equals(snapshot.opt("revision"))) {
                snapshot = WatchCatalog.object(request("/v2/state", null, null));
            }
            return new Result(catalog, parseState(snapshot, catalog), source, pairing.id(), null);
        }

        Result prove(Source source, Pairing pairing) throws IOException, JSONException {
            if (!cloud.controllerSerial.equalsIgnoreCase(pairing.serial())) throw new WrongController();
            WatchCatalog catalog = layout(pairing.serial(), null);
            State state = parseState(WatchCatalog.object(request("/v2/state", null, null)), catalog);
            return new Result(catalog, state, source, pairing.id(), null);
        }

        private WatchCatalog layout(String serial, WatchCatalog baseline) throws IOException, JSONException {
            return verify(WatchCatalog.parse(request("/v2/layout", null, null)), serial, baseline);
        }

        String request(String path, JSONObject body, SendGuard guard) throws IOException, JSONException {
            try {
                return exchange(path, body, guard);
            } catch (WbCloud.RejectedTunnel expired) {
                if (body != null || Thread.currentThread().isInterrupted()) throw expired;
                try {
                    return exchange(path, null, null);
                } catch (WbCloud.RejectedTunnel rejected) {
                    throw new WbCloud.LoginRequired();
                }
            }
        }

        private String exchange(String path, JSONObject body, SendGuard guard) throws IOException, JSONException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Read cancelled");
            HttpsURLConnection connection = cloud == null ? RemoteRoom.open(https.origin(), path)
                : cloud.open(path, body == null, () -> Thread.currentThread().isInterrupted());
            try {
                if (guard != null) guard.check();
                connection.setRequestProperty("Authorization", "Bearer " + (cloud == null ? https.token() : cloud.gatewayKey()));
                return RemoteRoom.exchangeBody(connection, body, WatchCatalog.MAX_BYTES);
            } catch (RemoteRoom.HttpFailure failure) {
                if ("/v2/layout".equals(path) && WbCloud.isGatewayNotFound(connection, failure.status)) throw new LegacyGateway();
                throw cloud == null ? failure : cloud.gatewayRejected(failure);
            } finally {
                connection.disconnect();
            }
        }
    }
}
